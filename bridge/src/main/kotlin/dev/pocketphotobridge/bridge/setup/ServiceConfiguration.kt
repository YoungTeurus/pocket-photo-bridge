package dev.pocketphotobridge.bridge.setup

/** Immutable local credentials. Deliberately not a data class: no generated secret-bearing toString. */
class ServiceConfiguration(
    val cameraSsid: String,
    val cameraPassword: String,
    val printerAddress: String,
    val printerToken: String,
    val wifiInterface: String = "wlan0",
    val autoPrintEnabled: Boolean = false,
    val crop: Boolean = false,
) {
    init {
        require(Charsets.UTF_8.newEncoder().canEncode(cameraSsid) && cameraSsid.toByteArray(Charsets.UTF_8).size in 1..32 &&
            cameraSsid.none { it.isISOControl() }) { "Invalid camera SSID" }
        require(validWpaPassword(cameraPassword)) { "Invalid camera Wi-Fi password" }
        require(validPrinterAddress(printerAddress)) { "Invalid printer address" }
        require(Regex("[0-9a-fA-F]{24}").matches(printerToken)) { "Invalid printer token" }
        require(Regex("[A-Za-z][A-Za-z0-9_.-]{0,14}").matches(wifiInterface)) { "Invalid Wi-Fi interface" }
    }

    override fun toString() = "ServiceConfiguration(credentials=REDACTED, autoPrintEnabled=$autoPrintEnabled, crop=$crop)"
}

class SetupCredentials(val ssid: String, val hotspotPassword: String, val adminPassword: String) {
    override fun toString() = "SetupCredentials(REDACTED)"
}

internal fun validWpaPassword(value: String): Boolean =
    (value.length in 8..63 && value.all { it.code in 32..126 }) || Regex("[0-9a-fA-F]{64}").matches(value)

internal fun validPrinterAddress(value: String) = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(value)
