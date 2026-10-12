package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.linux.*
import dev.pocketphotobridge.bridge.setup.*
import dev.pocketphotobridge.bridge.simulation.*
import dev.pocketphotobridge.printer.PrinterTransport
import java.net.InetSocketAddress
import java.nio.file.Path

interface ServiceDevices : AutoCloseable {
    val simulation: Boolean
    fun startSetup(credentials: SetupCredentials, wifiInterface: String): InetSocketAddress
    fun stopSetup()
    fun cameraPresent(configuration: ServiceConfiguration): Boolean
    fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink
    fun printer(configuration: ServiceConfiguration): PrinterTransport
    fun printers(): List<SetupPrinter>
    fun pairPrinter(address: String)
    fun simulationAction(action: String) { throw UnsupportedOperationException() }
    fun safeCounts(): Map<String, Any> = emptyMap()
    fun now(): Long = System.nanoTime() / 1_000_000
    fun pause(ms: Long) { Thread.sleep(ms) }
}

/** Fake wire devices only. This class has no native/radio or process calls. */
class SimulatedServiceDevices : ServiceDevices {
    override val simulation = true
    val clock = SimulationClock()
    val configuration = ServiceConfiguration("SIMULATED_CAMERA", "simulation-password",
        "02:00:00:00:00:01", SIMULATION_TOKEN, autoPrintEnabled = true)
    private val fixtures = listOf("sony-selected.jpg", "sony-selected-next.jpg").map { name ->
        javaClass.getResourceAsStream("/simulation/$name")!!.use { it.readBytes() }
    }
    private var selected = false
    private var absencePending = false
    private var index = -1
    private var interrupted = false
    private val printers = mutableListOf(SimulatedPrinter(PrinterFault.OFFLINE))
    private val cameras = mutableListOf<SimulatedSony>()

    override fun startSetup(credentials: SetupCredentials, wifiInterface: String) = InetSocketAddress("127.0.0.1", 8080)
    override fun stopSetup() { }
    @Synchronized override fun cameraPresent(configuration: ServiceConfiguration): Boolean {
        if (absencePending) { absencePending = false; return false }
        return selected
    }
    @Synchronized override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger): CameraLink {
        if (!selected) throw BridgeFailure(BridgeFailureCode.CAMERA_OFFLINE)
        val device = SimulatedSony(fixtures[index.coerceAtLeast(0)], if (interrupted) SonyFault.INTERRUPTED else SonyFault.NONE)
        val broken = interrupted; interrupted = false
        cameras += device
        val link = device.connect(trigger)
        return CameraLink(link.endpoint, link.transport) {
            link.close()
            synchronized(this) { if (device.completedTransfers > 0 || broken) selected = false }
        }
    }
    @Synchronized override fun printer(configuration: ServiceConfiguration) = printers.last().connect()
    override fun printers() = listOf(SetupPrinter(configuration.printerAddress, "Synthetic Xiaomi (no physical printer)"))
    override fun pairPrinter(address: String) { require(address == configuration.printerAddress) }
    @Synchronized override fun simulationAction(action: String) {
        when (action) {
            "select-photo" -> { index = (index + 1) % fixtures.size; selected = true; interrupted = false }
            "duplicate-photo" -> { if (index < 0) index = 0; selected = true; interrupted = false }
            "interrupted-download" -> { index = (index + 1) % fixtures.size; selected = true; interrupted = true }
            "printer-off" -> printers += SimulatedPrinter(PrinterFault.OFFLINE)
            "printer-on", "printer-ready" -> printers += SimulatedPrinter()
            "printer-busy" -> printers += SimulatedPrinter(PrinterFault.BUSY)
            "uncertain-print" -> printers += SimulatedPrinter(PrinterFault.DROP_AFTER_SUBMIT)
            else -> throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
        }
        if (action in setOf("select-photo", "duplicate-photo", "interrupted-download")) absencePending = true
        if (printers.size > 256 || cameras.size > 256) throw BridgeFailure(BridgeFailureCode.IO_ERROR)
    }
    @Synchronized override fun safeCounts(): Map<String, Any> = mapOf("prints" to printers.sumOf { it.submissions },
        "completed" to printers.sumOf { it.completedJobs }, "captured" to cameras.sumOf { it.completedTransfers })
    override fun now() = clock.now()
    override fun pause(ms: Long) = clock.pause(ms)
    override fun close() { }
}

/** Administrative radio adapter is instantiated only by the explicit Linux service mode. */
class LinuxServiceDevices(private val state: Path, private val report: (String) -> Unit) : ServiceDevices {
    override val simulation = false
    private var manager: LinuxRadioManager? = null
    private var wifiInterface = ""
    private fun radio(name: String = wifiInterface): LinuxRadioManager {
        if (manager == null || wifiInterface != name) {
            manager?.close(); wifiInterface = name
            manager = LinuxRadioManager(state.resolve("radio"), name, report)
        }
        return manager!!
    }
    override fun startSetup(credentials: SetupCredentials, wifiInterface: String): InetSocketAddress =
        InetSocketAddress(radio(wifiInterface).startSetup(credentials.ssid, credentials.hotspotPassword), 8080)
    override fun stopSetup() { manager?.stopSetup() }
    override fun cameraPresent(configuration: ServiceConfiguration) = radio(configuration.wifiInterface).isCameraPresent(configuration.cameraSsid)
    override fun camera(configuration: ServiceConfiguration, trigger: SelectionTrigger) =
        radio(configuration.wifiInterface).connectCamera(configuration.cameraSsid, configuration.cameraPassword)
    override fun printer(configuration: ServiceConfiguration) = LinuxPrinterConnector(configuration.printerAddress, report).connect()
    override fun printers() = radio().pairedPrinters().map { SetupPrinter(it.address, it.name) }
    override fun pairPrinter(address: String) = radio().pairPrinter(address)
    override fun close() { manager?.close() }
}
