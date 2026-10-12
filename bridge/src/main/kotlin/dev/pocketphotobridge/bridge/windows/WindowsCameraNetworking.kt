package dev.pocketphotobridge.bridge.windows

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pocketphotobridge.bridge.CameraLink
import dev.pocketphotobridge.sony.CameraEndpoint
import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local selection only; never include interface identity in exported diagnostics. */
data class WindowsCameraCandidate(val interfaceIndex: Int, val displayName: String,
    val sourceAddress: String, val gateway: String)

object WindowsCameraNetworking {
    private const val MAX_DISCOVERY_BYTES = 65_536
    private const val DISCOVERY_TIMEOUT_MILLIS = 8_000L

    // No user input is interpolated into this command. Find-NetRoute asks Windows which
    // source and route it would actually choose for each configured gateway.
    private val discoveryScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding(${ '$' }false)
        ${'$'}result = @()
        foreach (${ '$' }configuration in @(Get-NetIPConfiguration)) {
            if (${ '$' }null -eq ${ '$' }configuration.NetAdapter -or ${ '$' }configuration.NetAdapter.Status -ne 'Up') { continue }
            foreach (${ '$' }source in @(${ '$' }configuration.IPv4Address)) {
                foreach (${ '$' }gateway in @(${ '$' }configuration.IPv4DefaultGateway)) {
                    try {
                        ${ '$' }selected = @(Find-NetRoute -LocalIPAddress ${ '$' }source.IPAddress -RemoteIPAddress ${ '$' }gateway.NextHop -ErrorAction Stop)
                        ${ '$' }routes = @(${ '$' }selected | Where-Object { ${ '$' }null -ne ${ '$' }_.DestinationPrefix })
                        ${ '$' }addresses = @(${ '$' }selected | Where-Object { ${ '$' }null -ne ${ '$' }_.IPAddress })
                        if (${ '$' }routes.Count -ne 1 -or ${ '$' }addresses.Count -ne 1) { continue }
                        ${ '$' }result += [PSCustomObject]@{
                            InterfaceIndex = [int]${ '$' }configuration.InterfaceIndex
                            DisplayName = [string]${ '$' }configuration.InterfaceAlias
                            SourceAddress = [string]${ '$' }source.IPAddress
                            PrefixLength = [int]${ '$' }source.PrefixLength
                            Gateway = [string]${ '$' }gateway.NextHop
                            RouteInterfaceIndex = [int]${ '$' }routes[0].InterfaceIndex
                            RouteNextHop = [string]${ '$' }routes[0].NextHop
                            RouteSourceAddress = [string]${ '$' }addresses[0].IPAddress
                        }
                        if (${ '$' }result.Count -gt 128) { exit 1 }
                    } catch { continue }
                }
            }
        }
        ConvertTo-Json -InputObject @(${ '$' }result) -Depth 3 -Compress
    """.trimIndent()

    fun candidates(): List<WindowsCameraCandidate> = candidates {}

    fun candidates(report: (String) -> Unit): List<WindowsCameraCandidate> {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return emptyList()
        if (Thread.currentThread().isInterrupted) throw ProtocolFailure(Failure.CANCELLED)
        val executable = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32",
            "WindowsPowerShell", "v1.0", "powershell.exe").toString()
        report("WINDOWS_CAMERA_DISCOVERY_START")
        val process = try {
            ProcessBuilder(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", discoveryScript)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) { report("WINDOWS_CAMERA_DISCOVERY_LAUNCH_FAILED"); throw ProtocolFailure(Failure.HTTP_ERROR) }
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "windows-camera-discovery").apply { isDaemon = true }
        }
        val output = reader.submit<ByteArray> {
            process.inputStream.use { it.readNBytes(MAX_DISCOVERY_BYTES + 1) }.also {
                if (it.size > MAX_DISCOVERY_BYTES) process.destroyForcibly()
            }
        }
        try {
            if (!process.waitFor(DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                report("WINDOWS_CAMERA_DISCOVERY_TIMEOUT"); throw ProtocolFailure(Failure.HTTP_ERROR)
            }
            val bytes = output.get(1, TimeUnit.SECONDS)
            report("WINDOWS_CAMERA_DISCOVERY_EXIT count=${process.exitValue().coerceAtLeast(0)}")
            report("WINDOWS_CAMERA_DISCOVERY_BYTES count=${bytes.size}")
            if (process.exitValue() != 0 || bytes.size > MAX_DISCOVERY_BYTES) throw ProtocolFailure(Failure.HTTP_ERROR)
            return parseCandidates(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProtocolFailure(Failure.CANCELLED)
        } catch (e: ProtocolFailure) { throw e }
        catch (_: Exception) { throw ProtocolFailure(Failure.HTTP_ERROR) }
        finally {
            process.destroyForcibly()
            output.cancel(true)
            reader.shutdownNow()
        }
    }

    fun connect(candidate: WindowsCameraCandidate): CameraLink =
        connectVerified(candidate, ::candidates, ::sourceIsAssigned)

    internal fun connectVerified(candidate: WindowsCameraCandidate, discover: () -> List<WindowsCameraCandidate>,
        assigned: (Int, String) -> Boolean, socketFactory: () -> Socket = { Socket() }): CameraLink {
        val verify = {
            if (candidate !in discover() || !assigned(candidate.interfaceIndex, candidate.sourceAddress))
                throw ProtocolFailure(Failure.UNSAFE_URL)
        }
        verify()
        val transport = BoundCameraHttpTransport(candidate, verifyRoute = verify, socketFactory = socketFactory)
        return CameraLink(CameraEndpoint(candidate.gateway), transport, transport::close)
    }

    private fun sourceIsAssigned(index: Int, address: String): Boolean = try {
        val network = NetworkInterface.getByIndex(index)
        network != null && network.isUp && !network.isLoopback &&
            Collections.list(network.inetAddresses).any { it is Inet4Address && it.hostAddress == address }
    } catch (_: Exception) { false }

    internal fun parseCandidates(json: String): List<WindowsCameraCandidate> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_DISCOVERY_BYTES) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray || root.asJsonArray.size() > 128) return emptyList()
            root.asJsonArray.mapNotNull { value ->
                if (!value.isJsonObject) return@mapNotNull null
                val row = value.asJsonObject
                val index = row.integer("InterfaceIndex") ?: return@mapNotNull null
                val name = row.string("DisplayName") ?: return@mapNotNull null
                val source = row.string("SourceAddress") ?: return@mapNotNull null
                val gateway = row.string("Gateway") ?: return@mapNotNull null
                val prefix = row.integer("PrefixLength") ?: return@mapNotNull null
                val sourceBytes = privateIpv4(source) ?: return@mapNotNull null
                val gatewayBytes = privateIpv4(gateway) ?: return@mapNotNull null
                if (index <= 0 || name.isBlank() || name.length > 256 || name.any { it.code < 32 || it.code == 127 } ||
                    row.integer("RouteInterfaceIndex") != index || row.string("RouteNextHop") != "0.0.0.0" ||
                    row.string("RouteSourceAddress") != source || prefix !in 1..30 || source == gateway) return@mapNotNull null
                val sourceNumber = sourceBytes.fold(0L) { number, byte -> (number shl 8) or (byte.toLong() and 255) }
                val gatewayNumber = gatewayBytes.fold(0L) { number, byte -> (number shl 8) or (byte.toLong() and 255) }
                val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
                val network = sourceNumber and mask
                val broadcast = network or (mask xor 0xffffffffL)
                if (gatewayNumber and mask != network || sourceNumber == network || sourceNumber == broadcast ||
                    gatewayNumber == network || gatewayNumber == broadcast) return@mapNotNull null
                WindowsCameraCandidate(index, name, source, gateway)
            }.distinct().sortedWith(compareBy({ it.interfaceIndex }, { it.sourceAddress }, { it.gateway }))
        } catch (_: Exception) { emptyList() }
    }

    private fun JsonObject.integer(name: String): Int? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("[0-9]+").matches(it.asString)
    }?.asString?.toIntOrNull()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    /** Parse literals ourselves so neither validation nor socket creation can invoke DNS. */
    internal fun privateIpv4(address: String): ByteArray? {
        if (!Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}").matches(address)) return null
        val octets = address.split('.').map { it.toInt() }
        if (octets.any { it > 255 } || !(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 192 && octets[1] == 168)) return null
        return octets.map { it.toByte() }.toByteArray()
    }
}
