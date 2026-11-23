package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.SelectionTrigger
import org.junit.Assert.*
import org.junit.Test

class ServiceLoopTest {
    private class Actions : ServiceLoopActions {
        var transitions = 0; var captures = 0; var prints = 0
        var captureStep = CaptureStep.NEW_PHOTO
        var printFails = false
        var printStep = PrintStep.IDLE
        var printTakesMs = 0L
        val printedAt = mutableListOf<Long>()
        var clock = 0L
        override fun enterOperation() { transitions++ }
        override fun capture(trigger: SelectionTrigger): CaptureStep { captures++; return captureStep }
        override fun printNext(): PrintStep {
            prints++; printedAt += clock; clock += printTakesMs
            return if (printFails) PrintStep.FAILED else printStep
        }
    }
    private fun loop(policy: BootModePolicy, actions: Actions) = ServiceLoop(policy, { actions.clock }, actions)
    /** The service ticks about once a second. */
    private fun ServiceLoop.runUntil(actions: Actions, end: Long, camera: Boolean = false) {
        while (actions.clock < end) { actions.clock += 1_000; tick(actions.clock, true, camera) }
    }
    @Test fun firstBootWaitsThenExplicitRunTransitionsBeforeCaptureAndRetriesPrinter() {
        val actions = Actions(); val policy = BootModePolicy(0); val loop = loop(policy, actions)
        loop.tick(0, false, true)
        assertEquals(0, actions.captures); assertEquals(0, actions.prints)
        policy.requestOperation(); loop.tick(1, true, true)
        assertEquals(1, actions.transitions); assertEquals(1, actions.captures); assertEquals(1, actions.prints)
        loop.tick(2_001, true, true)
        assertEquals(1, actions.captures); assertEquals(2, actions.prints)
    }
    @Test fun cameraEpochRearmsOnlyAfterAbsenceAndInterruptedCaptureRetriesWithBackoff() {
        val actions = Actions(); val loop = loop(BootModePolicy(0, windowMs = 30_000), actions)
        actions.captureStep = CaptureStep.RETRY
        loop.tick(30_000, true, true); loop.tick(30_001, true, true)
        assertEquals(1, actions.captures)
        actions.captureStep = CaptureStep.NEW_PHOTO; loop.tick(35_000, true, true)
        assertEquals(2, actions.captures)
        loop.tick(36_000, true, true); assertEquals(2, actions.captures)
        loop.tick(37_000, true, false); loop.tick(38_000, true, true)
        assertEquals(3, actions.captures)
    }
    @Test fun disabledConfigurationAndAuthenticatedSetupNeverPrint() {
        val actions = Actions(); val policy = BootModePolicy(0); policy.holdSetup()
        val loop = loop(policy, actions)
        loop.tick(60_000, true, true)
        assertEquals(0, actions.transitions)
        policy.requestOperation(); loop.tick(60_001, false, true)
        assertEquals(0, actions.prints)
    }
    @Test fun failedPrintAttemptsBackOffByDoublingToTheCapAndResetOnSuccessOrNewCapture() {
        val actions = Actions(); val policy = BootModePolicy(0); policy.requestOperation()
        val loop = loop(policy, actions)
        actions.printFails = true
        loop.tick(0, true, false)
        loop.runUntil(actions, 120_000)
        assertEquals(listOf(0L, 2_000, 6_000, 14_000, 30_000, 60_000, 90_000, 120_000), actions.printedAt)
        assertEquals(30_000L, ServiceLoop.MAX_PRINT_RETRY_MS)
        actions.printFails = false
        loop.runUntil(actions, 150_000)
        assertEquals(listOf(150_000L), actions.printedAt.drop(8))
        loop.runUntil(actions, 154_000)
        assertEquals(listOf(150_000L, 152_000, 154_000), actions.printedAt.drop(8))
        actions.printFails = true; actions.printedAt.clear()
        loop.runUntil(actions, 180_000)
        assertEquals(listOf(156_000L, 158_000, 162_000, 170_000), actions.printedAt)
        // A new capture prints at once and restarts the pause at its start.
        loop.runUntil(actions, 181_000, camera = true)
        loop.runUntil(actions, 185_000)
        assertEquals(listOf(181_000L, 183_000), actions.printedAt.drop(4))
    }
    @Test fun aSlowFailedAttemptWaitsTheWholePauseAfterItEnds() {
        val actions = Actions(); val policy = BootModePolicy(0); policy.requestOperation()
        val loop = loop(policy, actions)
        actions.printFails = true; actions.printTakesMs = 10_000
        loop.tick(0, true, false)
        loop.runUntil(actions, 110_000)
        assertEquals(listOf(0L, 12_000, 26_000, 44_000, 70_000, 110_000), actions.printedAt)
    }
    @Test fun aDuplicateCaptureLeavesTheBackOffUnchanged() {
        val actions = Actions(); val policy = BootModePolicy(0); policy.requestOperation()
        val loop = loop(policy, actions)
        actions.printFails = true
        loop.tick(0, true, false)
        loop.runUntil(actions, 14_000)
        actions.captureStep = CaptureStep.DUPLICATE
        loop.runUntil(actions, 30_000, camera = true)
        assertEquals(1, actions.captures)
        assertEquals(listOf(0L, 2_000, 6_000, 14_000, 30_000), actions.printedAt)
    }
}
