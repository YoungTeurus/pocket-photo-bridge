package dev.pocketphotobridge.printing

import android.os.Looper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrinterActivityTest {
    @Test fun longerTokenInputIsRejectedWholeRatherThanSilentlyTruncated() {
        val screen = Robolectric.buildActivity(PrinterActivity::class.java).setup()
        try {
            val activity = screen.get()
            finishWork(activity)
            val input = field(activity, "tokenInput") as EditText
            for (synthetic in listOf("0".repeat(32), "0".repeat(24) + " ".repeat(232) + "invalid suffix")) {
                input.setText(synthetic)
                val captured = PrinterActivity::class.java.getDeclaredMethod("captureToken").apply { isAccessible = true }.invoke(activity) as String
                assertEquals("Pasting a different key format must not produce a plausible 24-character token", synthetic, captured)
                assertEquals(dev.pocketphotobridge.printer.PrinterFailureCode.INVALID_KEY,
                    assertThrows(dev.pocketphotobridge.printer.PrinterFailure::class.java) {
                        dev.pocketphotobridge.printer.PrinterCipher(captured)
                    }.code)
                assertEquals("", input.text.toString())
            }
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun pickerResultAfterRecreationWaitsForInitialPreparationInsteadOfBeingDropped() {
        val screen = Robolectric.buildActivity(PrinterActivity::class.java).setup()
        val context = RuntimeEnvironment.getApplication()
        val picked = java.io.File(context.cacheDir, "local-picker-fixture.jpg")
        val bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        picked.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        try {
            finishWork(screen.get())
            val owner = field(screen.get(), "images") as PrinterImages
            val lock = requireNotNull(PrinterImages::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(owner))
            // Keep preparation active while Android delivers the pending picker result.
            synchronized(lock) {
                screen.recreate()
                PrinterActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                    .invoke(screen.get(), 20, android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(picked)))
            }
            finishWork(screen.get())
            finishWork(screen.get())
            val imported = (field(screen.get(), "images") as PrinterImages).source!!
            assertArrayEquals("The selected document must replace the initial chart", picked.readBytes(), imported.readBytes())
        } finally { screen.pause().stop().destroy(); picked.delete() }
    }

    @Test fun rotationPreservesSonyPrintSourceAndPreparedPreview() {
        val context = RuntimeEnvironment.getApplication()
        val sourceOwner = PrinterImages(context)
        val staged = try { PrinterImages.stageSony(context, sourceOwner.testChart()) } finally { sourceOwner.clear() }
        val launch = Intent(context, PrinterActivity::class.java).putExtra(PrinterActivity.STAGED_IMAGE, staged)
        val screen = Robolectric.buildActivity(PrinterActivity::class.java, launch).setup()
        try {
            finishWork(screen.get())
            assertNotNull(field(screen.get(), "prepared"))
            screen.recreate()
            finishWork(screen.get())
            assertNotNull("Rotation must keep the Sony print copy", field(screen.get(), "prepared"))
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun missingConsumedSonyStageDoesNotPreventChoosingAnotherImage() {
        val launch = Intent(RuntimeEnvironment.getApplication(), PrinterActivity::class.java)
            .putExtra(PrinterActivity.STAGED_IMAGE, "${java.util.UUID.randomUUID()}.jpg")
        val screen = Robolectric.buildActivity(PrinterActivity::class.java, launch).setup()
        try {
            val activity = screen.get()
            finishWork(activity)
            assertNull(field(activity, "prepared"))
            button(activity, "Generate test image").performClick()
            finishWork(activity)
            assertNotNull("An unavailable prior stage must allow a new local image", field(activity, "prepared"))
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun standaloneScreenPreparesRealTestChartWithoutCameraOrPrinter() {
        val screen = Robolectric.buildActivity(PrinterActivity::class.java).setup()
        try {
            val activity = screen.get()
            finishWork(activity)
            val images = field(activity, "images") as PrinterImages
            assertTrue(images.source!!.isFile)
            assertTrue((field(activity, "prepared") as java.io.File).length() > 1000)
            assertFalse(button(activity, "Print one copy").isEnabled)
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun leavingScreenClearsUnsavedTokenButKeepsImageForSettingsReturn() {
        val screen = Robolectric.buildActivity(PrinterActivity::class.java).setup()
        try {
            val activity = screen.get()
            finishWork(activity)
            val input = field(activity, "tokenInput") as EditText
            assertFalse(input.isSaveEnabled)
            // Deliberately not a token: this verifies clearing, not printer authentication.
            input.setText("private input")
            val source = (field(activity, "images") as PrinterImages).source!!
            screen.pause().stop()
            assertEquals("", input.text.toString())
            assertTrue(source.exists())
            screen.restart().start().resume()
            assertTrue(source.exists())
        } finally { screen.pause().stop().destroy() }
    }

    @Test fun unresolvedPrintRequiresPositiveCheckedPrinterConfirmation() {
        val screen = Robolectric.buildActivity(PrinterActivity::class.java).setup()
        val activity = screen.get()
        val guard = StoredPrintJobGuard(activity)
        try {
            finishWork(activity)
            guard.reserve()
            PrinterActivity::class.java.getDeclaredMethod("controls").apply { isAccessible = true }.invoke(activity)
            assertFalse(button(activity, "Print one copy").isEnabled)
            val clear = button(activity, "I checked the printer · clear unknown job")
            assertTrue(clear.isEnabled)
            clear.performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(StoredPrintJobGuard(activity).unresolved)
            clear.performClick()
            assertTrue(guard.unresolved)
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            finishWork(activity)
            assertFalse(StoredPrintJobGuard(activity).unresolved)
        } finally { guard.acknowledgeCheckedPrinter(); screen.pause().stop().destroy() }
    }

    private fun finishWork(activity: PrinterActivity) {
        (field(activity, "task") as Future<*>).get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun field(activity: PrinterActivity, name: String) = PrinterActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun button(activity: PrinterActivity, label: String): Button = find(activity.window.decorView, label)
    private fun find(view: View, label: String): Button {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            try { return find(view.getChildAt(index), label) } catch (_: NoSuchElementException) { }
        }
        throw NoSuchElementException(label)
    }
}
