package dev.pocketphotobridge.bridge

import org.junit.Assert.*
import org.junit.Test

class ServiceCliOptionsTest {
    @Test fun serviceModesAreExplicitAndDoNotChangeDefaultSimulation() {
        assertEquals("SERVICE", CliOptions.parse(arrayOf("--service", "--state-dir=state")).mode.name)
        assertEquals("SERVICE_SIMULATION", CliOptions.parse(arrayOf("--simulate-service")).mode.name)
        assertEquals("BOOTSTRAP", CliOptions.parse(arrayOf("--make-bootstrap", "--state-dir=state")).mode.name)
        assertEquals("LINUX_NATIVE", CliOptions.parse(arrayOf("--linux-native-check")).mode.name)
        assertEquals("SUITE", CliOptions.parse(emptyArray()).mode.name)
    }

    @Test fun unattendedModeCannotBeMixedWithSimulationOrManualConfirmation() {
        for (arguments in listOf(arrayOf("--service", "--simulate"),
            arrayOf("--simulate-service", "--windows-live"), arrayOf("--service", "--confirm"),
            arrayOf("--make-bootstrap", "--token=secret"))) {
            try { CliOptions.parse(arguments); fail("Expected rejection") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.ARGUMENT_ERROR, failure.code) }
        }
    }
}
