package dev.pocketphotobridge.bridge.service

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** BlueZ has bounded but long discovery/pairing windows; HTTP never waits for them. */
class PairingTask(private val pair: (String) -> Unit, private val report: (String) -> Unit = {}) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "printer-pairing").apply { isDaemon = true } }
    @Volatile var pending = false
        private set
    @Volatile var failure: String? = null
        private set
    private var closed = false
    @Synchronized fun start(address: String) {
        check(!closed && !pending) { "PAIRING_UNAVAILABLE" }
        pending = true; failure = null
        report("SERVICE_PAIRING_STARTED")
        worker.submit {
            try { pair(address); report("SERVICE_PAIRING_FINISHED") }
            catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                failure = BridgeService.category(exception)
                report("SERVICE_PAIRING_FAILED category=$failure")
            } finally { pending = false }
        }
    }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        worker.shutdownNow()
        try { worker.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (pending) { pending = false; failure = "CANCELLED" }
    }
}
