package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.SelectionTrigger
import java.util.UUID

interface ServiceLoopActions {
    fun enterOperation()
    /** True once selection is retained or known to be a duplicate. */
    fun capture(trigger: SelectionTrigger): Boolean
    /** True when a print attempt failed (printer unreachable, busy or refusing); an empty queue is not a failure. */
    fun printNext(): Boolean
}

/** Single worker. Camera presence generations rearm only after an observed absence. */
class ServiceLoop(private val policy: BootModePolicy, private val actions: ServiceLoopActions) {
    @Volatile var operating: Boolean = false
        private set
    private val boot = UUID.randomUUID().toString()
    private var epoch = 0L
    private var present = false
    private var captured = false
    private var nextCapture = 0L
    private var nextPrint = 0L
    private var printPause = 2_000L

    fun tick(now: Long, enabled: Boolean, cameraAvailable: Boolean) {
        if (!operating) {
            if (!policy.shouldOperate(now, enabled)) return
            actions.enterOperation()
            operating = true
        }
        if (!enabled) return
        if (!cameraAvailable) {
            present = false; captured = false; nextCapture = 0
        } else {
            if (!present) { epoch = Math.addExact(epoch, 1); present = true }
            if (!captured && now >= nextCapture) {
                nextCapture = now + 5_000
                captured = actions.capture(SelectionTrigger("$boot:$epoch"))
                // A new photo is worth an immediate attempt and a fresh back-off.
                if (captured) { nextPrint = now; printPause = 2_000 }
            }
        }
        if (now >= nextPrint) {
            val failed = actions.printNext()
            nextPrint = now + if (failed) printPause else 2_000
            printPause = if (failed) minOf(printPause * 2, MAX_PRINT_RETRY_MS) else 2_000
        }
    }

    companion object {
        /** Longest pause between failed print attempts; to be confirmed in the field. */
        const val MAX_PRINT_RETRY_MS = 30_000L
    }
}
