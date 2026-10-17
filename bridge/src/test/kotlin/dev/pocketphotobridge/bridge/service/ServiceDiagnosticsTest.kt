package dev.pocketphotobridge.bridge.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class ServiceDiagnosticsTest {
    @Test fun arbitraryUppercaseSecretsCannotBecomeAStageOrCategoryAndCountsRemainUseful() {
        val directory = Files.createTempDirectory("redacted-service-")
        val output = ByteArrayOutputStream()
        val report = ServiceDiagnostics(directory, PrintStream(output))
        report.emit("SERVICE_OPERATION")
        report.emit("BRIDGE_NEEDS_ATTENTION queued=1 category=UNRESOLVED_JOB")
        report.emit("LINUX_BLUETOOTH_CONNECT reason=PENDING native_error=115")
        report.emit("SONY_JPEG_BYTES count=13805942")
        report.emit("MYSECRETTOKEN")
        report.emit("SERVICE_FAILED category=AABBCCDDEEFFAABBCCDDEEFF")
        report.emit("SERVICE_FAILED category=http://private-device/")
        val saved = Files.readString(directory.resolve("diagnostics.txt"))
        assertFalse(saved.contains("MYSECRETTOKEN")); assertFalse(saved.contains("AABBCCDDEEFF"))
        assertFalse(saved.contains("private-device"))
        assertTrue(saved.contains("SONY_JPEG_BYTES count=13805942"))
        assertTrue(saved.contains("LINUX_BLUETOOTH_CONNECT reason=PENDING native_error=115"))
        assertEquals(3, report.events().count { it == "DIAGNOSTIC_REDACTED" })
    }
}
