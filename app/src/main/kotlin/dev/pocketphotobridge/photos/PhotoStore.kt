package dev.pocketphotobridge.photos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PhotoStore(private val context: Context) {
    val directory = File(File(context.cacheDir, "received"), UUID.randomUUID().toString()).apply { mkdirs() }

    fun clear() { directory.deleteRecursively() }

    fun preview(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > 200_000_000) throw IOException("INVALID_JPEG")
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: throw IOException("INVALID_JPEG")
    }

    fun save(file: File): Uri {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Sony_${timestamp}_${UUID.randomUUID().toString().take(8)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket Photo Bridge")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("SAVE_FAILED")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("CANCELLED")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("SAVE_FAILED")
            if (Thread.currentThread().isInterrupted) throw IOException("CANCELLED")
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, ready, null, null) != 1) throw IOException("SAVE_FAILED")
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
