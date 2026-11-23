package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.linux.LinuxRadioFailure
import dev.pocketphotobridge.printer.PrintJobGuard
import dev.pocketphotobridge.printer.PrinterDiagnostic
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
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
/** [dropped] counts photos forgotten after a printer refusal since the last successful print. */
data class ServiceSnapshot(val state: ServiceState, val queued: Int, val category: String? = null, val dropped: Int = 0) {
    /** The print step failed: the printer was unreachable, busy or refusing. An empty queue or a job needing attention is not a failure. */
    val printAttemptFailed get() = state == ServiceState.WAITING_PRINTER
    val printStep get() = when {
        printAttemptFailed -> PrintStep.FAILED
        state == ServiceState.PRINTED -> PrintStep.PRINTED
        else -> PrintStep.IDLE
    }
    val captureStep get() = when {
        category == "DUPLICATE_SUPPRESSED" -> CaptureStep.DUPLICATE
        category == "QUEUE_FULL" || state in setOf(ServiceState.NEEDS_ATTENTION, ServiceState.STATE_ERROR) -> CaptureStep.BLOCKED
        state == ServiceState.QUEUED -> CaptureStep.NEW_PHOTO
        else -> CaptureStep.RETRY
    }
}

/** Camera acknowledgement and printer availability are independent durable boundaries. */
/** Holds the report lines of a repeated print attempt until its outcome shows whether they say anything new. */
interface AttemptLog {
    fun hold()
    fun release(keep: Boolean)
    companion object { val NONE = object : AttemptLog { override fun hold() {}; override fun release(keep: Boolean) {} } }
}

class AutonomousBridge(stateDir: Path, private val camera: CameraWifiConnector, private val printer: PrinterConnector,
    private val images: PrintImagePreparer, private val token: () -> String, private val crop: Boolean = false,
    private val report: (String) -> Unit = {}, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }, private val attempts: AttemptLog = AttemptLog.NONE) : AutoCloseable {
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
        // Only this unattended service forgets a refused photo; the journal's own guard keeps suppressing it.
        override fun release(reservation: String) {
            checkJournalDisk()
            journal.withdraw(reservation)
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
    private var retries = 0
    private var dropped = 0
    private val seriesRefusals = mutableSetOf<Long>()
    /** Photos dropped after a refusal in this selection generation (one camera presence); memory only. */
    private val droppedPhotos = mutableSetOf<String>()
    private var generation: String? = null

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

    @Synchronized fun beginBoot(bootId: String, keepQueueAcrossBoots: Boolean): ServiceSnapshot {
        try {
            checkState()
            val count = queue.beginBoot(bootId, keepQueueAcrossBoots, journal::wasSubmitted)
            if (count > 0) emit("BRIDGE_BOOT_QUEUE_DISCARDED count=$count")
            return result(baseState())
        } catch (_: Exception) { return stateError() }
    }

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
            // A new generation follows an observed absence, so the owner may send a dropped photo again.
            if (trigger.id != generation) { generation = trigger.id; droppedPhotos.clear() }
            if (!queue.canAccept()) return result(ServiceState.QUEUED, "QUEUE_FULL")
            result(ServiceState.RECEIVING)
            emit("BRIDGE_CAMERA_CONNECTING")
            receiving = queue.receiveDirectory()
            camera.connect(trigger).use { link ->
                SonyTransferSession(link.transport, link.endpoint, report = {
                    if (it.stage == Stage.END_UNCONFIRMED) endUnconfirmed = true
                    emit("SONY_" + it.safeText())
                }, validateJpeg = { source ->
                    checkActive()
                    // A repeat is recognised without decoding; preparation decodes and validates a new source.
                    val fingerprint = DurableCaptureQueue.fingerprint(source.toPath())
                    if (queue.contains(fingerprint) || journal.wasSubmitted(fingerprint) || fingerprint in droppedPhotos) duplicate = true
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
        val previous = current
        val retrying = previous.state == ServiceState.WAITING_PRINTER && previous.category != null
        var refusal: Long? = null
        try {
            checkState()
            if (journal.unresolved) return result(ServiceState.NEEDS_ATTENTION, "UNRESOLVED_JOB")
            checkActive()
            val entry = queue.entries.firstOrNull() ?: return result(ServiceState.WAITING_CAMERA)
            val prepared = queue.prepared(entry)
            journal.selectPhoto(entry.fingerprint)
            // Every line of a retry, radio steps included, is held until its outcome shows whether it repeats the last one.
            if (retrying) attempts.hold()
            result(ServiceState.WAITING_PRINTER); emit("BRIDGE_PRINTER_CONNECTING")
            var printingReported = false
            try { printer.connect().use { transport ->
                val progress: (PrinterDiagnostic) -> Unit = { diagnostic ->
                    if (diagnostic.stage in PROGRESS) emit("PRINTER_${diagnostic.stage.name}")
                    if (diagnostic.stage == PrinterStage.SUBMIT_REFUSED) { refusal = diagnostic.code; emit(diagnostic.safeText()) }
                    if (diagnostic.stage == PrinterStage.JOB_RESERVED) {
                        attempts.release(keep = true)
                        result(ServiceState.PRINTING)
                    }
                    if (diagnostic.stage == PrinterStage.PRINTING && !printingReported) {
                        printingReported = true
                        emit("PRINTER_PRINTING")
                    }
                }
                PrinterSession(transport, token(), progress, now, pause).use { it.print(prepared, guard) }
            } } catch (rejected: PrinterFailure) {
                // The guard already forgot the refused photo; dropping it after that lets the camera send it again.
                if (rejected.code == PrinterFailureCode.JOB_REJECTED && refusal != null) {
                    queue.remove(entry)
                    droppedPhotos += entry.fingerprint
                    dropped++
                    emit("BRIDGE_QUEUE_RECONCILED count=1")
                }
                throw rejected
            }
            queue.remove(entry)
            dropped = 0
            return result(ServiceState.PRINTED)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val outcome = recover(failure, ServiceState.WAITING_PRINTER)
            // A refusal code is news once per retry series, even between busy preflights with the same outcome.
            if (!retrying) seriesRefusals.clear()
            val newRefusal = refusal?.let(seriesRefusals::add) ?: false
            val repeated = retrying && outcome == previous && !newRefusal
            attempts.release(keep = !repeated)
            retries = if (repeated) retries + 1 else 0
            // Doubling intervals keep a day of retries to a few dozen lines.
            if (Integer.bitCount(retries) == 1)
                emit("BRIDGE_WAITING_PRINTER queued=${outcome.queued} category=${outcome.category} count=$retries")
            return outcome
        } finally { attempts.release(keep = true) }
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
        if (journal.unresolved) return
        val submitted = queue.entries.filter { journal.wasSubmitted(it.fingerprint) }
        submitted.forEach(queue::remove)
        if (submitted.isNotEmpty()) emit("BRIDGE_QUEUE_RECONCILED count=${submitted.size}")
    }
    private fun baseState() = when {
        journal.unresolved -> ServiceState.NEEDS_ATTENTION
        queue.size > 0 -> ServiceState.QUEUED
        else -> ServiceState.WAITING_CAMERA
    }
    private fun result(state: ServiceState, category: String? = null): ServiceSnapshot {
        val next = ServiceSnapshot(state, queue.size, category, dropped)
        // An unchanged state is not re-emitted, so idle hours cannot evict the run.
        if (next == current) return current
        current = next
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
        is LinuxRadioFailure -> failure.code.name
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
