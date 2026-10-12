package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.simulation.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WindowsLiveWorkflowTest {
    private val jpeg = javaClass.getResourceAsStream("/simulation/sony-selected.jpg")!!.readBytes()
    private class Ui(var answer: String = "PRINT", var token: String = SIMULATION_TOKEN) : LiveUi {
        val messages = mutableListOf<String>()
        val passwords = mutableListOf<CharArray>()
        override fun line(message: String) { messages += message }
        override fun ask(message: String): String = answer
        override fun password(message: String): CharArray = token.toCharArray().also { passwords += it }
        override fun preview(path: Path) { assertTrue(Files.exists(path)) }
    }
    private class Devices(val sony: SimulatedSony, val xiaomi: SimulatedPrinter) : LiveDeviceAccess {
        var cameraChoices = 0; var printerChoices = 0; var connections = 0
        override fun camera(ui: LiveUi): CameraWifiConnector { cameraChoices++; return sony }
        override fun printer(ui: LiveUi): PrinterConnector { printerChoices++; return PrinterConnector { connections++; xiaomi.connect() } }
        override fun nativeCheck(report: (String) -> Unit) { report("WINDOWS_WINSOCK_BINDINGS_READY") }
    }
    private fun state() = Files.createTempDirectory("live-test-")
    private fun workflow(d: Devices, ui: Ui): WindowsLiveWorkflow {
        val clock = SimulationClock()
        return WindowsLiveWorkflow(d, ui, now=clock::now, pause=clock::pause)
    }
    @Test fun receiveOnlyRetainsExactOriginalAndNeverTouchesPrinter() {
        val s=state(); val d=Devices(SimulatedSony(jpeg),SimulatedPrinter()); val ui=Ui()
        assertEquals(0,workflow(d,ui).run(LiveAction.RECEIVE,s))
        assertEquals(0,d.printerChoices); assertEquals(0,d.connections)
        Files.walk(s).use { files ->
            val original=files.filter { it.fileName.toString()=="original.jpg" }.findFirst().orElseThrow()
            assertArrayEquals(jpeg,Files.readAllBytes(original))
        }
        assertTrue(Files.readString(s.resolve("diagnostics.txt")).contains("LIVE_CAMERA_RECEIVED"))
    }
    @Test fun statusQueriesRealSessionButSubmitsNoJobAndClearsInputCharacters() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter()); val ui=Ui(); val s=state()
        assertEquals(0,workflow(d,ui).run(LiveAction.STATUS,s))
        assertEquals(0,d.cameraChoices); assertEquals(1,d.connections); assertEquals(0,d.xiaomi.submissions)
        assertTrue(ui.passwords.single().all { it=='\u0000' })
        assertTrue(Files.readString(s.resolve("diagnostics.txt")).contains("PRINTER_STATUS_READY"))
    }
    @Test fun cancellationNeverConnectsPrinterOrReservesJob() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter()); val ui=Ui("cancel"); val s=state()
        assertEquals(2,workflow(d,ui).run(LiveAction.PRINT,s))
        assertEquals(0,d.connections); assertEquals(0,d.xiaomi.submissions)
        FileBridgeJournal(s.resolve("physical-jobs")).use { assertFalse(it.unresolved) }
    }
    @Test fun fullFlowUsesRealCoordinatorAndSuppressesSamePhotoOnFreshSelection() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter()); val ui=Ui(); val s=state()
        assertEquals(0,workflow(d,ui).run(LiveAction.PRINT,s))
        assertEquals(1,d.xiaomi.completedJobs)
        assertEquals(0,workflow(d,ui).run(LiveAction.PRINT,s))
        assertEquals(1,d.connections); assertEquals(1,d.xiaomi.submissions)
        assertTrue(Files.readString(s.resolve("diagnostics.txt")).contains("BRIDGE_DUPLICATE_SUPPRESSED"))
    }
    @Test fun unknownAcceptanceBlocksAllNewDeviceActionsUntilCheckedAcknowledgement() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)); val ui=Ui(); val s=state()
        assertEquals(3,workflow(d,ui).run(LiveAction.PRINT,s))
        val cameraChoices=d.cameraChoices; val printerChoices=d.printerChoices
        assertEquals(3,workflow(d,ui).run(LiveAction.PRINT,s))
        assertEquals(cameraChoices,d.cameraChoices); assertEquals(printerChoices,d.printerChoices)
        ui.answer="CHECKED"
        assertEquals(0,workflow(d,ui).run(LiveAction.ACKNOWLEDGE,s))
        ui.answer="PRINT"
        assertEquals(0,workflow(d,ui).run(LiveAction.PRINT,s))
        assertEquals(1,d.xiaomi.submissions); assertEquals(1,d.connections)
    }
    @Test fun invalidTokenFailsBeforeConnectionAndIsNotExported() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter()); val ui=Ui(token="private-invalid-token"); val s=state()
        assertEquals(2,workflow(d,ui).run(LiveAction.STATUS,s))
        assertEquals(0,d.connections)
        val report=Files.readString(s.resolve("diagnostics.txt"))
        assertFalse(report.contains(ui.token)); assertFalse(report.contains(s.toString()))
        assertTrue(report.contains("INVALID_KEY")); assertTrue(ui.passwords.single().all { it=='\u0000' })
    }
    @Test fun nativeLinkageFailureAfterAcceptanceIsAnUnknownOutcome() {
        val d=Devices(SimulatedSony(jpeg),SimulatedPrinter(PrinterFault.POWER_CUT,crash={ throw UnsatisfiedLinkError("native-secret") }))
        val s=state()
        assertEquals(3,workflow(d,Ui()).run(LiveAction.PRINT,s))
        FileBridgeJournal(s.resolve("physical-jobs")).use { assertTrue(it.unresolved) }
        val text=Files.readString(s.resolve("diagnostics.txt"))
        assertTrue(text.contains("BRIDGE_UNKNOWN_OUTCOME")); assertFalse(text.contains("native-secret"))
    }
    @Test fun exportedReportsNeverContainInteractiveSecretsPathsOrInjectedMessages() {
        val d=object:LiveDeviceAccess {
            override fun camera(ui:LiveUi):CameraWifiConnector = throw IllegalStateException("secret-name /private/path")
            override fun printer(ui:LiveUi):PrinterConnector = throw AssertionError()
            override fun nativeCheck(report:(String)->Unit) { report("secret /private/path") }
        }
        val ui=Ui(); val s=state()
        assertEquals(2,WindowsLiveWorkflow(d,ui).run(LiveAction.RECEIVE,s))
        val text=Files.readString(s.resolve("diagnostics.txt"))
        assertFalse(text.contains("secret")); assertFalse(text.contains("/private")); assertFalse(text.contains(s.toString()))
        assertEquals(0,WindowsLiveWorkflow(d,ui).run(LiveAction.NATIVE,s))
        assertTrue(Files.readString(s.resolve("diagnostics.txt")).contains("DIAGNOSTIC_REDACTED"))
    }
}
