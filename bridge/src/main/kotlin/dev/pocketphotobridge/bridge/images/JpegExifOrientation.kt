package dev.pocketphotobridge.bridge.images

import dev.pocketphotobridge.bridge.BridgeFailure
import dev.pocketphotobridge.bridge.BridgeFailureCode

/** Reads only the bounded APP1 TIFF IFD0; never follows thumbnail or nested-IFD offsets. */
internal object JpegExifOrientation {
    fun read(app1: ByteArray): Int? {
        if (app1.size < 4 || !app1.copyOfRange(0, 4).contentEquals("Exif".toByteArray())) return null
        if (app1.size < 20 || app1[4] != 0.toByte() || app1[5] != 0.toByte()) invalid()
        val tiff = app1.copyOfRange(6, app1.size)
        val littleEndian = when (tiff.copyOfRange(0, 2).toString(Charsets.US_ASCII)) {
            "II" -> true
            "MM" -> false
            else -> invalid()
        }
        fun word(offset: Int): Int {
            if (offset < 0 || offset.toLong() + 2 > tiff.size) invalid()
            val first = tiff[offset].toInt() and 255
            val second = tiff[offset + 1].toInt() and 255
            return if (littleEndian) first or (second shl 8) else (first shl 8) or second
        }
        fun dword(offset: Int): Long {
            val first = word(offset).toLong()
            val second = word(offset + 2).toLong()
            return if (littleEndian) first or (second shl 16) else (first shl 16) or second
        }
        if (word(2) != 42) invalid()
        val ifd = dword(4)
        if (ifd < 8 || ifd + 2 > tiff.size) invalid()
        val count = word(ifd.toInt())
        val end = ifd + 2 + count.toLong() * 12
        if (end + 4 > tiff.size) invalid()
        var orientation = 1
        var found = false
        repeat(count) { index ->
            if (Thread.currentThread().isInterrupted) throw BridgeFailure(BridgeFailureCode.CANCELLED)
            val offset = ifd.toInt() + 2 + index * 12
            if (word(offset) == 0x0112) {
                if (found || word(offset + 2) != 3 || dword(offset + 4) != 1L) invalid()
                orientation = word(offset + 8)
                if (orientation !in 1..8) invalid()
                found = true
            }
        }
        // A thumbnail IFD may exist, but its pointer must still refer inside this APP1 segment.
        val nextIfd = dword(end.toInt())
        if (nextIfd != 0L && (nextIfd < 8 || nextIfd + 2 > tiff.size)) invalid()
        return orientation
    }

    private fun invalid(): Nothing = throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
}
