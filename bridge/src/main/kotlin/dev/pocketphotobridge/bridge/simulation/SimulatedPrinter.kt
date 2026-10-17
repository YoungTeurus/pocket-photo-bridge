package dev.pocketphotobridge.bridge.simulation

import dev.pocketphotobridge.bridge.PrinterConnector
import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.printer.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGHuffmanTable

/** Public synthetic fixture key. Never use this for an actual printer. */
const val SIMULATION_TOKEN = "000102030405060708090a0b"
enum class PrinterFault { NONE, OFFLINE, BUSY, DROP_AFTER_SUBMIT, NEVER_FINISH, POWER_CUT }
class SimulatedPowerLoss : Error("SIMULATED_POWER_LOSS")

class SimulationClock {
    var milliseconds: Long = 0
        private set
    fun now(): Long = milliseconds
    fun pause(ms: Long) {
        require(ms >= 0) { "INVALID_SIMULATED_PAUSE" }
        milliseconds = Math.addExact(milliseconds, ms)
    }
}

/** Parses actual encrypted protocol traffic, but never connects to radios or prints. */
class SimulatedPrinter(private val fault: PrinterFault = PrinterFault.NONE,
    private val crash: () -> Unit = { throw SimulatedPowerLoss() }) : PrinterConnector {
    var submissions: Int = 0
        private set
    var completedJobs: Int = 0
        private set
    var uploadedFrames: Int = 0
        private set
    private var uploaded: ByteArray? = null
    val uploadedJpeg: ByteArray? get() = uploaded?.copyOf()
    private val commands = mutableListOf<String>()
    val methods: List<String> get() = commands.toList()
    private var job: Job? = null
    private var nextJobId = 7L

    override fun connect(): PrinterTransport {
        if (fault == PrinterFault.OFFLINE) throw BridgeFailure(BridgeFailureCode.PRINTER_OFFLINE)
        return Connection()
    }

    private class Job(val id: Long, val size: Int) {
        val total = (size + 923) / 924
        val bytes = ByteArrayOutputStream()
        var nextPart = 1
        var confirmed = false
        var finished = false
    }

    private inner class Connection : PrinterTransport {
        private val cipher = PrinterCipher(SIMULATION_TOKEN)
        private val incoming = ArrayDeque<ByteArray>()
        private var closed = false
        private var cleaned = false
        private var lastSequence = 0L

        override fun write(bytes: ByteArray) {
            ensureOpen()
            try {
                requireProtocol(bytes.size >= 22 && bytes[1] == 0x64.toByte())
                val frame = PrinterProtocol.decode(bytes)
                requireProtocol(frame.interactive == 6 && frame.encryption == 4 && frame.sequence > lastSequence)
                lastSequence = frame.sequence
                val body = cipher.crypt(frame.body)
                when (frame.channel) {
                    1 -> {
                        requireProtocol(frame.encoding == 3 && frame.total == 1 && frame.part == 1)
                        val request = JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
                        requireProtocol(number(request["id"]) == frame.sequence)
                        command(frame.sequence, request)
                    }
                    2 -> upload(frame, body)
                    else -> invalid()
                }
            } catch (e: IOException) {
                close()
                throw e
            } catch (e: Exception) {
                close()
                throw if (e is PrinterFailure) e else PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
            }
        }

        private fun command(id: Long, request: JsonObject) {
            val method = request["method"]?.asString ?: invalid()
            val params = request["params"] ?: invalid()
            commands += method
            val result = when (method) {
                "mixed_status" -> {
                    requireProtocol(params.isJsonArray && params.asJsonArray.size() == 0)
                    val processing = fault == PrinterFault.BUSY || job?.let { !it.finished } == true
                    if (processing) "{\"category\":\"processing\",\"sub_category\":\"printing\",\"error\":0,\"battery\":80}"
                    else "{\"category\":\"idle\",\"sub_category\":\"init\",\"error\":0,\"battery\":80}"
                }
                "get_prop" -> {
                    requireProtocol(params.toString() == "[\"device_info\"]")
                    "[{\"fw_ver\":\"synthetic\",\"hw_ver\":\"synthetic\"}]"
                }
                "clean_data" -> {
                    requireProtocol(params.isJsonObject && number(params.asJsonObject["delay_times"]) == 0L)
                    requireProtocol(job?.let { !it.finished } != true)
                    cleaned = true
                    job = null
                    return // The real session correctly sends this without waiting for a reply.
                }
                "print_job" -> {
                    requireProtocol(cleaned && job == null && params.isJsonObject)
                    val values = params.asJsonObject
                    requireProtocol(number(values["copies"]) == 1L && number(values["channel"]) == 64L &&
                        number(values["job_type"]) == 0L)
                    val size = number(values["file_size"])
                    requireProtocol(size in 16..PrinterSession.MAX_JPEG_BYTES.toLong())
                    job = Job(nextJobId++, size.toInt())
                    cleaned = false
                    submissions++
                    if (fault == PrinterFault.DROP_AFTER_SUBMIT) throw IOException("SIMULATED_PRINTER_DISCONNECTED")
                    if (fault == PrinterFault.POWER_CUT) {
                        close()
                        crash() // Acceptance and the caller's durable guard both precede this hook.
                        throw SimulatedPowerLoss()
                    }
                    "{\"job_id\":${job!!.id}}"
                }
                "confirm_job" -> {
                    val current = matchingJob(params)
                    requireProtocol(!current.confirmed && current.nextPart == current.total + 1 && current.bytes.size() == current.size)
                    validateUploadedJpeg(current.bytes.toByteArray())
                    current.confirmed = true
                    "[\"OK\"]"
                }
                "job_info" -> {
                    val current = matchingJob(params)
                    requireProtocol(current.confirmed)
                    if (fault != PrinterFault.NEVER_FINISH && !current.finished) {
                        current.finished = true
                        completedJobs++
                    }
                    "[{\"job_id\":${current.id},\"job_state\":\"${if (current.finished) "finished" else "processing"}\"}]"
                }
                else -> invalid()
            }
            enqueue(id, "{\"id\":$id,\"result\":$result}")
        }

        private fun matchingJob(params: JsonElement): Job {
            val current = job ?: invalid()
            requireProtocol(params.isJsonArray && params.asJsonArray.size() == 1 && number(params.asJsonArray[0]) == current.id)
            return current
        }

        private fun upload(frame: PrinterFrame, body: ByteArray) {
            val current = job ?: invalid()
            requireProtocol(!current.confirmed && frame.encoding == 2 && body.size in 5..928 &&
                frame.total == current.total && frame.part == current.nextPart)
            val jobId = ByteBuffer.wrap(body, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            requireProtocol(jobId == current.id)
            val expected = minOf(924, current.size - current.bytes.size())
            requireProtocol(body.size - 4 == expected)
            current.bytes.write(body, 4, body.size - 4)
            current.nextPart++
            uploadedFrames++
            if (current.nextPart == current.total + 1) uploaded = current.bytes.toByteArray()
        }

        private fun enqueue(id: Long, reply: String) {
            fun packet(json: String): ByteArray {
                val packet = PrinterProtocol.encode(PrinterFrame(1, id, cipher.crypt(json.toByteArray())))
                packet[1] = 1 // Documented observed incoming version, while requests retain 0x64.
                packet[packet.lastIndex - 1] = (((0 until packet.size - 2).sumOf { packet[it].toInt() and 255 } - 126) and 255).toByte()
                return packet
            }
            // A matching JSON id on a method notification must never satisfy the pending RPC.
            val bytes = packet("{\"id\":$id,\"method\":\"synthetic_notification\",\"result\":{}}") + packet(reply)
            var position = 0
            val fragments = intArrayOf(1, 3, 11, 7, 29)
            var fragment = 0
            while (position < bytes.size) {
                val end = minOf(bytes.size, position + fragments[fragment++ % fragments.size])
                incoming += bytes.copyOfRange(position, end)
                position = end
            }
        }

        override fun read(timeoutMs: Long): ByteArray {
            ensureOpen()
            if (timeoutMs <= 0) throw PrinterFailure(PrinterFailureCode.TIMEOUT)
            return incoming.removeFirstOrNull() ?: throw PrinterFailure(PrinterFailureCode.TIMEOUT)
        }

        private fun ensureOpen() { if (closed) throw IOException("SIMULATED_PRINTER_CLOSED") }
        override fun close() {
            if (closed) return
            closed = true
            incoming.clear()
            cipher.close()
        }
    }

    /** Independent Java decoder and Annex K table constants validate the reassembled bytes. */
    private fun validateUploadedJpeg(bytes: ByteArray) {
        requireProtocol(bytes.size >= 16 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        val expectedTables = mapOf(0 to JPEGHuffmanTable.StdDCLuminance, 1 to JPEGHuffmanTable.StdDCChrominance,
            16 to JPEGHuffmanTable.StdACLuminance, 17 to JPEGHuffmanTable.StdACChrominance)
        val tables = mutableSetOf<Int>()
        var baseline = false
        var scan = false
        var position = 2
        while (position + 4 <= bytes.size) {
            requireProtocol(bytes[position] == 0xff.toByte())
            val marker = bytes[position + 1].toInt() and 255
            val length = word(bytes, position + 2)
            val end = position + length + 2
            requireProtocol(length >= 2 && end <= bytes.size)
            if (marker == 0xc2) invalid()
            if (marker == 0xc0) {
                requireProtocol(!baseline && length == 17 && bytes[position + 4] == 8.toByte() &&
                    word(bytes, position + 5) == 1560 && word(bytes, position + 7) == 1040 && bytes[position + 9] == 3.toByte())
                baseline = true
            }
            if (marker == 0xc4) {
                var offset = position + 4
                while (offset < end) {
                    requireProtocol(offset + 17 <= end)
                    val selector = bytes[offset++].toInt() and 255
                    val table = expectedTables[selector] ?: invalid()
                    requireProtocol(tables.add(selector))
                    val counts = ShortArray(16) { (bytes[offset + it].toInt() and 255).toShort() }
                    offset += 16
                    val size = counts.sumOf { it.toInt() }
                    requireProtocol(offset + size <= end && counts.contentEquals(table.lengths))
                    val values = ShortArray(size) { (bytes[offset + it].toInt() and 255).toShort() }
                    requireProtocol(values.contentEquals(table.values))
                    offset += size
                }
            }
            if (marker == 0xda) { scan = true; break }
            position = end
        }
        requireProtocol(baseline && scan && tables == expectedTables.keys)
        val image = try { ImageIO.read(ByteArrayInputStream(bytes)) ?: invalid() }
            catch (_: IOException) { invalid() }
        try { requireProtocol(image.width == 1040 && image.height == 1560) } finally { image.flush() }
    }

    private fun word(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun number(value: JsonElement?): Long = value?.toString()?.toLongOrNull() ?: invalid()
    private fun requireProtocol(valid: Boolean) { if (!valid) invalid() }
    private fun invalid(): Nothing = throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR)
}
