package dev.pocketphotobridge.bridge.service

import org.junit.Assert.*
import org.junit.Test

class BootModePolicyTest {
    @Test fun setupStartupTimeDoesNotConsumeTheReachableSetupWindow() {
        val policy = BootModePolicy(0)
        policy.beginWindow(60_000)
        assertFalse(policy.shouldOperate(119_999, true))
        assertTrue(policy.shouldOperate(120_000, true))
    }
    @Test fun unconfiguredBootNeverLeavesSetupAndConfiguredBootOffersSixtySeconds() {
        val first = BootModePolicy(100)
        assertFalse(first.shouldOperate(1_000_000, false))
        assertFalse(first.shouldOperate(60_099, true))
        assertTrue(first.shouldOperate(60_100, true))
    }
    @Test fun authenticatedInteractionHoldsSetupUntilExplicitRun() {
        val policy = BootModePolicy(0)
        policy.holdSetup()
        assertFalse(policy.shouldOperate(90_000, true))
        policy.requestOperation()
        assertTrue(policy.shouldOperate(90_001, true))
        assertFalse(policy.shouldOperate(90_001, false))
    }
    @Test fun requestedRunRequiresEnabledConfigurationAndNewBootResetsHold() {
        val policy = BootModePolicy(0)
        policy.requestOperation()
        assertFalse(policy.shouldOperate(5, false))
        assertTrue(policy.shouldOperate(5, true))
        assertFalse(BootModePolicy(5).shouldOperate(5, true))
    }
}
