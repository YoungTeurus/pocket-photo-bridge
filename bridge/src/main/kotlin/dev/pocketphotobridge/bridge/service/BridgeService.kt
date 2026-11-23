package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.bridge.images.JvmPrintImagePreparer
import dev.pocketphotobridge.bridge.linux.*
import dev.pocketphotobridge.bridge.setup.*
import dev.pocketphotobridge.bridge.simulation.SIMULATION_TOKEN
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
        output.println("Setup URL: http://$SETUP_HOTSPOT_ADDRESS:8080/")
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
        loop = ServiceLoop(policy, ::monotonicNow, object : ServiceLoopActions {
            override fun enterOperation() {
                // The setup listener closes before Linux switches the exclusive radio.
                if (!devices.simulation) { server?.close(); server = null }
                devices.stopSetup()
                synchronized(bridgeLock) { bridge.close(); bridge = openBridge() }
                diagnostics.emit("SERVICE_OPERATION")
            }
            override fun capture(trigger: SelectionTrigger) = synchronized(bridgeLock) { bridge.capture(trigger) }.captureStep
            override fun printNext() = synchronized(bridgeLock) { bridge.printNext() }.printStep
            override fun cameraPresence(scan: Result<Boolean>) = diagnostics.emit(scan.fold(
                { if (it) "SERVICE_CAMERA_PRESENT" else "SERVICE_CAMERA_ABSENT" },
                { "SERVICE_CAMERA_WAIT category=${BridgeService.category(it as Exception)}" }))
        }, devices.firstRecheckMs, devices.maxRecheckMs)
    }

    private fun requiredConfiguration() = configuration.get() ?: throw BridgeFailure(BridgeFailureCode.ARGUMENT_ERROR)
    private fun openBridge() = AutonomousBridge(state.resolve("service"),
        CameraWifiConnector { devices.camera(requiredConfiguration(), it) },
        PrinterConnector { devices.printer(requiredConfiguration()) }, JvmPrintImagePreparer(),
        { if (devices.simulation) SIMULATION_TOKEN else requiredConfiguration().printerToken },
        configuration.get()?.crop ?: false, diagnostics::emit, devices::now, devices::pause, diagnostics).also { bridge ->
            bootId?.let { bridge.beginBoot(it, configuration.get()?.keepQueueAcrossBoots == true) }
        }

    fun run() {
        val initialInterface = configuration.get()?.wifiInterface ?: System.getenv("POCKET_BRIDGE_WIFI_INTERFACE") ?: "wlan0"
        val address = startSetup(initialInterface)
        // The setup window counts from the moment the hotspot is up, however many attempts that took.
        policy.beginWindow(monotonicNow())
        server = SetupServer(address, credentials.adminPassword, backend()).also { it.start() }
        diagnostics.emit("SERVICE_SETUP")
        output.println("Setup page: http://${address.address.hostAddress}:${address.port}/")
        output.println("Use the administrator password shown during installation or by --make-bootstrap in a private local terminal.")
        val worker = Thread.currentThread()
        val shutdown = Thread({ running.set(false); worker.interrupt() }, "bridge-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            while (running.get() && !worker.isInterrupted) {
                val config = configuration.get()
                // Enabled implies a saved configuration, so the scan always has one.
                loop.tick(monotonicNow(), config?.autoPrintEnabled == true && !pairing.pending) { devices.cameraPresent(requireNotNull(config)) }
                Thread.sleep(1_000)
            }
        } catch (_: InterruptedException) { worker.interrupt() }
        finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { }
            diagnostics.emit("SERVICE_STOPPED")
        }
    }

    /** A slow radio cold start is retried; the unit restarts the process after the limit. */
    private fun startSetup(wifiInterface: String): InetSocketAddress {
        val first = devices.now()
        while (true) {
            try { return devices.startSetup(credentials, wifiInterface) }
            catch (failure: LinuxRadioFailure) {
                // No attempt starts at or after the limit; one already running is allowed to finish.
                if (!running.get() || devices.now() + SETUP_RETRY_PAUSE_MS - first >= SETUP_RETRY_LIMIT_MS) throw failure
                devices.pause(SETUP_RETRY_PAUSE_MS)
            }
        }
    }

    internal fun backend() = object : SetupBackend {
        override fun status(): Map<String, Any> {
            val snapshot = synchronized(bridgeLock) { bridge.snapshot() }
            return linkedMapOf<String, Any>("build" to BridgeCli.BUILD, "mode" to if (loop.operating) "OPERATION" else "SETUP",
                "stage" to if (pairing.pending) "PAIRING" else snapshot.state.name, "queued" to snapshot.queued, "simulation" to devices.simulation,
                "configured" to (configuration.get() != null), "autoPrintEnabled" to (configuration.get()?.autoPrintEnabled == true),
                "blocked" to (snapshot.blocked != null))
                .apply {
                    (snapshot.category ?: pairing.failure)?.let { put("category", it) }
                    snapshot.blocked?.let { put("blockedReason", it.name) }
                    snapshot.discardedAtBoot?.let { put("discardedAtBoot", it) }
                    putAll(devices.safeCounts())
                }
        }
        override fun configuration() = this@ServiceRuntime.configuration.get()
        override fun diagnostics() = status() + mapOf("header" to diagnostics.header, "events" to diagnostics.events(),
            "previousRuns" to diagnostics.previousRuns())
        override fun staySetup() { if (!loop.operating) policy.holdSetup() }
        override fun saveConfig(config: ServiceConfiguration) {
            check(!loop.operating) { "SETUP_REQUIRED" }
            configStore.save(config); configuration.set(config)
            diagnostics.emit("SERVICE_CONFIG_SAVED")
        }
        override fun wifiInterfaceExists(name: String) = devices.wifiInterfaceExists(name)
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
    companion object {
        const val SETUP_RETRY_PAUSE_MS = 5_000L
        const val SETUP_RETRY_LIMIT_MS = 120_000L
    }
}

/** Only fixed codes and counts enter disk/web reports. Bootstrap credentials have a separate output path. */
class ServiceDiagnostics(private val state: Path, private val output: PrintStream,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }, uptimeSeconds: Long? = kernelUptime()) : AttemptLog {
    private val start = now()
    private var lines = emptyList<String>()
    /** No wall clock: the board has neither a backed-up clock nor a time server. */
    val header: String

    init {
        // Rotate instead of parsing old runs back into memory; a probe or restart is one more run.
        try {
            if (Files.isDirectory(state, NOFOLLOW_LINKS)) {
                // A power pull between write and rename leaves these behind.
                Files.newDirectoryStream(state, "diagnostic-*.tmp").use { stale ->
                    stale.forEach { if (Files.isRegularFile(it, NOFOLLOW_LINKS)) Files.deleteIfExists(it) } }
                Files.deleteIfExists(generation(GENERATIONS))
                for (n in GENERATIONS - 1 downTo 1)
                    if (Files.exists(generation(n), NOFOLLOW_LINKS)) Files.move(generation(n), generation(n + 1), ATOMIC_MOVE)
                if (Files.exists(state.resolve(FILE), NOFOLLOW_LINKS)) Files.move(state.resolve(FILE), generation(1), ATOMIC_MOVE)
            }
        } catch (_: Exception) { output.println("SERVICE_REPORT_WRITE_FAILED") }
        val run = (previousRuns().maxOfOrNull { HEADER.matchEntire(it["header"] as String)!!.groupValues[1].toIntOrNull() ?: 0 } ?: 0) + 1
        header = "Pocket Photo Bridge ${BridgeCli.BUILD} | headless service run=$run" + (uptimeSeconds?.let { " uptime_s=$it" } ?: "")
    }

    @Synchronized fun events(): List<String> = lines
    private var holder: Thread? = null
    /** Each held line with the second it happened. */
    private val held = ArrayList<Pair<Long, String>>()
    /** Holds only the calling thread's lines, so setup and pairing threads keep reporting. */
    @Synchronized override fun hold() { holder = Thread.currentThread(); held.clear() }
    @Synchronized override fun release(keep: Boolean) {
        if (holder !== Thread.currentThread()) return
        holder = null
        val pending = held.toList(); held.clear()
        if (keep) pending.forEach { (at, safe) -> append(at, safe, print = false) }
    }
    /** Oldest first; every line passes the same validator as the current run. */
    fun previousRuns(): List<Map<String, Any>> = (GENERATIONS downTo 1).mapNotNull { n ->
        try {
            val file = generation(n)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 65536) return@mapNotNull null
            val text = Files.readAllLines(file)
            safeRun(text.firstOrNull(), text.drop(1))
        } catch (_: Exception) { null }
    }
    private fun generation(n: Int) = state.resolve("diagnostics.$n.txt")
    @Synchronized fun emit(message: String) {
        val safe = if (safeEvent(message)) message else "DIAGNOSTIC_REDACTED"
        val at = (now() - start) / 1000
        // The journal has no cap, so it shows a held line live; only the capped run waits for the attempt's outcome.
        if (holder === Thread.currentThread()) {
            if (held.size < 256) { output.println(safe); held += at to safe; return }
            release(true)
        }
        append(at, safe, print = true)
    }
    private fun append(at: Long, safe: String, print: Boolean) {
        // An unchanged state is not re-emitted, so idle hours cannot evict the run; redactions stay counted.
        if (safe != "DIAGNOSTIC_REDACTED" && lines.lastOrNull()?.substringAfter(' ') == safe) return
        lines = recent(lines + "$at $safe"); if (print) output.println(safe)
        // The board usually stops by a power pull; force only failures to spare the SD card.
        val force = " category=" in safe && NOT_FAILURES.none { " category=$it" in safe }
        try {
            if (!Files.isDirectory(state) || Files.isSymbolicLink(state.resolve(FILE))) return
            val temp = Files.createTempFile(state, "diagnostic-", ".tmp")
            try {
                Files.writeString(temp, header + "\n" + lines.joinToString("\n") + "\n")
                if (force)
                    java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(temp, state.resolve(FILE), ATOMIC_MOVE, REPLACE_EXISTING)
                // The rename is durable only once the directory is synced; Windows cannot open a directory channel.
                if (force) try {
                    java.nio.channels.FileChannel.open(state, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                } catch (_: Exception) { }
            } finally { Files.deleteIfExists(temp) }
        } catch (_: Exception) { output.println("SERVICE_REPORT_WRITE_FAILED") }
    }
    companion object {
        /** Categories that name an outcome, not a failure: a re-check finding a known photo, or a transfer kept before its acknowledgement. */
        private val NOT_FAILURES = setOf("DUPLICATE_SUPPRESSED", "CAMERA_ACK_PENDING")
        private const val FILE = "diagnostics.txt"
        /** Previous runs kept besides the current one. */
        const val GENERATIONS = 5
        /** Per-run cap in file bytes: six capped runs stay within the 64 KiB export even with Gson's escaped '='. */
        private const val RUN_BYTES = 7168
        /** Validated lines, most recent last, within the per-run cap. */
        fun recent(lines: List<*>): List<String> {
            var bytes = 0
            return lines.asReversed().asSequence().mapNotNull { (it as? String)?.takeIf(::safeLine) }
                .takeWhile { bytes += it.length + 1; bytes <= RUN_BYTES }.toList().asReversed()
        }
        /** Files written before run headers existed have no run counter; they count as run 0. Builds before #13 carry no build suffix. */
        private val HEADER = Regex("Pocket Photo Bridge [0-9]+\\.[0-9]+\\.[0-9]+(?:${BridgeCli.BUILD_SUFFIX})? \\| headless service(?: run=([1-9][0-9]{0,8})( uptime_s=[0-9]{1,10})?)?")
        fun safeHeader(text: String) = HEADER.matches(text)
        /** A retained run, revalidated: its header and validated events, or null without a valid header. */
        fun safeRun(header: Any?, events: List<*>): Map<String, Any>? =
            (header as? String)?.takeIf(::safeHeader)?.let { mapOf("header" to it, "events" to recent(events)) }
        /** The one validator for file and HTTP: whole seconds since start, then a fixed-vocabulary event (no offset in pre-header files). */
        fun safeLine(text: String) = safeEvent(text) || OFFSET.matches(text.substringBefore(' ', "")) && safeEvent(text.substringAfter(' '))
        private val OFFSET = Regex("[0-9]{1,9}")
        /** Absent off Linux (Windows simulation, test runners): the header then omits it. */
        private fun kernelUptime(): Long? = try {
            Files.readString(Path.of("/proc/uptime")).substringBefore('.').toLongOrNull()
        } catch (_: Exception) { null }
        private val CATEGORIES = BridgeFailureCode.entries.map { it.name }.toSet() +
            LinuxRadioFailureCode.entries.map { it.name } +
            dev.pocketphotobridge.printer.PrinterFailureCode.entries.map { it.name } +
            dev.pocketphotobridge.sony.Failure.entries.map { it.name } +
            setOf("CAMERA_ACK_PENDING", "QUEUE_FULL", "DUPLICATE_SUPPRESSED", "NATIVE_UNAVAILABLE")
        private val STAGES = setOf("DIAGNOSTIC_REDACTED", "SIMULATION_ONLY", "NO_PHYSICAL_PRINT", "LINUX_HARDWARE_VALIDATION_PENDING",
            "SERVICE_FAILED", "SERVICE_SETUP", "SERVICE_OPERATION", "SERVICE_STOPPED", "SERVICE_CAMERA_WAIT", "SERVICE_CAMERA_PRESENT", "SERVICE_CAMERA_ABSENT",
            "SERVICE_CONFIG_SAVED", "SERVICE_RADIO_CLEANUP_FAILED", "SERVICE_PAIRING_STARTED",
            "SERVICE_PAIRING_FINISHED", "SERVICE_PAIRING_FAILED", "BRIDGE_CAMERA_CONNECTING",
            "BRIDGE_IMAGE_PREPARING", "BRIDGE_PRINTER_CONNECTING", "BRIDGE_BOOT_QUEUE_DISCARDED", "BRIDGE_QUEUE_RECONCILED", "LINUX_SETUP_STARTED", "LINUX_SETUP_STOPPED",
            "LINUX_CAMERA_ACTIVATED", "LINUX_CAMERA_ACTIVATION_FAILED", "LINUX_CAMERA_CONNECTED", "LINUX_PRINTER_PAIRED", "LINUX_SPP_DISCOVERY_START", "LINUX_SPP_DISCOVERED") +
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
                    "count", "queued", "code", "native_error", "nm_exit", "nm_state", "nm_reason" -> pair[1].matches(Regex("[0-9]{1,8}")) &&
                        (pair[1].toLongOrNull() ?: Long.MAX_VALUE) <= when (pair[0]) {
                            "queued" -> 16; "code" -> dev.pocketphotobridge.printer.PrinterSession.MAX_CODE; "native_error" -> 4095; "nm_exit" -> 255
                            "nm_state", "nm_reason" -> 1000; else -> 64L * 1024 * 1024 }
                    else -> false
                }
            }
        }
    }
}
