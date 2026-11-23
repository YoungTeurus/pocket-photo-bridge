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

/** RETRY tries the selection again; the others end it, and only a NEW_PHOTO resets the print back-off. */
enum class CaptureStep { RETRY, DUPLICATE, NEW_PHOTO }

/**
 * Single worker. A camera presence starts a selection generation; while the camera stays visible after a
 * successful transfer (re-check mode) it is checked again on the next pass after a successful print.
 */
class ServiceLoop(private val policy: BootModePolicy, private val clock: () -> Long, private val actions: ServiceLoopActions) {
    @Volatile var operating: Boolean = false
        private set
    private val boot = UUID.randomUUID().toString()
    private var epoch = 0L
    private var present = false
    private var captured = false
    private var printed = false
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
            present = false; captured = false; printed = false; nextCapture = 0
        } else {
            if (!present) { epoch = Math.addExact(epoch, 1); present = true }
            // A print is a blind window: whatever the camera offered meanwhile is fetched right after it.
            if (!captured && now >= nextCapture || captured && printed) {
                nextCapture = now + 5_000
                val step = actions.capture(SelectionTrigger("$boot:$epoch"))
                captured = step != CaptureStep.RETRY
                printed = false
                // A new photo is worth an immediate attempt and a fresh back-off; a duplicate changes nothing.
                if (step == CaptureStep.NEW_PHOTO) { nextPrint = now; printPause = 2_000 }
            }
        }
        if (now >= nextPrint) {
            val step = actions.printNext()
            val failed = step == PrintStep.FAILED
            if (step == PrintStep.PRINTED && captured) printed = true
            // The pause counts from the end of the attempt, which can take seconds to fail.
            nextPrint = clock() + if (failed) printPause else 2_000
            printPause = if (failed) minOf(printPause * 2, MAX_PRINT_RETRY_MS) else 2_000
        }
    }

    companion object {
        /** Longest pause between failed print attempts; to be confirmed in the field. */
        const val MAX_PRINT_RETRY_MS = 30_000L
    }
}
