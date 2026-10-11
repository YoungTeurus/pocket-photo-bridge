package dev.pocketphotobridge

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainActivityTest {
    @Test fun wifiLossPreservesPendingLocalPhotoDeliveryAndSaveWork() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val token = MainActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val task = MainActivity::class.java.getDeclaredField("task").apply { isAccessible = true }
        val busy = MainActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
        val pending = CompletableFuture<Unit>()
        token.setInt(activity, 42)
        task.set(activity, pending)
        busy.setBoolean(activity, true)
        try {
            activity.handleWifiLoss()
            assertEquals("A validated preview callback must remain deliverable", 42, token.getInt(activity))
            assertFalse("Wi-Fi loss must not interrupt completion or a local Gallery save", pending.isCancelled)
            assertTrue("Completion still owns the busy state", busy.getBoolean(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
