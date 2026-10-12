package dev.pocketphotobridge.printer

import org.junit.Assert.*
import org.junit.Test

class PrinterProtocolTest {
    // Synthetic fixture token, unrelated to any printer.
    private val token = "000102030405060708090a0b"
    @Test fun observedVersionOneHeaderCanBeDecodedAndFragmentedWithSyntheticBody() {
        // Header matches the tablet trace. Body is synthetic, not a hardware capture.
        val packet = versionOnePacket()
        val headers = mutableListOf<PrinterFrameMetadata>()
        val reader = PrinterFrameReader(headers::add)
        assertTrue(reader.accept(packet.copyOfRange(0, 13)).isEmpty())
        val decoded = reader.accept(packet.copyOfRange(13, packet.size)).single()
        assertEquals(1, headers.single().version)
        assertEquals(6, decoded.interactive)
        assertEquals(4, decoded.encryption)
        assertEquals(81, decoded.body.size)
        assertEquals(4177, headers.single().attributes)
        assertArrayEquals(ByteArray(81) { it.toByte() }, decoded.body)
    }
    @Test fun versionOneStillRequiresTheOriginalChecksum() {
        val packet = versionOnePacket().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        val error = assertThrows(PrinterFailure::class.java) { PrinterProtocol.decode(packet) }
        assertEquals(PrinterFailureReason.FRAME_CHECKSUM, error.reason)
    }
    private fun versionOnePacket(): ByteArray {
        val packet = ByteArray(103)
        byteArrayOf(0x7e, 1, 0, 1, 6, 3).copyInto(packet)
        packet[10] = 1; packet[14] = 1; packet[16] = 1
        packet[18] = 0x51; packet[19] = 0x10
        ByteArray(81) { it.toByte() }.copyInto(packet, 20)
        packet[101] = (((0 until 101).sumOf { packet[it].toInt() and 255 } - 126) and 255).toByte()
        packet[102] = 0x7e
        return packet
    }
    @Test fun matchesIndependentFrameVector() {
        val packet = PrinterProtocol.encode(PrinterFrame(1, 1, byteArrayOf(0x7b, 0x7d), encryption = 0))
        assertEquals("7e640001060300000000010000000100010002007b7d6b7e", packet.hex())
        val decoded = PrinterProtocol.decode(packet)
        assertEquals(1, decoded.channel)
        assertEquals(1L, decoded.sequence)
        assertArrayEquals(byteArrayOf(0x7b, 0x7d), decoded.body)
    }
    @Test fun frameReaderHandlesFragmentationAndCoalescing() {
        val first = PrinterProtocol.encode(PrinterFrame(1, 7, byteArrayOf(1, 2)))
        val second = PrinterProtocol.encode(PrinterFrame(2, 8, byteArrayOf(3), total = 2, part = 2, encoding = 2))
        val reader = PrinterFrameReader()
        assertTrue(reader.accept(first.copyOfRange(0, 11)).isEmpty())
        val frames = reader.accept(first.copyOfRange(11, first.size) + second)
        assertEquals(listOf(7L, 8L), frames.map { it.sequence })
        assertEquals(2, frames.last().part)
    }
    @Test fun checksumAndUnsupportedHeaderAreRejected() {
        val valid = PrinterProtocol.encode(PrinterFrame(1, 1, byteArrayOf(1, 2)))
        val corrupt = valid.clone().apply { this[20] = 5 }
        rejected(PrinterFailureCode.PROTOCOL_ERROR) { PrinterProtocol.decode(corrupt) }
        val version = valid.clone().apply { this[1] = 2 }
        rejected(PrinterFailureCode.PROTOCOL_ERROR) { PrinterProtocol.decode(version) }
    }
    @Test fun boundsPreventOversizedBodiesAndInputBuffers() {
        rejected(PrinterFailureCode.LIMIT_EXCEEDED) {
            PrinterProtocol.encode(PrinterFrame(1, 1, ByteArray(1024)))
        }
        rejected(PrinterFailureCode.LIMIT_EXCEEDED) { PrinterFrameReader().accept(ByteArray(8193)) }
    }
    @Test fun rc4MatchesIndependentVectorAndRestartsForEachFrame() {
        val cipher = PrinterCipher(token)
        // Reference RC4 KSA/PRGA vector for ascending synthetic 12-byte key.
        assertEquals("63fd878d109ea1ac57ea3ed3e01986a9", cipher.crypt(ByteArray(16)).hex())
        val plain = ByteArray(928) { it.toByte() }
        assertArrayEquals(plain, cipher.crypt(cipher.crypt(plain)))
        assertArrayEquals(cipher.crypt(plain), cipher.crypt(plain))
        cipher.close()
        rejected(PrinterFailureCode.INVALID_KEY) { cipher.crypt(plain) }
    }
    @Test fun invalidTokensAreRejectedWithoutEchoingThem() {
        listOf("", "ff", "x".repeat(24), "0".repeat(32)).forEach { key ->
            try { PrinterCipher(key); fail("Expected invalid key") }
            catch (e: PrinterFailure) { assertEquals("INVALID_KEY", e.message) }
        }
    }
    @Test fun fitPreservesEntireLandscapeAndCropFillsMedia() {
        val fit = PrinterImageLayout.place(2000, 1000, false)
        assertEquals(1040f, fit.width, 0f)
        assertEquals(520f, fit.height, 0f)
        assertEquals(520f, fit.top, 0f)
        val crop = PrinterImageLayout.place(2000, 1000, true)
        assertEquals(3120f, crop.width, 0f)
        assertEquals(-1040f, crop.left, 0f)
        assertEquals(1560f, crop.height, 0f)
    }
    private fun rejected(code: PrinterFailureCode, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: PrinterFailure) { assertEquals(code, e.code) }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
