package dev.pocketphotobridge.bridge.images

import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.printer.PrinterImageLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class JvmPrintImagePreparerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val preparer = JvmPrintImagePreparer()

    @Test fun producesPrinterSizeBaselineRgbWithExactAnnexKTablesAndJpeg90Quantizers() {
        val jpeg = preparer.prepare(source(quadrants()))
        val decoded = decode(jpeg)
        assertEquals(1040, decoded.width)
        assertEquals(1560, decoded.height)
        assertEquals(3, decoded.colorModel.numColorComponents)
        assertFalse(decoded.colorModel.hasAlpha())
        assertTrue(jpeg.size <= 4 * 1024 * 1024)
        val frame = segments(jpeg).single { it.marker == 0xc0 }.payload
        assertEquals(8, frame[0].unsigned())
        assertEquals(3, frame[5].unsigned())
        assertFalse(segments(jpeg).any { it.marker == 0xc2 })
        // Independent Pillow 12.3/libjpeg fixture, baseline quality90, optimize=False.
        val reference = fixture("annex-k-jpeg90.jpg")
        assertEquals(4, segments(jpeg).count { it.marker == 0xc4 })
        assertTablesEqual(tables(reference, 0xc4), tables(jpeg, 0xc4))
        assertTablesEqual(tables(reference, 0xdb), tables(jpeg, 0xdb))
    }

    @Test fun fitCentersTheCompleteImageOnWhite() {
        val output = decode(preparer.prepare(source(quadrants())))
        assertColor(Color.WHITE, output, 520, 100)
        assertColor(Color.WHITE, output, 520, 1460)
        assertCorners(output, 1)
    }

    @Test fun cropFillsTheCanvasAndKeepsTheCenteredContent() {
        val image = BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED; fillRect(0, 0, 200, 200)
            color = Color.GREEN; fillRect(200, 0, 200, 200)
            color = Color.BLUE; fillRect(400, 0, 200, 200)
            dispose()
        }
        val file = source(jpeg(image))
        val fit = decode(preparer.prepare(file, crop = false))
        assertColor(Color.RED, fit, 50, 780)
        assertColor(Color.BLUE, fit, 990, 780)
        val crop = decode(preparer.prepare(file, crop = true))
        for ((x, y) in listOf(20 to 20, 1020 to 20, 20 to 1540, 1020 to 1540, 520 to 780))
            assertColor(Color.GREEN, crop, x, y)
    }

    @Test fun appliesAllEightExifRotationsAndMirrorsBeforePlacement() {
        for (orientation in 1..8) {
            val bytes = app1(quadrants(), exif(orientation, littleEndian = orientation % 2 == 0))
            val output = decode(preparer.prepare(source(bytes)))
            assertCorners(output, orientation)
            if (orientation <= 4) assertColor(Color.WHITE, output, 520, 100)
            else {
                assertColor(Color.WHITE, output, 40, 780)
                assertColor(Color.WHITE, output, 1000, 780)
            }
        }
    }

    @Test fun leavesTheReceivedSourceBytesUnchangedAfterValidationFitAndCrop() {
        val original = app1(quadrants(), exif(7))
        val file = source(original)
        preparer.validateSource(file)
        preparer.prepare(file)
        preparer.prepare(file, crop = true)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun ignoresUnrelatedApp1MetadataAndExifWithoutOrientation() {
        for (metadata in listOf("http://ns.adobe.com/xap/1.0/\u0000unrelated".toByteArray(), exif(null))) {
            val output = decode(preparer.prepare(source(app1(quadrants(), metadata))))
            assertCorners(output, 1)
        }
    }

    @Test fun rejectsTruncatedAndMalformedExifWithoutFollowingUncheckedOffsets() {
        val malformed = listOf(
            "Exif\u0000\u0000II".toByteArray(),
            exif(1).also { it[8] = 1 }, // Bad TIFF version.
            exif(1).also { for (i in 10..13) it[i] = 0xff.toByte() }, // Offset beyond segment.
            exif(1).also { it[14] = 0x7f; it[15] = 0xff.toByte() }, // IFD count beyond segment.
            exif(1).copyOf(25),
            exif(0), exif(9),
            exif(1).also { it[18] = 0; it[19] = 4 }, // Wrong orientation type.
            exif(1).also { it[23] = 2 } // Wrong orientation count.
        )
        for (metadata in malformed) invalidBoth(source(app1(quadrants(), metadata)))
    }

    @Test fun rejectsMalformedTruncatedAndNonJpegSourcesDuringValidationAndPreparation() {
        val valid = quadrants()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), png,
            valid.copyOf(32), valid.copyOf(valid.size / 2), valid.copyOf(valid.size - 2)))
            invalidBoth(source(bytes))
        invalidBoth(File(temporary.root, "missing.jpg"))
        invalidBoth(temporary.root)
    }

    @Test fun rejectsTruncatedEntropyEvenWhenAnEndMarkerHasBeenAppended() {
        val valid = quadrants()
        invalidBoth(source(valid.copyOf(valid.size / 2) + byteArrayOf(0xff.toByte(), 0xd9.toByte())))
    }

    @Test fun enforcesThe64MiBSourceBoundBeforeParsing() {
        val file = source(quadrants())
        RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        invalidBoth(file)
    }

    @Test fun rejectsExcessiveHeaderMetadataBeforeDecoding() {
        val jpeg = quadrants()
        val padded = ByteArrayOutputStream().apply {
            write(jpeg, 0, 2)
            repeat(18) { write(segment(0xfe, ByteArray(65_530))) }
            write(jpeg, 2, jpeg.size - 2)
        }.toByteArray()
        invalidBoth(source(padded))
    }

    @Test fun rejectsImpossibleOrExcessiveSourceDimensionsBeforeDecoding() {
        for ((width, height) in listOf(0 to 200, 400 to 0, 65_535 to 65_535)) {
            val bytes = quadrants()
            val frame = segments(bytes).single { it.marker == 0xc0 }
            bytes[frame.start + 5] = (height ushr 8).toByte()
            bytes[frame.start + 6] = height.toByte()
            bytes[frame.start + 7] = (width ushr 8).toByte()
            bytes[frame.start + 8] = width.toByte()
            invalidBoth(source(bytes))
        }
    }

    @Test fun realLargeJpegIsSubsampledWithinA64MiBHeap() {
        val file = source(fixture("large-quadrants.jpg"))
        val classpath = listOf(LimitedHeapImagePreparation::class.java, JvmPrintImagePreparer::class.java,
            BridgeFailure::class.java, PrinterImageLayout::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val executable = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val process = ProcessBuilder(executable.path, "-Xmx64m", "-Djava.awt.headless=true", "-cp", classpath,
            LimitedHeapImagePreparation::class.java.name, file.path).redirectErrorStream(true).start()
        val finished = process.waitFor(45, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("bounded JPEG decoding timed out", finished)
        val diagnostics = process.inputStream.bufferedReader().readText()
        assertEquals(diagnostics, 0, process.exitValue())
        assertTrue(diagnostics, diagnostics.contains("BOUNDED_IMAGE_OK"))
    }

    @Test fun interruptionCancelsValidationAndPreparationWithoutClearingTheFlag() {
        val file = source(quadrants())
        for (operation in listOf<() -> Unit>({ preparer.validateSource(file) }, { preparer.prepare(file) })) {
            Thread.currentThread().interrupt()
            try {
                assertEquals(BridgeFailureCode.CANCELLED,
                    assertThrows(BridgeFailure::class.java) { operation() }.code)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
    }

    private fun invalidBoth(file: File) {
        assertEquals(BridgeFailureCode.INVALID_IMAGE,
            assertThrows(BridgeFailure::class.java) { preparer.validateSource(file) }.code)
        assertEquals(BridgeFailureCode.INVALID_IMAGE,
            assertThrows(BridgeFailure::class.java) { preparer.prepare(file) }.code)
    }

    private fun assertCorners(output: BufferedImage, orientation: Int) {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val order = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0),
            listOf(2, 3, 0, 1), listOf(0, 2, 1, 3), listOf(2, 0, 3, 1),
            listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))[orientation - 1]
        val coordinates = if (orientation <= 4) listOf(104 to 572, 936 to 572, 104 to 988, 936 to 988)
            else listOf(208 to 156, 832 to 156, 208 to 1404, 832 to 1404)
        coordinates.forEachIndexed { index, (x, y) -> assertColor(colors[order[index]], output, x, y) }
    }

    private fun assertColor(expected: Color, image: BufferedImage, x: Int, y: Int) {
        val actual = Color(image.getRGB(x, y))
        for ((want, got) in listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue))
            assertTrue("pixel ($x,$y): expected $expected, got $actual", kotlin.math.abs(want - got) <= 12)
    }

    private fun source(bytes: ByteArray) = temporary.newFile().also { it.writeBytes(bytes) }
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/jpeg/$name")).use { it.readBytes() }
    private fun decode(bytes: ByteArray) = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    private fun quadrants(): ByteArray {
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED; fillRect(0, 0, 200, 100)
            color = Color.GREEN; fillRect(200, 0, 200, 100)
            color = Color.BLUE; fillRect(0, 100, 200, 100)
            color = Color.YELLOW; fillRect(200, 100, 200, 100)
            dispose()
        }
        return jpeg(image)
    }
    private fun jpeg(image: BufferedImage) = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpeg", it)) }.toByteArray()

    private fun exif(orientation: Int?, littleEndian: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        fun word(value: Int) {
            if (littleEndian) { output.write(value); output.write(value ushr 8) }
            else { output.write(value ushr 8); output.write(value) }
        }
        fun dword(value: Int) { if (littleEndian) { word(value); word(value ushr 16) } else { word(value ushr 16); word(value) } }
        output.write("Exif\u0000\u0000".toByteArray())
        output.write(if (littleEndian) "II".toByteArray() else "MM".toByteArray())
        word(42); dword(8); word(if (orientation == null) 0 else 1)
        if (orientation != null) { word(0x0112); word(3); dword(1); word(orientation); word(0) }
        dword(0)
        return output.toByteArray()
    }

    private fun app1(jpeg: ByteArray, payload: ByteArray) = jpeg.copyOfRange(0, 2) + segment(0xe1, payload) + jpeg.copyOfRange(2, jpeg.size)
    private fun segment(marker: Int, payload: ByteArray) = byteArrayOf(0xff.toByte(), marker.toByte(),
        ((payload.size + 2) ushr 8).toByte(), (payload.size + 2).toByte()) + payload
    private data class Segment(val marker: Int, val start: Int, val payload: ByteArray)
    private fun segments(jpeg: ByteArray): List<Segment> {
        val result = mutableListOf<Segment>()
        var offset = 2
        while (offset + 4 <= jpeg.size) {
            val size = (jpeg[offset + 2].unsigned() shl 8) + jpeg[offset + 3].unsigned()
            result += Segment(jpeg[offset + 1].unsigned(), offset, jpeg.copyOfRange(offset + 4, offset + size + 2))
            if (jpeg[offset + 1].unsigned() == 0xda) break
            offset += size + 2
        }
        return result
    }
    private fun tables(jpeg: ByteArray, marker: Int): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        for (segment in segments(jpeg).filter { it.marker == marker }) {
            var offset = 0
            while (offset < segment.payload.size) {
                val id = segment.payload[offset].unsigned()
                val size = if (marker == 0xdb) 65 else 17 + (1..16).sumOf { segment.payload[offset + it].unsigned() }
                result[id] = segment.payload.copyOfRange(offset + 1, offset + size)
                offset += size
            }
        }
        return result
    }
    private fun assertTablesEqual(expected: Map<Int, ByteArray>, actual: Map<Int, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (id, table) -> assertArrayEquals("JPEG table $id", table, actual[id]) }
    }
    private fun Byte.unsigned() = toInt() and 255
}

/** Separate constrained JVM proves a full 8192x8192 raster is never allocated. */
object LimitedHeapImagePreparation {
    @JvmStatic fun main(args: Array<String>) {
        val preparer = JvmPrintImagePreparer()
        preparer.validateSource(File(args.single()))
        val output = preparer.prepare(File(args.single()))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(output)))
        check(image.width == 1040 && image.height == 1560)
        check(Color(image.getRGB(300, 550)).red > 240)
        check(Color(image.getRGB(740, 1010)).green > 240)
        println("BOUNDED_IMAGE_OK")
    }
}
