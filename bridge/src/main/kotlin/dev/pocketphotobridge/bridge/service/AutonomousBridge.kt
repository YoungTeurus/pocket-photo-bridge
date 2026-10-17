package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.printer.PrintJobGuard
import dev.pocketphotobridge.printer.PrinterDiagnostic
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterImageLayout
import dev.pocketphotobridge.printer.PrinterSession
import dev.pocketphotobridge.printer.PrinterStage
import dev.pocketphotobridge.sony.ProtocolFailure
import dev.pocketphotobridge.sony.SonyTransferSession
import dev.pocketphotobridge.sony.Stage
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

enum class ServiceState { WAITING_CAMERA, RECEIVING, QUEUED, WAITING_PRINTER, PRINTING, PRINTED, NEEDS_ATTENTION, STATE_ERROR }
data class ServiceSnapshot(val state: ServiceState, val queued: Int, val category: String? = null)

/** Camera acknowledgement and printer availability are independent durable boundaries. */
class AutonomousBridge(stateDir: Path, private val camera: CameraWifiConnector, private val printer: PrinterConnector,
    private val images: PrintImagePreparer, private val token: () -> String, private val crop: Boolean = false,
    private val report: (String) -> Unit = {}, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) : AutoCloseable {
    private val queue = DurableCaptureQueue(stateDir)
    private val journal: FileBridgeJournal
    private var journalHash: String? = null
    private val guard = object : PrintJobGuard {
        override val unresolved get() = journal.unresolved
        override fun reserve(): String {
            checkJournalDisk()
            return journal.reserve().also { journalHash = readJournalHash() }
        }
        override fun resolve(reservation: String) {
            checkJournalDisk()
            journal.resolve(reservation)
            journalHash = readJournalHash()
        }
        override fun acknowledgeCheckedPrinter() {
            checkJournalDisk()
            journal.acknowledgeCheckedPrinter()
            journalHash = readJournalHash()
        }
    }
    private var closed = false
    private var failed = false
    private var current = ServiceSnapshot(ServiceState.WAITING_CAMERA, queue.size)

    init {
        var opened: FileBridgeJournal? = null
        try {
            val journalRoot = queue.root.resolve("print-journal")
            if (Files.isSymbolicLink(journalRoot) || (!queue.fresh &&
                (!Files.isRegularFile(journalRoot.resolve("journal.state"), NOFOLLOW_LINKS) ||
                    !Files.isRegularFile(journalRoot.resolve("instance.lock"), NOFOLLOW_LINKS))))
                throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
            opened = FileBridgeJournal(journalRoot)
            journal = opened
            journalHash = readJournalHash()
            reconcile()
            current = ServiceSnapshot(baseState(), queue.size)
        } catch (failure: Exception) {
            try { opened?.close() } finally { queue.close() }
            throw if (failure is BridgeFailure) failure else BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun beginBoot(bootId: String, keepQueueAcrossBoots: Boolean): ServiceSnapshot = snapshot()

    @Synchronized fun capture(trigger: SelectionTrigger): ServiceSnapshot {
        var receiving: Path? = null
        var retained = false
        var duplicate = false
        var endUnconfirmed = false
        try {
            checkState()
            if (journal.unresolved) return result(ServiceState.NEEDS_ATTENTION, "UNRESOLVED_JOB")
            checkActive()
            val triggerBytes = trigger.id.toByteArray(Charsets.UTF_8)
            if (triggerBytes.isEmpty() || triggerBytes.size > 1024) throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
            if (!queue.canAccept()) return result(ServiceState.QUEUED, "QUEUE_FULL")
            result(ServiceState.RECEIVING)
            emit("BRIDGE_CAMERA_CONNECTING")
            receiving = queue.receiveDirectory()
            camera.connect(trigger).use { link ->
                SonyTransferSession(link.transport, link.endpoint, report = {
                    if (it.stage == Stage.END_UNCONFIRMED) endUnconfirmed = true
                    emit("SONY_${it.stage.name}")
                }, validateJpeg = { source ->
                    checkActive()
                    images.validateSource(source)
                    val fingerprint = DurableCaptureQueue.fingerprint(source.toPath())
                    if (queue.contains(fingerprint) || journal.wasSubmitted(fingerprint)) duplicate = true
                    else {
                        emit("BRIDGE_IMAGE_PREPARING")
                        val prepared = images.prepare(source, crop)
                        checkActive()
                        validatePrepared(prepared, receiving!!)
                        queue.add(source, fingerprint, prepared)
                        retained = true
                    }
                }).receive(receiving!!.toFile())
            }
            return result(baseState(), when {
                duplicate -> "DUPLICATE_SUPPRESSED"
                endUnconfirmed -> "CAMERA_ACK_PENDING"
                else -> null
            })
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return recover(failure, if (retained) ServiceState.QUEUED else ServiceState.WAITING_CAMERA,
                if (retained) "CAMERA_ACK_PENDING" else null)
        } finally {
            // This private receive directory is never the durable image directory.
            receiving?.toFile()?.deleteRecursively()
        }
    }

    @Synchronized fun printNext(): ServiceSnapshot {
        try {
            checkState()
            if (journal.unresolved) return result(ServiceState.NEEDS_ATTENTION, "UNRESOLVED_JOB")
            checkActive()
            val entry = queue.entries.firstOrNull() ?: return result(ServiceState.WAITING_CAMERA)
            val prepared = queue.prepared(entry)
            journal.selectPhoto(entry.fingerprint)
            result(ServiceState.WAITING_PRINTER)
            emit("BRIDGE_PRINTER_CONNECTING")
            var printingReported = false
            printer.connect().use { transport ->
                val progress: (PrinterDiagnostic) -> Unit = { diagnostic ->
                    if (diagnostic.stage in PROGRESS) emit("PRINTER_${diagnostic.stage.name}")
                    if (diagnostic.stage == PrinterStage.JOB_RESERVED)
                        result(ServiceState.PRINTING)
                    if (diagnostic.stage == PrinterStage.PRINTING && !printingReported) {
                        printingReported = true
                        emit("PRINTER_PRINTING")
                    }
                }
                PrinterSession(transport, token(), progress, now, pause).use { it.print(prepared, guard) }
            }
            queue.remove(entry)
            return result(ServiceState.PRINTED)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return recover(failure, ServiceState.WAITING_PRINTER)
        }
    }

    @Synchronized fun snapshot(): ServiceSnapshot {
        try {
            checkState()
            if (journal.unresolved) return result(ServiceState.NEEDS_ATTENTION, current.category ?: "UNRESOLVED_JOB")
            return current.copy(queued = queue.size)
        } catch (_: Exception) { return stateError() }
    }

    @Synchronized fun acknowledgeCheckedPrinter(): ServiceSnapshot {
        try {
            checkState()
            guard.acknowledgeCheckedPrinter()
            // Tombstones remain in the print journal; queue cleanup may safely resume after a crash.
            reconcile()
            return result(baseState())
        } catch (_: Exception) { return stateError() }
    }

    private fun recover(failure: Exception, state: ServiceState, categoryOverride: String? = null): ServiceSnapshot {
        try {
            checkState()
            if (failure is BridgeFailure && failure.code in setOf(BridgeFailureCode.PERSISTENCE_ERROR, BridgeFailureCode.INSTANCE_RUNNING))
                return stateError()
            if (journal.unresolved) return result(ServiceState.NEEDS_ATTENTION, category(failure))
            return result(state, categoryOverride ?: category(failure))
        } catch (_: Exception) { return stateError() }
    }

    private fun checkState() {
        if (closed || failed) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        queue.verify()
        checkJournalDisk()
        reconcile()
    }
    private fun checkJournalDisk() {
        if (readJournalHash() != journalHash) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
    }
    private fun readJournalHash(): String {
        val root = queue.root.resolve("print-journal")
        val snapshot = root.resolve("journal.state")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isRegularFile(root.resolve("instance.lock"), NOFOLLOW_LINKS) ||
            !Files.isRegularFile(snapshot, NOFOLLOW_LINKS) || Files.size(snapshot) > 16 * 1024 * 1024)
            throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        return DurableCaptureQueue.fingerprint(snapshot)
    }
    private fun reconcile() {
        if (!journal.unresolved) queue.entries.filter { journal.wasSubmitted(it.fingerprint) }.forEach(queue::remove)
    }
    private fun baseState() = when {
        journal.unresolved -> ServiceState.NEEDS_ATTENTION
        queue.size > 0 -> ServiceState.QUEUED
        else -> ServiceState.WAITING_CAMERA
    }
    private fun result(state: ServiceState, category: String? = null): ServiceSnapshot {
        current = ServiceSnapshot(state, queue.size, category)
        emit("BRIDGE_${state.name} queued=${queue.size}" + (category?.let { " category=$it" } ?: ""))
        return current
    }
    private fun stateError(): ServiceSnapshot {
        failed = true
        return result(ServiceState.STATE_ERROR, "PERSISTENCE_ERROR")
    }
    private fun emit(message: String) { try { report(message) } catch (_: Exception) { /* Reporting cannot alter job safety. */ } }
    private fun validatePrepared(prepared: ByteArray, receiving: Path) {
        fun invalid(): Nothing = throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
        if (prepared.size !in 16..PrinterSession.MAX_JPEG_BYTES || prepared[0] != 0xff.toByte() ||
            prepared[1] != 0xd8.toByte() || prepared[prepared.lastIndex - 1] != 0xff.toByte() ||
            prepared.last() != 0xd9.toByte()) invalid()
        var baseline = false
        var scan = false
        var offset = 2
        fun word(at: Int) = ((prepared[at].toInt() and 255) shl 8) or (prepared[at + 1].toInt() and 255)
        while (offset + 4 <= prepared.size) {
            checkActive()
            if (prepared[offset] != 0xff.toByte()) invalid()
            val marker = prepared[offset + 1].toInt() and 255
            val length = word(offset + 2)
            if (length < 2 || offset + 2 + length > prepared.size) invalid()
            if (marker in 0xc0..0xcf && marker !in setOf(0xc0, 0xc4)) invalid()
            if (marker == 0xc0) {
                if (baseline || length < 17 || prepared[offset + 4] != 8.toByte() ||
                    word(offset + 5) != PrinterImageLayout.HEIGHT || word(offset + 7) != PrinterImageLayout.WIDTH) invalid()
                baseline = true
            }
            if (marker == 0xda) { scan = true; break }
            offset += 2 + length
        }
        if (!baseline || !scan) invalid()
        val validationFile = Files.createTempFile(receiving, "prepared-", ".jpg")
        try {
            Files.write(validationFile, prepared)
            images.validateSource(validationFile.toFile())
        } finally { Files.deleteIfExists(validationFile) }
    }
    private fun checkActive() { if (Thread.currentThread().isInterrupted) throw BridgeFailure(BridgeFailureCode.CANCELLED) }
    private fun category(failure: Exception): String = when (failure) {
        is BridgeFailure -> failure.code.name
        is PrinterFailure -> failure.code.name
        is ProtocolFailure -> failure.failure.name
        is QueueCapacityExceeded -> "QUEUE_FULL"
        is InterruptedException -> "CANCELLED"
        else -> "IO_ERROR"
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { journal.close() } finally { queue.close() }
    }

    companion object {
        private val PROGRESS = setOf(PrinterStage.PREFLIGHT, PrinterStage.JOB_RESERVED, PrinterStage.UPLOAD,
            PrinterStage.JOB_CONFIRMED, PrinterStage.PRINT_FINISHED, PrinterStage.OUTCOME_UNKNOWN, PrinterStage.REJECTED)
    }
}
