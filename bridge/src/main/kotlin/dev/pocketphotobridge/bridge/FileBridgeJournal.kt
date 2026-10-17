package dev.pocketphotobridge.bridge

import dev.pocketphotobridge.printer.PrintJobGuard
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Single owner, fail-closed snapshots. No secrets, names, URLs or photo bytes are stored here. */
class FileBridgeJournal(directory: Path) : PrintJobGuard, AutoCloseable {
    private data class Snapshot(val events: Set<String> = emptySet(), val photos: Set<String> = emptySet(),
        val reservation: String? = null)

    private val root = directory.toAbsolutePath().normalize()
    private val stateFile = root.resolve("journal.state")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var state = Snapshot()
    private var selectedPhoto: String? = null
    private var closed = false
    private var poisoned = false
    private val owner = Any()
    private var registeredRoot: Path? = null
    private val posix = root.fileSystem.supportedFileAttributeViews().contains("posix")

    init {
        try {
            if (Files.isSymbolicLink(root)) persistence()
            if (posix) Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else Files.createDirectories(root)
            // On POSIX, closing a second descriptor can release this process's first lock.
            // Reject another in-process owner before opening any descriptor for the lock file.
            val identity = root.toRealPath()
            if (LOCAL_OWNERS.putIfAbsent(identity, owner) != null) throw BridgeFailure(BridgeFailureCode.INSTANCE_RUNNING)
            registeredRoot = identity
            val lockFile = root.resolve("instance.lock")
            val previousOwner = Files.exists(lockFile, NOFOLLOW_LINKS)
            if (Files.isSymbolicLink(lockFile) || Files.isSymbolicLink(stateFile)) persistence()
            channel = FileChannel.open(lockFile, CREATE, WRITE)
            secure(lockFile)
            lock = try { channel!!.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw BridgeFailure(BridgeFailureCode.INSTANCE_RUNNING)
            if (Files.exists(stateFile, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(stateFile, NOFOLLOW_LINKS) || Files.size(stateFile) > MAX_STATE_BYTES) persistence()
                state = decode(Files.readString(stateFile, Charsets.UTF_8))
                secure(stateFile)
            } else {
                // A lost snapshot cannot be mistaken for a new installation.
                if (previousOwner) persistence()
                persist(state)
            }
        } catch (failure: Exception) {
            close()
            throw if (failure is BridgeFailure) failure else BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        }
    }

    override val unresolved: Boolean
        get() = synchronized(this) { checkOpen(); state.reservation != null }

    @Synchronized fun consumeEvent(id: String): Boolean {
        checkOpen()
        val bytes = id.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 1024) throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
        val digest = fingerprint(bytes)
        if (digest in state.events) return false
        update(state.copy(events = state.events + digest))
        return true
    }

    @Synchronized fun wasSubmitted(photo: String): Boolean {
        checkOpen(); validateDigest(photo)
        return photo in state.photos
    }

    @Synchronized fun selectPhoto(photo: String) {
        checkOpen(); validateDigest(photo)
        selectedPhoto = photo
    }

    @Synchronized override fun reserve(): String {
        checkOpen()
        if (state.reservation != null) throw PrinterFailure(PrinterFailureCode.UNRESOLVED_JOB)
        val photo = selectedPhoto ?: persistence()
        if (photo in state.photos) throw PrinterFailure(PrinterFailureCode.UNRESOLVED_JOB)
        val reservation = UUID.randomUUID().toString()
        // Suppression survives both resolution and a crash after a finished/rejection reply.
        update(state.copy(photos = state.photos + photo, reservation = reservation))
        return reservation
    }

    @Synchronized override fun resolve(reservation: String) {
        checkOpen()
        if (state.reservation != reservation) return
        update(state.copy(reservation = null))
        selectedPhoto = null
    }

    @Synchronized override fun acknowledgeCheckedPrinter() {
        checkOpen()
        if (state.reservation != null) update(state.copy(reservation = null))
        selectedPhoto = null
    }

    private fun update(next: Snapshot) {
        try {
            persist(next)
            state = next
        } catch (_: Exception) {
            // An atomic replacement may have succeeded before a later force failed.
            // Never continue using an in-memory snapshot whose disk state is uncertain.
            poisoned = true
            persistence()
        }
    }

    private fun persist(snapshot: Snapshot) {
        if (snapshot.events.size > MAX_KEYS || snapshot.photos.size > MAX_KEYS) persistence()
        val payload = "PPB-JOURNAL 1\nreservation=${snapshot.reservation ?: "-"}\n" +
            "events=${snapshot.events.sorted().joinToString(",")}\nphotos=${snapshot.photos.sorted().joinToString(",")}\n"
        val bytes = (payload + "checksum=${fingerprint(payload.toByteArray(Charsets.UTF_8))}\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) persistence()
        val temporary = Files.createTempFile(root, "journal-", ".tmp")
        try {
            secure(temporary)
            FileChannel.open(temporary, WRITE).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(temporary, stateFile, ATOMIC_MOVE, REPLACE_EXISTING)
            // Linux supports forcing a directory; Windows uses the forced file + atomic rename.
            if (posix) FileChannel.open(root, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun decode(text: String): Snapshot {
        val lines = text.split('\n')
        if (lines.size != 6 || lines[0] != "PPB-JOURNAL 1" || lines.last() != "" ||
            !lines[1].startsWith("reservation=") || !lines[2].startsWith("events=") ||
            !lines[3].startsWith("photos=") || !lines[4].startsWith("checksum=")) persistence()
        val payload = lines.take(4).joinToString("\n") + "\n"
        if (lines[4].removePrefix("checksum=") != fingerprint(payload.toByteArray(Charsets.UTF_8))) persistence()
        val reservation = lines[1].removePrefix("reservation=").takeUnless { it == "-" }
        if (reservation != null && try { UUID.fromString(reservation).toString() != reservation } catch (_: Exception) { true })
            persistence()
        fun keys(line: String, prefix: String): Set<String> {
            val value = line.removePrefix(prefix)
            if (value.isEmpty()) return emptySet()
            val values = value.split(',')
            if (values.size > MAX_KEYS || values.any { !DIGEST.matches(it) } || values.toSet().size != values.size) persistence()
            return values.toSet()
        }
        return Snapshot(keys(lines[2], "events="), keys(lines[3], "photos="), reservation)
    }

    private fun secure(path: Path) {
        if (posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
    private fun validateDigest(value: String) { if (!DIGEST.matches(value)) persistence() }
    private fun checkOpen() { if (closed || poisoned || lock?.isValid != true) persistence() }
    private fun persistence(): Nothing = throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { lock?.release() } finally {
            try { channel?.close() } finally { registeredRoot?.let { LOCAL_OWNERS.remove(it, owner) } }
        }
    }

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val LOCAL_OWNERS = ConcurrentHashMap<Path, Any>()
        private const val MAX_KEYS = 100_000
        private const val MAX_STATE_BYTES = 16 * 1024 * 1024
        internal fun fingerprint(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
