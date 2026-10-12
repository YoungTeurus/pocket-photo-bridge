package dev.pocketphotobridge.bridge.setup

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SetupServerTest {
    private class Backend : SetupBackend {
        var held = 0
        var run = 0
        var acknowledged = 0
        var paired: String? = null
        var simulated: String? = null
        var saved: ServiceConfiguration? = null
        var throwSecret = false
        var stage = "SETUP"
        var pairingTask: dev.pocketphotobridge.bridge.service.PairingTask? = null
        override fun status(): Map<String, Any> = mapOf("stage" to if (pairingTask?.pending == true) "PAIRING" else stage, "category" to "READY", "queued" to 2, "prints" to 3,
            "unresolved" to true, "simulation" to true, "adminPassword" to "never-disclose", "log" to "private-token",
            "printerToken" to "0123456789abcdef01234567", "cameraSsid" to "sensitive-camera-name")
        override fun configuration() = saved
        override fun diagnostics(): Map<String, Any> = status() + mapOf("events" to listOf(
            "BRIDGE_NEEDS_ATTENTION queued=2 category=UNRESOLVED_JOB",
            "LINUX_BLUETOOTH_CONNECT reason=FAILED native_error=111",
            "SONY_TRANSFER_START", "PRIVATE_TOKEN private-token", "SONY_TRANSFER_START password=private-token",
            "SONY_TRANSFER_START category=private-token", "LINUX_BLUETOOTH_CONNECT reason=private-token native_error=111"))
        override fun saveConfig(config: ServiceConfiguration) { if (throwSecret) error("private-token"); saved = config }
        override fun acknowledgeCheckedPrinter() { acknowledged++ }
        override fun startOperation() { run++ }
        override fun staySetup() { held++ }
        override fun printers() = listOf(SetupPrinter("AA:BB:CC:DD:EE:FF", "<script>alert('x')</script>"))
        override fun pairPrinter(address: String) { pairingTask?.let { it.start(address); return }; paired = address }
        override fun simulationAction(action: String) { simulated = action }
    }

    private data class Response(val code: Int, val body: String, val cookie: String? = null)
    private class Client(val base: String) {
        private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        var cookie: String? = null
        fun request(path: String, fields: Map<String, String>? = null, origin: String? = base, useCookie: Boolean = true): Response {
            val builder = HttpRequest.newBuilder(URI.create(base + path))
            if (useCookie) cookie?.let { builder.header("Cookie", it) }
            if (fields != null) {
                origin?.let { builder.header("Origin", it) }
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                val body = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                builder.POST(HttpRequest.BodyPublishers.ofString(body))
            }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return Response(response.statusCode(), response.body(), response.headers().firstValue("Set-Cookie").orElse(null))
        }
        fun login(password: String = "generated-admin-password"): Response {
            val page = request("/")
            cookie = page.cookie?.substringBefore(';')
            val result = request("/login", mapOf("csrf" to csrf(page.body), "password" to password))
            if (result.code == 303) cookie = result.cookie?.substringBefore(';')
            return result
        }
        fun csrfPage() = csrf(request("/").body)
        companion object {
            fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
            fun csrf(html: String): String = Regex("name=\"csrf\" value=\"([A-Za-z0-9_-]+)\"").find(html)?.groupValues?.get(1)
                ?: error("Expected CSRF field")
        }
    }

    private fun server(test: (Client, Backend, SetupServer) -> Unit) {
        val backend = Backend()
        SetupServer(InetSocketAddress("127.0.0.1", 0), "generated-admin-password", backend).use { server ->
            server.start()
            test(Client("http://127.0.0.1:${server.address.port}"), backend, server)
        }
    }

    @Test fun unauthorizedRequestsCannotSeeStatusOrMutateAndDoNotHoldSetup() = server { client, backend, _ ->
        assertEquals(401, client.request("/status").code)
        assertEquals(401, client.request("/diagnostics").code)
        assertEquals(401, client.request("/run", mapOf("csrf" to "forged")).code)
        assertEquals(0, backend.held)
        assertEquals(0, backend.run)
        assertEquals(200, client.request("/").code)
        assertEquals(0, backend.held)
    }

    @Test fun loginUsesPrivateSessionCookieAndAuthenticatedInteractionHoldsSetup() = server { client, backend, _ ->
        val response = client.login()
        assertEquals(303, response.code)
        assertTrue(response.cookie!!.contains("HttpOnly"))
        assertTrue(response.cookie.contains("SameSite=Strict"))
        assertFalse(response.cookie.contains("generated-admin-password"))
        assertEquals(1, backend.held)
        assertEquals(200, client.request("/status").code)
        assertEquals(2, backend.held)
    }

    @Test fun rejectsWrongLoginCsrfAndCrossOriginBeforeMutation() = server { client, backend, _ ->
        assertEquals(401, client.login("wrong-password").code)
        assertEquals(303, client.login().code)
        val token = client.csrfPage()
        assertEquals(403, client.request("/run", mapOf("csrf" to "wrong")).code)
        assertEquals(403, client.request("/run", mapOf("csrf" to token), "http://foreign.example").code)
        assertEquals(403, client.request("/run", mapOf("csrf" to token), null).code)
        assertEquals(0, backend.run)
        assertEquals(303, client.request("/run", mapOf("csrf" to token)).code)
        assertEquals(1, backend.run)
    }

    @Test fun redactsStatusDiagnosticsAndEscapesUntrustedPrinterNames() = server { client, _, _ ->
        client.login()
        val status = client.request("/status")
        val diagnostics = client.request("/diagnostics")
        assertEquals(200, status.code)
        assertEquals(200, diagnostics.code)
        listOf(status.body, diagnostics.body, client.request("/").body).forEach { body ->
            listOf("never-disclose", "private-token", "0123456789abcdef01234567", "sensitive-camera-name", "generated-admin-password").forEach {
                assertFalse("Secret appeared in response", body.contains(it))
            }
        }
        val page = client.request("/").body
        assertFalse(page.contains("<script>"))
        assertTrue(page.contains("&lt;script&gt;"))
        assertFalse(page.contains("http://foreign"))
        assertFalse(page.contains("https://"))
    }

    @Test fun diagnosticsRetainsOnlyKnownEventsEnumsAndBoundedNativeCodes() = server { client, _, _ ->
        client.login()
        val diagnostics = client.request("/diagnostics").body
        val events = JsonParser.parseString(diagnostics).asJsonObject.getAsJsonArray("events").map { it.asString }
        assertTrue(events.contains("BRIDGE_NEEDS_ATTENTION queued=2 category=UNRESOLVED_JOB"))
        assertTrue(events.contains("LINUX_BLUETOOTH_CONNECT reason=FAILED native_error=111"))
        assertTrue(events.contains("SONY_TRANSFER_START"))
        assertFalse(diagnostics.contains("PRIVATE_TOKEN"))
        assertFalse(diagnostics.contains("private-token"))
    }

    @Test fun exposesFixedControllerStagesAndSubmissionCounts() = server { client, backend, _ ->
        client.login()
        backend.stage = "NEEDS_ATTENTION"
        val status = client.request("/status").body
        assertTrue(status.contains("NEEDS_ATTENTION"))
        assertTrue(status.contains("\"prints\":3"))
        backend.stage = "private-token"
        assertFalse(client.request("/status").body.contains("private-token"))
    }

    @Test fun savesConfigOnlyAfterExplicitOptInAndPreservesBlankSecrets() = server { client, backend, _ ->
        client.login()
        val form = mapOf("csrf" to client.csrfPage(), "cameraSsid" to "DIRECT-camera", "cameraPassword" to "camera-password",
            "printerAddress" to "AA:BB:CC:DD:EE:FF", "printerToken" to "0123456789abcdef01234567", "wifiInterface" to "wlan0")
        assertEquals(303, client.request("/config", form).code)
        assertFalse(backend.saved!!.autoPrintEnabled)
        assertEquals(303, client.request("/config", form + mapOf("cameraPassword" to "", "printerToken" to "", "autoPrintEnabled" to "on", "crop" to "on")).code)
        assertEquals("camera-password", backend.saved!!.cameraPassword)
        assertEquals("0123456789abcdef01234567", backend.saved!!.printerToken)
        assertTrue(backend.saved!!.autoPrintEnabled)
        assertTrue(backend.saved!!.crop)
        assertFalse(client.request("/").body.contains("camera-password"))
    }

    @Test fun invalidOversizeAndErrorResponsesNeverEchoSecrets() = server { client, backend, _ ->
        client.login()
        val form = mapOf("csrf" to client.csrfPage(), "cameraSsid" to "DIRECT-camera", "cameraPassword" to "camera-password",
            "printerAddress" to "AA:BB:CC:DD:EE:FF", "printerToken" to "private-token", "wifiInterface" to "wlan0")
        val rejected = client.request("/config", form)
        assertEquals(400, rejected.code)
        assertFalse(rejected.body.contains("private-token"))
        assertNull(backend.saved)
        assertEquals(413, client.request("/config", form + ("cameraPassword" to "s".repeat(20_000))).code)
        backend.throwSecret = true
        val failed = client.request("/config", form + ("printerToken" to "0123456789abcdef01234567"))
        assertEquals(503, failed.code)
        assertFalse(failed.body.contains("private-token"))
    }

    @Test fun pairAcknowledgementAndSimulationRequireAuthenticatedCsrfAndFixedInputs() = server { client, backend, _ ->
        client.login()
        val token = client.csrfPage()
        assertEquals(400, client.request("/pair", mapOf("csrf" to token, "address" to "AA:BB;private-token")).code)
        assertEquals(303, client.request("/pair", mapOf("csrf" to token, "address" to "AA:BB:CC:DD:EE:FF")).code)
        assertEquals("AA:BB:CC:DD:EE:FF", backend.paired)
        assertEquals(400, client.request("/acknowledge", mapOf("csrf" to token)).code)
        assertEquals(303, client.request("/acknowledge", mapOf("csrf" to token, "checkedPrinter" to "on")).code)
        assertEquals(1, backend.acknowledged)
        assertEquals(400, client.request("/simulate", mapOf("csrf" to token, "action" to "foreign-command")).code)
        assertEquals(303, client.request("/simulate", mapOf("csrf" to token, "action" to "printer-on")).code)
        assertEquals("printer-on", backend.simulated)
        assertEquals(303, client.request("/simulate", mapOf("csrf" to token, "action" to "duplicate-photo")).code)
        assertEquals("duplicate-photo", backend.simulated)
    }

    @Test fun delayedPairingReturnsHttpResponseAndReportsPendingWithoutWaitingForBluez() = server { client, backend, _ ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        dev.pocketphotobridge.bridge.service.PairingTask({ entered.countDown(); release.await() }).use { task ->
            backend.pairingTask = task
            client.login(); val csrf = client.csrfPage()
            try {
                assertEquals(303, client.request("/pair", mapOf("csrf" to csrf, "address" to "AA:BB:CC:DD:EE:FF")).code)
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(task.pending)
                assertEquals("PAIRING", JsonParser.parseString(client.request("/status").body).asJsonObject["stage"].asString)
            } finally { release.countDown() }
        }
    }

    @Test fun rejectsCredentialUrlsAndDnsRebindingHost() = server { client, backend, listener ->
        assertEquals(400, client.request("/?password=private-token").code)
        java.net.Socket("127.0.0.1", listener.address.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: foreign.example\r\nConnection: close\r\n\r\n".toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response.startsWith("HTTP/1.1 403"))
        }
        assertEquals(0, backend.held)
    }

    @Test fun failedLoginIsRateLimited() = server { client, _, _ ->
        repeat(5) { assertEquals(401, client.login("incorrect").code) }
        assertEquals(429, client.login("incorrect").code)
    }

    @Test fun wildcardAndPublicListenersAreRefused() {
        for (host in listOf("0.0.0.0", "8.8.8.8")) {
            try { SetupServer(InetSocketAddress(host, 0), "generated-admin-password", Backend()).close(); fail("Unsafe bind accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
