package dev.pocketphotobridge.bridge.linux

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Raw tool output stays inside the adapter and must never be forwarded to diagnostics. */
internal data class LinuxCommandResult(val exitCode: Int, val output: String)
internal interface LinuxCommandRunner {
    fun run(command: List<String>, timeoutMillis: Long, input: String? = null): LinuxCommandResult
}

enum class LinuxRadioFailureCode {
    INVALID_INPUT, COMMAND_FAILED, COMMAND_TIMEOUT, CANCELLED, PROFILE_UNSAFE,
    CAMERA_ROUTE_UNSAFE, SPP_UNAVAILABLE, UNSUPPORTED_PLATFORM, NATIVE_UNAVAILABLE,
    NATIVE_SELF_TEST_FAILED, CLOSED
}
class LinuxRadioFailure(val code: LinuxRadioFailureCode) : RuntimeException(code.name)

internal fun radioFail(code: LinuxRadioFailureCode): Nothing = throw LinuxRadioFailure(code)
internal fun reportRadio(report: (String) -> Unit, event: String) {
    try { report(event) } catch (_: Throwable) { }
}
internal fun checkRadioInterrupted() {
    if (Thread.currentThread().isInterrupted) radioFail(LinuxRadioFailureCode.CANCELLED)
}
internal fun bluetoothAddress(value: String): String {
    if (!Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(value) || value.replace(":", "").toLong(16) == 0L)
        radioFail(LinuxRadioFailureCode.INVALID_INPUT)
    return value.uppercase(java.util.Locale.ROOT)
}

/** Absolute executables, no shell, no inherited proxy/agent environment, bounded output and total duration. */
internal class BoundedLinuxCommandRunner(private val processStart: (List<String>) -> Process = { command ->
    ProcessBuilder(command).apply {
        environment().clear()
        environment().putAll(mapOf("LC_ALL" to "C", "LANG" to "C", "PATH" to "/usr/sbin:/usr/bin:/sbin:/bin"))
        redirectError(ProcessBuilder.Redirect.DISCARD)
    }.start()
}) : LinuxCommandRunner {
    override fun run(command: List<String>, timeoutMillis: Long, input: String?): LinuxCommandResult {
        checkRadioInterrupted()
        if (command.isEmpty() || command.first() !in EXECUTABLES || timeoutMillis !in 1..45_000 ||
            command.size > 32 || command.any { it.length > 4096 || it.any { character -> character.code < 32 || character.code == 127 } } ||
            input != null && input.toByteArray(Charsets.UTF_8).size > 1024)
            radioFail(LinuxRadioFailureCode.INVALID_INPUT)
        val process = try {
            processStart(command)
        } catch (_: Exception) { radioFail(LinuxRadioFailureCode.COMMAND_FAILED) }
        val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "linux-radio-command").apply { isDaemon = true } }
        val output = workers.submit<ByteArray> {
            process.inputStream.use { it.readNBytes(MAX_OUTPUT_BYTES + 1) }.also { if (it.size > MAX_OUTPUT_BYTES) process.destroyForcibly() }
        }
        val writer = workers.submit {
            process.outputStream.use { stream -> if (input != null) stream.write(input.toByteArray(Charsets.UTF_8)) }
        }
        val start = System.nanoTime()
        val duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (true) {
                checkRadioInterrupted()
                val remaining = duration - (System.nanoTime() - start)
                if (remaining <= 0) radioFail(LinuxRadioFailureCode.COMMAND_TIMEOUT)
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) break
            }
            val remaining = duration - (System.nanoTime() - start)
            if (remaining <= 0) radioFail(LinuxRadioFailureCode.COMMAND_TIMEOUT)
            writer.get(remaining, TimeUnit.NANOSECONDS)
            val bytes = output.get(maxOf(1, duration - (System.nanoTime() - start)), TimeUnit.NANOSECONDS)
            if (bytes.size > MAX_OUTPUT_BYTES) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
            return LinuxCommandResult(process.exitValue(), bytes.toString(Charsets.UTF_8))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); radioFail(LinuxRadioFailureCode.CANCELLED)
        } catch (failure: LinuxRadioFailure) { throw failure }
        catch (_: java.util.concurrent.TimeoutException) { radioFail(LinuxRadioFailureCode.COMMAND_TIMEOUT) }
        catch (_: Exception) { radioFail(LinuxRadioFailureCode.COMMAND_FAILED) }
        finally {
            process.destroyForcibly()
            try { process.inputStream.close() } catch (_: Exception) { }
            try { process.outputStream.close() } catch (_: Exception) { }
            output.cancel(true); writer.cancel(true); workers.shutdownNow()
        }
    }
    private companion object {
        const val MAX_OUTPUT_BYTES = 65_536
        val EXECUTABLES = setOf("/usr/bin/nmcli", "/usr/bin/bluetoothctl", "/usr/bin/sdptool", "/usr/sbin/ip",
            "/usr/bin/readlink", "/usr/sbin/wpa_cli")
    }
}
