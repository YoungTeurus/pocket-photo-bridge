package dev.pocketphotobridge.printer

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PrinterSessionTest {
    private val token = "000102030405060708090a0b" // Synthetic, not a device credential.
    private fun jpeg(size: Int = 2000): ByteArray {
        val header = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, 6, 24, 4, 16, 3,
            1, 17, 0, 2, 17, 0, 3, 17, 0, -1, -38, 0, 2)
        return header + ByteArray(size - header.size - 2) { 1 } + byteArrayOf(-1, -39)
    }
    private class Guard : PrintJobGuard {
        override var unresolved = false
        var reservations = 0
        override fun reserve(): String {
            if (unresolved) throw PrinterFailure(PrinterFailureCode.UNRESOLVED_JOB)
            unresolved = true; reservations++; return "synthetic-reservation"
        }
        override fun resolve(reservation: String) { unresolved = false }
        override fun acknowledgeCheckedPrinter() { unresolved = false }
    }
    private inner class CameraIndependentPrinter(
        val guard: Guard = Guard(),
        val processing: Boolean = false,
        val rejection: Boolean = false,
        val lostMethod: String? = null,
        val neverFinished: Boolean = false,
        val wrongJob: Boolean = false,
        val wrongKey: Boolean = false,
        val unrelatedReply: Boolean = false,
        val processingAfterStart: Boolean = false,
        val finishAfter: Int = 1,
        val malformedError: Boolean = false,
        val nullError: Boolean = false
    ) : PrinterTransport {
        val methods = mutableListOf<String>()
        val fileFrames = mutableListOf<PrinterFrame>()
        private val incoming = ArrayDeque<ByteArray>()
        private val cipher = PrinterCipher(token)
        private var jobChecks = 0
        override fun write(bytes: ByteArray) {
            val frame = PrinterProtocol.decode(bytes)
            if (frame.channel == 2) {
                fileFrames += frame.copy(body = cipher.crypt(frame.body)); return
            }
            val rpc = JsonParser.parseString(cipher.crypt(frame.body).toString(Charsets.UTF_8)).asJsonObject
            val method = rpc["method"].asString
            methods += method
            val id = rpc["id"].asInt
            if (method == "print_job") {
                assertTrue("Guard must be durable before reserving on printer", guard.unresolved)
                assertEquals("Only one copy may be requested", 1, rpc["params"].asJsonObject["copies"].asInt)
            }
            if (method == "clean_data" || method == lostMethod) return
            val result = when (method) {
                "mixed_status" -> if (processing || (processingAfterStart && methods.contains("print_job"))) "{\"category\":\"processing\",\"sub_category\":\"printing\",\"error\":0}" else
                    "{\"category\":\"idle\",\"sub_category\":\"init\",\"error\":0,\"battery\":80}"
                "get_prop" -> "[{\"fw_ver\":\"1.1.4_0092\",\"hw_ver\":\"XMKDDYJHT02\"}]"
                "print_job" -> "{\"job_id\":7}"
                "confirm_job" -> "[\"OK\"]"
                "job_info" -> "[{\"job_id\":${if (wrongJob) 8 else 7},\"job_state\":\"${if (neverFinished || ++jobChecks < finishAfter) "processing" else "finished"}\"}]"
                else -> error("Unexpected test method")
            }
            val reply = when {
                malformedError && method == "print_job" -> "{\"id\":$id,\"error\":\"unknown\"}"
                rejection && method == "print_job" -> "{\"id\":$id,\"error\":{\"code\":-8006}}"
                nullError -> "{\"id\":$id,\"error\":null,\"result\":$result}"
                else -> "{\"id\":$id,\"result\":$result}"
            }
            fun response(json: String) = PrinterProtocol.encode(PrinterFrame(1, id.toLong(),
                if (wrongKey) ByteArray(json.length) { 23 } else cipher.crypt(json.toByteArray()), interactive = 7))
            if (unrelatedReply) incoming += response("{\"id\":0,\"result\":{}}")
            incoming += response(reply)
        }
        override fun read(timeoutMs: Long): ByteArray = incoming.removeFirstOrNull()
            ?: throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        override fun close() { cipher.close() }
    }
    private fun session(printer: CameraIndependentPrinter, events: MutableList<PrinterDiagnostic> = mutableListOf()): PrinterSession {
        var clock = 0L
        return PrinterSession(printer, token, events::add, now = { clock }, pause = { clock += it })
    }
    private fun fails(code: PrinterFailureCode, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: PrinterFailure) { assertEquals(code, e.code) }
    }
    @Test fun uploadsOneCopyWithBoundedChunksAndRequiresExplicitFinishedJob() {
        val printer = CameraIndependentPrinter()
        val bytes = jpeg()
        session(printer).use { it.print(bytes, printer.guard) }
        assertEquals(listOf("mixed_status", "clean_data", "print_job", "confirm_job", "job_info"), printer.methods)
        assertEquals(1, printer.guard.reservations)
        assertFalse(printer.guard.unresolved)
        assertEquals(listOf(1, 2, 3), printer.fileFrames.map { it.part })
        assertTrue(printer.fileFrames.all { it.total == 3 && it.body.size <= 928 })
        assertTrue(printer.fileFrames.all { PrinterProtocol.get(it.body, 0, 4) == 7L })
        assertArrayEquals(bytes, printer.fileFrames.flatMap { it.body.drop(4) }.toByteArray())
    }
    @Test fun processingPrinterStopsBeforeClearDataOrJobReservation() {
        val printer = CameraIndependentPrinter(processing = true)
        fails(PrinterFailureCode.BUSY) { session(printer).print(jpeg(), printer.guard) }
        assertEquals(listOf("mixed_status"), printer.methods)
        assertFalse(printer.guard.unresolved)
    }
    @Test fun ownAcceptedJobMayRemainProcessingUntilExplicitCompletion() {
        val printer = CameraIndependentPrinter(processingAfterStart = true, finishAfter = 2)
        session(printer).print(jpeg(), printer.guard)
        assertFalse(printer.guard.unresolved)
        assertEquals(2, printer.methods.count { it == "job_info" })
    }
    @Test fun malformedRejectionCannotUnlockUnknownReservation() {
        val printer = CameraIndependentPrinter(malformedError = true)
        fails(PrinterFailureCode.PROTOCOL_ERROR) { session(printer).print(jpeg(), printer.guard) }
        assertTrue(printer.guard.unresolved)
    }
    @Test fun explicitNullErrorDoesNotRejectSuccessfulReplies() {
        val printer = CameraIndependentPrinter(nullError = true)
        session(printer).print(jpeg(), printer.guard)
        assertFalse(printer.guard.unresolved)
    }
    @Test fun knownJobRejectionReleasesGuardWithoutRetry() {
        val printer = CameraIndependentPrinter(rejection = true)
        fails(PrinterFailureCode.BUSY) { session(printer).print(jpeg(), printer.guard) }
        assertFalse(printer.guard.unresolved)
        assertEquals(1, printer.methods.count { it == "print_job" })
        assertTrue(printer.fileFrames.isEmpty())
    }
    @Test fun lostReservationReplyLeavesUnknownOutcomeAndNeverRetries() {
        val printer = CameraIndependentPrinter(lostMethod = "print_job")
        val events = mutableListOf<PrinterDiagnostic>()
        fails(PrinterFailureCode.TIMEOUT) { session(printer, events).print(jpeg(), printer.guard) }
        assertTrue(printer.guard.unresolved)
        assertTrue(events.any { it.stage == PrinterStage.OUTCOME_UNKNOWN })
        assertEquals(1, printer.methods.count { it == "print_job" })
    }
    @Test fun lostConfirmationReplyLeavesUnknownOutcome() {
        val printer = CameraIndependentPrinter(lostMethod = "confirm_job")
        fails(PrinterFailureCode.TIMEOUT) { session(printer).print(jpeg(), printer.guard) }
        assertTrue(printer.guard.unresolved)
        assertEquals(1, printer.methods.count { it == "print_job" })
    }
    @Test fun idleStatusDoesNotProvePhysicalCompletion() {
        val printer = CameraIndependentPrinter(neverFinished = true)
        fails(PrinterFailureCode.TIMEOUT) { session(printer).print(jpeg(), printer.guard) }
        assertTrue(printer.guard.unresolved)
    }
    @Test fun finishedDifferentJobCannotResolveThisJob() {
        val printer = CameraIndependentPrinter(wrongJob = true)
        fails(PrinterFailureCode.PROTOCOL_ERROR) { session(printer).print(jpeg(), printer.guard) }
        assertTrue(printer.guard.unresolved)
    }
    @Test fun unresolvedReservationBlocksAllNewPrintTraffic() {
        val printer = CameraIndependentPrinter().apply { guard.unresolved = true }
        fails(PrinterFailureCode.UNRESOLVED_JOB) { session(printer).print(jpeg(), printer.guard) }
        assertTrue(printer.methods.isEmpty())
    }
    @Test fun invalidKeyReplyStopsReadOnlyDiagnosis() {
        val printer = CameraIndependentPrinter(wrongKey = true)
        fails(PrinterFailureCode.KEY_OR_PROFILE_REJECTED) { session(printer).diagnose() }
        assertEquals(listOf("mixed_status"), printer.methods)
    }
    @Test fun unrelatedResponsesDoNotSatisfyCurrentRequest() {
        val printer = CameraIndependentPrinter(unrelatedReply = true)
        val status = session(printer).diagnose()
        assertEquals("init", status.phase)
        assertEquals(80, status.battery)
        assertEquals(listOf("mixed_status", "get_prop"), printer.methods)
    }
    @Test fun invalidProgressiveOrWrongSizedImageSendsNothing() {
        val progressive = jpeg().apply { this[3] = -62 }
        val wrongSize = jpeg().apply { this[10] = 1 }
        listOf(byteArrayOf(1, 2, 3), progressive, wrongSize).forEach { bytes ->
            val printer = CameraIndependentPrinter()
            fails(PrinterFailureCode.INVALID_IMAGE) { session(printer).print(bytes, printer.guard) }
            assertTrue(printer.methods.isEmpty())
        }
    }
    @Test fun cancellationAfterReservationLeavesGuardAndSendsNoFileOrConfirmation() {
        val printer = CameraIndependentPrinter()
        var clock = 0L
        val session = PrinterSession(printer, token, report = {
            if (it.stage == PrinterStage.JOB_RESERVED) Thread.currentThread().interrupt()
        }, now = { clock }, pause = { clock += it })
        try {
            fails(PrinterFailureCode.CANCELLED) { session.print(jpeg(), printer.guard) }
            assertTrue(printer.guard.unresolved)
            assertTrue(printer.fileFrames.isEmpty())
            assertFalse(printer.methods.contains("confirm_job"))
        } finally { Thread.interrupted(); session.close() }
    }
}
