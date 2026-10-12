package dev.pocketphotobridge.bridge

import dev.pocketphotobridge.printer.PrinterDiagnostic
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterSession
import dev.pocketphotobridge.printer.PrinterStage
import dev.pocketphotobridge.sony.ProtocolFailure
import dev.pocketphotobridge.sony.SonyTransferSession
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The same coordinator is used by simulation and future radio adapters. No retry is implicit. */
class PrintCoordinator(private val camera: CameraWifiConnector, private val printer: PrinterConnector,
    private val images: PrintImagePreparer, private val journal: FileBridgeJournal, private val cache: Path,
    private val token: () -> String, private val crop: Boolean = false, private val report: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) {

    @Synchronized fun process(trigger: SelectionTrigger): BridgeResult {
        var failurePhase = BridgeOutcome.CAMERA_FAILED
        var directory: File? = null
        var reportedPrinting = false
        try {
            checkActive()
            if (journal.unresolved) return result(BridgeOutcome.UNKNOWN_OUTCOME, PrinterFailureCode.UNRESOLVED_JOB.name)
            if (!journal.consumeEvent(trigger.id)) return result(BridgeOutcome.DUPLICATE_SUPPRESSED)
            report("BRIDGE_CAMERA_CONNECTING")
            Files.createDirectories(cache)
            directory = Files.createTempDirectory(cache, "receive-").toFile()
            val photo = camera.connect(trigger).use { link ->
                report("BRIDGE_RECEIVING")
                SonyTransferSession(link.transport, link.endpoint, report = { report("SONY_${it.safeText()}") },
                    validateJpeg = images::validateSource).receive(directory)
            }
            failurePhase = BridgeOutcome.IMAGE_FAILED
            checkActive()
            val fingerprint = fingerprint(photo.file)
            if (journal.wasSubmitted(fingerprint)) return result(BridgeOutcome.DUPLICATE_SUPPRESSED)
            report("BRIDGE_IMAGE_PREPARING")
            val prepared = images.prepare(photo.file, crop)
            checkActive()
            journal.selectPhoto(fingerprint)
            failurePhase = BridgeOutcome.PRINTER_FAILED
            report("BRIDGE_PRINTER_CONNECTING")
            printer.connect().use { transport ->
                val printerReports: (PrinterDiagnostic) -> Unit = { diagnostic ->
                    // Retain useful progress without flooding the user with each virtual poll/frame.
                    if (diagnostic.stage in PROGRESS) report(diagnostic.safeText())
                    if (diagnostic.stage == PrinterStage.PRINTING && !reportedPrinting) {
                        reportedPrinting = true
                        report(diagnostic.safeText())
                    }
                }
                PrinterSession(transport, token(), printerReports, now, pause).use { session ->
                    session.print(prepared, journal)
                }
            }
            return result(BridgeOutcome.PRINTED)
        } catch (failure: Exception) {
            val uncertain = try { journal.unresolved } catch (_: Exception) {
                return result(BridgeOutcome.STATE_FAILED, BridgeFailureCode.PERSISTENCE_ERROR.name)
            }
            val category = when (failure) {
                is BridgeFailure -> failure.code.name
                is PrinterFailure -> failure.code.name
                is ProtocolFailure -> failure.failure.name
                is InterruptedException -> BridgeFailureCode.CANCELLED.name
                else -> BridgeFailureCode.IO_ERROR.name
            }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (uncertain) return result(BridgeOutcome.UNKNOWN_OUTCOME, category)
            val outcome = when {
                category == "CANCELLED" -> BridgeOutcome.CANCELLED
                failure is BridgeFailure && failure.code == BridgeFailureCode.INVALID_IMAGE -> BridgeOutcome.IMAGE_FAILED
                failure is BridgeFailure && failure.code in setOf(BridgeFailureCode.PERSISTENCE_ERROR, BridgeFailureCode.INSTANCE_RUNNING) -> BridgeOutcome.STATE_FAILED
                else -> failurePhase
            }
            return result(outcome, category)
        } finally {
            // Only this operation's private cache is deleted, including failures before TransferEnd.
            directory?.deleteRecursively()
        }
    }

    private fun result(outcome: BridgeOutcome, category: String? = null): BridgeResult {
        report("BRIDGE_${outcome.name}" + (category?.let { " category=$it" } ?: ""))
        return BridgeResult(outcome, category)
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 64L * 1024 * 1024) throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw BridgeFailure(BridgeFailureCode.CANCELLED)
    }

    companion object {
        private val PROGRESS = setOf(PrinterStage.PREFLIGHT, PrinterStage.JOB_RESERVED, PrinterStage.UPLOAD,
            PrinterStage.JOB_CONFIRMED, PrinterStage.PRINT_FINISHED, PrinterStage.OUTCOME_UNKNOWN, PrinterStage.REJECTED)
    }
}
