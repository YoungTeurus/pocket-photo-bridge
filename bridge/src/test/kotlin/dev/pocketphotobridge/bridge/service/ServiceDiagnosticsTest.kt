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

    @Test fun headerOmitsUptimeWhereTheKernelDoesNotReportIt() {
        val directory = Files.createTempDirectory("header-service-")
        ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null).emit("SERVICE_SETUP")
        assertEquals("Pocket Photo Bridge ${BridgeCli.VERSION} | headless service run=1",
            Files.readAllLines(directory.resolve("diagnostics.txt")).first())
    }
}
