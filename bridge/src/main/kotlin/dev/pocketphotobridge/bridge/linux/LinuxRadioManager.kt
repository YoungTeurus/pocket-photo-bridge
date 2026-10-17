package dev.pocketphotobridge.bridge.linux

import com.google.gson.JsonParser
import dev.pocketphotobridge.bridge.CameraLink
import dev.pocketphotobridge.bridge.windows.BoundCameraHttpTransport
import dev.pocketphotobridge.bridge.windows.WindowsCameraCandidate
import dev.pocketphotobridge.sony.CameraEndpoint
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** Identity is displayed only by the authenticated local UI. */
data class LinuxPrinterDevice(val address: String, val name: String)

/** Exclusive single-radio setup/camera modes. All credentials are in temporary root-private NM keyfiles. */
class LinuxRadioManager internal constructor(private val stateDir: Path, private val wifiInterface: String,
    private val report: (String) -> Unit, private val runner: LinuxCommandRunner, private val profileDir: Path) : AutoCloseable {
    constructor(stateDir: Path, wifiInterface: String = "wlan0", report: (String) -> Unit = {}) :
        this(stateDir, wifiInterface, report, BoundedLinuxCommandRunner(), Path.of("/run/NetworkManager/system-connections"))

    private val id = MessageDigest.getInstance("SHA-256").digest(stateDir.toAbsolutePath().normalize().toString().toByteArray())
        .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal val setupProfileName = "pocket-photo-bridge-$id-setup.nmconnection"
    private val cameraProfileName = "pocket-photo-bridge-$id-camera.nmconnection"
    private val setupUuid = UUID.nameUUIDFromBytes("ppb:$id:setup".toByteArray()).toString()
    private val cameraUuid = UUID.nameUUIDFromBytes("ppb:$id:camera".toByteArray()).toString()
    private var setupActive = false
    private var cameraActive = false
    private var cameraLink: CameraLink? = null
    private var closed = false

    init {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,14}").matches(wifiInterface)) radioFail(LinuxRadioFailureCode.INVALID_INPUT)
    }

    @Synchronized fun startSetup(ssid: String, password: String): InetAddress {
        active(); validateWifi(ssid, password)
        stopCamera()
        stopSetup()
        deactivate("device", "disconnect", wifiInterface)
        val profile = profileDir.resolve(setupProfileName)
        try {
            writeProfile(profile, profileText(setupUuid, ssid, password, hotspot = true))
            nm("connection", "load", profile.toString())
            nm("connection", "up", "uuid", setupUuid, "ifname", wifiInterface)
            setupActive = true
            configureUweSetupSecurity()
            reportRadio(report, "LINUX_SETUP_STARTED")
            return InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 50, 1))
        } catch (failure: Throwable) {
            // NM already owns an active AP if security adjustment fails. Keep
            // ownership until teardown succeeds; deleting a keyfile is insufficient.
            try { stopSetup() } catch (cleanup: Throwable) { throw safeRadioFailure(cleanup) }
            throw safeRadioFailure(failure)
        }
    }

    @Synchronized fun stopSetup() {
        if (setupActive) {
            // Do not permit a transition to camera if NetworkManager could not stop the AP.
            deactivate("connection", "down", "uuid", setupUuid)
            setupActive = false
        }
        cleanupProfile(profileDir.resolve(setupProfileName), setupUuid)
        reportRadio(report, "LINUX_SETUP_STOPPED")
    }

    @Synchronized fun isCameraPresent(ssid: String): Boolean {
        active(); validateSsid(ssid)
        val scan = nm("-t", "-e", "yes", "-f", "SSID", "device", "wifi", "list", "ifname", wifiInterface, "--rescan", "yes")
        return scan.lineSequence().map(::unescapeNm).any { it == ssid }
    }

    @Synchronized fun connectCamera(ssid: String, password: String): CameraLink {
        active(); validateWifi(ssid, password)
        stopSetup(); stopCamera()
        deactivate("device", "disconnect", wifiInterface)
        val profile = profileDir.resolve(cameraProfileName)
        try {
            writeProfile(profile, profileText(cameraUuid, ssid, password, hotspot = false))
            nm("connection", "load", profile.toString())
            nm("connection", "up", "uuid", cameraUuid, "ifname", wifiInterface)
            cameraActive = true
            val candidate = cameraCandidate()
            verifyRoute(candidate)
            val transport = BoundCameraHttpTransport(candidate, verifyRoute = { verifyRoute(candidate) })
            val link = CameraLink(CameraEndpoint(candidate.gateway), transport) {
                transport.close()
                synchronized(this) {
                    if (cameraLink?.transport === transport) { cameraLink = null; stopCamera() }
                }
            }
            cameraLink = link
            reportRadio(report, "LINUX_CAMERA_CONNECTED")
            return link
        } catch (failure: Throwable) {
            stopCamera(); cleanupProfile(profile, cameraUuid)
            throw safeRadioFailure(failure)
        }
    }

    @Synchronized fun pairedPrinters(): List<LinuxPrinterDevice> {
        active()
        val output = command(listOf(BLUETOOTHCTL, "devices", "Paired"), 15_000)
        return output.lineSequence().mapNotNull { line ->
            val match = Regex("Device ((?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})(?: (.*))?").matchEntire(line.trim()) ?: return@mapNotNull null
            val address = try { bluetoothAddress(match.groupValues[1]) } catch (_: LinuxRadioFailure) { return@mapNotNull null }
            val name = match.groupValues[2].filter { it.code >= 32 && it.code != 127 }.take(128)
            LinuxPrinterDevice(address, name)
        }.distinctBy { it.address }.take(128).toList()
    }

    @Synchronized fun pairPrinter(address: String) {
        active(); val validated = bluetoothAddress(address)
        command(listOf(BLUETOOTHCTL, "power", "on"), 15_000)
        if (!isPaired(validated)) {
            // BlueZ Pair requires a discovered device proxy. Keep classic discovery alive for its bounded tool window.
            command(listOf(BLUETOOTHCTL, "--timeout", "10", "scan", "bredr"), 15_000)
            // A piped pair + quit would exit before the asynchronous callback. Verify state after the tool window.
            command(listOf(BLUETOOTHCTL, "--timeout", "30", "--agent", "NoInputNoOutput", "pair", validated), 35_000)
            if (!isPaired(validated)) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        }
        command(listOf(BLUETOOTHCTL, "trust", validated), 15_000)
        reportRadio(report, "LINUX_PRINTER_PAIRED")
    }

    private fun isPaired(address: String): Boolean {
        val result = try { runner.run(listOf(BLUETOOTHCTL, "info", address), 15_000) }
        catch (failure: Throwable) { throw safeRadioFailure(failure) }
        if (result.output.toByteArray(Charsets.UTF_8).size > 65_536) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        // An unknown device may return nonzero before discovery; do not export the tool's error text.
        return result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "Paired: yes" }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { stopCamera() } finally { stopSetup() }
    }

    private fun active() { checkRadioInterrupted(); if (closed) radioFail(LinuxRadioFailureCode.CLOSED) }
    private fun stopCamera() {
        val link = cameraLink; cameraLink = null
        link?.close()
        if (cameraActive) {
            deactivate("connection", "down", "uuid", cameraUuid)
            cameraActive = false
        }
        cleanupProfile(profileDir.resolve(cameraProfileName), cameraUuid)
    }

    private fun cameraCandidate(): WindowsCameraCandidate {
        val output = nm("-t", "-e", "yes", "-f", "IP4.ADDRESS,IP4.GATEWAY,DHCP4.OPTION,GENERAL.STATE", "device", "show", wifiInterface)
        val fields = output.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':'); if (colon < 0) null else line.substring(0, colon) to unescapeNm(line.substring(colon + 1))
        }.toList()
        if (fields.singleOrNull { it.first == "GENERAL.STATE" }?.second?.startsWith("100") != true) radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        val addresses = fields.filter { it.first.startsWith("IP4.ADDRESS[") }.map { it.second }
        val gateways = fields.filter { it.first == "IP4.GATEWAY" && it.second.isNotBlank() }.map { it.second }
        val dhcpGateways = fields.filter { it.first.startsWith("DHCP4.OPTION[") }.mapNotNull {
            Regex("routers = ([0-9.]+)").matchEntire(it.second)?.groupValues?.get(1)
        }
        val gateway = (gateways + dhcpGateways).distinct().singleOrNull() ?: radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        val gatewayNumber = privateAddress(gateway) ?: radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        val eligible = addresses.mapNotNull { text ->
            val parts = text.split('/'); if (parts.size != 2) return@mapNotNull null
            val prefix = parts[1].toIntOrNull()?.takeIf { it in 1..30 } ?: return@mapNotNull null
            val source = privateAddress(parts[0]) ?: return@mapNotNull null
            val mask = (-1L shl (32 - prefix)) and 0xffffffffL
            val network = source and mask; val broadcast = network or (mask.inv() and 0xffffffffL)
            if (gatewayNumber and mask != network || gatewayNumber == network || gatewayNumber == broadcast ||
                source == network || source == broadcast || source == gatewayNumber) return@mapNotNull null
            parts[0]
        }.distinct()
        val source = eligible.singleOrNull() ?: radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        return WindowsCameraCandidate(0, wifiInterface, source, gateway)
    }

    private fun verifyRoute(candidate: WindowsCameraCandidate) {
        active()
        val current = cameraCandidate()
        if (current.sourceAddress != candidate.sourceAddress || current.gateway != candidate.gateway) radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        val output = command(listOf("/usr/sbin/ip", "-j", "-4", "route", "get", candidate.gateway,
            "from", candidate.sourceAddress), 10_000)
        try {
            val route = JsonParser.parseString(output).asJsonArray.single().asJsonObject
            if (route["dev"]?.asString != wifiInterface || route["dst"]?.asString != candidate.gateway ||
                (route["from"]?.asString ?: route["prefsrc"]?.asString) != candidate.sourceAddress ||
                route.has("gateway") || route["type"]?.asString?.let { it != "unicast" } == true)
                radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE)
        } catch (failure: LinuxRadioFailure) { throw failure }
        catch (_: Exception) { radioFail(LinuxRadioFailureCode.CAMERA_ROUTE_UNSAFE) }
    }

    /** NetworkManager rejects a normal already-inactive disconnect. Confirm state, never parse raw error text. */
    private fun deactivate(vararg arguments: String) {
        val result = runner.run(listOf("/usr/bin/nmcli", "--wait", "15") + arguments, 20_000)
        if (result.exitCode == 0) return
        val state = nm("-g", "GENERAL.STATE", "device", "show", wifiInterface).trim()
        if (!Regex("30(?: \\([^\\r\\n]*\\))?").matches(state)) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
    }
    private fun nm(vararg arguments: String): String = command(listOf("/usr/bin/nmcli", "--wait", "15") + arguments, 20_000)

    /** UWE5622 firmware rejects AP association when NM advertises PSK-SHA256,
     * even with PMF disabled. Only alter our current AP, before announcing it. */
    private fun configureUweSetupSecurity() {
        val driver = runner.run(listOf("/usr/bin/readlink", "-f", "/sys/class/net/$wifiInterface/device/driver"), 5_000)
        if (driver.exitCode != 0 || driver.output.trim().substringAfterLast('/') !in setOf("unisoc_wifi", "sprdwl_ng")) return
        if (nm("-g", "GENERAL.CON-UUID", "device", "show", wifiInterface).trim() != setupUuid)
            radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        fun wpa(vararg arguments: String) = command(listOf("/usr/sbin/wpa_cli", "-i", wifiInterface) + arguments, 5_000).trim()
        val current = wpa("list_networks").lineSequence().map { it.split('\t') }
            .filter { it.size == 4 && it[3] == "[CURRENT]" }.toList().singleOrNull()
            ?: radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        val network = current[0].takeIf { Regex("0|[1-9][0-9]{0,8}").matches(it) }
            ?: radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        if (wpa("get_network", network, "mode") != "2") radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        val keyMgmt = wpa("get_network", network, "key_mgmt").split(Regex("\\s+")).toSet()
        if (keyMgmt == setOf("WPA-PSK")) return
        if (keyMgmt != setOf("WPA-PSK", "WPA-PSK-SHA256")) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        for (arguments in listOf(listOf("set_network", network, "key_mgmt", "WPA-PSK"),
            listOf("disable_network", network), listOf("enable_network", network))) {
            if (wpa(*arguments.toTypedArray()) != "OK") radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        }
        if (wpa("get_network", network, "key_mgmt") != "WPA-PSK") radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
    }

    private fun command(arguments: List<String>, timeout: Long): String {
        checkRadioInterrupted()
        val result = try { runner.run(arguments, timeout) } catch (failure: Throwable) { throw safeRadioFailure(failure) }
        if (result.exitCode != 0) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        if (result.output.toByteArray(Charsets.UTF_8).size > 65_536) radioFail(LinuxRadioFailureCode.COMMAND_FAILED)
        return result.output
    }

    private fun writeProfile(path: Path, text: String) {
        try {
            var parent: Path? = profileDir.toAbsolutePath()
            while (parent != null) { if (Files.isSymbolicLink(parent)) radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE); parent = parent.parent }
            Files.createDirectories(profileDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            if (!Files.isDirectory(profileDir, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE)
            val permissions = Files.getPosixFilePermissions(profileDir)
            if (permissions.any { it.name in setOf("GROUP_WRITE", "OTHERS_WRITE") }) radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE)
            val temp = Files.createTempFile(profileDir, ".ppb-$id-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.writeString(temp, text)
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } catch (failure: LinuxRadioFailure) { throw failure }
        catch (_: Exception) { radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE) }
    }

    private fun cleanupProfile(path: Path, uuid: String) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path)) radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE)
        // Delete by adapter-owned UUID; raw failures are discarded, but keyfiles are always removed.
        try { runner.run(listOf("/usr/bin/nmcli", "--wait", "5", "connection", "delete", "uuid", uuid), 10_000) }
        catch (_: Exception) { } catch (_: LinkageError) { }
        try { Files.deleteIfExists(path) } catch (_: Exception) { radioFail(LinuxRadioFailureCode.PROFILE_UNSAFE) }
    }

    private fun profileText(uuid: String, ssid: String, password: String, hotspot: Boolean) = """
        [connection]
        id=pocket-photo-bridge-$id-${if (hotspot) "setup" else "camera"}
        uuid=$uuid
        type=wifi
        interface-name=$wifiInterface
        autoconnect=false

        [wifi]
        mode=${if (hotspot) "ap" else "infrastructure"}
        ssid=${ssid.toByteArray(Charsets.UTF_8).joinToString(";") { (it.toInt() and 255).toString() }};

        [wifi-security]
        key-mgmt=wpa-psk
        psk=${escapeKeyfile(password)}
        psk-flags=0
        ${if (hotspot) "pmf=1\n        proto=rsn;\n        pairwise=ccmp;\n        group=ccmp;" else ""}

        [ipv4]
        method=${if (hotspot) "shared" else "auto"}
        ${if (hotspot) "address1=192.168.50.1/24" else ""}
        never-default=true
        ignore-auto-dns=true

        [ipv6]
        method=disabled
    """.trimIndent() + "\n"

    private companion object {
        const val BLUETOOTHCTL = "/usr/bin/bluetoothctl"
        fun validateSsid(ssid: String) {
            if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32 || ssid.any { it.code < 32 || it.code == 127 }) radioFail(LinuxRadioFailureCode.INVALID_INPUT)
        }
        fun validateWifi(ssid: String, password: String) {
            validateSsid(ssid)
            if (!(password.toByteArray(Charsets.UTF_8).size in 8..63 && password.all { it.code in 32..126 }) &&
                !Regex("[0-9A-Fa-f]{64}").matches(password)) radioFail(LinuxRadioFailureCode.INVALID_INPUT)
        }
        fun escapeKeyfile(value: String): String = value.flatMap { c -> when (c) { '\\' -> "\\\\"; ' ' -> "\\s"; else -> c.toString() }.toList() }.joinToString("")
        fun unescapeNm(value: String): String {
            val out = StringBuilder(); var index = 0
            while (index < value.length) {
                val c = value[index++]
                if (c == '\\') { if (index >= value.length || value[index] !in charArrayOf(':', '\\')) radioFail(LinuxRadioFailureCode.COMMAND_FAILED); out.append(value[index++]) }
                else out.append(c)
            }
            return out.toString()
        }
        fun privateAddress(value: String): Long? {
            val parts = value.split('.'); if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }; if (octets.any { it !in 0..255 }) return null
            if (!(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168)) return null
            return octets.fold(0L) { value, octet -> value * 256 + octet }
        }
        fun safeRadioFailure(failure: Throwable): Throwable = when (failure) {
            is LinuxRadioFailure -> failure
            is Exception, is LinkageError -> LinuxRadioFailure(LinuxRadioFailureCode.COMMAND_FAILED)
            else -> failure
        }
    }
}
