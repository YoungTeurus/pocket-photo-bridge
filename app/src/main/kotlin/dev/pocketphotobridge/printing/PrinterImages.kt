package dev.pocketphotobridge.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterImageLayout
import dev.pocketphotobridge.printer.JpegHuffman
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Screen-owned copies. Originals are only opened for reading, including Sony's preview. */
class PrinterImages(context: Context, stagedFilename: String? = null) {
    enum class FitMode { FIT, CROP }

    private val lock = Any()
    private val directory = File(File(context.cacheDir, "printer-images"), UUID.randomUUID().toString())
    @Volatile private var closed = false
    @Volatile var source: File? = null
        private set
    private var prepared: File? = null

    init {
        if (!directory.mkdirs()) throw PrinterFailure(PrinterFailureCode.PERSISTENCE_ERROR)
        if (stagedFilename != null) {
            try {
                if (!STAGED_NAME.matches(stagedFilename)) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
                val staged = File(File(context.cacheDir, "printer-staging"), stagedFilename)
                if (!staged.isFile || staged.length() !in 1..MAX_SOURCE_BYTES)
                    throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
                val adopted = newFile()
                if (!staged.renameTo(adopted)) {
                    copyBounded(staged.inputStream(), adopted)
                    staged.delete()
                }
                source = adopted
            } catch (e: Exception) {
                directory.deleteRecursively()
                if (e is PrinterFailure) throw e
                throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            }
        }
    }

    fun testChart(): File {
        checkActive()
        val bitmap = Bitmap.createBitmap(PrinterImageLayout.WIDTH, PrinterImageLayout.HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.DKGRAY; paint.textSize = 56f
            canvas.drawText("Pocket Photo Bridge", 64f, 110f, paint)
            paint.textSize = 34f
            canvas.drawText("TOP - orientation / color test", 64f, 178f, paint)
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
            colors.forEachIndexed { index, color ->
                paint.color = color
                val left = 64f + (index % 3) * 312f
                val top = 250f + (index / 3) * 260f
                canvas.drawRect(left, top, left + 280f, top + 230f, paint)
            }
            repeat(8) { index ->
                val shade = index * 255 / 7
                paint.color = Color.rgb(shade, shade, shade)
                canvas.drawRect(64f + index * 114f, 840f, 64f + (index + 1) * 114f, 1040f, paint)
            }
            paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            canvas.drawRect(32f, 32f, 1008f, 1528f, paint)
            canvas.drawCircle(520f, 1240f, 130f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 34f
            canvas.drawText("BOTTOM - 1040 x 1560", 64f, 1460f, paint)
            return publishSource(writeJpeg(bitmap))
        } finally { bitmap.recycle() }
    }

    /** Takes ownership of the stream and closes it on every path. */
    fun copyPicked(input: InputStream): File {
        val output = try { newFile() } catch (e: Exception) { input.close(); throw e }
        try {
            copyBounded(input, output)
            checkActive()
            return publishSource(output)
        } catch (e: Exception) {
            output.delete()
            if (e is PrinterFailure) throw e
            throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        }
    }

    fun prepare(source: File, fit: FitMode): File {
        checkActive()
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var output: Bitmap? = null
        try {
            decoded = decode(source)
            checkActive()
            oriented = orient(decoded, source)
            checkActive()
            output = Bitmap.createBitmap(PrinterImageLayout.WIDTH, PrinterImageLayout.HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val placement = PrinterImageLayout.place(oriented.width, oriented.height, crop = fit == FitMode.CROP)
            canvas.drawBitmap(oriented, null, RectF(placement.left, placement.top,
                placement.left + placement.width, placement.top + placement.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            checkActive()
            val file = writeJpeg(output)
            synchronized(lock) {
                try { checkActive() } catch (e: PrinterFailure) { file.delete(); throw e }
                prepared?.delete()
                prepared = file
            }
            return file
        } catch (e: Exception) {
            if (e is PrinterFailure) throw e
            throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        } finally {
            output?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    /** The caller owns the returned bitmap and must recycle it after removing it from the view. */
    fun preview(file: File): Bitmap {
        checkActive()
        return decode(file)
    }

    fun clear() {
        synchronized(lock) { closed = true; source = null; prepared = null }
        directory.deleteRecursively()
    }

    private fun decode(file: File): Bitmap {
        checkActive()
        if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS ||
            bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp"))
            throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION || bounds.outHeight / sample > MAX_DECODE_DIMENSION) sample *= 2
        checkActive()
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
    }

    private fun orient(bitmap: Bitmap, source: File): Bitmap {
        val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun newFile(): File = synchronized(lock) {
        checkActive()
        File(directory, "${UUID.randomUUID()}.jpg")
    }

    private fun writeJpeg(bitmap: Bitmap): File {
        val file = newFile()
        try {
            file.outputStream().use {
                checkActive()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            }
            checkActive()
            if (file.length() > MAX_PREPARED_BYTES) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
            // Skia optimizes Huffman tables; the pinned printer profile requires Annex K tables.
            val standard = JpegHuffman.standardize(file.readBytes())
            checkActive()
            file.outputStream().use { it.write(standard) }
            checkActive()
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun publishSource(file: File): File = synchronized(lock) {
        try { checkActive() } catch (e: PrinterFailure) { file.delete(); throw e }
        source?.delete(); prepared?.delete(); prepared = null
        source = file
        file
    }

    private fun checkActive() {
        if (closed || Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED)
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        private const val MAX_PREPARED_BYTES = 4L * 1024 * 1024
        private const val MAX_DIMENSION = 32_768
        private const val MAX_PIXELS = 100_000_000L
        private const val MAX_DECODE_DIMENSION = 2048
        private val STAGED_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        private val stagingLock = Any()

        /** Copy synchronously before stopping the camera screen, which owns the original cache. */
        fun stageSony(context: Context, source: File): String = synchronized(stagingLock) {
            if (!source.isFile || source.length() !in 1..MAX_SOURCE_BYTES) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            val staging = File(context.cacheDir, "printer-staging")
            if (!staging.isDirectory && !staging.mkdirs()) throw PrinterFailure(PrinterFailureCode.PERSISTENCE_ERROR)
            // A failed Activity launch can leave an unclaimed stage. Keep this handoff cache bounded.
            staging.listFiles()?.filter { STAGED_NAME.matches(it.name) }?.sortedBy { it.lastModified() }
                ?.let { files -> files.take((files.size - 3).coerceAtLeast(0)).forEach { it.delete() } }
            val file = File(staging, "${UUID.randomUUID()}.jpg")
            try { copyBounded(source.inputStream(), file); file.name }
            catch (e: Exception) {
                file.delete()
                if (e is PrinterFailure) throw e
                throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            }
        }

        private fun copyBounded(input: InputStream, output: File) {
            input.use { stream ->
                output.outputStream().use { destination ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw PrinterFailure(PrinterFailureCode.CANCELLED)
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > MAX_SOURCE_BYTES) throw PrinterFailure(PrinterFailureCode.LIMIT_EXCEEDED)
                        destination.write(buffer, 0, count)
                    }
                    if (copied == 0L) throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
                }
            }
        }
    }
}
