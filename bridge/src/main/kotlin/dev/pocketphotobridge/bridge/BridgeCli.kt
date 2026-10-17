package dev.pocketphotobridge.bridge

import dev.pocketphotobridge.bridge.simulation.SimulationRunner
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

fun main(arguments: Array<String>) { exitProcess(BridgeCli.run(arguments)) }

object BridgeCli {
    const val VERSION = "0.5.0"

    fun run(arguments: Array<String>, output: PrintStream = System.out, defaultState: Path? = null): Int {
        output.println("Pocket Photo Bridge $VERSION | headless bridge")
        val options = try { CliOptions.parse(arguments) } catch (_: Exception) {
            output.println("BRIDGE_ARGUMENT_ERROR")
            output.println("Use --help for supported options.")
            return 64
        }
        if (options.mode == CliMode.HELP) { output.println(HELP); return 0 }
        if (options.mode == CliMode.BOOTSTRAP)
            return dev.pocketphotobridge.bridge.service.BridgeService.bootstrap(options.stateDir ?: defaultState ?: dev.pocketphotobridge.bridge.service.BridgeService.defaultState(true), output)
        if (options.mode == CliMode.LINUX_NATIVE)
            return dev.pocketphotobridge.bridge.service.BridgeService.nativeCheck(output)
        if (options.mode in setOf(CliMode.LINUX_STATUS, CliMode.LINUX_RECEIVE))
            return dev.pocketphotobridge.bridge.linux.LinuxDiagnostics.run(options.mode == CliMode.LINUX_RECEIVE,
                options.stateDir ?: defaultState ?: dev.pocketphotobridge.bridge.service.BridgeService.defaultState(false), output)
        if (options.mode in setOf(CliMode.SERVICE, CliMode.SERVICE_SIMULATION)) {
            val simulated = options.mode == CliMode.SERVICE_SIMULATION
            return dev.pocketphotobridge.bridge.service.BridgeService.run(simulated,
                options.stateDir ?: defaultState ?: dev.pocketphotobridge.bridge.service.BridgeService.defaultState(simulated), output)
        }
        if (options.mode == CliMode.WINDOWS_LIVE)
            return dev.pocketphotobridge.bridge.windows.WindowsLive.run(options.crop, options.stateDir, output)
        if (options.mode == CliMode.WINDOWS_NATIVE)
            return dev.pocketphotobridge.bridge.windows.WindowsLive.nativeCheck(output)
        if (options.mode == CliMode.HARDWARE) {
            output.println("BRIDGE_HARDWARE_NOT_IMPLEMENTED")
            output.println("Use --service for the configured Linux service. Legacy hardware configuration was not read.")
            return 64
        }
        val report = SimulationReport(output)
        report.emit("SIMULATION_ONLY")
        report.emit("NO_PHYSICAL_PRINT")
        val directory = options.stateDir ?: defaultState ?: defaultStateDirectory()
        var code = try {
            privateDirectory(directory)
            when (options.mode) {
                CliMode.SUITE -> {
                    val run = Files.createTempDirectory(directory, "suite-")
                    val cases = SimulationScenario.entries.filter { it != SimulationScenario.POWER_CUT }
                    var passed = true
                    for (scenario in cases) {
                        val result = SimulationRunner.run(scenario, run.resolve(scenario.option), options.crop, report::emit)
                        passed = passed && result.verified
                    }
                    report.emit(if (passed) "SIMULATION_SUITE_PASS checks=${cases.size}" else "SIMULATION_SUITE_FAILED checks=${cases.size}")
                    if (passed) 0 else 1
                }
                CliMode.SIMULATION -> {
                    val result = SimulationRunner.run(options.scenario, directory, options.crop, report::emit, crash = {
                        report.emit("SIMULATED_POWER_CUT")
                        report.save(directory)
                        Runtime.getRuntime().halt(71)
                    })
                    when {
                        !result.verified -> 1
                        result.outcome in setOf(BridgeOutcome.PRINTED, BridgeOutcome.DUPLICATE_SUPPRESSED) -> 0
                        result.outcome == BridgeOutcome.UNKNOWN_OUTCOME -> 3
                        else -> 2
                    }
                }
                CliMode.ACKNOWLEDGE -> {
                    FileBridgeJournal(directory).use { it.acknowledgeCheckedPrinter() }
                    report.emit("BRIDGE_MANUAL_ACKNOWLEDGED")
                    report.emit("SIMULATED_PRINTS count=0")
                    0
                }
                else -> 64
            }
        } catch (failure: Exception) {
            val category = if (failure is BridgeFailure) failure.code.name else BridgeFailureCode.IO_ERROR.name
            report.emit("BRIDGE_STATE_FAILED category=$category")
            report.emit("SIMULATED_PRINTS count=0")
            2
        }
        try { report.save(directory); report.emit("REPORT_WRITTEN") }
        catch (_: Exception) { report.emit("REPORT_WRITE_FAILED"); if (code == 0) code = 2 }
        if (options.interactive) System.console()?.readLine("Press Enter to close.")
        return code
    }

    private fun defaultStateDirectory(): Path {
        val local = System.getenv("LOCALAPPDATA")
        return if (System.getProperty("os.name").startsWith("Windows") && !local.isNullOrBlank())
            Path.of(local, "PocketPhotoBridge", "simulation")
        else Path.of(System.getProperty("user.home"), ".local", "state", "pocket-photo-bridge", "simulation")
    }

    private fun privateDirectory(path: Path) {
        if (Files.isSymbolicLink(path)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createDirectories(path)
    }

    private const val HELP = """Offline simulation; no camera, printer, WSL or installed Java required in the Windows ZIP.
  --simulate                  One synthetic selection; repeat uses durable duplicate protection.
  --simulate-all              Check all 14 deterministic scenarios in isolated state directories.
  --simulate-failure=<name>    Run one experiment; expected failures still return nonzero.
  --state-dir=<directory>     Local simulation state and diagnostics.txt (paths may contain spaces).
  --crop                      Prepare centered CROP; default is FIT with white borders.
  --acknowledge-checked-printer --confirm
                              Clear uncertainty only; retain selection/photo suppression.
  --hardware [--config=<file>] Legacy mode refused; configuration is not read.
  --simulate-service          Automatic service and local setup page with synthetic devices.
  --service                   Linux automatic service; requires setup and auto-print opt-in.
  --make-bootstrap            Show unique setup credentials in a private local terminal.
  --linux-native-check        Read-only Linux native ABI/error check; no physical print.
  --linux-status-only         Saved setup: SDP/SPP and encrypted status only; stop service first.
  --linux-receive-only        Saved setup: queue one selected Sony JPEG, never print; stop service first.
  --windows-live              Interactive real Windows devices: receive, status, confirmed print.
  --windows-native-check      Read-only Windows native bindings/paired-device diagnostics.
  --help                      Show these instructions.
No arguments: run the suite and wait for Enter when an interactive console is attached.
Scenarios: missing-ssid, camera-offline, camera-timeout, no-selected-jpeg, interrupted-transfer,
printer-offline, printer-busy, dropped-after-submit, uncertain-completion, duplicate-trigger,
duplicate-photo, restart, manual-recovery, power-cut.
Exit codes: 0 success/suppressed; 1 failed simulation check; 2 pre-print/state failure;
3 unknown print outcome; 64 invalid usage/unsupported hardware; 71 simulated process power cut.
Share diagnostics.txt only; it contains fixed stages/counts, no credentials, identities or photos."""
}

private class SimulationReport(private val output: PrintStream) {
    private val lines = ArrayDeque<String>()
    fun emit(message: String) {
        // Callers use only enums/fixed categories/counts; discard any accidental raw diagnostic.
        val safe = if (message.length <= 512 && SAFE_LINE.matches(message)) message else "DIAGNOSTIC_REDACTED"
        if (lines.size == 2048) lines.removeFirst()
        lines.addLast(safe)
        output.println(safe)
    }

    fun save(directory: Path) {
        val destination = directory.resolve("diagnostics.txt")
        if (Files.isSymbolicLink(destination)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        val temporary = Files.createTempFile(directory, "report-", ".tmp")
        try {
            val header = "Pocket Photo Bridge ${BridgeCli.VERSION} | headless simulation\nSIMULATION_ONLY\nNO_PHYSICAL_PRINT\n"
            Files.writeString(temporary, header + lines.joinToString("\n") + "\n", Charsets.UTF_8)
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private val SAFE_LINE = Regex("[A-Z][A-Z0-9_]*( (?:(?:count|checks)=[0-9]+|(?:category|reason)=[A-Z][A-Z0-9_]*))*")
    }
}
