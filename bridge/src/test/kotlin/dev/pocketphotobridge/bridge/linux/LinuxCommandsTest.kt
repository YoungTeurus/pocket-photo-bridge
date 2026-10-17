package dev.pocketphotobridge.bridge.linux

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class LinuxCommandsTest {
    @Test fun `runner permits bounded driver and supplicant commands without a shell`() {
        val calls = mutableListOf<List<String>>()
        val runner = BoundedLinuxCommandRunner { command -> calls += command; FakeProcess("OK\n".toByteArray()) }
        listOf(listOf("/usr/bin/readlink", "-f", "/sys/class/net/wlan0/device/driver"),
            listOf("/usr/sbin/wpa_cli", "-i", "wlan0", "get_network", "7", "key_mgmt")).forEach { command ->
            assertEquals(LinuxCommandResult(0, "OK\n"), runner.run(command, 500, null))
        }
        assertEquals(2, calls.size)
    }
    @Test fun `runner rejects shell executables excess timeout and injected command controls before launch`() {
        var launches = 0
        val runner = BoundedLinuxCommandRunner { launches++; FakeProcess(byteArrayOf()) }
        listOf(listOf("/bin/sh", "-c", "echo secret"), listOf("/usr/bin/nmcli", "bad\nargument"),
            listOf("/usr/bin/nmcli", "\u0000")).forEach {
            assertEquals(LinuxRadioFailureCode.INVALID_INPUT, assertThrows(LinuxRadioFailure::class.java) { runner.run(it, 100, null) }.code)
        }
        assertThrows(LinuxRadioFailure::class.java) { runner.run(listOf("/usr/bin/nmcli"), 45_001, null) }
        assertEquals(0, launches)
    }

    @Test fun `runner bounds raw output without including it in failure`() {
        val process = FakeProcess(ByteArray(65_537) { 'x'.code.toByte() })
        val runner = BoundedLinuxCommandRunner { process }
        val failure = assertThrows(LinuxRadioFailure::class.java) { runner.run(listOf("/usr/bin/nmcli"), 500, null) }
        assertEquals("COMMAND_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(process.destroyed)
    }

    @Test fun `runner has a total deadline and terminates stalled process`() {
        val process = FakeProcess(byteArrayOf()).apply { complete = false }
        val runner = BoundedLinuxCommandRunner { process }
        assertEquals(LinuxRadioFailureCode.COMMAND_TIMEOUT, assertThrows(LinuxRadioFailure::class.java) { runner.run(listOf("/usr/bin/nmcli"), 20, null) }.code)
        assertTrue(process.destroyed)
    }

    @Test fun `runner returns bounded data privately and closes process input`() {
        val process = FakeProcess("local output".toByteArray())
        val runner = BoundedLinuxCommandRunner { process }
        assertEquals(LinuxCommandResult(0, "local output"), runner.run(listOf("/usr/bin/nmcli"), 500, null))
        assertEquals(0, process.input.size())
        assertTrue(process.destroyed)
    }

    private class FakeProcess(output: ByteArray) : Process() {
        val input = ByteArrayOutputStream()
        private val output = ByteArrayInputStream(output)
        var complete = true
        var destroyed = false
        override fun getOutputStream(): OutputStream = input
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!complete) Thread.sleep(minOf(5, unit.toMillis(timeout)))
            return complete
        }
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
