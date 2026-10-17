package dev.pocketphotobridge.bridge.setup

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pocketphotobridge.bridge.BridgeFailureCode
import dev.pocketphotobridge.bridge.linux.LinuxRadioFailureCode
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterStage
import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.Stage
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class SetupPrinter(val address: String, val name: String)

/** Callbacks run on HTTP workers. Implementations must bound radio operations and transition asynchronously. */
interface SetupBackend {
    fun status(): Map<String, Any>
    fun configuration(): ServiceConfiguration? = null
    fun diagnostics(): Map<String, Any> = status()
    fun saveConfig(config: ServiceConfiguration)
    fun acknowledgeCheckedPrinter()
    fun startOperation()
    fun staySetup()
    fun printers(): List<SetupPrinter>
    fun pairPrinter(address: String)
    fun simulationAction(action: String) { throw UnsupportedOperationException() }
}

/**
 * Local HTTP intentionally relies on the installation's unique WPA setup hotspot boundary.
 * Bind to its specific private address, or loopback in simulation; never expose this on a routed LAN.
 * Generated administrator login, exact authority/origin, session CSRF and fixed diagnostics apply together.
 */
class SetupServer(bind: InetSocketAddress, adminPassword: String, private val backend: SetupBackend) : AutoCloseable {
    private class Session(val csrf: String, val expires: Long)
    private class Attempts(var failures: Int = 0, var window: Long = now())
    private class RequestFailure(val code: Int, val category: String) : RuntimeException()
    private val passwordDigest = hash(adminPassword)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val logins = ConcurrentHashMap<String, Session>()
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val requestRates = ConcurrentHashMap<String, Attempts>()
    private val executor = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "local-setup-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val server: HttpServer
    private val authority: String
    private val origin: String
    val address: InetSocketAddress get() = server.address

    init {
        require(!bind.isUnresolved && !bind.address.isAnyLocalAddress &&
            (bind.address.isLoopbackAddress || bind.address.isSiteLocalAddress)) { "Setup requires a specific local address" }
        require(adminPassword.length in 16..128 && adminPassword.none { it.isISOControl() }) { "Invalid setup administrator password" }
        // JDK HttpServer parses before dispatch. Bound header counts, idle clients and slow request bodies there.
        mapOf("jdk.httpserver.maxConnections" to "32", "sun.net.httpserver.maxReqHeaders" to "32", "sun.net.httpserver.maxIdleConnections" to "16",
            "sun.net.httpserver.idleInterval" to "15", "sun.net.httpserver.maxReqTime" to "15",
            "sun.net.httpserver.maxRspTime" to "20", "sun.net.httpserver.timerMillis" to "1000").forEach { (key, value) ->
            if (System.getProperty(key) == null) System.setProperty(key, value)
        }
        server = HttpServer.create(bind, 16)
        val host = server.address.address.hostAddress
        authority = (if (host.contains(':')) "[$host]" else host) + ":${server.address.port}"
        origin = "http://$authority"
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
    }

    fun start() { server.start() }

    private fun handle(exchange: HttpExchange) {
        try {
            headers(exchange)
            validateLocalRequest(exchange)
            val path = exchange.requestURI.rawPath
            val method = exchange.requestMethod
            if (path == "/" && method == "GET") {
                val session = session(exchange)
                if (session == null) loginPage(exchange) else {
                    backend.staySetup()
                    html(exchange, dashboard(session))
                }
                return
            }
            if (path == "/login" && method == "POST") { login(exchange); return }
            val session = session(exchange) ?: reject(401, "LOGIN_REQUIRED")
            if (method == "GET") {
                when (path) {
                    "/status" -> { backend.staySetup(); json(exchange, safeStatus(backend.status())) }
                    "/diagnostics" -> {
                        backend.staySetup()
                        exchange.responseHeaders.set("Content-Disposition", "attachment; filename=bridge-diagnostics.json")
                        json(exchange, safeDiagnostics(backend.diagnostics()))
                    }
                    "/printers" -> {
                        backend.staySetup()
                        json(exchange, safePrinters().map { mapOf("address" to it.address, "name" to it.name) })
                    }
                    else -> reject(404, "NOT_FOUND")
                }
                return
            }
            if (method != "POST") reject(405, "METHOD_NOT_ALLOWED")
            val form = readForm(exchange)
            if (!constantEqual(form["csrf"].orEmpty(), session.csrf)) reject(403, "INVALID_CSRF")
            when (path) {
                "/config" -> save(form)
                "/pair" -> {
                    val printer = form["address"].orEmpty()
                    if (!validPrinterAddress(printer)) reject(400, "INVALID_PRINTER_ADDRESS")
                    backend.pairPrinter(printer)
                }
                "/acknowledge" -> {
                    if (form["checkedPrinter"] != "on") reject(400, "CHECK_PRINTER_FIRST")
                    backend.acknowledgeCheckedPrinter()
                }
                "/stay" -> Unit
                "/run" -> {
                    backend.staySetup()
                    // The backend owns the asynchronous stop-HTTP/stop-hotspot/join-camera transition.
                    backend.startOperation()
                    redirect(exchange)
                    return
                }
                "/simulate" -> {
                    if (!address.address.isLoopbackAddress || backend.status()["simulation"] != true) reject(404, "NOT_FOUND")
                    val action = form["action"].orEmpty()
                    if (action !in SIMULATION_ACTIONS) reject(400, "INVALID_SIMULATION_ACTION")
                    backend.simulationAction(action)
                }
                "/logout" -> {
                    cookie(exchange, SESSION_COOKIE)?.let { sessions.remove(it) }
                    setCookie(exchange, SESSION_COOKIE, "", 0)
                }
                else -> reject(404, "NOT_FOUND")
            }
            backend.staySetup()
            redirect(exchange)
        } catch (failure: RequestFailure) {
            reply(exchange, failure.code, "text/plain; charset=utf-8", failure.category)
        } catch (_: IllegalArgumentException) {
            reply(exchange, 400, "text/plain; charset=utf-8", "INVALID_CONFIGURATION")
        } catch (_: Exception) {
            reply(exchange, 503, "text/plain; charset=utf-8", "SETUP_OPERATION_UNAVAILABLE")
        } finally { exchange.close() }
    }

    private fun validateLocalRequest(exchange: HttpExchange) {
        if (exchange.localAddress.address != address.address || exchange.localAddress.port != address.port) reject(403, "LOCAL_ADDRESS_REQUIRED")
        if (address.address.isLoopbackAddress && !exchange.remoteAddress.address.isLoopbackAddress) reject(403, "LOCAL_ADDRESS_REQUIRED")
        if (!address.address.isLoopbackAddress && !exchange.remoteAddress.address.isSiteLocalAddress) reject(403, "LOCAL_ADDRESS_REQUIRED")
        val hosts = exchange.requestHeaders["Host"] ?: reject(403, "INVALID_HOST")
        if (hosts.size != 1 || hosts[0] != authority) reject(403, "INVALID_HOST")
        val origins = exchange.requestHeaders["Origin"]
        if (origins != null && (origins.size != 1 || origins[0] != origin)) reject(403, "INVALID_ORIGIN")
        if (exchange.requestMethod == "POST" && origins == null) reject(403, "ORIGIN_REQUIRED")
        val fetchSite = exchange.requestHeaders.getFirst("Sec-Fetch-Site")
        if (fetchSite != null && fetchSite !in setOf("same-origin", "none")) reject(403, "INVALID_ORIGIN")
        if (exchange.requestURI.rawQuery != null || exchange.requestURI.rawPath.length > 64 ||
            exchange.requestURI.rawPath.contains('%') || exchange.requestURI.isAbsolute) reject(400, "INVALID_URL")
        val ip = exchange.remoteAddress.address.hostAddress
        if (requestRates.size >= 128 && !requestRates.containsKey(ip)) reject(429, "REQUEST_RATE_LIMITED")
        val rate = requestRates.computeIfAbsent(ip) { Attempts() }
        synchronized(rate) {
            if (now() - rate.window > 60_000) { rate.window = now(); rate.failures = 0 }
            if (++rate.failures > 120) reject(429, "REQUEST_RATE_LIMITED")
        }
        pruneSessions()
    }

    private fun loginPage(exchange: HttpExchange) {
        if (logins.size >= 64) reject(429, "LOGIN_RATE_LIMITED")
        val id = secretToken()
        val session = Session(secretToken(), now() + 10 * 60_000)
        logins[id] = session
        setCookie(exchange, LOGIN_COOKIE, id, 600)
        html(exchange, shell("Bridge setup", """
            <h1>Pocket Photo Bridge</h1><p>Local setup</p>
            <p>Use the administrator password shown during installation. This page is available only on your private setup hotspot or local simulation.</p>
            <form action="/login" method="post">${csrfField(session)}
            <label>Administrator password<input type="password" name="password" autocomplete="current-password" required maxlength="128"></label>
            <button type="submit">Sign in</button></form>
        """.trimIndent()))
    }

    private fun login(exchange: HttpExchange) {
        val ip = exchange.remoteAddress.address.hostAddress
        if (attempts.size >= 128 && !attempts.containsKey(ip)) reject(429, "LOGIN_RATE_LIMITED")
        val rate = attempts.computeIfAbsent(ip) { Attempts() }
        synchronized(rate) {
            if (now() - rate.window > 60_000) { rate.window = now(); rate.failures = 0 }
            if (rate.failures >= 5) reject(429, "LOGIN_RATE_LIMITED")
            val form = readForm(exchange)
            val id = cookie(exchange, LOGIN_COOKIE) ?: reject(403, "INVALID_CSRF")
            val pending = logins.remove(id) ?: reject(403, "INVALID_CSRF")
            if (pending.expires <= now() || !constantEqual(form["csrf"].orEmpty(), pending.csrf)) reject(403, "INVALID_CSRF")
            if (!MessageDigest.isEqual(passwordDigest, hash(form["password"].orEmpty()))) {
                rate.failures++
                reject(401, "LOGIN_FAILED")
            }
            if (sessions.size >= 32) reject(429, "SESSION_LIMIT_REACHED")
            val sessionId = secretToken()
            sessions[sessionId] = Session(secretToken(), now() + 30 * 60_000)
            rate.failures = 0
            setCookie(exchange, SESSION_COOKIE, sessionId, 1800)
            backend.staySetup()
            redirect(exchange)
        }
    }

    private fun session(exchange: HttpExchange): Session? {
        val id = cookie(exchange, SESSION_COOKIE) ?: return null
        val session = sessions[id] ?: return null
        if (session.expires <= now()) { sessions.remove(id); return null }
        return session
    }

    private fun cookie(exchange: HttpExchange, name: String): String? {
        val values = exchange.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }
            .map { it.trim() }.filter { it.substringBefore('=') == name }
        if (values.size != 1) return null
        val value = values.single().substringAfter('=', "")
        return value.takeIf { Regex("[A-Za-z0-9_-]{32}").matches(it) }
    }

    private fun readForm(exchange: HttpExchange): Map<String, String> {
        if (exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "application/x-www-form-urlencoded")
            reject(415, "FORM_REQUIRED")
        val lengths = exchange.requestHeaders["Content-Length"]
        if (lengths != null && (lengths.size != 1 || lengths[0].toLongOrNull() == null || lengths[0].toLong() < 0)) reject(400, "INVALID_REQUEST")
        if (lengths != null && lengths[0].toLong() > MAX_BODY) reject(413, "REQUEST_TOO_LARGE")
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) reject(413, "REQUEST_TOO_LARGE")
        val result = linkedMapOf<String, String>()
        val text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val pairs = text.split('&')
        if (pairs.size > 16) reject(400, "INVALID_REQUEST")
        for (pair in pairs) {
            val split = pair.split('=', limit = 2)
            if (split.size != 2) reject(400, "INVALID_REQUEST")
            val name = URLDecoder.decode(split[0], StandardCharsets.UTF_8)
            val value = URLDecoder.decode(split[1], StandardCharsets.UTF_8)
            if (!Regex("[A-Za-z]{1,32}").matches(name) || result.put(name, value) != null) reject(400, "INVALID_REQUEST")
        }
        return result
    }

    private fun save(form: Map<String, String>) {
        val previous = backend.configuration()
        val password = form["cameraPassword"].orEmpty().ifEmpty { previous?.cameraPassword.orEmpty() }
        val token = form["printerToken"].orEmpty().ifEmpty { previous?.printerToken.orEmpty() }
        val config = ServiceConfiguration(form["cameraSsid"].orEmpty(), password, form["printerAddress"].orEmpty(), token,
            form["wifiInterface"] ?: "wlan0", form["autoPrintEnabled"] == "on", form["crop"] == "on")
        backend.saveConfig(config)
    }

    private fun dashboard(session: Session): String {
        val status = safeStatus(backend.status())
        val config = backend.configuration()
        val printers = safePrinters()
        val devices = if (printers.isEmpty()) "<p>No paired printers found. Enter the Bluetooth address from your printer.</p>"
            else printers.joinToString("") { printer -> "<li>${escape(printer.name)} — ${escape(printer.address)}</li>" }
        val simulation = if (address.address.isLoopbackAddress && status["simulation"] == true) """
            <section><h2>Simulation controls</h2><p>These controls use synthetic devices and never print paper.</p>
            <form action="/simulate" method="post">${csrfField(session)}<select name="action">
            ${SIMULATION_ACTIONS.joinToString("") { "<option value=\"$it\">${escape(it.replace('-', ' '))}</option>" }}
            </select><button type="submit">Apply simulation</button></form></section>
        """.trimIndent() else ""
        return shell("Bridge setup", """
            <h1>Pocket Photo Bridge</h1><p>Local setup is open. Authenticated interactions keep this setup window open.</p>
            <section><h2>Status</h2><dl>${status.entries.joinToString("") { "<dt>${escape(it.key)}</dt><dd>${escape(it.value.toString())}</dd>" }}</dl></section>
            <section><h2>Camera and printer</h2><form action="/config" method="post">${csrfField(session)}
            <label>Camera Wi-Fi name (SSID)<input name="cameraSsid" value="${escape(config?.cameraSsid.orEmpty())}" required maxlength="32" autocomplete="off"></label>
            <label>Camera Wi-Fi password<input type="password" name="cameraPassword" autocomplete="new-password" maxlength="64" placeholder="${if (config == null) "Enter camera password" else "Leave blank to keep saved password"}"></label>
            <label>Printer Bluetooth address<input name="printerAddress" value="${escape(config?.printerAddress.orEmpty())}" required maxlength="17" placeholder="AA:BB:CC:DD:EE:FF"></label>
            <label>Printer token (24 hex characters)<input type="password" name="printerToken" autocomplete="new-password" maxlength="24" placeholder="${if (config == null) "Enter printer token" else "Leave blank to keep saved token"}"></label>
            <details><summary>Advanced configuration</summary><label>Wi-Fi interface<input name="wifiInterface" value="${escape(config?.wifiInterface ?: "wlan0")}" required maxlength="15"></label></details>
            <label class="check"><input type="checkbox" name="autoPrintEnabled" ${if (config?.autoPrintEnabled == true) "checked" else ""}>Enable automatic printing of newly selected JPEG photos</label>
            <p>Enable this once to print each new photo automatically during normal operation. Uncertain jobs remain blocked until you check the printer.</p>
            <label class="check"><input type="checkbox" name="crop" ${if (config?.crop == true) "checked" else ""}>Crop to fill the paper (otherwise fit the whole photo)</label>
            <button type="submit">Save configuration</button></form></section>
            <section><h2>Printer pairing</h2><ul>$devices</ul><form action="/pair" method="post">${csrfField(session)}
            <label>Bluetooth address<input name="address" required maxlength="17" placeholder="AA:BB:CC:DD:EE:FF"></label>
            <button type="submit">Pair printer</button></form><p>Pairing runs in the background. Refresh this page while status shows PAIRING; start operation after pairing finishes.</p></section>
            <section><h2>Uncertain print job</h2><p>If a job is unresolved, check whether the printer produced the photo before continuing. Acknowledging preserves duplicate protection.</p>
            <form action="/acknowledge" method="post">${csrfField(session)}
            <label class="check"><input type="checkbox" name="checkedPrinter" required>I have checked the printer</label><button type="submit">Acknowledge checked printer</button></form></section>
            <section><h2>Run the bridge</h2><form action="/run" method="post">${csrfField(session)}<button type="submit">Start operation</button></form>
            <form action="/stay" method="post">${csrfField(session)}<button type="submit">Keep setup open</button></form></section>
            $simulation
            <details><summary>Advanced troubleshooting</summary><p>Download fixed status categories and counts. Credentials and device names are excluded.</p><a href="/diagnostics" download>Download redacted diagnostics</a></details>
            <form action="/logout" method="post">${csrfField(session)}<button type="submit">Sign out</button></form>
        """.trimIndent())
    }

    private fun safePrinters(): List<SetupPrinter> = backend.printers().take(64).filter { validPrinterAddress(it.address) }
        .map { SetupPrinter(it.address, it.name.take(128).filterNot(Char::isISOControl)) }

    private fun safeStatus(raw: Map<String, Any>): Map<String, Any> = linkedMapOf<String, Any>().apply {
        for (key in listOf("stage", "category", "mode")) {
            val value = raw[key]
            if (value is String && value in STATUS_VALUES) put(key, value)
        }
        for (key in listOf("queued", "pending", "completed", "captured", "printed", "prints", "queueCount", "queuedCount", "submittedCount", "unknownCount", "retainedBytes")) {
            val value = raw[key]
            if (value is Int && value in 0..1_000_000) put(key, value)
            else if (value is Long && value in 0..1_000_000_000_000L) put(key, value)
        }
        for (key in listOf("unresolved", "blocked", "configured", "autoPrintEnabled", "cameraConnected", "printerConnected", "simulation")) {
            val value = raw[key]
            if (value is Boolean) put(key, value)
        }
    }

    private fun safeDiagnostics(raw: Map<String, Any>): Map<String, Any> = linkedMapOf<String, Any>().apply {
        putAll(safeStatus(raw))
        val events = raw["events"] as? List<*>
        if (events != null) put("events", events.asSequence().take(256).mapNotNull { safeEvent(it as? String) }.toList())
    }

    /** Exact known stages and typed attributes only: arbitrary message text can never pass this filter. */
    private fun safeEvent(text: String?): String? {
        if (text == null || text.length > 256) return null
        val parts = text.split(' ')
        if (parts.size !in 1..5 || parts[0] !in EVENT_VALUES) return null
        val seen = mutableSetOf<String>()
        for (attribute in parts.drop(1)) {
            val pair = attribute.split('=', limit = 2)
            if (pair.size != 2 || !seen.add(pair[0])) return null
            val valid = when (pair[0]) {
                "category" -> pair[1] in STATUS_VALUES
                "reason" -> pair[1] in setOf("OK", "FAILED", "PENDING")
                "queued", "count" -> pair[1].matches(Regex("[0-9]{1,6}"))
                "native_error" -> pair[1].matches(Regex("[0-9]{1,4}"))
                else -> false
            }
            if (!valid) return null
        }
        return text
    }

    private fun pruneSessions() {
        val time = now()
        sessions.entries.removeIf { it.value.expires <= time }
        logins.entries.removeIf { it.value.expires <= time }
        attempts.entries.removeIf { time - it.value.window > 60_000 }
        requestRates.entries.removeIf { time - it.value.window > 60_000 }
    }

    private fun headers(exchange: HttpExchange) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Pragma", "no-cache")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        // Fetch gives form POSTs an opaque Origin under no-referrer, including our own login form.
        // Retain same-origin form metadata while suppressing referrers to other origins.
        exchange.responseHeaders.set("Referrer-Policy", "same-origin")
        exchange.responseHeaders.set("X-Frame-Options", "DENY")
        exchange.responseHeaders.set("Connection", "close")
    }

    private fun csrfField(session: Session) = "<input type=\"hidden\" name=\"csrf\" value=\"${session.csrf}\">"
    private fun setCookie(exchange: HttpExchange, name: String, value: String, age: Int) {
        exchange.responseHeaders.add("Set-Cookie", "$name=$value; Path=/; Max-Age=$age; HttpOnly; SameSite=Strict")
    }
    private fun redirect(exchange: HttpExchange) { exchange.responseHeaders.set("Location", "/"); exchange.sendResponseHeaders(303, -1) }
    private fun html(exchange: HttpExchange, body: String) = reply(exchange, 200, "text/html; charset=utf-8", body)
    private fun json(exchange: HttpExchange, body: Any) = reply(exchange, 200, "application/json; charset=utf-8", Gson().toJson(body))
    private fun reply(exchange: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
    private fun shell(title: String, content: String) = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(title)}</title><style>
        :root{font-family:system-ui,sans-serif;color:#182b35;background:#eef3f2}body{max-width:760px;margin:2rem auto;padding:0 1rem}h1{color:#124638}section,details{background:white;padding:1.3rem;margin:1rem 0;border-radius:12px}label{display:block;margin:1rem 0}input:not([type=checkbox]),select{box-sizing:border-box;display:block;width:100%;padding:.65rem;margin-top:.35rem;border:1px solid #78968d;border-radius:5px}button{border:0;border-radius:6px;padding:.75rem 1rem;background:#12674e;color:white;cursor:pointer;margin:.3rem 0}.check{display:flex;gap:.7rem;align-items:flex-start}dt{font-weight:bold}dd{margin:0 0 .7rem}a{color:#125b45}p{line-height:1.5}li{overflow-wrap:anywhere}
        </style></head><body>$content</body></html>
    """.trimIndent()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        sessions.clear()
        logins.clear()
    }

    companion object {
        private const val MAX_BODY = 8192
        private const val LOGIN_COOKIE = "setupLogin"
        private const val SESSION_COOKIE = "setupSession"
        private val SIMULATION_ACTIONS = listOf("select-photo", "duplicate-photo", "printer-on", "printer-off", "printer-busy", "printer-ready", "interrupted-download", "uncertain-print")
        private val SERVICE_STATES = setOf("WAITING_CAMERA", "RECEIVING", "QUEUED", "WAITING_PRINTER", "PRINTING", "PRINTED", "NEEDS_ATTENTION", "STATE_ERROR", "PAIRING")
        private val STATUS_VALUES = setOf("SETUP", "OPERATION", "STARTING", "STOPPED", "READY", "IDLE", "CAPTURING", "BLOCKED", "UNKNOWN_JOB", "ERROR", "NONE", "OK", "OFFLINE", "PENDING", "CONFIGURATION_REQUIRED", "CAMERA_UNAVAILABLE", "PRINTER_UNAVAILABLE", "NETWORK_UNAVAILABLE", "RADIO_UNAVAILABLE", "SETUP_STATE_UNAVAILABLE", "CAPACITY_REACHED", "INVALID_JPEG", "RECEIVE_INTERRUPTED", "PRINT_UNCERTAIN", "QUEUE_FULL", "CAMERA_ACK_PENDING", "DUPLICATE_SUPPRESSED") + SERVICE_STATES +
            BridgeFailureCode.entries.map { it.name } + PrinterFailureCode.entries.map { it.name } +
            Failure.entries.map { it.name } + LinuxRadioFailureCode.entries.map { it.name }
        private val EVENT_VALUES = Stage.entries.map { "SONY_${it.name}" }.toSet() +
            PrinterStage.entries.map { "PRINTER_${it.name}" } + SERVICE_STATES.map { "BRIDGE_$it" } +
            setOf("SERVICE_SETUP", "SERVICE_OPERATION", "SERVICE_STOPPED", "SERVICE_FAILED", "SIMULATION_ONLY", "NO_PHYSICAL_PRINT",
                "SERVICE_PAIRING_STARTED", "SERVICE_PAIRING_FINISHED", "SERVICE_PAIRING_FAILED",
                "LINUX_SETUP_STARTED", "LINUX_SETUP_STOPPED", "LINUX_CAMERA_CONNECTED", "LINUX_PRINTER_PAIRED",
                "LINUX_SPP_DISCOVERY_START", "LINUX_SPP_DISCOVERED", "LINUX_BLUETOOTH_CONNECTED") +
            listOf("SOCKET", "CONNECT", "POLL", "SO_ERROR", "SEND", "RECEIVE", "SHUTDOWN", "CLOSE").map { "LINUX_BLUETOOTH_$it" }
        private fun reject(code: Int, category: String): Nothing = throw RequestFailure(code, category)
        private fun now(): Long = System.currentTimeMillis()
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        private fun constantEqual(first: String, second: String) = MessageDigest.isEqual(hash(first), hash(second))
        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
