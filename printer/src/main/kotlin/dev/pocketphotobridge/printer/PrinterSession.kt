package dev.pocketphotobridge.printer

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException

data class PrinterStatus(val phase: String, val battery: Int?)
enum class PrinterStage { STATUS_READY, PREFLIGHT, JOB_RESERVED, UPLOAD, JOB_CONFIRMED, PRINTING, PRINT_FINISHED, OUTCOME_UNKNOWN }
data class PrinterDiagnostic(val stage: PrinterStage, val count: Long? = null) {
    fun safeText() = "PRINTER_${stage.name}" + (count?.let { " count=$it" } ?: "")
}

class PrinterSession(private val transport: PrinterTransport, token: String,
    private val report: (PrinterDiagnostic) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) : AutoCloseable {
    private val cipher = PrinterCipher(token)
    private val reader = PrinterFrameReader()
    private val incoming = ArrayDeque<PrinterFrame>()
    private var sequence = 0L
    private var deadline = Long.MAX_VALUE

    fun diagnose(): PrinterStatus {
        val status = status()
        // Verify another documented read-only operation, without exporting identity fields.
        val info = result(rpc("get_prop", JsonArray().apply { add("device_info") }))
        if (!info.isJsonArray || info.asJsonArray.size() != 1 || !info.asJsonArray[0].isJsonObject)
            throw PrinterFailure(PrinterFailureCode.UNSUPPORTED_STATUS)
        report(PrinterDiagnostic(PrinterStage.STATUS_READY))
        return status
    }

    fun print(jpeg: ByteArray, guard: PrintJobGuard) {
        if (guard.unresolved) throw PrinterFailure(PrinterFailureCode.UNRESOLVED_JOB)
        validateJpeg(jpeg)
        deadline = now() + 240_000
        var reservation: String? = null
        try {
            checkActive()
            report(PrinterDiagnostic(PrinterStage.PREFLIGHT))
            val before = status()
            if (before.phase !in setOf("init", "idle")) throw PrinterFailure(PrinterFailureCode.BUSY)
            rpc("clean_data", JsonObject().apply { addProperty("delay_times", 0) }, reply = false)
            waitFor(300)
            // Disk reservation precedes the write: a lost reply/process death must block duplicates.
            reservation = guard.reserve()
            val jobReply = rpc("print_job", JsonObject().apply {
                addProperty("copies", 1); addProperty("channel", 64)
                addProperty("job_type", 0); addProperty("file_size", jpeg.size)
            })
            if (hasError(jobReply)) {
                val code = errorCode(jobReply)
                // Only a structured rejection is proof that no job was reserved.
                if (code == null || code >= 0) badResponse()
                guard.resolve(reservation)
                throw PrinterFailure(if (code == -8006L) PrinterFailureCode.BUSY else PrinterFailureCode.JOB_REJECTED)
            }
            val job = result(jobReply)
            if (!job.isJsonObject) badResponse()
            val jobId = unsignedInteger(job.asJsonObject["job_id"])
            report(PrinterDiagnostic(PrinterStage.JOB_RESERVED))
            val total = (jpeg.size + 923) / 924
            repeat(total) { part ->
                checkActive()
                val start = part * 924
                val data = jpeg.copyOfRange(start, minOf(start + 924, jpeg.size))
                val body = ByteArray(data.size + 4)
                PrinterProtocol.put(body, 0, jobId, 4); data.copyInto(body, 4)
                transport.write(PrinterProtocol.encode(PrinterFrame(2, nextSequence(), cipher.crypt(body),
                    encoding = 2, total = total, part = part + 1)))
                if (part == 0 || part == total - 1 || part % 64 == 0)
                    report(PrinterDiagnostic(PrinterStage.UPLOAD, minOf(start + 924, jpeg.size).toLong()))
                waitFor(30)
            }
            val confirmation = result(rpc("confirm_job", JsonArray().apply { add(jobId) }))
            if (!confirmation.isJsonArray || confirmation.asJsonArray.size() != 1 ||
                confirmation.asJsonArray[0] != JsonPrimitive("OK")) badResponse()
            report(PrinterDiagnostic(PrinterStage.JOB_CONFIRMED))
            while (true) {
                checkActive()
                val info = result(rpc("job_info", JsonArray().apply { add(jobId) }))
                if (!info.isJsonArray || info.asJsonArray.size() != 1 || !info.asJsonArray[0].isJsonObject) badResponse()
                val current = info.asJsonArray[0].asJsonObject
                if (unsignedInteger(current["job_id"]) != jobId) badResponse()
                val state = text(current["job_state"])
                if (state == "finished") {
                    guard.resolve(reservation)
                    report(PrinterDiagnostic(PrinterStage.PRINT_FINISHED))
                    return
                }
                // Idle without an explicit matching finished job is insufficient evidence.
                status(allowProcessing = true)
                report(PrinterDiagnostic(PrinterStage.PRINTING))
                waitFor(1500)
            }
        } catch (e: Exception) {
            if (reservation != null && guard.unresolved) report(PrinterDiagnostic(PrinterStage.OUTCOME_UNKNOWN))
            throw when (e) {
                is PrinterFailure -> e
                is InterruptedException -> { Thread.currentThread().interrupt(); PrinterFailure(PrinterFailureCode.CANCELLED) }
                is IOException -> PrinterFailure(PrinterFailureCode.DISCONNECTED)
                else -> PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
            }
        } finally { deadline = Long.MAX_VALUE }
    }

    private fun status(allowProcessing: Boolean = false): PrinterStatus {
        val status = result(rpc("mixed_status", JsonArray()))
        if (!status.isJsonObject) throw PrinterFailure(PrinterFailureCode.UNSUPPORTED_STATUS)
        val value = status.asJsonObject
        val error = value["error"]
        if (hasFault(error)) throw PrinterFailure(PrinterFailureCode.PRINTER_FAULT)
        val category = text(value["category"])
        if (category == "processing" && !allowProcessing) throw PrinterFailure(PrinterFailureCode.BUSY)
        if (category !in setOf("idle", "processing")) throw PrinterFailure(PrinterFailureCode.UNSUPPORTED_STATUS)
        val phase = text(value["sub_category"])
        if (phase !in PHASES) throw PrinterFailure(PrinterFailureCode.UNSUPPORTED_STATUS)
        val battery = value["battery"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.toString()?.toIntOrNull()?.takeIf { it in 0..100 }
        return PrinterStatus(phase, battery)
    }

    private fun rpc(method: String, params: JsonElement, reply: Boolean = true): JsonObject {
        checkActive()
        val id = nextSequence()
        val request = JsonObject().apply {
            addProperty("id", id); addProperty("method", method); add("params", params)
        }.toString().toByteArray(Charsets.UTF_8)
        transport.write(PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt(request))))
        if (!reply) return JsonObject()
        val end = minOf(deadline, now() + 15_000)
        var ignored = 0
        while (true) {
            checkActive()
            val remaining = end - now()
            if (remaining <= 0) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
            if (incoming.isEmpty()) {
                incoming.addAll(reader.accept(transport.read(remaining)))
                if (incoming.size > 64) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
                continue
            }
            val frame = incoming.removeFirst()
            if (++ignored > 128) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
            if (frame.channel != 1 || frame.interactive != 7) continue
            if (frame.encoding != 3 || frame.encryption != 4 || frame.total != 1)
                throw PrinterFailure(PrinterFailureCode.KEY_OR_PROFILE_REJECTED)
            val json = try {
                JsonParser.parseString(cipher.crypt(frame.body).toString(Charsets.UTF_8)).asJsonObject
            } catch (_: Exception) { throw PrinterFailure(PrinterFailureCode.KEY_OR_PROFILE_REJECTED) }
            val responseId = json["id"]?.toString()?.toLongOrNull() ?: continue
            if (responseId == id) return json
        }
    }

    private fun result(reply: JsonObject): JsonElement {
        if (hasError(reply)) throw PrinterFailure(if (errorCode(reply) == -8006L) PrinterFailureCode.BUSY else PrinterFailureCode.PRINTER_FAULT)
        return reply["result"] ?: badResponse()
    }
    private fun errorCode(reply: JsonObject): Long? = reply["error"]?.takeIf { it.isJsonObject }
        ?.asJsonObject?.get("code")?.toString()?.toLongOrNull()
    private fun hasError(reply: JsonObject) = reply["error"]?.let { !it.isJsonNull } ?: false
    private fun hasFault(error: JsonElement?): Boolean = when {
        error == null || error.isJsonNull -> false
        error.isJsonArray -> error.asJsonArray.size() != 0
        error.isJsonObject -> error.asJsonObject.size() != 0
        else -> error != JsonPrimitive(0) && error != JsonPrimitive(false) && error != JsonPrimitive("")
    }
    private fun unsignedInteger(value: JsonElement?): Long = value?.toString()?.toLongOrNull()
        ?.takeIf { it in 0..0xffffffffL } ?: badResponse()
    private fun text(value: JsonElement?): String = value?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString ?: badResponse()
    private fun badResponse(): Nothing = throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
    private fun nextSequence(): Long { if (++sequence > 0xffffffffL) badResponse(); return sequence }
    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED)
        if (now() >= deadline) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
    }
    private fun waitFor(milliseconds: Long) {
        checkActive()
        try { pause(milliseconds) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); throw PrinterFailure(PrinterFailureCode.CANCELLED)
        }
        checkActive()
    }
    override fun close() { cipher.close() }

    companion object {
        private val PHASES = setOf("init", "smart_sheet", "decoding", "pre_heat", "load_paper", "printing", "idle")
        const val MAX_JPEG_BYTES = 4 * 1024 * 1024
        private fun validateJpeg(bytes: ByteArray) {
            if (bytes.size !in 16..MAX_JPEG_BYTES || bytes[0] != (-1).toByte() || bytes[1] != (-40).toByte() ||
                bytes[bytes.lastIndex - 1] != (-1).toByte() || bytes.last() != (-39).toByte())
                throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            var offset = 2
            var baseline = false
            while (offset + 4 <= bytes.size) {
                if (bytes[offset] != (-1).toByte()) break
                val marker = bytes[offset + 1].toInt() and 255
                val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
                if (length < 2 || offset + 2 + length > bytes.size) break
                if (marker == 0xc2) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
                if (marker == 0xc0) {
                    if (length < 8) break
                    val height = ((bytes[offset + 5].toInt() and 255) shl 8) or (bytes[offset + 6].toInt() and 255)
                    val width = ((bytes[offset + 7].toInt() and 255) shl 8) or (bytes[offset + 8].toInt() and 255)
                    baseline = bytes[offset + 4] == 8.toByte() && width == PrinterImageLayout.WIDTH && height == PrinterImageLayout.HEIGHT
                }
                if (marker == 0xda) break
                offset += 2 + length
            }
            if (!baseline) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        }
    }
}
