package dev.pocketphotobridge.bridge

import dev.pocketphotobridge.printer.PrinterTransport
import dev.pocketphotobridge.sony.CameraEndpoint
import dev.pocketphotobridge.sony.CameraTransport
import java.io.File

/** Opaque adapter generation. Never exported; the journal stores only its digest. */
data class SelectionTrigger(val id: String)

class CameraLink(val endpoint: CameraEndpoint, val transport: CameraTransport,
    private val release: () -> Unit = {}) : AutoCloseable {
    override fun close() = release()
}

fun interface CameraWifiConnector { fun connect(trigger: SelectionTrigger): CameraLink }
fun interface PrinterConnector { fun connect(): PrinterTransport }

interface PrintImagePreparer {
    fun validateSource(source: File)
    fun prepare(source: File, crop: Boolean = false): ByteArray
}

enum class BridgeFailureCode {
    SSID_MISSING, CAMERA_OFFLINE, CAMERA_TIMEOUT, PRINTER_OFFLINE, INVALID_IMAGE,
    PERSISTENCE_ERROR, INSTANCE_RUNNING, CANCELLED, HARDWARE_NOT_IMPLEMENTED, ARGUMENT_ERROR, IO_ERROR
}

/** Fixed category only: never let adapter error text become a diagnostic. */
class BridgeFailure(val code: BridgeFailureCode) : RuntimeException(code.name)

enum class BridgeOutcome {
    PRINTED, DUPLICATE_SUPPRESSED, UNKNOWN_OUTCOME, CAMERA_FAILED, PRINTER_FAILED,
    IMAGE_FAILED, STATE_FAILED, CANCELLED
}

data class BridgeResult(val outcome: BridgeOutcome, val category: String? = null)
