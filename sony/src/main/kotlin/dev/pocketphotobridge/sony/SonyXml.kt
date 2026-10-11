package dev.pocketphotobridge.sony

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal data class Service(val type: String, val control: String, val description: String)
internal data class Resource(val url: String, val size: Long, val protocol: String)
internal data class SelectedItem(val id: String, val title: String, val resources: List<Resource>)
internal data class BrowsePage(val containers: List<String>, val items: List<SelectedItem>, val returned: Int, val total: Int)

/** No raw XML is ever included in an exception or sent to a logger. */
internal object SonyXml {
    const val PUSH = "urn:schemas-sony-com:service:XPushList:1"
    const val CONTENT = "urn:schemas-upnp-org:service:ContentDirectory:1"

    private fun parse(xml: String): Document {
        if (xml.length > 2 * 1024 * 1024 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
            throw ProtocolFailure(Failure.INVALID_XML)
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw ProtocolFailure(Failure.INVALID_XML) }
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) { throw e }
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            return builder.parse(InputSource(StringReader(xml)))
        } catch (_: Exception) { throw ProtocolFailure(Failure.INVALID_XML) }
    }

    private fun Element.elements(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }
    private fun Element.text(name: String): String = elements(name).singleOrNull()?.textContent?.trim()
        ?: throw ProtocolFailure(Failure.INVALID_XML)

    private fun Element.childText(name: String): String = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { (it.localName ?: it.tagName) == name }.singleOrNull()?.textContent?.trim()
        ?: throw ProtocolFailure(Failure.INVALID_XML)

    fun services(xml: String): List<Service> = parse(xml).documentElement.elements("service").map {
        Service(it.text("serviceType"), it.text("controlURL"), it.text("SCPDURL"))
    }

    // SCPD argument names are nested inside the action; only its own name identifies the action.
    fun actions(xml: String): Set<String> = parse(xml).documentElement.elements("action").map { it.childText("name") }.toSet()

    fun checkSoap(xml: String) {
        if (parse(xml).documentElement.elements("Fault").isNotEmpty()) throw ProtocolFailure(Failure.HTTP_ERROR)
    }

    fun browse(xml: String): BrowsePage {
        val outer = parse(xml).documentElement
        if (outer.elements("Fault").isNotEmpty()) throw ProtocolFailure(Failure.WRONG_MODE)
        val didl = parse(outer.text("Result")).documentElement
        val containers = didl.elements("container").map { it.getAttribute("id") }
        val items = didl.elements("item").map { item ->
            SelectedItem(item.getAttribute("id"), item.text("title"), item.elements("res").map { res ->
                Resource(res.textContent.trim(), res.getAttribute("size").toLongOrNull() ?: 0, res.getAttribute("protocolInfo"))
            })
        }
        val returned = outer.text("NumberReturned").toIntOrNull() ?: throw ProtocolFailure(Failure.INVALID_XML)
        val total = outer.text("TotalMatches").toIntOrNull() ?: throw ProtocolFailure(Failure.INVALID_XML)
        if (returned < 0 || total < returned || returned != containers.size + items.size ||
            (returned == 0 && total > 0) || (containers + items.map { it.id }).any { it.isBlank() })
            throw ProtocolFailure(Failure.INVALID_XML)
        return BrowsePage(containers, items, returned, total)
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun envelope(type: String, action: String, arguments: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$type\">$arguments</u:$action></s:Body></s:Envelope>"
}
