package dev.pocketphotobridge.bridge

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class CliOptionsTest {
    @Test fun explorerDefaultRunsSuiteAndAllowsInteractiveCompletion() {
        val options = CliOptions.parse(emptyArray())
        assertEquals(CliMode.SUITE, options.mode)
        assertTrue(options.interactive)
    }

    @Test fun explicitSimulationNeverPausesAndAcceptsSpaceContainingStatePath() {
        val options = CliOptions.parse(arrayOf("--simulate", "--state-dir", "directory with spaces", "--crop"))
        assertEquals(CliMode.SIMULATION, options.mode)
        assertEquals(SimulationScenario.NORMAL, options.scenario)
        assertEquals(Path.of("directory with spaces"), options.stateDir)
        assertTrue(options.crop)
        assertFalse(options.interactive)
    }

    @Test fun supportedFailuresAndHardwareModeAreExplicit() {
        val options = CliOptions.parse(arrayOf("--simulate-failure=dropped-after-submit", "--state-dir=state"))
        assertEquals(SimulationScenario.DROPPED_AFTER_SUBMIT, options.scenario)
        assertEquals(CliMode.SIMULATION, options.mode)
        assertEquals(CliMode.HARDWARE, CliOptions.parse(arrayOf("--hardware", "--config=not-read.properties")).mode)
    }

    @Test fun manualAcknowledgementRequiresItsOwnConfirmationAndCannotStartAnotherMode() {
        assertEquals(CliMode.ACKNOWLEDGE, CliOptions.parse(arrayOf("--acknowledge-checked-printer", "--confirm")).mode)
        rejected("--acknowledge-checked-printer")
        rejected("--confirm")
        rejected("--acknowledge-checked-printer", "--confirm", "--simulate")
    }

    @Test fun unknownCredentialsArgumentsConflictsAndMissingPathsAreNeverEchoed() {
        rejected("--token=private-device-secret")
        rejected("--simulate", "--hardware")
        rejected("--simulate-failure=private-wifi-name")
        rejected("--state-dir")
        rejected("--state-dir=")
        rejected("--simulate", "--state-dir=first", "--state-dir=second")
        rejected("--simulate-all", "--config=secret-file")
    }

    @Test fun helpModeDoesNotOpenStateOrHardware() {
        val options = CliOptions.parse(arrayOf("--help"))
        assertEquals(CliMode.HELP, options.mode)
        assertNull(options.stateDir)
        assertFalse(options.interactive)
    }

    private fun rejected(vararg arguments: String) {
        try { CliOptions.parse(arrayOf(*arguments)); fail("Expected fixed argument rejection") }
        catch (failure: BridgeFailure) {
            assertEquals(BridgeFailureCode.ARGUMENT_ERROR, failure.code)
            assertEquals("ARGUMENT_ERROR", failure.message)
        }
    }
}
