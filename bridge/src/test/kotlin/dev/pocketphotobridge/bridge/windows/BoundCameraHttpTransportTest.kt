package dev.pocketphotobridge.bridge.windows

import dev.pocketphotobridge.sony.CameraRequest
import dev.pocketphotobridge.sony.Failure
import dev.pocketphotobridge.sony.ProtocolFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundCameraHttpTransportTest {
    private val address = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress && !it.isLoopbackAddress }
    private val chosen = WindowsCameraCandidate(2, "Fixture interface", address.hostAddress, address.hostAddress)

    private class Fixture(address: InetAddress, private val reply: (Socket, String) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 1, address)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit<String> {
            server.accept().use { socket ->
                socket.soTimeout = 3_000
                val input = socket.getInputStream()
                val headers = ByteArrayOutputStream()
                while (!headers.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
                    val value = input.read(); check(value >= 0); headers.write(value)
                    check(headers.size() <= 32_768)
                }
                val head = headers.toString(Charsets.US_ASCII)
                val length = Regex("(?im)^Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                val request = head + body.toString(Charsets.UTF_8)
                reply(socket, request)
                socket.inetAddress.hostAddress + "\n" + request
            }
        }
        override fun close() { server.close(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun transport(verify: () -> Unit = {}) = BoundCameraHttpTransport(chosen, verifyRoute = verify,
        connectTimeoutMillis = 1_000, readTimeoutMillis = 1_000)
    private fun request(port: Int, path: String = "/DmsDescPush.xml", body: String? = null) =
        CameraRequest(URI("http://${address.hostAddress}:$port$path"), body?.let { "urn:sony#Browse" }, body)
    private fun assertFailure(expected: Failure, operation: () -> Unit) {
        try { operation(); fail("Expected $expected") } catch (e: ProtocolFailure) { assertEquals(expected, e.failure) }
    }
    private fun response(value: String, consume: (BoundCameraHttpTransport, Int) -> Unit) {
        Fixture(address) { socket, _ -> socket.getOutputStream().write(value.toByteArray(Charsets.US_ASCII)) }.use { fixture ->
            transport().use { consume(it, fixture.server.localPort) }
            fixture.task.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun getUsesSelectedLocalAddressAndCameraOnlyHeaders() {
        Fixture(address) { socket, _ -> socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n<OK/>".toByteArray()) }.use { fixture ->
            val client = Socket()
            BoundCameraHttpTransport(chosen, socketFactory = { client }).use { transport ->
                transport.execute(request(fixture.server.localPort, "/DmsDescPush.xml?x=1")).use { result ->
                    assertEquals(chosen.sourceAddress, client.localAddress.hostAddress)
                    assertEquals(200, result.status); assertEquals(5L, result.length)
                    assertEquals("<OK/>", result.body.readBytes().toString(Charsets.US_ASCII))
                }
            }
            val wire = fixture.task.get(3, TimeUnit.SECONDS)
            assertTrue(wire.substringAfter("\n").startsWith("GET /DmsDescPush.xml?x=1 HTTP/1.1\r\n"))
            assertTrue(wire.contains("Host: ${address.hostAddress}:${fixture.server.localPort}\r\n"))
            assertTrue(wire.contains("Accept-Encoding: identity\r\n"))
            assertTrue(wire.contains("Connection: close\r\n"))
        }
    }

    @Test fun postSendsUtf8FixedLengthAndSoapAction() {
        Fixture(address) { socket, _ -> socket.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()) }.use { fixture ->
            transport().use { it.execute(request(fixture.server.localPort, "/control", "<Title>日本</Title>")).close() }
            val wire = fixture.task.get(3, TimeUnit.SECONDS)
            assertTrue(wire.contains("POST /control HTTP/1.1\r\n"))
            assertTrue(wire.contains("Content-Length: ${"<Title>日本</Title>".toByteArray(Charsets.UTF_8).size}\r\n"))
            assertTrue(wire.contains("SOAPACTION: \"urn:sony#Browse\"\r\n"))
            assertTrue(wire.endsWith("<Title>日本</Title>"))
        }
    }

    @Test fun decodesChunkedBodyAndTrailers() = response(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;camera=yes\r\n<O\r\n3\r\nK/>\r\n0\r\nX-Camera: done\r\n\r\n") { transport, port ->
        transport.execute(request(port)).use { assertNull(it.length); assertEquals("<OK/>", it.body.readBytes().toString(Charsets.US_ASCII)) }
    }

    @Test fun supportsCloseDelimitedCameraResponses() = response("HTTP/1.0 200 OK\r\n\r\n<OK/>") { transport, port ->
        transport.execute(request(port)).use { assertEquals("<OK/>", it.body.readBytes().toString(Charsets.US_ASCII)) }
    }

    @Test fun returnsRedirectWithoutFollowingLocation() = response(
        "HTTP/1.1 302 Found\r\nLocation: http://8.8.8.8/stolen\r\nContent-Length: 99\r\n\r\n") { transport, port ->
        transport.execute(request(port)).use { assertEquals(302, it.status); assertEquals(-1, it.body.read()) }
    }

    @Test fun rejectsUnsafeTargetsAndHeaderInjectionBeforeOpeningSocket() {
        var opened = 0
        val transport = BoundCameraHttpTransport(chosen, socketFactory = { opened++; Socket() })
        transport.use {
            listOf("http://8.8.8.8/", "http://localhost/", "http://127.0.0.1/", "https://${address.hostAddress}/",
                "http://user@${address.hostAddress}/", "http://${address.hostAddress}/#fragment")
                .forEach { uri -> assertFailure(Failure.UNSAFE_URL) { it.execute(CameraRequest(URI(uri))) } }
            assertFailure(Failure.UNSAFE_URL) { it.execute(CameraRequest(URI("http://${address.hostAddress}/"), "start\r\nInjected: yes", "SOAP")) }
        }
        assertEquals(0, opened)
    }

    @Test fun rejectsAmbiguousMalformedAndOversizedFraming() {
        listOf(
            "Content-Length: 4\r\nContent-Length: 5\r\n", "Content-Length: -1\r\n", "Content-Length: 67108865\r\n",
            "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n", "Transfer-Encoding: gzip\r\n",
            "Content-Encoding: gzip\r\n", "Bad Header: value\r\n", " Folded: value\r\n",
            "X-Long: ${"x".repeat(33_000)}\r\n")
            .forEach { headers ->
                response("HTTP/1.1 200 OK\r\n$headers\r\n<OK/>") { transport, port ->
                    try { transport.execute(request(port)).close(); fail("Must reject framing") }
                    catch (e: ProtocolFailure) { assertTrue(e.failure == Failure.HTTP_ERROR || e.failure == Failure.LIMIT_EXCEEDED) }
                }
            }
    }

    @Test fun rejectsTruncatedLengthAndMalformedChunksDuringRead() {
        listOf("Content-Length: 7\r\n\r\n<OK/>", "Transfer-Encoding: chunked\r\n\r\n5\r\n<OK/>",
            "Transfer-Encoding: chunked\r\n\r\nx\r\n", "Transfer-Encoding: chunked\r\n\r\n1\r\naXX0\r\n\r\n")
            .forEach { framing -> response("HTTP/1.1 200 OK\r\n$framing") { transport, port ->
                assertFailure(Failure.HTTP_ERROR) { transport.execute(request(port)).use { it.body.readBytes() } }
            } }
    }

    @Test fun freshRouteCheckPreventsChangedLinkBeforeRequest() {
        var verified = 0
        transport { verified++; throw ProtocolFailure(Failure.UNSAFE_URL) }.use { transport ->
            assertFailure(Failure.UNSAFE_URL) { transport.execute(request(64321)) }
        }
        assertEquals(1, verified)
    }

    @Test fun closeCancelsBlockedReadAndFutureRequests() {
        val receiving = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        Fixture(address) { socket, _ ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\n".toByteArray())
            receiving.countDown(); releaseServer.await(3, TimeUnit.SECONDS)
        }.use { fixture ->
            val transport = transport()
            val executor = Executors.newSingleThreadExecutor()
            val result = executor.submit<Failure?> {
                try { transport.execute(request(fixture.server.localPort)).use { it.body.read() }; null }
                catch (e: ProtocolFailure) { e.failure }
            }
            try {
                assertTrue(receiving.await(3, TimeUnit.SECONDS))
                transport.close(); transport.close()
                assertEquals(Failure.CANCELLED, result.get(3, TimeUnit.SECONDS))
                assertFailure(Failure.CANCELLED) { transport.execute(request(fixture.server.localPort)) }
            } finally { releaseServer.countDown(); transport.close(); executor.shutdownNow() }
        }
    }
    private open inner class RecordingSocket : Socket() {
        val sent = ByteArrayOutputStream()
        var bound: InetSocketAddress? = null
        var target: InetSocketAddress? = null
        override fun bind(endpoint: SocketAddress?) { bound = endpoint as InetSocketAddress }
        override fun connect(endpoint: SocketAddress?, timeout: Int) { target = endpoint as InetSocketAddress }
        override fun getLocalAddress(): InetAddress = address
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    @Test fun secondRouteCheckRefusesSoapAfterNetworkChangesDuringConnect() {
        val socket = RecordingSocket()
        var checks = 0
        BoundCameraHttpTransport(chosen, verifyRoute = {
            if (++checks == 2) throw ProtocolFailure(Failure.UNSAFE_URL)
        }, socketFactory = { socket }).use { transport ->
            assertFailure(Failure.UNSAFE_URL) { transport.execute(request(64321, "/control", "SOAP")) }
        }
        assertEquals(2, checks)
        assertEquals(chosen.sourceAddress, socket.bound!!.address.hostAddress)
        assertEquals(chosen.gateway, socket.target!!.address.hostAddress)
        assertFalse(socket.target!!.isUnresolved)
        assertEquals(0, socket.sent.size())
        assertTrue(socket.isClosed)
    }

    @Test fun cancellationDuringConnectClosesSocketWithoutSendingSoap() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : RecordingSocket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }
            override fun close() { super.close(); release.countDown() }
        }
        val transport = BoundCameraHttpTransport(chosen, socketFactory = { socket })
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Failure?> {
            try { transport.execute(request(64321, "/control", "SOAP")).close(); null }
            catch (e: ProtocolFailure) { e.failure }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            transport.close()
            assertEquals(Failure.CANCELLED, result.get(3, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            assertEquals(0, socket.sent.size())
        } finally { transport.close(); release.countDown(); executor.shutdownNow() }
    }

    @Test fun requestDeadlineClosesBlockedPostWrite() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : RecordingSocket() {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown(); check(release.await(3, TimeUnit.SECONDS))
                    throw IOException("closed fixture")
                }
            }
            override fun close() { super.close(); release.countDown() }
        }
        val transport = BoundCameraHttpTransport(chosen, socketFactory = { socket }, requestTimeoutMillis = 100)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Failure?> {
            try { transport.execute(request(64321, "/control", "SOAP")).close(); null }
            catch (e: ProtocolFailure) { e.failure }
        }
        try {
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            assertEquals(Failure.LIMIT_EXCEEDED, result.get(1, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
        } finally { transport.close(); release.countDown(); executor.shutdownNow() }
    }

}
