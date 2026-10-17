package dev.pocketphotobridge.bridge.simulation

import dev.pocketphotobridge.bridge.BridgeOutcome
import dev.pocketphotobridge.bridge.SimulationScenario
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SimulationRunnerTest {
    private fun root(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("simulation-runner-")
        try { test(directory) } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun everyOfflineScenarioChecksTheActualCoordinatorOutcomeAndSubmissionCounts() = root { root ->
        for (scenario in SimulationScenario.entries.filter { it != SimulationScenario.POWER_CUT }) {
            val lines = mutableListOf<String>()
            val result = SimulationRunner.run(scenario, root.resolve(scenario.option), false, lines::add)
            assertTrue("Expected simulation check: ${scenario.option}", result.verified)
            assertTrue(lines.any { it.startsWith("BRIDGE_") })
            if (scenario == SimulationScenario.NORMAL) {
                assertEquals(BridgeOutcome.PRINTED, result.outcome)
                assertEquals(1, result.submissions); assertEquals(1, result.prints)
            }
            assertFalse(lines.joinToString("\n").contains(SIMULATION_TOKEN))
            assertFalse(lines.joinToString("\n").contains("192.168.122.1"))
        }
    }

    @Test fun normalSecondProcessViewSuppressesSameSyntheticSelection() = root { root ->
        assertEquals(1, SimulationRunner.run(SimulationScenario.NORMAL, root, false, {}).prints)
        val next = SimulationRunner.run(SimulationScenario.NORMAL, root, false, {})
        assertEquals(BridgeOutcome.DUPLICATE_SUPPRESSED, next.outcome)
        assertEquals(0, next.submissions); assertEquals(0, next.prints)
        assertTrue(next.verified)
    }

    @Test fun postSubmissionFaultRemainsBlockedAfterReopenAndManualRecoveryRetainsTombstones() = root { root ->
        val unknown = SimulationRunner.run(SimulationScenario.DROPPED_AFTER_SUBMIT, root, false, {})
        assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, unknown.outcome)
        val restarted = SimulationRunner.run(SimulationScenario.NORMAL, root, false, {})
        assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, restarted.outcome)
        assertEquals(0, restarted.submissions); assertEquals(0, restarted.prints)
        assertTrue(restarted.verified)
    }

    @Test fun virtualNeverFinishedJobReachesRealSessionDeadlineWithoutPhysicalWait() = root { root ->
        val result = SimulationRunner.run(SimulationScenario.UNCERTAIN_COMPLETION, root, false, {})
        assertEquals(BridgeOutcome.UNKNOWN_OUTCOME, result.outcome)
        assertEquals("TIMEOUT", result.category)
        assertEquals(1, result.submissions); assertEquals(0, result.prints)
        assertTrue(result.verified)
    }
}
