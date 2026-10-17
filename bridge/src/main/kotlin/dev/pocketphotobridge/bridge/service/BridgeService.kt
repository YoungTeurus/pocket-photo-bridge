package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.linux.*
import dev.pocketphotobridge.bridge.setup.*
import dev.pocketphotobridge.bridge.simulation.SIMULATION_TOKEN
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BridgeService {
    fun defaultState(simulation: Boolean): Path {
        val local = System.getenv("LOCALAPPDATA")
        return if (simulation && System.getProperty("os.name").startsWith("Windows") && !local.isNullOrBlank())
            Path.of(local, "PocketPhotoBridge", "service-simulation")
        else if (simulation) Path.of(System.getProperty("user.home"), ".local", "state", "pocket-photo-bridge", "service-simulation")
        else Path.of("/var/lib/pocket-photo-bridge")
    }

    /** Invoke only in a private local terminal. Service logs never call this. */
    fun bootstrap(state: Path, output: PrintStream): Int = try {
        privateRoot(state)
        val credentials = BootstrapStore(state.resolve("setup")).loadOrCreate()
        output.println("Setup SSID: ${credentials.ssid}")
        output.println("Setup Wi-Fi password: ${credentials.hotspotPassword}")
        output.println("Setup administrator password: ${credentials.adminPassword}")
        output.println("Setup URL: http://192.168.50.1:8080/")
        0
    } catch (_: Exception) { output.println("SERVICE_BOOTSTRAP_FAILED category=PERSISTENCE_ERROR"); 2 }

    fun nativeCheck(output: PrintStream): Int {
        output.println("NO_PHYSICAL_PRINT")
        if (!linux64()) { output.println("LINUX_REQUIRED"); return 64 }
        return try {
            LinuxBluetooth.nativeSelfTest()
            output.println("LINUX_NATIVE_BINDINGS_READY")
            output.println("LINUX_HARDWARE_VALIDATION_PENDING")
            0
        } catch (failure: Exception) {
            output.println("LINUX_NATIVE_FAILED category=${category(failure)}"); 2
        } catch (_: LinkageError) { output.println("LINUX_NATIVE_FAILED category=NATIVE_UNAVAILABLE"); 2 }
    }

    fun run(simulation: Boolean, state: Path, output: PrintStream): Int {
        if (!simulation && !linux64()) { output.println("LINUX_REQUIRED"); return 64 }
        val diagnostics = ServiceDiagnostics(state, output)
        val devices: ServiceDevices = if (simulation) SimulatedServiceDevices() else LinuxServiceDevices(state, diagnostics::emit)
        return try {
            privateRoot(state)
            diagnostics.emit(if (simulation) "SIMULATION_ONLY" else "LINUX_HARDWARE_VALIDATION_PENDING")
            if (simulation) diagnostics.emit("NO_PHYSICAL_PRINT")
            val bootId = if (simulation) null else Files.newInputStream(Path.of("/proc/sys/kernel/random/boot_id")).use {
                it.readNBytes(38).toString(Charsets.UTF_8).trim()
            }
            ServiceRuntime(state, devices, diagnostics, output, bootId).use { it.run() }
            0
        } catch (failure: Exception) {
            diagnostics.emit("SERVICE_FAILED category=${category(failure)}"); 2
        } catch (_: LinkageError) { diagnostics.emit("SERVICE_FAILED category=NATIVE_UNAVAILABLE"); 2 }
        finally { try { devices.close() } catch (_: Exception) { diagnostics.emit("SERVICE_RADIO_CLEANUP_FAILED") } }
    }

    private fun linux64() = System.getProperty("os.name").startsWith("Linux") &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64", "aarch64", "arm64")

    internal fun privateRoot(state: Path) {
        if (Files.isSymbolicLink(state)) throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR)
        if (state.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"))
        } else Files.createDirectories(state)
    }

    internal fun category(failure: Exception): String = when (failure) {
        is BridgeFailure -> failure.code.name
        is LinuxRadioFailure -> failure.code.name
        is SetupPersistenceException -> "PERSISTENCE_ERROR"
        is InterruptedException -> "CANCELLED"
        else -> "IO_ERROR"
    }
}

/** Live setup and the operation worker compose the same protocols used by the simulation. */
class ServiceRuntime(private val state: Path, private val devices: ServiceDevices,
    private val diagnostics: ServiceDiagnostics, private val output: PrintStream,
    private val bootId: String? = null) : AutoCloseable {
    private val configStore = ServiceConfigStore(state.resolve("setup"))
    private val configuration = AtomicReference(configStore.load())
    private val credentials = BootstrapStore(state.resolve("setup")).loadOrCreate()
    private val policy = BootModePolicy(monotonicNow())
    private val running = AtomicBoolean(true)
    private val bridgeLock = Any()
    private val printerCache = AtomicReference<List<SetupPrinter>>(emptyList())
    private val pairing = PairingTask({ address ->
        devices.pairPrinter(address)
        printerCache.set(devices.printers())
    }, diagnostics::emit)
    private var bridge: AutonomousBridge
    private var server: SetupServer? = null
    private val loop: ServiceLoop
    private var closed = false

    init {
        if (devices is SimulatedServiceDevices && configuration.get() == null) {
            configStore.save(devices.configuration); configuration.set(devices.configuration)
        }
        bridge = openBridge()
        loop = ServiceLoop(policy, object : ServiceLoopActions {
            override fun enterOperation() {
                // The setup listener closes before Linux switches the exclusive radio.
                if (!devices.simulation) { server?.close(); server = null }
                devices.stopSetup()
                synchronized(bridgeLock) { bridge.close(); bridge = openBridge() }
                diagnostics.emit("SERVICE_OPERATION")
            }
            override fun capture(trigger: SelectionTrigger): Boolean {
                val result = synchronized(bridgeLock) {
                    if (bridge.snapshot().state in setOf(ServiceState.NEEDS_ATTENTION, ServiceState.STATE_ERROR)) return false
                    bridge.capture(trigger)
                }
                return result.state == ServiceState.QUEUED && result.category != "QUEUE_FULL" || result.category == "DUPLICATE_SUPPRESSED"
            }
            override fun printNext() { synchronized(bridgeLock) { bridge.printNext() } }
        })
    }

    private fun requiredConfiguration() = configuration.get() ?: throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
    private fun openBridge() = AutonomousBridge(state.resolve("service"),
        CameraWifiConnector { devices.camera(requiredConfiguration(), it) },
        PrinterConnector { devices.printer(requiredConfiguration()) }, JvmPrintImagePreparer(),
        { if (devices.simulation) SIMULATION_TOKEN else requiredConfiguration().printerToken },
        configuration.get()?.crop ?: false, diagnostics::emit, devices::now, devices::pause).also { bridge ->
            bootId?.let { bridge.beginBoot(it, configuration.get()?.keepQueueAcrossBoots == true) }
        }

    fun run() {
        val initialInterface = configuration.get()?.wifiInterface ?: System.getenv("POCKET_BRIDGE_WIFI_INTERFACE") ?: "wlan0"
        val address = devices.startSetup(credentials, initialInterface)
        policy.beginWindow(monotonicNow())
        server = SetupServer(address, credentials.adminPassword, backend()).also { it.start() }
        diagnostics.emit("SERVICE_SETUP")
        output.println("Setup page: http://${address.address.hostAddress}:${address.port}/")
        output.println("Use the administrator password shown during installation or by --make-bootstrap in a private local terminal.")
        val worker = Thread.currentThread()
        val shutdown = Thread({ running.set(false); worker.interrupt() }, "bridge-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        var lastPresence = false
        try {
            while (running.get() && !worker.isInterrupted) {
                val config = configuration.get()
                if (loop.operating && config != null) {
                    try { lastPresence = devices.cameraPresent(config) }
                    catch (failure: Exception) { diagnostics.emit("SERVICE_CAMERA_WAIT category=${BridgeService.category(failure)}") }
                }
                loop.tick(monotonicNow(), config?.autoPrintEnabled == true && !pairing.pending, lastPresence)
                Thread.sleep(1_000)
            }
        } catch (_: InterruptedException) { worker.interrupt() }
        finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { }
            diagnostics.emit("SERVICE_STOPPED")
        }
    }

    private fun backend() = object : SetupBackend {
        override fun status(): Map<String, Any> {
            val snapshot = synchronized(bridgeLock) { bridge.snapshot() }
            return linkedMapOf<String, Any>("mode" to if (loop.operating) "OPERATION" else "SETUP",
                "stage" to if (pairing.pending) "PAIRING" else snapshot.state.name, "queued" to snapshot.queued, "simulation" to devices.simulation,
                "configured" to (configuration.get() != null), "autoPrintEnabled" to (configuration.get()?.autoPrintEnabled == true))
                .apply { (snapshot.category ?: pairing.failure)?.let { put("category", it) }; putAll(devices.safeCounts()) }
        }
        override fun configuration() = this@ServiceRuntime.configuration.get()
        override fun diagnostics() = status() + mapOf("events" to diagnostics.events().takeLast(256))
        override fun staySetup() { if (!loop.operating) policy.holdSetup() }
        override fun saveConfig(config: ServiceConfiguration) {
            check(!loop.operating) { "SETUP_REQUIRED" }
            configStore.save(config); configuration.set(config)
            diagnostics.emit("SERVICE_CONFIG_SAVED")
        }
        override fun startOperation() {
            check(!pairing.pending) { "PAIRING_IN_PROGRESS" }
            check(requiredConfiguration().autoPrintEnabled) { "AUTOMATIC_PRINTING_DISABLED" }
            policy.requestOperation()
        }
        override fun acknowledgeCheckedPrinter() {
            synchronized(bridgeLock) { bridge.acknowledgeCheckedPrinter() }
        }
        override fun printers(): List<SetupPrinter> {
            if (pairing.pending) return printerCache.get()
            return devices.printers().also { printerCache.set(it) }
        }
        override fun pairPrinter(address: String) { check(!loop.operating); pairing.start(address) }
        override fun simulationAction(action: String) { check(devices.simulation); devices.simulationAction(action) }
    }

    override fun close() {
        if (closed) return
        closed = true; running.set(false)
        try { server?.close() } finally { pairing.close(); synchronized(bridgeLock) { bridge.close() } }
    }
    private fun monotonicNow() = System.nanoTime() / 1_000_000
}

/** Only fixed codes and counts enter disk/web reports. Bootstrap credentials have a separate output path. */
class ServiceDiagnostics(private val state: Path, private val output: PrintStream) {
    private val lines = ArrayDeque<String>()
    @Synchronized fun events(): List<String> = lines.toList()
    @Synchronized fun emit(message: String) {
        val safe = if (safeEvent(message)) message else "DIAGNOSTIC_REDACTED"
        if (lines.size == 2048) lines.removeFirst()
        lines.addLast(safe); output.println(safe)
        try {
            if (!Files.isDirectory(state) || Files.isSymbolicLink(state.resolve("diagnostics.txt"))) return
            val temp = Files.createTempFile(state, "diagnostic-", ".tmp")
            try {
                Files.writeString(temp, "Pocket Photo Bridge ${BridgeCli.VERSION} | headless service\n" + lines.joinToString("\n") + "\n")
                Files.move(temp, state.resolve("diagnostics.txt"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } catch (_: Exception) { output.println("SERVICE_REPORT_WRITE_FAILED") }
    }
    companion object {
        private val CATEGORIES = BridgeFailureCode.entries.map { it.name }.toSet() +
            LinuxRadioFailureCode.entries.map { it.name } +
            dev.pocketphotobridge.printer.PrinterFailureCode.entries.map { it.name } +
            dev.pocketphotobridge.sony.Failure.entries.map { it.name } +
            setOf("CAMERA_ACK_PENDING", "QUEUE_FULL", "DUPLICATE_SUPPRESSED", "NATIVE_UNAVAILABLE")
        private val STAGES = setOf("SIMULATION_ONLY", "NO_PHYSICAL_PRINT", "LINUX_HARDWARE_VALIDATION_PENDING",
            "SERVICE_FAILED", "SERVICE_SETUP", "SERVICE_OPERATION", "SERVICE_STOPPED", "SERVICE_CAMERA_WAIT",
            "SERVICE_CONFIG_SAVED", "SERVICE_RADIO_CLEANUP_FAILED", "SERVICE_PAIRING_STARTED",
            "SERVICE_PAIRING_FINISHED", "SERVICE_PAIRING_FAILED", "BRIDGE_CAMERA_CONNECTING",
            "BRIDGE_IMAGE_PREPARING", "BRIDGE_PRINTER_CONNECTING", "BRIDGE_BOOT_QUEUE_DISCARDED", "LINUX_SETUP_STARTED", "LINUX_SETUP_STOPPED",
            "LINUX_CAMERA_CONNECTED", "LINUX_PRINTER_PAIRED", "LINUX_SPP_DISCOVERY_START", "LINUX_SPP_DISCOVERED") +
            ServiceState.entries.map { "BRIDGE_${it.name}" } +
            dev.pocketphotobridge.sony.Stage.entries.map { "SONY_${it.name}" } +
            dev.pocketphotobridge.printer.PrinterStage.entries.map { "PRINTER_${it.name}" } +
            setOf("SOCKET", "CONNECT", "POLL", "SO_ERROR", "SEND", "RECEIVE", "SHUTDOWN", "CLOSE", "CONNECTED").map { "LINUX_BLUETOOTH_$it" }
        private fun safeEvent(message: String): Boolean {
            if (message.length > 256) return false
            val parts = message.split(' ')
            if (parts.size !in 1..5 || parts[0] !in STAGES) return false
            val seen = mutableSetOf<String>()
            return parts.drop(1).all { field ->
                val pair = field.split('=', limit = 2)
                pair.size == 2 && seen.add(pair[0]) && when (pair[0]) {
                    "category" -> pair[1] in CATEGORIES
                    "reason" -> pair[1] in setOf("OK", "FAILED", "PENDING")
                    "count", "queued", "native_error" -> pair[1].matches(Regex("[0-9]{1,8}")) &&
                        (pair[1].toLongOrNull() ?: Long.MAX_VALUE) <= when (pair[0]) { "queued" -> 16; "native_error" -> 4095; else -> 64L * 1024 * 1024 }
                    else -> false
                }
            }
        }
    }
}
