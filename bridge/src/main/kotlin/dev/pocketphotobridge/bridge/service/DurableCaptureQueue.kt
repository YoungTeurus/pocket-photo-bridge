package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.printer.PrinterSession
import java.io.File
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
import java.util.concurrent.ConcurrentHashMap

internal class QueueCapacityExceeded : RuntimeException("QUEUE_FULL")

/** Originals, prepared images and queue metadata have one exclusive owner. */
internal class DurableCaptureQueue(directory: Path) : AutoCloseable {
    data class Entry(val fingerprint: String, val preparedHash: String, val originalBytes: Long, val preparedBytes: Int) {
        fun encode() = "$fingerprint:$preparedHash:$originalBytes:$preparedBytes"
    }

    val root: Path = directory.toAbsolutePath().normalize()
    private val imageRoot = root.resolve("images")
    private val stateFile = root.resolve("queue.state")
    private val bootFile = root.resolve("boot.state")
    private val lockFile = root.resolve("queue.lock")
    private val posix = root.fileSystem.supportedFileAttributeViews().contains("posix")
    private val owner = Any()
    private var identity: Path? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var closed = false
    private var poisoned = false
    private var records = emptyList<Entry>()
    var fresh: Boolean = false
        private set
    val entries: List<Entry> get() = records.toList()
    val size: Int get() = records.size

    init {
        try {
            // Reject redirected ancestors as well as the state directory itself.
            var ancestor: Path? = root
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor)) persistence()
                ancestor = ancestor.parent
            }
            makeDirectory(root)
            val realRoot = root.toRealPath()
            if (OWNERS.putIfAbsent(realRoot, owner) != null) throw BridgeFailure(BridgeFailureCode.INSTANCE_RUNNING)
            identity = realRoot
            val priorOwner = Files.exists(lockFile, NOFOLLOW_LINKS)
            if (priorOwner && !Files.isRegularFile(lockFile, NOFOLLOW_LINKS)) persistence()
            channel = FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS)
            secure(lockFile)
            lock = try { channel!!.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw BridgeFailure(BridgeFailureCode.INSTANCE_RUNNING)
            if (Files.exists(stateFile, NOFOLLOW_LINKS)) {
                records = decode(readState())
                if (!Files.isDirectory(imageRoot, NOFOLLOW_LINKS)) persistence()
                makeDirectory(imageRoot)
            } else {
                if (priorOwner || Files.exists(imageRoot, NOFOLLOW_LINKS) || Files.exists(root.resolve("print-journal"), NOFOLLOW_LINKS)) persistence()
                fresh = true
                makeDirectory(imageRoot)
                persist(emptyList())
            }
            verify()
            cleanUnreferencedImages()
            cleanInterruptedReceives()
        } catch (failure: Exception) {
            close()
            throw if (failure is BridgeFailure) failure else BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        }
    }

    fun verify() {
        try {
            checkOpen()
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isDirectory(imageRoot, NOFOLLOW_LINKS) ||
                !Files.isRegularFile(lockFile, NOFOLLOW_LINKS)) persistence()
            if (decode(readState()) != records) persistence()
            records.forEach { entry ->
                verifyImage(originalPath(entry), entry.originalBytes, entry.fingerprint)
                verifyImage(preparedPath(entry), entry.preparedBytes.toLong(), entry.preparedHash)
            }
        } catch (_: Exception) { poisoned = true; persistence() }
    }

    fun canAccept() = records.size < MAX_ENTRIES && total(records) < MAX_QUEUE_BYTES
    fun contains(fingerprint: String) = records.any { it.fingerprint == fingerprint }
    fun receiveDirectory(): Path {
        checkOpen()
        return if (posix) Files.createTempDirectory(root, "receive-", DIRECTORY_PERMISSION)
            else Files.createTempDirectory(root, "receive-")
    }

    /** Both forced image files and the atomic metadata commit precede camera acknowledgement. */
    fun add(source: File, fingerprint: String, prepared: ByteArray) {
        checkOpen()
        if (!DIGEST.matches(fingerprint)) persistence()
        if (contains(fingerprint)) return
        val sourceBytes = Files.size(source.toPath())
        if (sourceBytes !in 4..MAX_ORIGINAL_BYTES || prepared.size !in 16..PrinterSession.MAX_JPEG_BYTES ||
            prepared[0] != 0xff.toByte() || prepared[1] != 0xd8.toByte() ||
            prepared[prepared.lastIndex - 1] != 0xff.toByte() || prepared.last() != 0xd9.toByte())
            throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
        val entry = Entry(fingerprint, hash(prepared), sourceBytes, prepared.size)
        val next = records + entry
        if (next.size > MAX_ENTRIES || total(next) > MAX_QUEUE_BYTES) throw QueueCapacityExceeded()
        try {
            writeImage(originalPath(entry)) { output ->
                Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw BridgeFailure(BridgeFailureCode.CANCELLED)
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > sourceBytes) persistence()
                        write(output, buffer, count)
                    }
                    if (copied != sourceBytes) persistence()
                }
            }
            writeImage(preparedPath(entry)) { write(it, prepared, prepared.size) }
            verifyImage(originalPath(entry), sourceBytes, fingerprint)
            verifyImage(preparedPath(entry), prepared.size.toLong(), entry.preparedHash)
            update(next)
        } catch (failure: Exception) {
            if (failure is BridgeFailure && failure.code == BridgeFailureCode.CANCELLED && !poisoned) throw failure
            poisoned = true
            persistence()
        }
    }

    fun prepared(entry: Entry): ByteArray {
        checkOpen()
        verifyImage(preparedPath(entry), entry.preparedBytes.toLong(), entry.preparedHash)
        return Files.readAllBytes(preparedPath(entry)).also {
            if (it.size != entry.preparedBytes || hash(it) != entry.preparedHash) persistence()
        }
    }

    fun remove(entry: Entry) {
        checkOpen()
        if (entry !in records) return
        update(records - entry)
        // Metadata removal comes first. An interrupted cleanup is safe to resume at startup.
        try {
            Files.deleteIfExists(originalPath(entry))
            Files.deleteIfExists(preparedPath(entry))
            forceDirectory(imageRoot)
        } catch (_: Exception) { /* No live record references these bytes anymore. */ }
    }

    /** The queue owner records a boot only after its discard commit has reached disk. */
    fun beginBoot(bootId: String, keepWaiting: Boolean, wasSubmitted: (String) -> Boolean): Int {
        checkOpen()
        if (!BOOT_ID.matches(bootId)) persistence()
        val previousBootKnown = Files.exists(bootFile, NOFOLLOW_LINKS)
        if (previousBootKnown) {
            if (!Files.isRegularFile(bootFile, NOFOLLOW_LINKS) || Files.size(bootFile) > 256) persistence()
            secure(bootFile)
            val text = Files.newInputStream(bootFile, NOFOLLOW_LINKS).use { it.readNBytes(257) }.toString(Charsets.UTF_8)
            val lines = text.split('\n')
            if (lines.size != 4 || lines[0] != "PPB-BOOT 1" || !BOOT_ID.matches(lines[1]) || lines[3] != "") persistence()
            val payload = "${lines[0]}\n${lines[1]}\n"
            if (lines[2] != "checksum=${hash(payload.toByteArray(Charsets.UTF_8))}") persistence()
            if (lines[1] == bootId) return 0
        }
        // A pre-feature queue has no boot marker. Adopt it without treating an upgrade as a power cycle.
        val discarded = if (keepWaiting || !previousBootKnown) emptyList() else records.filterNot { wasSubmitted(it.fingerprint) }
        discarded.forEach(::remove)
        val payload = "PPB-BOOT 1\n$bootId\n"
        val bytes = (payload + "checksum=${hash(payload.toByteArray(Charsets.UTF_8))}\n").toByteArray(Charsets.UTF_8)
        val temporary = Files.createTempFile(root, "boot-", ".tmp")
        try {
            secure(temporary)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output -> write(output, bytes, bytes.size); output.force(true) }
            if (Files.isSymbolicLink(bootFile)) persistence()
            Files.move(temporary, bootFile, ATOMIC_MOVE, REPLACE_EXISTING)
            forceDirectory(root)
        } finally { Files.deleteIfExists(temporary) }
        return discarded.size
    }

    private fun update(next: List<Entry>) {
        try { persist(next); records = next }
        catch (_: Exception) { poisoned = true; persistence() }
    }

    private fun persist(next: List<Entry>) {
        val payload = "PPB-QUEUE 1\n" + next.joinToString("") { it.encode() + "\n" }
        val bytes = (payload + "checksum=${hash(payload.toByteArray(Charsets.UTF_8))}\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) persistence()
        val temporary = Files.createTempFile(root, "queue-", ".tmp")
        try {
            secure(temporary)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                write(output, bytes, bytes.size)
                output.force(true)
            }
            if (Files.isSymbolicLink(stateFile)) persistence()
            Files.move(temporary, stateFile, ATOMIC_MOVE, REPLACE_EXISTING)
            forceDirectory(root)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun readState(): String {
        if (!Files.isRegularFile(stateFile, NOFOLLOW_LINKS) || Files.size(stateFile) > MAX_STATE_BYTES) persistence()
        secure(stateFile)
        return Files.newInputStream(stateFile, NOFOLLOW_LINKS).use { input ->
            val bytes = input.readNBytes(MAX_STATE_BYTES + 1)
            if (bytes.size > MAX_STATE_BYTES) persistence()
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun decode(text: String): List<Entry> {
        val lines = text.split('\n')
        if (lines.size !in 3..MAX_ENTRIES + 3 || lines.first() != "PPB-QUEUE 1" || lines.last() != "" ||
            !lines[lines.lastIndex - 1].startsWith("checksum=")) persistence()
        val payload = lines.dropLast(2).joinToString("\n") + "\n"
        if (lines[lines.lastIndex - 1].removePrefix("checksum=") != hash(payload.toByteArray(Charsets.UTF_8))) persistence()
        val next = lines.drop(1).dropLast(2).map { line ->
            val parts = line.split(':')
            if (parts.size != 4 || !DIGEST.matches(parts[0]) || !DIGEST.matches(parts[1])) persistence()
            val originalBytes = parts[2].toLongOrNull()?.takeIf { it in 4..MAX_ORIGINAL_BYTES } ?: persistence()
            val preparedBytes = parts[3].toIntOrNull()?.takeIf { it in 16..PrinterSession.MAX_JPEG_BYTES } ?: persistence()
            Entry(parts[0], parts[1], originalBytes, preparedBytes).also { if (it.encode() != line) persistence() }
        }
        if (next.map { it.fingerprint }.toSet().size != next.size || total(next) > MAX_QUEUE_BYTES) persistence()
        return next
    }

    private fun writeImage(destination: Path, body: (FileChannel) -> Unit) {
        if (Files.exists(destination, NOFOLLOW_LINKS)) persistence()
        val temporary = Files.createTempFile(imageRoot, "capture-", ".tmp")
        try {
            secure(temporary)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output -> body(output); output.force(true) }
            Files.move(temporary, destination, ATOMIC_MOVE)
            forceDirectory(imageRoot)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun verifyImage(path: Path, size: Long, digest: String) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != size || fingerprint(path, size) != digest) persistence()
        secure(path)
    }

    private fun cleanUnreferencedImages() {
        val expected = records.flatMap { listOf(originalPath(it), preparedPath(it)) }.toSet()
        Files.newDirectoryStream(imageRoot).use { stream ->
            var count = 0
            for (path in stream) {
                if (++count > MAX_ENTRIES * 4 || !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                    Files.size(path) > MAX_ORIGINAL_BYTES || !IMAGE_FILE.matches(path.fileName.toString())) persistence()
                if (path !in expected) Files.delete(path)
            }
        }
        forceDirectory(imageRoot)
    }

    private fun cleanInterruptedReceives() {
        Files.newDirectoryStream(root, "receive-*").use { stream ->
            var count = 0
            for (path in stream) {
                if (++count > MAX_ENTRIES || !Files.isDirectory(path, NOFOLLOW_LINKS)) persistence()
                Files.newDirectoryStream(path).use { photos ->
                    var photosSeen = 0
                    for (photo in photos) {
                        if (++photosSeen > 2 || !Files.isRegularFile(photo, NOFOLLOW_LINKS) ||
                            Files.size(photo) > MAX_ORIGINAL_BYTES || !photo.fileName.toString().matches(Regex("(?:sony|prepared)-[0-9]+\\.jpg"))) persistence()
                        Files.delete(photo)
                    }
                }
                Files.delete(path)
            }
        }
    }

    private fun originalPath(entry: Entry) = imageRoot.resolve("${entry.fingerprint}.original.jpg")
    private fun preparedPath(entry: Entry) = imageRoot.resolve("${entry.fingerprint}.print.jpg")
    private fun total(entries: List<Entry>) = entries.sumOf { it.originalBytes + it.preparedBytes }
    private fun makeDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)) persistence()
        if (posix) Files.createDirectories(path, DIRECTORY_PERMISSION) else Files.createDirectories(path)
        if (posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
    private fun secure(path: Path) {
        if (posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
    private fun forceDirectory(path: Path) { if (posix) FileChannel.open(path, READ).use { it.force(true) } }
    private fun checkOpen() { if (closed || poisoned || lock?.isValid != true) persistence() }
    private fun persistence(): Nothing = throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)

    override fun close() {
        if (closed) return
        closed = true
        try { lock?.release() } finally {
            try { channel?.close() } finally { identity?.let { OWNERS.remove(it, owner) } }
        }
    }

    companion object {
        const val MAX_ENTRIES = 16
        private const val MAX_STATE_BYTES = 16 * 1024
        private const val MAX_QUEUE_BYTES = 256L * 1024 * 1024
        private const val MAX_ORIGINAL_BYTES = 64L * 1024 * 1024
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val IMAGE_FILE = Regex("(?:[0-9a-f]{64}\\.(?:original|print)\\.jpg|capture-[A-Za-z0-9-]+\\.tmp)")
        private val DIRECTORY_PERMISSION = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val OWNERS = ConcurrentHashMap<Path, Any>()
        private fun write(output: FileChannel, bytes: ByteArray, count: Int) {
            val buffer = ByteBuffer.wrap(bytes, 0, count)
            while (buffer.hasRemaining()) output.write(buffer)
        }
        fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun fingerprint(path: Path, expectedBytes: Long = Files.size(path)): String {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || expectedBytes !in 4..MAX_ORIGINAL_BYTES)
                throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expectedBytes) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
                    digest.update(buffer, 0, count)
                }
                if (total != expectedBytes) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
