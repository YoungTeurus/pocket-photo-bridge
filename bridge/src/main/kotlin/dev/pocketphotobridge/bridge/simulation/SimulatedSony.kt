package dev.pocketphotobridge.bridge.simulation

import dev.pocketphotobridge.bridge.*
import dev.pocketphotobridge.sony.*
import java.io.ByteArrayInputStream
import java.io.IOException

enum class SonyFault { NONE, MISSING_SSID, OFFLINE, TIMEOUT, EMPTY_SELECTION, INTERRUPTED }

/** An offline wire device: no HTTP client, sockets, SSID scans or real camera identity. */
class SimulatedSony(jpeg: ByteArray, private val fault: SonyFault = SonyFault.NONE) : CameraWifiConnector {
    private val original = jpeg.copyOf()
    var transferStarts: Int = 0
        private set
    var completedTransfers: Int = 0
        private set
    var downloads: Int = 0
        private set

    override fun connect(trigger: SelectionTrigger): CameraLink {
        when (fault) {
            SonyFault.MISSING_SSID -> throw BridgeFailure(BridgeFailureCode.SSID_MISSING)
            SonyFault.OFFLINE -> throw BridgeFailure(BridgeFailureCode.CAMERA_OFFLINE)
            SonyFault.TIMEOUT -> throw BridgeFailure(BridgeFailureCode.CAMERA_TIMEOUT)
            else -> Unit
        }
        val endpoint = CameraEndpoint("192.168.122.1")
        val connection = Connection(endpoint)
        return CameraLink(endpoint, connection, connection::close)
    }

    private inner class Connection(private val endpoint: CameraEndpoint) : CameraTransport {
        private var closed = false
        private var described = false
        private var actionsRead = false
        private var started = false
        private var selected = false
        private var downloaded = false
        private var ended = false
        private val bodies = mutableSetOf<ByteArrayInputStream>()

        override fun execute(request: CameraRequest): CameraResponse {
            if (closed) throw IOException("SIMULATED_CAMERA_CLOSED")
            if (request.uri != endpoint.resolve(request.uri.path) || request.body?.contains("PhotoRoot") == true)
                reject()
            val bytes = when {
                request.uri.path == "/DmsDescPush.xml" && request.action == null && request.body == null -> {
                    described = true
                    """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><serviceList>
                        <service><serviceType>$PUSH</serviceType><controlURL>/push</controlURL><SCPDURL>/actions.xml</SCPDURL></service>
                        <service><serviceType>$CONTENT</serviceType><controlURL>/content</controlURL><SCPDURL>/content.xml</SCPDURL></service>
                        </serviceList></device></root>""".toByteArray()
                }
                request.uri.path == "/actions.xml" && request.action == null && request.body == null && described -> {
                    actionsRead = true
                    // The real parser must distinguish action names from nested argument names.
                    """<scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>
                        <action><name>X_TransferStart</name></action>
                        <action><name>X_TransferEnd</name><argumentList><argument><name>ErrCode</name>
                        <direction>in</direction><relatedStateVariable>A_ARG_TYPE_ErrCode</relatedStateVariable>
                        </argument></argumentList></action></actionList></scpd>""".toByteArray()
                }
                soapMatches(request, "/push", PUSH, "X_TransferStart") && actionsRead && !started -> {
                    started = true
                    transferStarts++
                    envelope(PUSH, "X_TransferStartResponse").toByteArray()
                }
                soapMatches(request, "/content", CONTENT, "Browse", BROWSE_ARGUMENTS) && started && !selected -> {
                    selected = true
                    selectionResponse().toByteArray()
                }
                request.uri.path == "/original.jpg" && request.action == null && request.body == null &&
                    selected && !downloaded && fault != SonyFault.EMPTY_SELECTION -> {
                    downloads++
                    downloaded = fault != SonyFault.INTERRUPTED
                    val payload = if (fault == SonyFault.INTERRUPTED) original.copyOf(maxOf(0, original.size - 2)) else original
                    return response(payload, original.size.toLong())
                }
                soapMatches(request, "/push", PUSH, "X_TransferEnd", "<ErrCode>0</ErrCode>") && downloaded && !ended -> {
                    ended = true
                    completedTransfers++
                    envelope(PUSH, "X_TransferEndResponse").toByteArray()
                }
                else -> reject()
            }
            return response(bytes, bytes.size.toLong())
        }

        private fun response(bytes: ByteArray, length: Long): CameraResponse {
            val stream = object : ByteArrayInputStream(bytes) {
                private var released = false
                override fun read(): Int {
                    if (released || closed) throw IOException("SIMULATED_CAMERA_CLOSED")
                    return super.read()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (released || closed) throw IOException("SIMULATED_CAMERA_CLOSED")
                    return super.read(buffer, offset, length)
                }
                override fun close() { released = true; super.close() }
            }
            bodies += stream
            return CameraResponse(200, stream, length) { bodies -= stream }
        }

        fun close() {
            if (closed) return
            closed = true
            bodies.forEach { it.close() }
            bodies.clear()
        }

        private fun selectionResponse(): String {
            val item = if (fault == SonyFault.EMPTY_SELECTION) "" else """<item id="synthetic-selection">
                <dc:title>synthetic.jpg</dc:title>
                <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" size="${original.size + 1}">http://192.168.122.1:64321/thumb.jpg</res>
                <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" size="${original.size}">http://192.168.122.1:64321/original.jpg</res>
                </item>"""
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">$item</DIDL-Lite>"
            val count = if (item.isEmpty()) 0 else 1
            return envelope(CONTENT, "BrowseResponse", "<Result>${escape(didl)}</Result><NumberReturned>$count</NumberReturned><TotalMatches>$count</TotalMatches><UpdateID>1</UpdateID>")
        }
    }

    private fun soapMatches(request: CameraRequest, path: String, type: String, action: String, arguments: String = "") =
        request.uri.path == path && request.action == "$type#$action" && request.body == envelope(type, action, arguments)

    private fun reject(): Nothing = throw ProtocolFailure(Failure.WRONG_MODE)

    companion object {
        private const val PUSH = "urn:schemas-sony-com:service:XPushList:1"
        private const val CONTENT = "urn:schemas-upnp-org:service:ContentDirectory:1"
        private const val BROWSE_ARGUMENTS = "<ObjectID>PushRoot</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>32</RequestedCount><SortCriteria></SortCriteria>"
        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        private fun envelope(type: String, action: String, arguments: String = "") =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$type\">$arguments</u:$action></s:Body></s:Envelope>"
    }
}
