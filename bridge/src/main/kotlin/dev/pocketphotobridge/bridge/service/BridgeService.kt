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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var bridge: ServiceBridge
    /** Counted per process run: a state error never ends the process, so it cannot restart and reset the count; per boot would need a new stored marker. */
    private var reopens = 0
    private var server: SetupServer? = null
    private val loop: ServiceLoop
    private var closed = false
    /** Set before the page closes: a request it let through must not reach the bridge reopened or closed after it. */
    @Volatile private var pageClosed = false

    init {
        if (devices is SimulatedServiceDevices && configuration.get() == null) {
            configStore.save(devices.configuration); configuration.set(devices.configuration)
        }
        bridge = openBridge()
        loop = ServiceLoop(policy, ::monotonicNow, object : ServiceLoopActions {
            override fun enterOperation() {
                // The setup listener closes before Linux switches the exclusive radio.
                if (!devices.simulation) { pageClosed = true; server?.close(); server = null }
                devices.stopSetup()
                // Routine, so not counted; a failure leaves a stand-in that operation reopens once, and the run stays up either way.
                synchronized(bridgeLock) { reopen(bridge.snapshot().queued, retry = true)?.let { reportReopen(it) } }
                diagnostics.emit("SERVICE_OPERATION")
            }
            override fun capture(trigger: SelectionTrigger) = step { it.capture(trigger) }.captureStep
            override fun printNext() = step { it.printNext() }.printStep
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

    /**
     * A state error in operation gets one reopen, which re-verifies the state from disk. A failed reopen leaves a stand-in
     * that reports the state error, so that occurrence gets no other; one left by the switch into operation is a new occurrence.
     * Never an exit: the unit's restart would loop through setup.
     */
    private fun step(action: (ServiceBridge) -> ServiceSnapshot): ServiceSnapshot = synchronized(bridgeLock) {
        val outcome = action(bridge)
        if (outcome.state != ServiceState.STATE_ERROR || (bridge as? StateErrorBridge)?.retry == false || reopens >= MAX_STATE_REOPENS) return outcome
        // A stop's interrupt can surface as a state error; the stop is not storage damage and must not wait on a reopen.
        if (!running.get() || Thread.currentThread().isInterrupted) return outcome
        reopens++
        val failure = reopen(outcome.queued)
        if (failure == null) diagnostics.emit("SERVICE_STATE_REOPENED reason=OK count=$reopens") else reportReopen(failure)
        outcome
    }

    /** Closes the bridge and opens it again from disk, under [bridgeLock]; a failure leaves the stand-in and is returned. */
    private fun reopen(queued: Int, retry: Boolean = false): Exception? {
        try { bridge.close() } catch (_: Exception) { }
        return try {
            bridge = openBridge().also { reopened ->
                if (reopened.snapshot().state == ServiceState.STATE_ERROR) { reopened.close(); throw BridgeFailure(BridgeFailureCode.PERSISTENCE_ERROR) }
            }
            null
        } catch (failure: Exception) { bridge = StateErrorBridge(queued, retry); failure }
    }
    private fun reportReopen(failure: Exception) =
        diagnostics.emit("SERVICE_STATE_REOPENED reason=FAILED category=${BridgeService.category(failure)} count=$reopens")

    fun run() {
        worker = Thread.currentThread()
        // Registered before the hotspot retries, so a stop at any point cleans up the radio.
        val shutdown = Thread(::stop, "bridge-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val initialInterface = configuration.get()?.wifiInterface ?: System.getenv("POCKET_BRIDGE_WIFI_INTERFACE") ?: "wlan0"
            val address = startSetup(initialInterface)
            setupInterface = initialInterface
            // The setup window counts from the moment the hotspot is up, however many attempts that took.
            policy.beginWindow(monotonicNow())
            server = SetupServer(address, credentials.adminPassword, backend()).also { it.start() }
            diagnostics.emit("SERVICE_SETUP")
            output.println("Setup page: http://${address.address.hostAddress}:${address.port}/")
            output.println("Use the administrator password shown during installation or by --make-bootstrap in a private local terminal.")
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val config = configuration.get()
                // Enabled implies a saved configuration, so the scan always has one.
                loop.tick(monotonicNow(), config?.autoPrintEnabled == true && !pairing.pending) { devices.cameraPresent(requireNotNull(config)) }
                Thread.sleep(1_000)
            }
        } catch (_: InterruptedException) {
        } catch (failure: Exception) {
            // A radio step refused because of the stop is part of the stop, not a failure.
            if (running.get()) throw failure
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch (_: IllegalStateException) { }
            // A hotspot that never came up and was not stopped ends as a failure, which the caller reports.
            if (setupInterface != null || !running.get()) diagnostics.emit("SERVICE_STOPPED")
            // The bounded command runner refuses an interrupted thread, so the radio cleanup runs with the flag clear.
            val interrupted = Thread.interrupted()
            // The page and a pairing in progress go first: pairing holds the radio, which the cleanup needs.
            try { close() } finally {
                try { devices.close() } catch (_: Exception) { diagnostics.emit("SERVICE_RADIO_CLEANUP_FAILED") }
                stopped.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    @Volatile private var worker: Thread? = null
    private val stopped = CountDownLatch(1)
    /** What a termination signal does: the JVM halts when this returns, so it waits, bounded, for the recorded stop and radio cleanup. */
    internal fun stop() {
        running.set(false); worker?.interrupt()
        stopped.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** The interface the setup hotspot is up on. Serving the page proves it, so a save keeps it even if the system check says no. */
    @Volatile private var setupInterface: String? = null

    /** A slow radio cold start is retried; the unit restarts the process after the limit. */
    private fun startSetup(wifiInterface: String): InetSocketAddress {
        val first = devices.now()
        var failures = 0
        var last: LinuxRadioFailureCode? = null
        var unreported = false
        fun report() { diagnostics.emit("SERVICE_SETUP_FAILED category=$last count=$failures"); unreported = false }
        while (true) {
            if (failures > 0) diagnostics.hold()
            try {
                val address = devices.startSetup(credentials, wifiInterface)
                diagnostics.release(keep = true)
                if (unreported) report()
                return address
            } catch (failure: LinuxRadioFailure) {
                // An attempt the stop cancelled is the stop, not a failed attempt; the count so far is written first.
                if (failure.code == LinuxRadioFailureCode.CANCELLED && !running.get()) {
                    diagnostics.release(keep = true); if (unreported) report(); throw failure
                }
                // A retry's own lines stay only when it fails differently from the attempt before it.
                val repeated = failure.code == last
                diagnostics.release(keep = !repeated)
                failures++; last = failure.code; unreported = true
                // No attempt starts at or after the limit; one already running is allowed to finish.
                val giveUp = devices.now() + SETUP_RETRY_PAUSE_MS - first >= SETUP_RETRY_LIMIT_MS
                if (!repeated || Integer.bitCount(failures) == 1 || giveUp) report()
                if (giveUp) throw failure
            } finally { diagnostics.release(keep = true) }
            // A stop usually lands in the pause, since a blocked radio fails fast; the count so far is written first.
            try { devices.pause(SETUP_RETRY_PAUSE_MS) } catch (stop: InterruptedException) { if (unreported) report(); throw stop }
        }
    }

    internal fun backend() = object : SetupBackend {
        override fun status(): Map<String, Any> {
            val snapshot = synchronized(bridgeLock) { check(!pageClosed) { "SETUP_REQUIRED" }; bridge.snapshot() }
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
        override fun wifiInterfaceExists(name: String) = name == setupInterface || devices.wifiInterfaceExists(name)
        override fun startOperation() {
            check(!pairing.pending) { "PAIRING_IN_PROGRESS" }
            check(requiredConfiguration().autoPrintEnabled) { "AUTOMATIC_PRINTING_DISABLED" }
            policy.requestOperation()
        }
        override fun acknowledgeCheckedPrinter() {
            synchronized(bridgeLock) { check(!pageClosed) { "SETUP_REQUIRED" }; bridge.acknowledgeCheckedPrinter() }
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
        closed = true; running.set(false); pageClosed = true
        try { server?.close() } finally { pairing.close(); synchronized(bridgeLock) { bridge.close() } }
    }
    private fun monotonicNow() = System.nanoTime() / 1_000_000
    companion object {
        const val SETUP_RETRY_PAUSE_MS = 5_000L
        const val SETUP_RETRY_LIMIT_MS = 120_000L
        /** Reopens after a runtime state error per process run, one per occurrence. */
        const val MAX_STATE_REOPENS = 3
        /** Longest wait for the stop's radio cleanup; below the unit's TimeoutStopSec=45 so the exit is clean, not killed. */
        const val STOP_WAIT_MS = 30_000L
    }
}

/** Stands in after a failed reopen: reports the state error and touches no storage; [retry] if operation may reopen it once. */
private class StateErrorBridge(queued: Int, val retry: Boolean) : ServiceBridge {
    private val error = ServiceSnapshot(ServiceState.STATE_ERROR, queued, "PERSISTENCE_ERROR")
    override fun capture(trigger: SelectionTrigger) = error
    override fun printNext() = error
    override fun snapshot() = error
    override fun acknowledgeCheckedPrinter() = error
    override fun close() { }
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
                // Endless restarts would rotate out the run before the outage; back-to-back failed starts share generation 1.
                val repeated = failedStart(state.resolve(FILE))
                if (repeated == null || repeated != failedStart(generation(1))) {
                    Files.deleteIfExists(generation(GENERATIONS))
                    for (n in GENERATIONS - 1 downTo 1)
                        if (Files.exists(generation(n), NOFOLLOW_LINKS)) Files.move(generation(n), generation(n + 1), ATOMIC_MOVE)
                }
                if (Files.exists(state.resolve(FILE), NOFOLLOW_LINKS))
                    Files.move(state.resolve(FILE), generation(1), ATOMIC_MOVE, REPLACE_EXISTING)
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
    /**
     * What a run that only failed to start repeats, or null for a run worth keeping. Hotspot retries match whatever their
     * category; any other failed start matches only the same failure, so the first of a different kind is kept.
     */
    private fun failedStart(file: Path): String? {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 65536) return null
        // A run torn by a power cut may not be text; it is rotated like any other run.
        val events = try { Files.readAllLines(file) } catch (_: Exception) { return null }.drop(1).map { it.substringAfter(' ') }
        if (!events.all { it.substringBefore(' ') in RETRY_STAGES }) return null
        return if (events.any { it.substringBefore(' ') == "SERVICE_SETUP_FAILED" }) "SERVICE_SETUP_FAILED"
            else events.lastOrNull { it.substringBefore(' ') == "SERVICE_FAILED" }
    }
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
        // A file channel closes on a pending interrupt, as a stop leaves on the worker and pairing threads; it is restored after.
        val interrupted = Thread.interrupted()
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
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    companion object {
        /** Categories that name an outcome, not a failure: a re-check finding a known photo, or a transfer kept before its acknowledgement. */
        private val NOT_FAILURES = setOf("DUPLICATE_SUPPRESSED", "CAMERA_ACK_PENDING")
        private const val FILE = "diagnostics.txt"
        private val RETRY_STAGES = setOf("LINUX_HARDWARE_VALIDATION_PENDING", "LINUX_SETUP_STARTED", "LINUX_SETUP_STOPPED",
            "SERVICE_SETUP_FAILED", "SERVICE_FAILED")
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
            "SERVICE_FAILED", "SERVICE_SETUP", "SERVICE_SETUP_FAILED", "SERVICE_OPERATION", "SERVICE_STOPPED", "SERVICE_CAMERA_WAIT", "SERVICE_CAMERA_PRESENT", "SERVICE_CAMERA_ABSENT",
            "SERVICE_CONFIG_SAVED", "SERVICE_RADIO_CLEANUP_FAILED", "SERVICE_STATE_REOPENED", "SERVICE_PAIRING_STARTED",
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
