package dev.pocketphotobridge.bridge.simulation

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import java.nio.file.Path

data class SimulationResult(val outcome: BridgeOutcome, val category: String?, val submissions: Int,
    val prints: Int, val verified: Boolean)

/** Deterministic fixtures feed the production coordinator; there are no real device connections. */
object SimulationRunner {
    private val first = SelectionTrigger("synthetic-selection-1")
    private val second = SelectionTrigger("synthetic-selection-2")
    private val third = SelectionTrigger("synthetic-selection-3")

    fun run(scenario: SimulationScenario, stateDir: Path, crop: Boolean, report: (String) -> Unit,
        crash: () -> Unit = { throw SimulatedPowerLoss() }): SimulationResult {
        report("SCENARIO_${scenario.name}")
        val source = fixture("sony-selected.jpg")
        val cameraFault = when (scenario) {
            SimulationScenario.MISSING_SSID -> SonyFault.MISSING_SSID
            SimulationScenario.CAMERA_OFFLINE -> SonyFault.OFFLINE
            SimulationScenario.CAMERA_TIMEOUT -> SonyFault.TIMEOUT
            SimulationScenario.NO_SELECTED_JPEG -> SonyFault.EMPTY_SELECTION
            SimulationScenario.INTERRUPTED_TRANSFER -> SonyFault.INTERRUPTED
            else -> SonyFault.NONE
        }
        val printerFault = when (scenario) {
            SimulationScenario.PRINTER_OFFLINE -> PrinterFault.OFFLINE
            SimulationScenario.PRINTER_BUSY -> PrinterFault.BUSY
            SimulationScenario.DROPPED_AFTER_SUBMIT, SimulationScenario.MANUAL_RECOVERY -> PrinterFault.DROP_AFTER_SUBMIT
            SimulationScenario.UNCERTAIN_COMPLETION -> PrinterFault.NEVER_FINISH
            SimulationScenario.POWER_CUT -> PrinterFault.POWER_CUT
            else -> PrinterFault.NONE
        }
        val camera = SimulatedSony(source, cameraFault)
        val printer = SimulatedPrinter(printerFault, crash)
        val clock = SimulationClock()
        fun coordinator(journal: FileBridgeJournal, sony: SimulatedSony = camera, xiaomi: SimulatedPrinter = printer) =
            PrintCoordinator(sony, xiaomi, JvmPrintImagePreparer(), journal, stateDir.resolve("cache"),
                token = { SIMULATION_TOKEN }, crop = crop, report = report, now = clock::now, pause = clock::pause)

        var previouslyUnknown = false
        var outcome = FileBridgeJournal(stateDir).use { journal ->
            previouslyUnknown = journal.unresolved
            val coordinator = coordinator(journal)
            val result = try { coordinator.process(first) }
            catch (_: SimulatedPowerLoss) {
                // Unit-level abrupt failure; packaged smoke additionally halts an actual JVM process.
                report("BRIDGE_UNKNOWN_OUTCOME category=SIMULATED_POWER_LOSS")
                BridgeResult(BridgeOutcome.UNKNOWN_OUTCOME, "SIMULATED_POWER_LOSS")
            }
            when (scenario) {
                SimulationScenario.DUPLICATE_TRIGGER -> coordinator.process(first)
                SimulationScenario.DUPLICATE_PHOTO -> coordinator.process(second)
                else -> result
            }
        }

        var recoverySubmissions = 0
        var recoveryPrints = 0
        var recoveryVerified = true
        if (scenario == SimulationScenario.RESTART) {
            FileBridgeJournal(stateDir).use { journal -> outcome = coordinator(journal).process(first) }
        }
        if (scenario == SimulationScenario.MANUAL_RECOVERY) {
            val readyPrinter = SimulatedPrinter()
            FileBridgeJournal(stateDir).use { journal ->
                val blocked = coordinator(journal, xiaomi = readyPrinter).process(second)
                recoveryVerified = outcome.outcome == BridgeOutcome.UNKNOWN_OUTCOME &&
                    blocked.outcome == BridgeOutcome.UNKNOWN_OUTCOME && readyPrinter.submissions == 0
                journal.acknowledgeCheckedPrinter()
                report("BRIDGE_MANUAL_ACKNOWLEDGED")
                val duplicate = coordinator(journal, xiaomi = readyPrinter).process(second)
                recoveryVerified = recoveryVerified && duplicate.outcome == BridgeOutcome.DUPLICATE_SUPPRESSED && readyPrinter.submissions == 0
                val differentCamera = SimulatedSony(fixture("sony-selected-next.jpg"))
                outcome = coordinator(journal, differentCamera, readyPrinter).process(third)
                recoveryVerified = recoveryVerified && !journal.unresolved
            }
            recoverySubmissions = readyPrinter.submissions
            recoveryPrints = readyPrinter.completedJobs
        }
        val submissions = printer.submissions + recoverySubmissions
        val prints = printer.completedJobs + recoveryPrints
        val expected = expected(scenario, previouslyUnknown)
        val verified = recoveryVerified && when (scenario) {
            SimulationScenario.NORMAL -> when {
                previouslyUnknown -> outcome.outcome == BridgeOutcome.UNKNOWN_OUTCOME && submissions == 0 && prints == 0
                outcome.outcome == BridgeOutcome.DUPLICATE_SUPPRESSED -> submissions == 0 && prints == 0
                else -> outcome.outcome == BridgeOutcome.PRINTED && submissions == 1 && prints == 1 &&
                    camera.downloads == 1 && camera.completedTransfers == 1 && printer.uploadedFrames > 0
            }
            SimulationScenario.DUPLICATE_TRIGGER, SimulationScenario.RESTART ->
                outcome.outcome == BridgeOutcome.DUPLICATE_SUPPRESSED && submissions == 1 && prints == 1 && camera.downloads == 1
            SimulationScenario.DUPLICATE_PHOTO ->
                outcome.outcome == BridgeOutcome.DUPLICATE_SUPPRESSED && submissions == 1 && prints == 1 && camera.downloads == 2
            SimulationScenario.MANUAL_RECOVERY -> outcome.outcome == BridgeOutcome.PRINTED && submissions == 2 && prints == 1
            else -> outcome.outcome == expected.first && outcome.category == expected.second &&
                prints == 0 && submissions == if (expected.first == BridgeOutcome.UNKNOWN_OUTCOME) 1 else 0
        }
        report("SIMULATED_SUBMISSIONS count=$submissions")
        report("SIMULATED_PRINTS count=$prints")
        report(if (verified) "SIMULATION_CASE_PASS" else "SIMULATION_CHECK_FAILED")
        return SimulationResult(outcome.outcome, outcome.category, submissions, prints, verified)
    }

    private fun expected(scenario: SimulationScenario, unknown: Boolean): Pair<BridgeOutcome, String?> = when (scenario) {
        SimulationScenario.MISSING_SSID -> BridgeOutcome.CAMERA_FAILED to "SSID_MISSING"
        SimulationScenario.CAMERA_OFFLINE -> BridgeOutcome.CAMERA_FAILED to "CAMERA_OFFLINE"
        SimulationScenario.CAMERA_TIMEOUT -> BridgeOutcome.CAMERA_FAILED to "CAMERA_TIMEOUT"
        SimulationScenario.NO_SELECTED_JPEG -> BridgeOutcome.CAMERA_FAILED to "NO_JPEG"
        SimulationScenario.INTERRUPTED_TRANSFER -> BridgeOutcome.CAMERA_FAILED to "TRUNCATED_JPEG"
        SimulationScenario.PRINTER_OFFLINE -> BridgeOutcome.PRINTER_FAILED to "PRINTER_OFFLINE"
        SimulationScenario.PRINTER_BUSY -> BridgeOutcome.PRINTER_FAILED to "BUSY"
        SimulationScenario.DROPPED_AFTER_SUBMIT -> BridgeOutcome.UNKNOWN_OUTCOME to "DISCONNECTED"
        SimulationScenario.UNCERTAIN_COMPLETION -> BridgeOutcome.UNKNOWN_OUTCOME to "TIMEOUT"
        SimulationScenario.POWER_CUT -> BridgeOutcome.UNKNOWN_OUTCOME to "SIMULATED_POWER_LOSS"
        else -> (if (unknown) BridgeOutcome.UNKNOWN_OUTCOME else BridgeOutcome.PRINTED) to null
    }

    private fun fixture(name: String): ByteArray =
        SimulationRunner::class.java.getResourceAsStream("/simulation/$name")?.use { it.readBytes() }
            ?: throw BridgeFailure(BridgeFailureCode.INVALID_IMAGE)
}
