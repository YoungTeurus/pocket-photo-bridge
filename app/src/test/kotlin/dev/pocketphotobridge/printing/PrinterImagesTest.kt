package dev.pocketphotobridge.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.JpegHuffman
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrinterImagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun nativePreparedJpegUsesExactAnnexKHuffmanTables() {
        val source = sourceImage(300, 100)
        val images = PrinterImages(context)
        try {
            val jpeg = images.prepare(source, PrinterImages.FitMode.FIT).readBytes()
            // Independent Pillow optimize=False tables, concatenated in selector order 00,01,10,11.
            assertEquals("532fd667aa5541439e4fc89f189259b1b137993ca5dfcbb6970ab411aee79cdf", tablesFingerprint(jpeg))
        } finally { images.clear(); source.delete() }
    }

    @Test fun nativeSkiaPortraitTranscodingLeavesDecodedPixelsExactlyUnchanged() {
        val bitmap = Bitmap.createBitmap(1040, 1560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, 520f, 780f, Paint().apply { color = Color.RED })
        val encoded = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded)
        bitmap.recycle()
        val raw = encoded.toByteArray()
        val standardized = JpegHuffman.standardize(raw)
        val before = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        val after = BitmapFactory.decodeByteArray(standardized, 0, standardized.size)
        try {
            val expected = IntArray(1040 * 1560)
            val actual = IntArray(expected.size)
            before.getPixels(expected, 0, 1040, 0, 0, 1040, 1560)
            after.getPixels(actual, 0, 1040, 0, 0, 1040, 1560)
            assertArrayEquals(expected, actual)
        } finally { before.recycle(); after.recycle() }
    }

    @Test fun cancellationWhileReplacementWaitsToPublishPreservesCurrentSource() {
        val images = PrinterImages(context)
        val original = images.testChart()
        val readStarted = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val stream = object : ByteArrayInputStream(original.readBytes()) {
            private var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    readStarted.countDown()
                    if (!resumeRead.await(5, TimeUnit.SECONDS)) throw AssertionError("stream gate timed out")
                }
                return super.read(buffer, offset, length)
            }
        }
        val worker = Thread {
            try { images.copyPicked(stream) } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        // Hold the real ownership monitor to reproduce cancellation after I/O but before publication.
        val monitor = requireNotNull(PrinterImages::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(images))
        worker.start()
        try {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            synchronized(monitor) {
                resumeRead.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
                assertEquals("Replacement must be waiting to publish", Thread.State.BLOCKED, worker.state)
                worker.interrupt()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(error.get() is PrinterFailure)
            assertEquals(PrinterFailureCode.CANCELLED, (error.get() as PrinterFailure).code)
            assertEquals(original, images.source)
            assertTrue(original.exists())
            assertEquals(listOf(original), original.parentFile!!.listFiles()!!.toList())
        } finally { resumeRead.countDown(); worker.interrupt(); images.clear() }
    }

    @Test fun fittingLandscapeKeepsOrientationAndProducesBaselinePortraitJpeg() {
        val source = sourceImage(300, 100)
        val original = source.readBytes()
        val images = PrinterImages(context)
        try {
            val result = images.prepare(source, PrinterImages.FitMode.FIT)
            val bitmap = BitmapFactory.decodeFile(result.path)
            assertEquals(1040, bitmap.width)
            assertEquals(1560, bitmap.height)
            assertNear(Color.WHITE, bitmap.getPixel(520, 100))
            assertNear(Color.RED, bitmap.getPixel(100, 780))
            assertNear(Color.BLUE, bitmap.getPixel(940, 780))
            bitmap.recycle()
            val bytes = result.readBytes()
            assertTrue("Android JPEG output must contain baseline SOF0", hasMarker(bytes, 0xc0))
            assertFalse("Progressive SOF2 must not be sent", hasMarker(bytes, 0xc2))
            assertArrayEquals(original, source.readBytes())
        } finally { images.clear(); source.delete() }
    }

    @Test fun cropFillsPortraitWithoutWhiteLetterbox() {
        val source = sourceImage(300, 100)
        val images = PrinterImages(context)
        try {
            val result = images.prepare(source, PrinterImages.FitMode.CROP)
            val bitmap = BitmapFactory.decodeFile(result.path)
            assertNear(Color.RED, bitmap.getPixel(50, 50))
            assertNear(Color.BLUE, bitmap.getPixel(990, 1510))
            bitmap.recycle()
        } finally { images.clear(); source.delete() }
    }

    @Test fun exifRotationMovesLeftHalfToTop() {
        val source = sourceImage(300, 100)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val images = PrinterImages(context)
        try {
            val bitmap = BitmapFactory.decodeFile(images.prepare(source, PrinterImages.FitMode.FIT).path)
            assertNear(Color.RED, bitmap.getPixel(520, 100))
            assertNear(Color.BLUE, bitmap.getPixel(520, 1460))
            bitmap.recycle()
        } finally { images.clear(); source.delete() }
    }

    @Test fun exifMirrorReversesLeftAndRight() {
        val source = sourceImage(300, 100)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_FLIP_HORIZONTAL.toString())
            saveAttributes()
        }
        val images = PrinterImages(context)
        try {
            val bitmap = BitmapFactory.decodeFile(images.prepare(source, PrinterImages.FitMode.FIT).path)
            assertNear(Color.BLUE, bitmap.getPixel(100, 780))
            assertNear(Color.RED, bitmap.getPixel(940, 780))
            bitmap.recycle()
        } finally { images.clear(); source.delete() }
    }

    @Test fun everyExifOrientationPreservesAllFourCorners() {
        val source = File(context.cacheDir, "corners-${UUID.randomUUID()}.jpg")
        val original = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(original)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        colors.forEachIndexed { index, color ->
            val left = (index % 2) * 100f
            val top = (index / 2) * 150f
            canvas.drawRect(left, top, left + 100, top + 150, Paint().apply { this.color = color })
        }
        source.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        original.recycle()
        val expected = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(3, 2, 1, 0),
            intArrayOf(2, 3, 0, 1), intArrayOf(0, 2, 1, 3), intArrayOf(2, 0, 3, 1),
            intArrayOf(3, 1, 2, 0), intArrayOf(1, 3, 0, 2)
        )
        val images = PrinterImages(context)
        try {
            (1..8).forEach { orientation ->
                ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val bitmap = BitmapFactory.decodeFile(images.prepare(source, PrinterImages.FitMode.FIT).path)
                val ys = if (orientation < 5) intArrayOf(390, 1170) else intArrayOf(607, 953)
                expected[orientation - 1].forEachIndexed { index, color ->
                    assertNear(colors[color], bitmap.getPixel(if (index % 2 == 0) 260 else 780, ys[index / 2]))
                }
                bitmap.recycle()
            }
        } finally { images.clear(); source.delete() }
    }

    @Test fun pngPickerSourceCanBePrepared() {
        val source = File(context.cacheDir, "picked-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val images = PrinterImages(context)
        try {
            val result = BitmapFactory.decodeFile(images.prepare(source, PrinterImages.FitMode.FIT).path)
            assertNear(Color.BLUE, result.getPixel(520, 780))
            result.recycle()
        } finally { images.clear(); source.delete() }
    }

    @Test fun stagedSonyCopySurvivesSourceRemovalAndClearDeletesOnlyCopy() {
        val source = sourceImage(300, 100)
        val original = source.readBytes()
        val name = PrinterImages.stageSony(context, source)
        source.delete()
        val images = PrinterImages(context, name)
        val staged = requireNotNull(images.source)
        assertArrayEquals(original, staged.readBytes())
        assertNotEquals(source.path, staged.path)
        images.clear()
        assertFalse(staged.exists())
    }

    @Test fun malformedSourceDoesNotLeavePreparedFiles() {
        val source = File(context.cacheDir, "bad-${UUID.randomUUID()}").apply { writeText("not an image") }
        val images = PrinterImages(context)
        try {
            val failure = assertThrows(PrinterFailure::class.java) { images.prepare(source, PrinterImages.FitMode.FIT) }
            assertEquals(PrinterFailureCode.INVALID_IMAGE, failure.code)
            assertTrue(source.exists())
        } finally { images.clear(); source.delete() }
    }

    @Test fun pickerCopiesStreamAndClearDeletesImportedSource() {
        val images = PrinterImages(context)
        val bytes = byteArrayOf(1, 2, 3)
        val copied = images.copyPicked(ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, copied.readBytes())
        assertEquals(copied, images.source)
        images.clear()
        assertFalse(copied.exists())
    }

    @Test fun oversizedPickerStreamIsRejectedAndClosed() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                return length
            }
            override fun close() { closed = true }
        }
        val images = PrinterImages(context)
        try {
            val failure = assertThrows(PrinterFailure::class.java) { images.copyPicked(input) }
            assertEquals(PrinterFailureCode.LIMIT_EXCEEDED, failure.code)
            assertTrue(closed)
            assertNull(images.source)
        } finally { images.clear() }
    }

    @Test fun cancellationPreventsPreparationAndPreservesOriginal() {
        val source = sourceImage(300, 100)
        val images = PrinterImages(context)
        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows(PrinterFailure::class.java) { images.prepare(source, PrinterImages.FitMode.FIT) }
            assertEquals(PrinterFailureCode.CANCELLED, failure.code)
            assertTrue(source.exists())
        } finally { Thread.interrupted(); images.clear(); source.delete() }
    }

    @Test fun generatedChartAndPreviewHaveBoundedPortraitDimensions() {
        val images = PrinterImages(context)
        try {
            val chart = images.testChart()
            assertEquals(chart, images.source)
            val preview = images.preview(chart)
            assertEquals(1040, preview.width)
            assertEquals(1560, preview.height)
            preview.recycle()
        } finally { images.clear() }
    }

    @Test fun stagedFilenameCannotEscapePrivateDirectory() {
        val failure = assertThrows(PrinterFailure::class.java) { PrinterImages(context, "../received/private.jpg") }
        assertEquals(PrinterFailureCode.INVALID_IMAGE, failure.code)
    }

    private fun sourceImage(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
        val file = File(context.cacheDir, "source-${UUID.randomUUID()}.jpg")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        return file
    }

    private fun assertNear(expected: Int, actual: Int) {
        assertTrue("Expected RGB $expected but got $actual", kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 15 &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 15 &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 15)
    }

    private fun hasMarker(bytes: ByteArray, marker: Int): Boolean {
        var position = 2
        while (position + 3 < bytes.size) {
            if ((bytes[position].toInt() and 255) != 255) return false
            val found = bytes[position + 1].toInt() and 255
            if (found == marker) return true
            if (found == 0xda || found == 0xd9) return false
            val size = ((bytes[position + 2].toInt() and 255) shl 8) or (bytes[position + 3].toInt() and 255)
            if (size < 2) return false
            position += size + 2
        }
        return false
    }

    private fun tablesFingerprint(bytes: ByteArray): String {
        val tables = sortedMapOf<Int, ByteArray>()
        var offset = 2
        while (offset + 3 < bytes.size) {
            val marker = bytes[offset + 1].toInt() and 255
            if (marker == 0xda) break
            val end = offset + 2 + ((bytes[offset + 2].toInt() and 255) shl 8) + (bytes[offset + 3].toInt() and 255)
            if (marker == 0xc4) {
                var table = offset + 4
                while (table < end) {
                    val size = 17 + (1..16).sumOf { bytes[table + it].toInt() and 255 }
                    tables[bytes[table].toInt() and 255] = bytes.copyOfRange(table, table + size)
                    table += size
                }
            }
            offset = end
        }
        val joined = ByteArrayOutputStream()
        tables.values.forEach { joined.write(it) }
        return MessageDigest.getInstance("SHA-256").digest(joined.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
