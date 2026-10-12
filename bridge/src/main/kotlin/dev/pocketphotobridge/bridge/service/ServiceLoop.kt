package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.SelectionTrigger
import java.util.UUID

interface ServiceLoopActions {
    fun enterOperation()
    /** True once selection is retained or known to be a duplicate. */
    fun capture(trigger: SelectionTrigger): Boolean
    fun printNext()
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
            }
        }
        if (now >= nextPrint) { nextPrint = now + 2_000; actions.printNext() }
    }
}
