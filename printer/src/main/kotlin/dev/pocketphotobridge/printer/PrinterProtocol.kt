package dev.pocketphotobridge.printer

enum class PrinterFailureCode {
    KEY_REQUIRED, INVALID_KEY, KEY_OR_PROFILE_REJECTED, TIMEOUT, CANCELLED, DISCONNECTED,
    PROTOCOL_ERROR, LIMIT_EXCEEDED, INVALID_IMAGE, UNSUPPORTED_STATUS, BUSY, PRINTER_FAULT,
    JOB_REJECTED, UNRESOLVED_JOB, PERSISTENCE_ERROR
}
enum class PrinterFailureReason {
    STREAM_HEADER, FRAME_SHORT, FRAME_DELIMITER, FRAME_VERSION, FRAME_RESERVED, FRAME_LENGTH,
    FRAME_ATTRIBUTES, FRAME_CHECKSUM, FRAME_CHANNEL, FRAME_INTERACTIVE, FRAME_ENCODING,
    FRAME_ENCRYPTION, FRAME_PACKAGE, FRAME_MULTIPART,
    RPC_RESULT_MISSING, REPLY_PROFILE, REPLY_JSON,
    STATUS_RESULT_TYPE, STATUS_CATEGORY_MISSING, STATUS_CATEGORY_TYPE, STATUS_CATEGORY_UNKNOWN,
    STATUS_PHASE_MISSING, STATUS_PHASE_TYPE, STATUS_PHASE_UNKNOWN, DEVICE_INFO_RESULT_TYPE, RESPONSE_SHAPE
}
class PrinterFailure(val code: PrinterFailureCode, val reason: PrinterFailureReason? = null) : RuntimeException(code.name)
/** Structural fields only. Sequence numbers, body bytes, identities and keys are excluded. */
data class PrinterFrameMetadata(val version: Int, val reserved: Int, val channel: Int,
    val interactive: Int, val encoding: Int, val encryption: Int,
    val total: Int, val part: Int, val bodyBytes: Int, val attributes: Int) {
    fun safeText() = "version=$version reserved=$reserved channel=$channel interactive=$interactive" +
        " encoding=$encoding encryption=$encryption parts=$total part=$part bytes=$bodyBytes attributes=$attributes"
}
interface PrinterTransport : java.io.Closeable {
    fun write(bytes: ByteArray)
    fun read(timeoutMs: Long): ByteArray
}
interface PrintJobGuard {
    val unresolved: Boolean
    fun reserve(): String
    fun resolve(reservation: String)
    fun acknowledgeCheckedPrinter()
}
data class PrinterFrame(val channel: Int, val sequence: Long, val body: ByteArray,
    val encryption: Int = 4, val encoding: Int = 3, val interactive: Int = 6,
    val total: Int = 1, val part: Int = 1)
object PrinterProtocol {
    fun encode(frame: PrinterFrame): ByteArray {
        if (frame.body.size > 1023) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        validate(frame)
        val packet = ByteArray(22 + frame.body.size)
        packet[0] = 0x7e; packet[1] = 0x64
        packet[3] = frame.channel.toByte(); packet[4] = frame.interactive.toByte(); packet[5] = frame.encoding.toByte()
        put(packet, 10, frame.sequence, 4)
        put(packet, 14, frame.total.toLong(), 2); put(packet, 16, frame.part.toLong(), 2)
        val attribute = frame.body.size or (frame.encryption shl 10) or (if (frame.total > 1) 0x2000 else 0)
        put(packet, 18, attribute.toLong(), 2)
        frame.body.copyInto(packet, 20)
        packet[packet.lastIndex - 1] = checksum(packet, packet.size - 2).toByte()
        packet[packet.lastIndex] = 0x7e
        return packet
    }

    fun decode(packet: ByteArray, observe: (PrinterFrameMetadata) -> Unit = {}): PrinterFrame {
        if (packet.size < 22) invalid(PrinterFailureReason.FRAME_SHORT)
        if (packet[0] != 0x7e.toByte()) invalid(PrinterFailureReason.FRAME_DELIMITER)
        observe(metadata(packet))
        if (packet[1] != 0x64.toByte()) invalid(PrinterFailureReason.FRAME_VERSION)
        if (packet[2] != 0.toByte()) invalid(PrinterFailureReason.FRAME_RESERVED)
        if (packet.last() != 0x7e.toByte()) invalid(PrinterFailureReason.FRAME_DELIMITER)
        val attr = get(packet, 18, 2).toInt()
        val size = attr and 0x3ff
        if (packet.size != size + 22) invalid(PrinterFailureReason.FRAME_LENGTH)
        if (attr and 0xc000 != 0) invalid(PrinterFailureReason.FRAME_ATTRIBUTES)
        if ((packet[packet.lastIndex - 1].toInt() and 255) != checksum(packet, packet.size - 2))
            invalid(PrinterFailureReason.FRAME_CHECKSUM)
        val frame = PrinterFrame(packet[3].toInt() and 255, get(packet, 10, 4), packet.copyOfRange(20, 20 + size),
            (attr shr 10) and 7, packet[5].toInt() and 255, packet[4].toInt() and 255,
            get(packet, 14, 2).toInt(), get(packet, 16, 2).toInt())
        validate(frame)
        if ((attr and 0x2000 != 0) != (frame.total > 1)) invalid(PrinterFailureReason.FRAME_MULTIPART)
        return frame
    }

    private fun validate(frame: PrinterFrame) {
        if (frame.channel !in listOf(1, 2, 255)) invalid(PrinterFailureReason.FRAME_CHANNEL)
        if (frame.interactive !in listOf(6, 7)) invalid(PrinterFailureReason.FRAME_INTERACTIVE)
        if (frame.encoding !in 1..3) invalid(PrinterFailureReason.FRAME_ENCODING)
        if (frame.encryption !in listOf(0, 4)) invalid(PrinterFailureReason.FRAME_ENCRYPTION)
        if (frame.sequence !in 0..0xffffffffL) invalid(PrinterFailureReason.RESPONSE_SHAPE)
        if (frame.total !in 1..65535 || frame.part !in 1..frame.total) invalid(PrinterFailureReason.FRAME_PACKAGE)
    }
    internal fun metadata(bytes: ByteArray, offset: Int = 0): PrinterFrameMetadata {
        val attr = get(bytes, offset + 18, 2).toInt()
        return PrinterFrameMetadata(bytes[offset + 1].toInt() and 255, bytes[offset + 2].toInt() and 255,
            bytes[offset + 3].toInt() and 255, bytes[offset + 4].toInt() and 255, bytes[offset + 5].toInt() and 255,
            (attr shr 10) and 7, get(bytes, offset + 14, 2).toInt(), get(bytes, offset + 16, 2).toInt(),
            attr and 0x3ff, attr)
    }
    private fun checksum(bytes: ByteArray, end: Int) = ((0 until end).sumOf { bytes[it].toInt() and 255 } - 126) and 255
    private fun invalid(reason: PrinterFailureReason): Nothing = throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR, reason)
    internal fun put(bytes: ByteArray, start: Int, value: Long, length: Int) {
        repeat(length) { bytes[start + it] = (value ushr (8 * it)).toByte() }
    }
    internal fun get(bytes: ByteArray, start: Int, length: Int): Long = (0 until length)
        .fold(0L) { value, i -> value or ((bytes[start + i].toLong() and 255) shl (8 * i)) }
}
class PrinterFrameReader(private val observe: (PrinterFrameMetadata) -> Unit = {}) {
    private var pending = ByteArray(0)
    fun accept(bytes: ByteArray): List<PrinterFrame> {
        if (pending.size + bytes.size > 8192) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        pending += bytes
        val frames = mutableListOf<PrinterFrame>()
        var offset = 0
        while (pending.size - offset >= 22) {
            if (pending[offset] != 0x7e.toByte())
                throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR, PrinterFailureReason.STREAM_HEADER)
            if (pending[offset + 1] != 0x64.toByte()) {
                observe(PrinterProtocol.metadata(pending, offset))
                throw PrinterFailure(PrinterFailureCode.PROTOCOL_ERROR, PrinterFailureReason.FRAME_VERSION)
            }
            val size = (PrinterProtocol.get(pending, offset + 18, 2).toInt() and 0x3ff) + 22
            if (pending.size - offset < size) break
            frames += PrinterProtocol.decode(pending.copyOfRange(offset, offset + size), observe)
            offset += size
        }
        pending = pending.copyOfRange(offset, pending.size)
        return frames
    }
}
class PrinterCipher(token: String) : AutoCloseable {
    private val stream: ByteArray
    private var closed = false
    init {
        if (!token.matches(Regex("[0-9a-fA-F]{24}"))) throw PrinterFailure(PrinterFailureCode.INVALID_KEY)
        val key = ByteArray(12) { token.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + state[i] + (key[i % key.size].toInt() and 255)) and 255
            val swap = state[i]; state[i] = state[j]; state[j] = swap
        }
        key.fill(0)
        var i = 0; j = 0
        stream = ByteArray(1023) {
            i = (i + 1) and 255; j = (j + state[i]) and 255
            val swap = state[i]; state[i] = state[j]; state[j] = swap
            state[(state[i] + state[j]) and 255].toByte()
        }
        state.fill(0)
    }
    fun crypt(bytes: ByteArray): ByteArray {
        if (closed) throw PrinterFailure(PrinterFailureCode.INVALID_KEY)
        if (bytes.size > stream.size) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
        return ByteArray(bytes.size) { (bytes[it].toInt() xor stream[it].toInt()).toByte() }
    }
    override fun close() { closed = true; stream.fill(0) }
}
data class ImagePlacement(val left: Float, val top: Float, val width: Float, val height: Float)
object PrinterImageLayout {
    const val WIDTH = 1040
    const val HEIGHT = 1560
    fun place(sourceWidth: Int, sourceHeight: Int, crop: Boolean): ImagePlacement {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        val x = WIDTH.toFloat() / sourceWidth; val y = HEIGHT.toFloat() / sourceHeight
        val scale = if (crop) maxOf(x, y) else minOf(x, y)
        val width = sourceWidth * scale; val height = sourceHeight * scale
        return ImagePlacement((WIDTH - width) / 2, (HEIGHT - height) / 2, width, height)
    }
}
