package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.SelectionTrigger
import org.junit.Assert.*
import org.junit.Test

class ServiceLoopTest {
    private class Actions : ServiceLoopActions {
        var transitions = 0; var captures = 0; var prints = 0
        var captureSucceeds = true
        override fun enterOperation() { transitions++ }
        override fun capture(trigger: SelectionTrigger): Boolean { captures++; return captureSucceeds }
        override fun printNext() { prints++ }
    }
    @Test fun firstBootWaitsThenExplicitRunTransitionsBeforeCaptureAndRetriesPrinter() {
        val actions = Actions(); val policy = BootModePolicy(0); val loop = ServiceLoop(policy, actions)
        loop.tick(0, false, true)
        assertEquals(0, actions.captures); assertEquals(0, actions.prints)
        policy.requestOperation(); loop.tick(1, true, true)
        assertEquals(1, actions.transitions); assertEquals(1, actions.captures); assertEquals(1, actions.prints)
        loop.tick(2_001, true, true)
        assertEquals(1, actions.captures); assertEquals(2, actions.prints)
    }
    @Test fun cameraEpochRearmsOnlyAfterAbsenceAndInterruptedCaptureRetriesWithBackoff() {
        val actions = Actions(); val loop = ServiceLoop(BootModePolicy(0), actions)
        actions.captureSucceeds = false
        loop.tick(30_000, true, true); loop.tick(30_001, true, true)
        assertEquals(1, actions.captures)
        actions.captureSucceeds = true; loop.tick(35_000, true, true)
        assertEquals(2, actions.captures)
        loop.tick(36_000, true, true); assertEquals(2, actions.captures)
        loop.tick(37_000, true, false); loop.tick(38_000, true, true)
        assertEquals(3, actions.captures)
    }
    @Test fun disabledConfigurationAndAuthenticatedSetupNeverPrint() {
        val actions = Actions(); val policy = BootModePolicy(0); policy.holdSetup()
        val loop = ServiceLoop(policy, actions)
        loop.tick(60_000, true, true)
        assertEquals(0, actions.transitions)
        policy.requestOperation(); loop.tick(60_001, false, true)
        assertEquals(0, actions.prints)
    }
}
