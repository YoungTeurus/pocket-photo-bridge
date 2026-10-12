package dev.pocketphotobridge.bridge.images

import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.bridge.PrintImagePreparer
import dev.pocketphotobridge.printer.JpegHuffman
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterImageLayout
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.event.IIOWriteProgressListener
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Portable JPEG preparation for Sony transfers; other source formats are deliberately rejected.
 * Source limits: 64 MiB, 1 MiB of JPEG headers, 32768 pixels/axis and 268,435,456 pixels.
 * Header dimensions are checked before decoding, whose raster is at most 2048 pixels/axis.
 * EXIF IFD0 orientation is applied before centered FIT/CROP; the source is never written.
 * Requires the standard JDK java.desktop module, with no platform-specific image library.
 */
class JvmPrintImagePreparer : PrintImagePreparer {
    override fun validateSource(source: File) = safely { decode(source).image.flush() }

    override fun prepare(source: File, crop: Boolean): ByteArray = safely {
        val decoded = decode(source)
        try {
            val output = render(decoded, crop)
            try { JpegHuffman.standardize(encode(output)).also { checkActive() } }
            finally { output.flush() }
        } finally { decoded.image.flush() }
    }

    private data class Header(val width: Int, val height: Int, val orientation: Int)
    private data class Decoded(val image: BufferedImage, val header: Header)

    private fun decode(source: File): Decoded {
        checkActive()
        val header = inspect(source)
        val reader = ImageIO.getImageReadersByFormatName("JPEG").asSequence().firstOrNull() ?: invalid()
        try {
            FileImageInputStream(source).use { input ->
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                dimensions(width, height)
                if (width != header.width || height != header.height) invalid()
                val sample = (maxOf(width, height) + DECODE_AXIS - 1) / DECODE_AXIS
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                var warning = false
                reader.addIIOReadWarningListener { _, _ -> warning = true }
                reader.addIIOReadProgressListener(ReadCancellation())
                checkActive()
                val image = reader.read(0, parameters) ?: invalid()
                try {
                    checkActive()
                    if (warning || image.width > DECODE_AXIS || image.height > DECODE_AXIS ||
                        image.width != (width + sample - 1) / sample || image.height != (height + sample - 1) / sample ||
                        source.length() > SOURCE_BYTES) invalid()
                    return Decoded(image, header)
                } catch (failure: Exception) { image.flush(); throw failure }
            }
        } finally { reader.dispose() }
    }

    /** Walk only the pre-scan segments; each segment and the aggregate are bounded. */
    private fun inspect(source: File): Header {
        if (!source.isFile || source.length() !in 4..SOURCE_BYTES) invalid()
        RandomAccessFile(source, "r").use {
            it.seek(it.length() - 2)
            if (it.readUnsignedShort() != 0xffd9) invalid()
        }
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            if (input.readUnsignedShort() != 0xffd8) invalid()
            var headerBytes = 2
            var width = 0
            var height = 0
            var orientation = 1
            var hasExif = false
            while (true) {
                checkActive()
                if (input.readUnsignedByte() != 255) invalid()
                headerBytes++
                var marker: Int
                do {
                    if (++headerBytes > HEADER_BYTES) invalid()
                    marker = input.readUnsignedByte()
                } while (marker == 255)
                if (marker in listOf(0, 0x01, 0xd8, 0xd9) || marker in 0xd0..0xd7) invalid()
                val length = input.readUnsignedShort()
                if (length < 2 || headerBytes.toLong() + length > HEADER_BYTES) invalid()
                headerBytes += length
                val payloadSize = length - 2
                when (marker) {
                    0xe1 -> {
                        val payload = ByteArray(payloadSize).also { input.readFully(it) }
                        val exif = JpegExifOrientation.read(payload)
                        if (exif != null) {
                            if (hasExif) invalid()
                            hasExif = true
                            orientation = exif
                        }
                    }
                    0xc0, 0xc1, 0xc2 -> {
                        if (width != 0 || payloadSize < 6 || input.readUnsignedByte() != 8) invalid()
                        height = input.readUnsignedShort()
                        width = input.readUnsignedShort()
                        dimensions(width, height)
                        skip(input, payloadSize - 5)
                    }
                    0xda -> {
                        if (width == 0 || payloadSize < 6) invalid()
                        skip(input, payloadSize)
                        return Header(width, height, orientation)
                    }
                    else -> skip(input, payloadSize)
                }
            }
        }
    }

    private fun skip(input: DataInputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            checkActive()
            val skipped = input.skipBytes(remaining)
            if (skipped == 0) { input.readUnsignedByte(); remaining-- } else remaining -= skipped
        }
    }

    private fun dimensions(width: Int, height: Int) {
        if (width !in 1..SOURCE_AXIS || height !in 1..SOURCE_AXIS || width.toLong() * height > SOURCE_PIXELS) invalid()
    }

    private fun render(decoded: Decoded, crop: Boolean): BufferedImage {
        checkActive()
        val header = decoded.header
        val swapped = header.orientation >= 5
        val placement = PrinterImageLayout.place(if (swapped) header.height else header.width,
            if (swapped) header.width else header.height, crop)
        val image = decoded.image
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val orientation = when (header.orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
            else -> AffineTransform()
        }
        val transform = AffineTransform().apply {
            translate(placement.left.toDouble(), placement.top.toDouble())
            scale(placement.width / if (swapped) height else width,
                placement.height / if (swapped) width else height)
            concatenate(orientation)
        }
        val output = BufferedImage(PrinterImageLayout.WIDTH, PrinterImageLayout.HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, output.width, output.height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, transform, null)
            checkActive()
            return output
        } catch (failure: Exception) { output.flush(); throw failure }
        finally { graphics.dispose() }
    }

    private fun encode(image: BufferedImage): ByteArray {
        checkActive()
        val writer = ImageIO.getImageWritersByFormatName("JPEG").asSequence().firstOrNull() ?: invalid()
        try {
            val bytes = BoundedOutput()
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.addIIOWriteProgressListener(WriteCancellation())
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.90f
                    progressiveMode = ImageWriteParam.MODE_DISABLED
                }
                writer.write(null, IIOImage(image, null, null), parameters)
                checkActive()
                output.flush()
            }
            return bytes.toByteArray()
        } finally { writer.dispose() }
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) { bound(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { bound(length); super.write(bytes, offset, length) }
        private fun bound(extra: Int) {
            checkActive()
            if (count.toLong() + extra > OUTPUT_BYTES) invalid()
        }
    }

    private class ReadCancellation : IIOReadProgressListener {
        private fun check(source: ImageReader) { if (Thread.currentThread().isInterrupted) source.abort() }
        override fun imageStarted(source: ImageReader, imageIndex: Int) = check(source)
        override fun imageProgress(source: ImageReader, percentageDone: Float) = check(source)
        override fun imageComplete(source: ImageReader) = check(source)
        override fun sequenceStarted(source: ImageReader, minIndex: Int) = Unit
        override fun sequenceComplete(source: ImageReader) = Unit
        override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) = Unit
        override fun thumbnailProgress(source: ImageReader, percentageDone: Float) = Unit
        override fun thumbnailComplete(source: ImageReader) = Unit
        override fun readAborted(source: ImageReader) = Unit
    }

    private class WriteCancellation : IIOWriteProgressListener {
        private fun check(source: ImageWriter) { if (Thread.currentThread().isInterrupted) source.abort() }
        override fun imageStarted(source: ImageWriter, imageIndex: Int) = check(source)
        override fun imageProgress(source: ImageWriter, percentageDone: Float) = check(source)
        override fun imageComplete(source: ImageWriter) = check(source)
        override fun thumbnailStarted(source: ImageWriter, imageIndex: Int, thumbnailIndex: Int) = Unit
        override fun thumbnailProgress(source: ImageWriter, percentageDone: Float) = Unit
        override fun thumbnailComplete(source: ImageWriter) = Unit
        override fun writeAborted(source: ImageWriter) = Unit
    }

    private inline fun <T> safely(operation: () -> T): T {
        checkActive()
        try { return operation() }
        catch (failure: BridgeFailure) { throw failure }
        catch (failure: PrinterFailure) {
            if (failure.code == PrinterFailureCode.CANCELLED) throw BridgeFailure(BridgeFailureCode.CANCELLED)
            invalid()
        }
        catch (_: IOException) { checkActive(); invalid() }
        catch (_: IllegalArgumentException) { checkActive(); invalid() }
        catch (_: SecurityException) { checkActive(); invalid() }
    }

    private companion object {
        const val SOURCE_BYTES = 64L * 1024 * 1024
        const val HEADER_BYTES = 1024 * 1024
        const val SOURCE_AXIS = 32768
        const val SOURCE_PIXELS = 268_435_456L
        const val DECODE_AXIS = 2048
        const val OUTPUT_BYTES = 4 * 1024 * 1024
        fun invalid(): Nothing = throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
        fun checkActive() {
            if (Thread.currentThread().isInterrupted) throw BridgeFailure(BridgeFailureCode.CANCELLED)
        }
    }
}
