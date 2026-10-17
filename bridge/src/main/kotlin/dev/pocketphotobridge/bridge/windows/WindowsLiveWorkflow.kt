package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.printer.*
import dev.pocketphotobridge.sony.ProtocolFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.UUID

interface LiveUi {
    fun line(message: String)
    fun ask(message: String): String?
    fun password(message: String): CharArray?
    fun preview(path: Path)
}
interface LiveDeviceAccess {
    fun camera(ui: LiveUi): CameraWifiConnector
    fun printer(ui: LiveUi): PrinterConnector
    fun printer(ui: LiveUi, report: (String) -> Unit): PrinterConnector = printer(ui)
    fun nativeCheck(report: (String) -> Unit)
}
enum class LiveAction { RECEIVE, STATUS, PRINT, NATIVE, ACKNOWLEDGE }

/** Live and simulated hardware share the existing coordinator; UI text is never a diagnostic. */
class WindowsLiveWorkflow(private val devices: LiveDeviceAccess, private val ui: LiveUi,
    private val images: PrintImagePreparer = JvmPrintImagePreparer(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    fun run(action: LiveAction, state: Path, crop: Boolean = false): Int {
        val report = LiveReport(ui)
        report.emit("LIVE_DEVICE_MODE")
        report.emit("WINDOWS_HARDWARE_VALIDATION_PENDING")
        if (action != LiveAction.PRINT) report.emit("LIVE_NO_PRINT_OPERATION")
        var code = 2
        try {
            privateDirectory(state)
            code = when (action) {
                LiveAction.NATIVE -> { devices.nativeCheck(report::emit); 0 }
                LiveAction.ACKNOWLEDGE -> {
                    if (ui.ask("Check the actual printer first. Type CHECKED to clear uncertainty without retrying the old photo:") != "CHECKED")
                        throw BridgeFailure(BridgeFailureCode.CANCELLED)
                    FileBridgeJournal(state.resolve("physical-jobs")).use { it.acknowledgeCheckedPrinter() }
                    report.emit("LIVE_MANUAL_ACKNOWLEDGED"); 0
                }
                LiveAction.STATUS -> status(report)
                LiveAction.RECEIVE, LiveAction.PRINT -> transfer(action, state, crop, report)
            }
        } catch (e: Exception) {
            val category = when (e) {
                is BridgeFailure -> e.code.name
                is PrinterFailure -> e.code.name
                is ProtocolFailure -> e.failure.name
                is WindowsBluetoothFailure -> e.code.name
                else -> "IO_ERROR"
            }
            report.emit("LIVE_FAILED category=$category")
            if (uncertainAfterFailure(action, state)) {
                report.emit("BRIDGE_UNKNOWN_OUTCOME category=$category"); code = 3
            }
        } catch (_: LinkageError) {
            report.emit("LIVE_FAILED category=NATIVE_UNAVAILABLE")
            if (uncertainAfterFailure(action, state)) {
                report.emit("BRIDGE_UNKNOWN_OUTCOME category=NATIVE_UNAVAILABLE"); code = 3
            }
        }
        try { report.save(state) } catch (_: Exception) { report.emit("LIVE_REPORT_WRITE_FAILED"); if (code == 0) code = 2 }
        return code
    }

    private fun uncertainAfterFailure(action: LiveAction, state: Path): Boolean {
        val directory = state.resolve("physical-jobs")
        if (action != LiveAction.PRINT || !Files.exists(directory)) return false
        return try { FileBridgeJournal(directory).use { it.unresolved } } catch (_: Exception) { true }
    }

    private fun status(report: LiveReport): Int {
        val printer = devices.printer(ui, report::emit)
        val token = readToken()
        printer.connect().use { transport ->
            PrinterSession(transport, token, { report.emit(it.safeText()) }, now, pause).use { session ->
                val status = session.diagnose()
                report.emit("LIVE_PRINTER_STATUS_READY")
                status.battery?.let { report.emit("LIVE_PRINTER_BATTERY count=$it") }
            }
        }
        return 0
    }

    private fun transfer(action: LiveAction, state: Path, crop: Boolean, report: LiveReport): Int {
        val printing = action == LiveAction.PRINT
        val policy = state.resolve(if (printing) "physical-jobs" else "capture-state")
        FileBridgeJournal(policy).use { journal ->
            if (printing && journal.unresolved) { report.emit("BRIDGE_UNKNOWN_OUTCOME category=UNRESOLVED_JOB"); return 3 }
            val camera = devices.camera(ui)
            val printer = if (printing) devices.printer(ui, report::emit) else null
            val token = if (printing) readToken() else ""
            var retained = false
            val retainingImages = object : PrintImagePreparer {
                override fun validateSource(source: File) = images.validateSource(source)
                override fun prepare(source: File, crop: Boolean): ByteArray {
                    val bytes = images.prepare(source, crop)
                    val directory = state.resolve("photos").resolve(UUID.randomUUID().toString())
                    privateDirectory(state.resolve("photos")); privateDirectory(directory)
                    Files.copy(source.toPath(), directory.resolve("original.jpg"))
                    val preview = directory.resolve("print-preview.jpg")
                    Files.write(preview, bytes)
                    retained = true
                    report.emit("LIVE_PHOTO_SAVED")
                    ui.line("Original and print preview saved locally in: $directory")
                    if (ui.ask("Type VIEW to open the print preview, or press Enter to continue:") == "VIEW") ui.preview(preview)
                    return bytes
                }
            }
            val confirmedPrinter = PrinterConnector {
                if (!printing) throw BridgeFailure(BridgeFailureCode.CANCELLED)
                if (ui.ask("This will consume one sheet. Type PRINT to print the received photo once, or anything else to cancel:") != "PRINT")
                    throw BridgeFailure(BridgeFailureCode.CANCELLED)
                report.emit("LIVE_PRINT_CONFIRMED")
                printer!!.connect()
            }
            val result = PrintCoordinator(camera, confirmedPrinter, retainingImages, journal,
                state.resolve("cache"), { token }, crop, {
                    if (!printing && retained && it.startsWith("BRIDGE_CANCELLED")) report.emit("LIVE_CAPTURE_COMPLETE")
                    else report.emit(it)
                }, now, pause).process(SelectionTrigger(UUID.randomUUID().toString()))
            if (!printing && retained && result.outcome == BridgeOutcome.CANCELLED) {
                report.emit("LIVE_CAMERA_RECEIVED"); return 0
            }
            return when (result.outcome) {
                BridgeOutcome.PRINTED, BridgeOutcome.DUPLICATE_SUPPRESSED -> 0
                BridgeOutcome.UNKNOWN_OUTCOME -> 3
                else -> 2
            }
        }
    }

    private fun readToken(): String {
        val chars = ui.password("Enter your own 24-character printer TOKEN (hidden; not saved):")
            ?: throw BridgeFailure(BridgeFailureCode.CANCELLED)
        try {
            val value = chars.concatToString().trim()
            PrinterCipher(value).close() // Reject invalid keys before opening a connection.
            return value
        } finally { chars.fill('\u0000') }
    }
}

internal fun privateDirectory(path: Path) {
    if (Files.isSymbolicLink(path)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
    Files.createDirectories(path)
    if (!Files.isDirectory(path)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
}

private class LiveReport(private val ui: LiveUi) {
    private val lines = ArrayDeque<String>()
    fun emit(value: String) {
        val permitted = value.substringBefore(' ').let { stage ->
            listOf("LIVE_", "WINDOWS_", "PRINTER_", "BRIDGE_", "SONY_").any(stage::startsWith)
        }
        val safe = if (permitted && value.length <= 512 && SYNTAX.matches(value)) value else "DIAGNOSTIC_REDACTED"
        if (lines.size == 2048) lines.removeFirst()
        lines.addLast(safe); ui.line(safe)
    }
    fun save(state: Path) {
        val destination = state.resolve("diagnostics.txt")
        if (Files.isSymbolicLink(destination)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        val temporary = Files.createTempFile(state, "diagnostic-", ".tmp")
        try {
            Files.writeString(temporary, "Pocket Photo Bridge ${dev.pocketphotobridge.bridge.BridgeCli.VERSION} | Windows live devices\n" + lines.joinToString("\n") + "\n")
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object {
        private val SYNTAX = Regex("[A-Z][A-Z0-9_]*(?: [a-z_]+=[A-Z0-9_]+)*")
    }
}
