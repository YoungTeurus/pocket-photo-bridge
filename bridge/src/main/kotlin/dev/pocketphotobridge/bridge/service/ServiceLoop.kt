package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.SelectionTrigger
import java.util.UUID

interface ServiceLoopActions {
    fun enterOperation()
    fun capture(trigger: SelectionTrigger): CaptureStep
    fun printNext(): PrintStep
}

/** FAILED: the printer was unreachable, busy or refusing. IDLE: nothing printed and nothing failed, such as an empty queue. */
enum class PrintStep { IDLE, PRINTED, FAILED }

/**
 * The outcome of a camera check. RETRY: the check failed (join, address, camera protocol). BLOCKED: no radio
 * connection was made (queue full, needs attention). Only a NEW_PHOTO resets the print back-off.
 */
enum class CaptureStep { RETRY, BLOCKED, DUPLICATE, NEW_PHOTO }

/**
 * Single worker. A camera presence starts a selection generation; while the camera stays visible after a
 * successful transfer (re-check mode) it is checked again on the next pass after a successful print, and
 * otherwise after a pause that doubles while it offers only known photos. Content dedupe makes a repeat harmless.
 */
class ServiceLoop(private val policy: BootModePolicy, private val clock: () -> Long, private val actions: ServiceLoopActions) {
    @Volatile var operating: Boolean = false
        private set
    private val boot = UUID.randomUUID().toString()
    private var epoch = 0L
    private var present = false
    private var recheck = false
    private var printed = false
    private var nextCapture = 0L
    private var nextPrint = 0L
    private var printPause = 2_000L
    private var recheckPause = FIRST_RECHECK_MS

    fun tick(now: Long, enabled: Boolean, cameraAvailable: Boolean) {
        if (!operating) {
            if (!policy.shouldOperate(now, enabled)) return
            actions.enterOperation()
            operating = true
        }
        if (!enabled) return
        if (!cameraAvailable) {
            present = false; recheck = false; printed = false; nextCapture = 0; recheckPause = FIRST_RECHECK_MS
        } else {
            if (!present) { epoch = Math.addExact(epoch, 1); present = true }
            // A print is a blind window: whatever the camera offered meanwhile is fetched right after it.
            if (now >= nextCapture || recheck && printed) {
                printed = false
                val step = actions.capture(SelectionTrigger("$boot:$epoch"))
                when (step) {
                    // A new photo is worth an immediate attempt and a fresh back-off; a duplicate changes nothing.
                    CaptureStep.NEW_PHOTO -> { recheck = true; recheckPause = FIRST_RECHECK_MS; nextPrint = now; printPause = 2_000 }
                    CaptureStep.DUPLICATE -> { recheck = true; recheckPause = minOf(recheckPause * 2, MAX_RECHECK_MS) }
                    // A failed check of a camera that already gave its photo costs like finding that photo again.
                    CaptureStep.RETRY -> if (recheck) recheckPause = minOf(recheckPause * 2, MAX_RECHECK_MS)
                    CaptureStep.BLOCKED -> { }
                }
                // Counted from this pass's start, so a long transfer or print does not stretch the pause.
                nextCapture = now + if (recheck && step != CaptureStep.BLOCKED) recheckPause else 5_000
            }
        }
        if (now >= nextPrint) {
            val step = actions.printNext()
            val failed = step == PrintStep.FAILED
            if (step == PrintStep.PRINTED && recheck) printed = true
            // The pause counts from the end of the attempt, which can take seconds to fail.
            nextPrint = clock() + if (failed) printPause else 2_000
            printPause = if (failed) minOf(printPause * 2, MAX_PRINT_RETRY_MS) else 2_000
        }
    }

    companion object {
        /** Longest pause between failed print attempts; to be confirmed in the field. */
        const val MAX_PRINT_RETRY_MS = 30_000L
        /** First pause before a still-visible camera is checked again; to be confirmed in the field. */
        const val FIRST_RECHECK_MS = 60_000L
        /** Longest pause between checks of a still-visible camera; to be confirmed in the field. */
        const val MAX_RECHECK_MS = 300_000L
    }
}
