package dev.pocketphotobridge.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpCameraTransportTest {
    private val request = CameraRequest(CameraEndpoint("192.168.122.1").resolve("/push"), "service#start", "SOAP")
    private class Connection : HttpURLConnection(URL("http://192.168.122.1:64321/push")) {
        var opened = 0
        val output = ByteArrayOutputStream()
        var configured: () -> Unit = {}
        var connecting: () -> Unit = {}
        override fun setRequestProperty(key: String?, value: String?) { configured() }
        override fun connect() { connecting(); opened++; connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy() = false
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream("<OK/>".toByteArray())
        override fun getContentLengthLong() = 5L
    }

    @Test fun closedTransportCannotOpenOrSendAfterConfigurationRace() {
        val connection = Connection()
        val transport = HttpCameraTransport({ connection })
        connection.configured = { transport.close() }
        try { transport.execute(request); fail("Closed transport must reject connection") }
        catch (e: ProtocolFailure) { assertEquals(Failure.CANCELLED, e.failure) }
        assertEquals(0, connection.opened)
        assertEquals(0, connection.output.size())
    }

    @Test fun cancellationWhileConnectingCannotSendSoap() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val connection = Connection().apply { connecting = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) } }
        val transport = HttpCameraTransport({ connection })
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Failure?> {
            try { transport.execute(request).close(); null } catch (e: ProtocolFailure) { e.failure }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            transport.cancel()
            proceed.countDown()
            assertEquals(Failure.CANCELLED, result.get(5, TimeUnit.SECONDS))
            transport.close()
            assertEquals(0, connection.output.size())
        } finally { proceed.countDown(); executor.shutdownNow() }
    }
}
