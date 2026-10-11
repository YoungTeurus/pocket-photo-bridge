package dev.pocketphotobridge.printer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class JpegHuffmanTest {
    @Test fun optimizedFixturesBecomeExactStandardEntropyWithoutPixelChanges() {
        // Independently encoded by Pillow 12.3.0 / libjpeg, quality90, optimize true/false.
        for (name in listOf("rgb420", "rgb422", "rgb444", "gray", "portrait")) {
            val optimized = fixture("$name-optimized.jpg")
            val expected = fixture("$name-standard.jpg")
            val actual = JpegHuffman.standardize(optimized)
            val tables = tables(expected)
            assertEquals(tables.keys, tables(actual).keys)
            tables.forEach { (id, table) -> assertArrayEquals("$name table $id", table, tables(actual)[id]) }
            assertArrayEquals("$name encoded coefficients must match independent standard encoder", entropy(expected), entropy(actual))
            assertArrayEquals("$name JPEG must match the reference encoder's standard layout", expected, actual)
            val before = ImageIO.read(ByteArrayInputStream(optimized))
            val after = ImageIO.read(ByteArrayInputStream(actual))
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            assertArrayEquals(before.getRGB(0, 0, before.width, before.height, null, 0, before.width),
                after.getRGB(0, 0, after.width, after.height, null, 0, after.width))
        }
    }

    @Test fun standardEncodingIsIdempotent() {
        val once = JpegHuffman.standardize(fixture("rgb420-standard.jpg"))
        assertArrayEquals(once, JpegHuffman.standardize(once))
    }

    @Test fun rejectsProgressiveAndRestartProfiles() {
        for (name in listOf("progressive.jpg", "restart.jpg")) {
            assertEquals(PrinterFailureCode.INVALID_IMAGE,
                assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(fixture(name)) }.code)
        }
    }

    @Test fun rejectsTruncatedEntropyAndTrailingData() {
        val bytes = fixture("rgb420-optimized.jpg")
        for (invalid in listOf(bytes.copyOf(bytes.size - 5), bytes + byteArrayOf(0), bytes.copyOf(15))) {
            assertEquals(PrinterFailureCode.INVALID_IMAGE,
                assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(invalid) }.code)
        }
    }

    @Test fun rejectsOversubscribedHuffmanTable() {
        val bytes = fixture("rgb420-optimized.jpg")
        val offset = segments(bytes).first { bytes[it + 1] == 0xc4.toByte() }
        val symbols = (5..20).sumOf { bytes[offset + it].toInt() and 255 }
        (5..20).forEach { bytes[offset + it] = 0 }
        bytes[offset + 5] = symbols.toByte() // Keep the segment valid; >2 one-bit codes oversubscribe the tree.
        assertEquals(PrinterFailureCode.INVALID_IMAGE,
            assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(bytes) }.code)
    }

    @Test fun rejectsMissingTableSelector() {
        val bytes = fixture("rgb420-optimized.jpg")
        val sos = segments(bytes).first { bytes[it + 1] == 0xda.toByte() }
        bytes[sos + 6] = 0x33
        assertEquals(PrinterFailureCode.INVALID_IMAGE,
            assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(bytes) }.code)
    }

    @Test fun byteBoundIsEnforcedBeforeParsing() {
        assertEquals(PrinterFailureCode.LIMIT_EXCEEDED,
            assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(ByteArray(4 * 1024 * 1024 + 1)) }.code)
    }

    @Test fun standardTableExpansionCannotExceedOutputByteBound() {
        val image = fixture("rgb420-optimized.jpg")
        val source = ByteArrayOutputStream()
        source.write(image, 0, 2)
        var padding = 4 * 1024 * 1024 - image.size - 8
        while (padding >= 4) {
            val size = minOf(padding, 65_537)
            source.write(255); source.write(0xe1)
            source.write((size - 2) ushr 8); source.write((size - 2) and 255)
            source.write(ByteArray(size - 4))
            padding -= size
        }
        source.write(image, 2, image.size - 2)
        assertTrue(source.size() < 4 * 1024 * 1024)
        assertEquals(PrinterFailureCode.LIMIT_EXCEEDED,
            assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(source.toByteArray()) }.code)
    }

    @Test fun cancellationStopsBeforeTranscoding() {
        Thread.currentThread().interrupt()
        try {
            assertEquals(PrinterFailureCode.CANCELLED,
                assertThrows(PrinterFailure::class.java) { JpegHuffman.standardize(fixture("rgb420-optimized.jpg")) }.code)
        } finally { Thread.interrupted() }
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/jpeg/$name")).use { it.readBytes() }

    private fun segments(bytes: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var offset = 2
        while (offset + 3 < bytes.size) {
            result += offset
            if (bytes[offset + 1] == 0xda.toByte()) break
            offset += 2 + ((bytes[offset + 2].toInt() and 255) shl 8) + (bytes[offset + 3].toInt() and 255)
        }
        return result
    }

    private fun entropy(bytes: ByteArray): ByteArray {
        val sos = segments(bytes).first { bytes[it + 1] == 0xda.toByte() }
        val start = sos + 2 + ((bytes[sos + 2].toInt() and 255) shl 8) + (bytes[sos + 3].toInt() and 255)
        return bytes.copyOfRange(start, bytes.size - 2)
    }

    private fun tables(bytes: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        segments(bytes).filter { bytes[it + 1] == 0xc4.toByte() }.forEach { start ->
            val end = start + 2 + ((bytes[start + 2].toInt() and 255) shl 8) + (bytes[start + 3].toInt() and 255)
            var offset = start + 4
            while (offset < end) {
                val size = 17 + (1..16).sumOf { bytes[offset + it].toInt() and 255 }
                result[bytes[offset].toInt() and 255] = bytes.copyOfRange(offset + 1, offset + size)
                offset += size
            }
        }
        return result
    }
}
