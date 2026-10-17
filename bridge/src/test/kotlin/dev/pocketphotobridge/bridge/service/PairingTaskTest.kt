package dev.pocketphotobridge.bridge.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PairingTaskTest {
    @Test fun startReturnsWhilePairingRunsAndRejectsConcurrentRequests() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        PairingTask({ started.countDown(); release.await(); finished.countDown() }).use { task ->
            task.start("AA:BB:CC:DD:EE:FF")
            assertTrue(started.await(2, TimeUnit.SECONDS)); assertTrue(task.pending)
            try { task.start("AA:BB:CC:DD:EE:FF"); fail("Concurrent pairing") } catch (_: IllegalStateException) { }
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }
    @Test fun failureAndShutdownAreFixedAndBounded() {
        val entered = CountDownLatch(1)
        val task = PairingTask({ entered.countDown(); CountDownLatch(1).await() })
        task.start("AA:BB:CC:DD:EE:FF")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        task.close()
        assertFalse(task.pending)
        assertEquals("CANCELLED", task.failure)
    }
}
