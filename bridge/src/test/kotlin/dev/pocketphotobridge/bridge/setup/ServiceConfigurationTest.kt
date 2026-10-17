package dev.pocketphotobridge.bridge.setup

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ServiceConfigurationTest {
    private fun config(ssid: String = "DIRECT-camera", password: String = "camera-password", address: String = "AA:BB:CC:DD:EE:FF", token: String = "0123456789abcdef01234567", wifi: String = "wlan0") =
        ServiceConfiguration(ssid, password, address, token, wifi)

    private fun directory(test: (Path) -> Unit) {
        val path = Files.createTempDirectory("setup-store-")
        try { test(path) } finally { path.toFile().deleteRecursively() }
    }

    @Test fun configurationSurvivesPrivateAtomicRestart() = directory { root ->
        val store = ServiceConfigStore(root)
        assertNull(store.load())
        val expected = ServiceConfiguration("DIRECT-camera", "camera-password", "AA:BB:CC:DD:EE:FF", "0123456789abcdef01234567", "wlan0", true, true, true)
        store.save(expected)
        val actual = ServiceConfigStore(root).load()!!
        assertEquals(expected.cameraSsid, actual.cameraSsid)
        assertEquals(expected.cameraPassword, actual.cameraPassword)
        assertEquals(expected.printerAddress, actual.printerAddress)
        assertEquals(expected.printerToken, actual.printerToken)
        assertEquals(expected.wifiInterface, actual.wifiInterface)
        assertTrue(actual.autoPrintEnabled)
        assertTrue(actual.crop)
        assertTrue(actual.keepQueueAcrossBoots)
        if (root.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve("configuration.state")))
        }
        assertFalse(Files.list(root).use { it.anyMatch { path -> path.fileName.toString().endsWith(".tmp") } })
    }

    @Test fun legacyConfigurationDefaultsToDiscardingWaitingPhotosOnNewBoot() = directory { root ->
        val payload = """{"cameraSsid":"DIRECT-camera","cameraPassword":"camera-password","printerAddress":"AA:BB:CC:DD:EE:FF","printerToken":"0123456789abcdef01234567","wifiInterface":"wlan0","autoPrintEnabled":true,"crop":false}"""
        val checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()))
        Files.writeString(root.resolve("configuration.state"), "PPB-CONFIG 1\nchecksum=$checksum\n$payload")
        val loaded = ServiceConfigStore(root).load()!!
        assertFalse(loaded.keepQueueAcrossBoots)
        assertTrue(loaded.autoPrintEnabled)
    }

    @Test fun generatedBootstrapIsUniquePrivateAndPersistent() = directory { root ->
        val first = BootstrapStore(root).loadOrCreate()
        val again = BootstrapStore(root).loadOrCreate()
        assertEquals(first.ssid, again.ssid)
        assertEquals(first.hotspotPassword, again.hotspotPassword)
        assertEquals(first.adminPassword, again.adminPassword)
        assertTrue(first.ssid.toByteArray().size in 1..32)
        assertTrue(first.hotspotPassword.length in 8..63)
        assertTrue(first.adminPassword.length >= 24)
        assertFalse(first.toString().contains(first.hotspotPassword))
        assertFalse(first.toString().contains(first.adminPassword))
        directory { other ->
            val independent = BootstrapStore(other).loadOrCreate()
            assertNotEquals(first.ssid, independent.ssid)
            assertNotEquals(first.hotspotPassword, independent.hotspotPassword)
            assertNotEquals(first.adminPassword, independent.adminPassword)
        }
        if (root.fileSystem.supportedFileAttributeViews().contains("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve("bootstrap.state")))
    }

    @Test fun configurationDefaultsToManualPrintingAndNeverIncludesSecretsInToString() {
        val value = config()
        assertFalse(value.autoPrintEnabled)
        assertFalse(value.crop)
        assertFalse(value.keepQueueAcrossBoots)
        assertFalse(value.toString().contains(value.cameraPassword))
        assertFalse(value.toString().contains(value.printerToken))
    }

    @Test fun validatesUtf8SsidWpaTokenMacAndInterfaceWithoutEchoingInputs() {
        val invalid = listOf<() -> Any>(
            { config(ssid = "") }, { config(ssid = "é".repeat(17)) }, { config(ssid = "camera\nsecret") },
            { config(password = "short") }, { config(password = "z".repeat(64)) }, { config(ssid = "\uD800") },
            { config(password = "bad\npassphrase") }, { config(token = "private-token") },
            { config(address = "AA:BB;touch") }, { config(wifi = "wlan0;secret") }, { config(wifi = "-bad") }
        )
        invalid.forEach { factory ->
            try { factory(); fail("Invalid configuration accepted") }
            catch (failure: IllegalArgumentException) {
                assertFalse(failure.message.orEmpty().contains("secret"))
                assertFalse(failure.message.orEmpty().contains("private-token"))
            }
        }
        assertNotNull(config(ssid = "é".repeat(16), password = "f".repeat(64)))
    }

    @Test fun corruptedAndOversizedSnapshotsFailClosedAndStayUntouched() = directory { root ->
        val store = ServiceConfigStore(root)
        store.save(config())
        val path = root.resolve("configuration.state")
        val valid = Files.readAllBytes(path)
        val damaged = valid.copyOf().also { it[10] = 'X'.code.toByte() }
        Files.write(path, damaged)
        assertPersistenceRejected { store.load() }
        assertArrayEquals(damaged, Files.readAllBytes(path))
        Files.write(path, ByteArray(32 * 1024))
        assertPersistenceRejected { store.load() }
    }

    @Test fun corruptBootstrapNeverRegeneratesCredentials() = directory { root ->
        BootstrapStore(root).loadOrCreate()
        val path = root.resolve("bootstrap.state")
        Files.writeString(path, "corrupt")
        assertPersistenceRejected { BootstrapStore(root).loadOrCreate() }
        assertEquals("corrupt", Files.readString(path))
    }

    @Test fun stateAndParentSymlinksAreRefusedWithoutTouchingTarget() = directory { root ->
        val target = root.resolve("untouched")
        Files.writeString(target, "private evidence")
        Files.createSymbolicLink(root.resolve("configuration.state"), target)
        assertPersistenceRejected { ServiceConfigStore(root).load() }
        assertPersistenceRejected { ServiceConfigStore(root).save(config()) }
        assertEquals("private evidence", Files.readString(target))
        val linked = root.resolve("linked")
        Files.createSymbolicLink(linked, root)
        assertPersistenceRejected { BootstrapStore(linked.resolve("child")).loadOrCreate() }
    }

    @Test fun persistenceLockSymlinkIsRefusedWithoutTouchingTarget() = directory { root ->
        val target = root.resolve("untouched")
        Files.writeString(target, "private evidence")
        Files.createSymbolicLink(root.resolve("setup.lock"), target)
        assertPersistenceRejected { BootstrapStore(root).loadOrCreate() }
        assertEquals("private evidence", Files.readString(target))
    }

    private fun assertPersistenceRejected(action: () -> Any?) {
        try { action(); fail("Unsafe state must fail closed") }
        catch (failure: SetupPersistenceException) { assertEquals("SETUP_STATE_UNAVAILABLE", failure.message) }
    }
}
