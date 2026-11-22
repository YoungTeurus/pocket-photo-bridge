package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.BridgeCli
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
        assertEquals(3, report.events().count { it.endsWith(" DIAGNOSTIC_REDACTED") })
    }

    @Test fun eachRunStartsWithAHeaderAndEventsCarryWholeSecondsSinceStart() {
        val directory = Files.createTempDirectory("header-service-")
        var millis = 5_000L
        val report = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { millis }, 1234)
        report.emit("SERVICE_SETUP")
        millis += 65_999
        report.emit("SERVICE_OPERATION")
        assertEquals(listOf("Pocket Photo Bridge ${BridgeCli.VERSION} | headless service run=1 uptime_s=1234",
            "0 SERVICE_SETUP", "65 SERVICE_OPERATION"), Files.readAllLines(directory.resolve("diagnostics.txt")))
        assertEquals(listOf("0 SERVICE_SETUP", "65 SERVICE_OPERATION"), report.events())
    }

    @Test fun aRestartKeepsTheFiveMostRecentRunsAsPreviousRunsOldestFirst() {
        val directory = Files.createTempDirectory("restart-service-")
        fun start() = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        fun header(run: Int) = "Pocket Photo Bridge ${BridgeCli.VERSION} | headless service run=$run"
        val crashed = start()
        crashed.emit("SERVICE_OPERATION")
        crashed.emit("BRIDGE_WAITING_CAMERA queued=0 category=IO_ERROR")
        val restarted = start()
        restarted.emit("SERVICE_SETUP")
        assertEquals(header(2), restarted.header)
        assertEquals(listOf(mapOf("header" to header(1),
            "events" to listOf("0 SERVICE_OPERATION", "0 BRIDGE_WAITING_CAMERA queued=0 category=IO_ERROR"))), restarted.previousRuns())
        assertEquals(listOf("0 SERVICE_SETUP"), restarted.events())
        repeat(5) { start().emit("SERVICE_SETUP") }
        val latest = start()
        assertEquals(header(8), latest.header)
        assertEquals((3..7).map(::header), latest.previousRuns().map { it["header"] })
    }

    @Test fun handWrittenSecretsInAnOldRunNeverReachTheNextRun() {
        val directory = Files.createTempDirectory("tampered-service-")
        val header = "Pocket Photo Bridge ${BridgeCli.VERSION} | headless service run=4"
        Files.writeString(directory.resolve("diagnostics.1.txt"), "$header token=MYSECRET\n3 SERVICE_OPERATION\n")
        Files.writeString(directory.resolve("diagnostics.txt"), listOf(header, "3 SERVICE_OPERATION", "sensitive-camera-name",
            "5 LINUX_PRINTER_PAIRED AA:BB:CC:DD:EE:FF", "7 SERVICE_FAILED category=0123456789abcdef01234567",
            "9 MYSECRET", "9 SERVICE_SETUP MYSECRET", "11 DIAGNOSTIC_REDACTED").joinToString("\n"))
        val next = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        next.emit("SERVICE_SETUP")
        assertEquals(listOf(mapOf("header" to header, "events" to listOf("3 SERVICE_OPERATION", "11 DIAGNOSTIC_REDACTED"))),
            next.previousRuns())
        val exported = next.previousRuns().toString() + next.events() + Files.readString(directory.resolve("diagnostics.txt"))
        listOf("MYSECRET", "sensitive-camera-name", "AA:BB:CC", "0123456789abcdef").forEach { assertFalse(exported.contains(it)) }
    }

    @Test fun theFileAcceptsTheEventsTheTroubleshootingTableReliesOn() {
        val directory = Files.createTempDirectory("accepted-service-")
        val report = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        val accepted = listOf("SERVICE_CAMERA_WAIT category=COMMAND_FAILED", "BRIDGE_CAMERA_CONNECTING",
            "BRIDGE_PRINTER_CONNECTING", "SERVICE_CONFIG_SAVED", "SONY_JPEG_BYTES count=13805942")
        accepted.forEach(report::emit)
        assertEquals(accepted.map { "0 $it" }, Files.readAllLines(directory.resolve("diagnostics.txt")).drop(1))
    }

    @Test fun headerOmitsUptimeWhereTheKernelDoesNotReportIt() {
        val directory = Files.createTempDirectory("header-service-")
        ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null).emit("SERVICE_SETUP")
        assertEquals("Pocket Photo Bridge ${BridgeCli.VERSION} | headless service run=1",
            Files.readAllLines(directory.resolve("diagnostics.txt")).first())
    }
}
