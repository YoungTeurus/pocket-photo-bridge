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
        assertEquals(listOf("Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=1 uptime_s=1234",
            "0 SERVICE_SETUP", "65 SERVICE_OPERATION"), Files.readAllLines(directory.resolve("diagnostics.txt")))
        assertEquals(listOf("0 SERVICE_SETUP", "65 SERVICE_OPERATION"), report.events())
    }

    @Test fun aRestartKeepsTheFiveMostRecentRunsAsPreviousRunsOldestFirst() {
        val directory = Files.createTempDirectory("restart-service-")
        fun start() = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        fun header(run: Int) = "Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=$run"
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
        val header = "Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=4"
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

    @Test fun radioStageEventsKeepOnlyBoundedNetworkManagerNumbers() {
        val directory = Files.createTempDirectory("radio-service-")
        val report = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        val accepted = listOf("LINUX_CAMERA_ACTIVATED", "LINUX_CAMERA_ACTIVATION_FAILED nm_exit=4 nm_state=30 nm_reason=53",
            "LINUX_CAMERA_ACTIVATION_FAILED nm_exit=255 nm_state=1000 nm_reason=0", "SONY_SELECTION count=2")
        val rejected = listOf("LINUX_CAMERA_ACTIVATION_FAILED nm_exit=256", "LINUX_CAMERA_ACTIVATION_FAILED nm_state=1001",
            "LINUX_CAMERA_ACTIVATION_FAILED nm_reason=DIRECT-camera", "LINUX_CAMERA_ACTIVATION_FAILED reason=53",
            "LINUX_CAMERA_ACTIVATED nm_exit=-1")
        (accepted + rejected).forEach(report::emit)
        val saved = Files.readAllLines(directory.resolve("diagnostics.txt")).drop(1)
        assertEquals(accepted.map { "0 $it" } + rejected.map { "0 DIAGNOSTIC_REDACTED" }, saved)
        assertFalse(saved.joinToString("\n").contains("DIRECT-camera"))
    }

    @Test fun theFirstStartAfterUpgradingKeepsTheOldFileAsRunZero() {
        val directory = Files.createTempDirectory("legacy-service-")
        Files.writeString(directory.resolve("diagnostics.txt"),
            "Pocket Photo Bridge 0.5.0 | headless service\nSERVICE_OPERATION\nBRIDGE_QUEUED queued=1\nMYSECRETTOKEN\n")
        val report = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), uptimeSeconds = null)
        assertEquals(listOf(mapOf("header" to "Pocket Photo Bridge 0.5.0 | headless service",
            "events" to listOf("SERVICE_OPERATION", "BRIDGE_QUEUED queued=1"))), report.previousRuns())
        assertEquals("Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=1", report.header)
    }

    @Test fun previousRunsKeepTheBuildTheyWereWrittenBy() {
        val directory = Files.createTempDirectory("build-service-")
        val headers = listOf("Pocket Photo Bridge 0.5.0+dev | headless service run=1",
            "Pocket Photo Bridge 0.5.0+1a2b3c4 | headless service run=2 uptime_s=40",
            "Pocket Photo Bridge 0.5.0+0123456789abcdef0123456789abcdef01234567 | headless service run=3")
        headers.forEachIndexed { index, header ->
            Files.writeString(directory.resolve("diagnostics.${headers.size - index}.txt"), "$header\n0 SERVICE_SETUP\n")
        }
        Files.writeString(directory.resolve("diagnostics.txt"), "Pocket Photo Bridge 0.5.0+1A2B3C4 | headless service run=4\n0 SERVICE_SETUP\n")
        val report = ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null)
        assertEquals(headers, report.previousRuns().map { it["header"] })
    }

    @Test fun theFileStartsWithTheBuildTheBinaryReports() {
        val directory = Files.createTempDirectory("build-header-service-")
        ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null).emit("SERVICE_SETUP")
        val first = Files.readAllLines(directory.resolve("diagnostics.txt")).first()
        assertTrue(first, Regex("Pocket Photo Bridge [0-9]+\\.[0-9]+\\.[0-9]+\\+([0-9a-f]{7,40}|dev) \\| headless service run=1").matches(first))
    }

    @Test fun aStartRemovesTemporaryFilesLeftByAPowerPull() {
        val directory = Files.createTempDirectory("stale-service-")
        Files.writeString(directory.resolve("diagnostic-123.tmp"), "half written")
        ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()))
        assertFalse(Files.exists(directory.resolve("diagnostic-123.tmp")))
    }

    @Test fun headerOmitsUptimeWhereTheKernelDoesNotReportIt() {
        val directory = Files.createTempDirectory("header-service-")
        ServiceDiagnostics(directory, PrintStream(ByteArrayOutputStream()), { 0 }, null).emit("SERVICE_SETUP")
        assertEquals("Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=1",
            Files.readAllLines(directory.resolve("diagnostics.txt")).first())
    }
}
