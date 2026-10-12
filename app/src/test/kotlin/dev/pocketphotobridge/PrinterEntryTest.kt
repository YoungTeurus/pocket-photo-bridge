package dev.pocketphotobridge

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.pocketphotobridge.printing.PrinterActivity
import dev.pocketphotobridge.printing.PrinterImages
import dev.pocketphotobridge.sony.ReceivedPhoto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PrinterEntryTest {
    @Test fun testImageOpensPrinterWithoutCameraOrWifi() {
        val screen = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = screen.get()
            assertFalse(button(activity.window.decorView, "Print received JPEG").isEnabled)
            button(activity.window.decorView, "Print test image").performClick()
            assertEquals(PrinterActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun printingStagesIndependentCopyAndPreservesSonyOriginal() {
        val screen = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = screen.get()
        val original = File(activity.cacheDir, "received/photo.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        var images: PrinterImages? = null
        try {
            MainActivity::class.java.getDeclaredField("photo").apply { isAccessible = true }
                .set(activity, ReceivedPhoto(original, 4))
            MainActivity::class.java.getDeclaredMethod("controls").apply { isAccessible = true }.invoke(activity)
            button(activity.window.decorView, "Print received JPEG").performClick()
            val task = MainActivity::class.java.getDeclaredField("task").apply { isAccessible = true }.get(activity) as Future<*>
            task.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(PrinterActivity::class.java.name, intent.component!!.className)
            images = PrinterImages(activity, intent.getStringExtra(PrinterActivity.STAGED_IMAGE))
            assertArrayEquals(original.readBytes(), images.source!!.readBytes())
            screen.pause().stop()
            assertTrue("Opening printing must preserve Sony preview for saving on return", original.exists())
            images.clear()
            assertTrue(original.exists())
        } finally { images?.clear(); screen.destroy() }
    }

    private fun button(view: View, text: String): Button {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            try { return button(view.getChildAt(index), text) } catch (_: NoSuchElementException) { }
        }
        throw NoSuchElementException(text)
    }
}
