package dev.pocketphotobridge.bridge.linux

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.service.*
import dev.pocketphotobridge.bridge.setup.ServiceConfiguration
import dev.pocketphotobridge.bridge.setup.ServiceConfigStore
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterSession
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID

/** Both probes are structurally unable to invoke printNext or PrinterSession.print. */
internal class NoPrintProbe(private val state: Path, private val config: ServiceConfiguration,
    private val camera: CameraWifiConnector, private val printer: PrinterConnector,
    private val report: (String) -> Unit, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    fun run(receiveOnly: Boolean): Int {
        report("NO_PHYSICAL_PRINT")
        // Use the service's actual queue and print journal, including their exclusive locks.
        // A running service or unknown print blocks this probe before any transport is opened.
        AutonomousBridge(state.resolve("service"), camera, printer, JvmPrintImagePreparer(),
            { config.printerToken }, config.crop, report, now, pause).use { bridge ->
            val before = bridge.snapshot()
            if (before.state in setOf(ServiceState.NEEDS_ATTENTION, ServiceState.STATE_ERROR)) return 3
            if (receiveOnly) {
                val result = bridge.capture(SelectionTrigger("linux-probe-${UUID.randomUUID()}"))
                return when {
                    result.state in setOf(ServiceState.NEEDS_ATTENTION, ServiceState.STATE_ERROR) -> 3
                    result.category != null && result.category !in setOf("CAMERA_ACK_PENDING", "DUPLICATE_SUPPRESSED") -> 2
                    result.queued > 0 || result.category == "DUPLICATE_SUPPRESSED" -> 0
                    else -> 2
                }
            }
            printer.connect().use { transport ->
                PrinterSession(transport, config.printerToken, { report("PRINTER_${it.stage.name}") }, now, pause).use {
                    it.diagnose()
                }
            }
            return 0
        }
    }
}

object LinuxDiagnostics {
    fun run(receiveOnly: Boolean, state: Path, output: PrintStream): Int {
        if (!System.getProperty("os.name").startsWith("Linux") ||
            System.getProperty("os.arch") !in setOf("amd64", "x86_64", "aarch64", "arm64")) {
            output.println("LINUX_REQUIRED"); return 64
        }
        val diagnostics = ServiceDiagnostics(state, output)
        return try {
            BridgeService.privateRoot(state)
            val config = ServiceConfigStore(state.resolve("setup")).load()
                ?: throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
            val devices = LinuxServiceDevices(state, diagnostics::emit)
            devices.use {
                NoPrintProbe(state, config,
                    CameraWifiConnector { devices.camera(config, it) },
                    PrinterConnector { devices.printer(config) }, diagnostics::emit).run(receiveOnly)
            }
        } catch (failure: Exception) {
            val category = if (failure is PrinterFailure) failure.code.name else BridgeService.category(failure)
            diagnostics.emit("SERVICE_FAILED category=$category"); 2
        } catch (_: LinkageError) {
            diagnostics.emit("SERVICE_FAILED category=NATIVE_UNAVAILABLE"); 2
        }
    }
}
