package dev.pocketphotobridge.bridge.setup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** A fixed message intentionally discards filesystem and parser details, which may contain credentials. */
class SetupPersistenceException : RuntimeException("SETUP_STATE_UNAVAILABLE")

class ServiceConfigStore(root: Path) {
    private val file = PrivateSnapshot(root, "configuration.state", "PPB-CONFIG 1")

    fun load(): ServiceConfiguration? = file.access {
        file.read()?.let { json ->
            ServiceConfiguration(json.string("cameraSsid"), json.string("cameraPassword"),
                json.string("printerAddress"), json.string("printerToken"), json.string("wifiInterface"),
                json.boolean("autoPrintEnabled"), json.boolean("crop"))
        }
    }

    fun save(config: ServiceConfiguration) = file.access {
        file.write(JsonObject().apply {
            addProperty("cameraSsid", config.cameraSsid)
            addProperty("cameraPassword", config.cameraPassword)
            addProperty("printerAddress", config.printerAddress)
            addProperty("printerToken", config.printerToken)
            addProperty("wifiInterface", config.wifiInterface)
            addProperty("autoPrintEnabled", config.autoPrintEnabled)
            addProperty("crop", config.crop)
        })
    }
}

/** Stores locally generated installation credentials separately from reports and diagnostic state. */
class BootstrapStore(root: Path) {
    private val file = PrivateSnapshot(root, "bootstrap.state", "PPB-BOOTSTRAP 1")

    fun loadOrCreate(): SetupCredentials = file.access {
        val existing = file.read()
        if (existing != null) {
            val credentials = SetupCredentials(existing.string("ssid"), existing.string("hotspotPassword"), existing.string("adminPassword"))
            if (!Regex("PocketPhoto-[0-9a-f]{12}").matches(credentials.ssid) ||
                !validWpaPassword(credentials.hotspotPassword) || !Regex("[A-Za-z0-9_-]{32,64}").matches(credentials.adminPassword))
                throw SetupPersistenceException()
            credentials
        } else {
            val suffix = HexFormat.of().formatHex(randomBytes(6))
            val credentials = SetupCredentials("PocketPhoto-$suffix", secretToken(), secretToken())
            file.write(JsonObject().apply {
                addProperty("ssid", credentials.ssid)
                addProperty("hotspotPassword", credentials.hotspotPassword)
                addProperty("adminPassword", credentials.adminPassword)
            })
            credentials
        }
    }
}

private fun JsonObject.string(name: String): String {
    val value = get(name) ?: throw SetupPersistenceException()
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw SetupPersistenceException()
    return value.asString
}

private fun JsonObject.boolean(name: String): Boolean {
    val value = get(name) ?: throw SetupPersistenceException()
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw SetupPersistenceException()
    return value.asBoolean
}

/** Bounded, checksummed snapshots; writes become visible only after a forced atomic replacement. */
private class PrivateSnapshot(directory: Path, name: String, private val header: String) {
    private val root = directory.toAbsolutePath().normalize()
    private val path = root.resolve(name)
    private val monitor = MONITORS.computeIfAbsent(root) { Any() }
    private val posix = root.fileSystem.supportedFileAttributeViews().contains("posix")

    fun <T> access(action: () -> T): T = synchronized(monitor) {
        try {
            checkAncestors()
            if (posix) Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else Files.createDirectories(root)
            secure(root, true)
            checkFile()
            val lockPath = root.resolve("setup.lock")
            if (Files.exists(lockPath, NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) throw SetupPersistenceException()
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                secure(lockPath, false)
                val lock = channel.tryLock() ?: throw SetupPersistenceException()
                lock.use { action() }
            }
        } catch (_: Exception) { throw SetupPersistenceException() }
    }

    private fun checkAncestors() {
        var current: Path? = root
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw SetupPersistenceException()
            current = current.parent
        }
    }

    private fun checkFile() {
        if (Files.exists(path, NOFOLLOW_LINKS) && (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
            throw SetupPersistenceException()
    }

    fun read(): JsonObject? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        secure(path, false)
        val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { input ->
            if (input.size() !in 1..MAX_BYTES.toLong()) throw SetupPersistenceException()
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (input.read(buffer) > 0) {
                if (!buffer.hasRemaining()) throw SetupPersistenceException()
            }
            ByteArray(buffer.position()).also { buffer.flip(); buffer.get(it) }
        }
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n', limit = 3)
        if (lines.size != 3 || lines[0] != header || !lines[1].startsWith("checksum=")) throw SetupPersistenceException()
        val payload = lines[2]
        if (lines[1].removePrefix("checksum=") != digest(payload.toByteArray(Charsets.UTF_8))) throw SetupPersistenceException()
        val json = JsonParser.parseString(payload)
        if (!json.isJsonObject) throw SetupPersistenceException()
        return json.asJsonObject
    }

    fun write(json: JsonObject) {
        checkAncestors()
        checkFile()
        val payload = json.toString()
        val bytes = "$header\nchecksum=${digest(payload.toByteArray(Charsets.UTF_8))}\n$payload".toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw SetupPersistenceException()
        val attributes = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        val temporary = Files.createTempFile(root, "setup-", ".tmp", *attributes)
        try {
            secure(temporary, false)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            checkFile()
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            if (posix) FileChannel.open(root, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun secure(file: Path, directory: Boolean) {
        if (Files.isSymbolicLink(file)) throw SetupPersistenceException()
        if (posix) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        else Files.getFileAttributeView(file, AclFileAttributeView::class.java, NOFOLLOW_LINKS)?.let { view ->
            val owner = Files.getOwner(file, NOFOLLOW_LINKS)
            view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
        } ?: throw SetupPersistenceException()
    }

    companion object {
        private const val MAX_BYTES = 8192
        private val MONITORS = ConcurrentHashMap<Path, Any>()
        private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

private val RANDOM = SecureRandom()
internal fun randomBytes(size: Int): ByteArray = ByteArray(size).also { RANDOM.nextBytes(it) }
internal fun secretToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24))
