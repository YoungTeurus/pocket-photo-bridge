package dev.pocketphotobridge.bridge.windows

import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.Assert.*
import org.junit.Test

class WindowsBluetoothNativeTest {
    @Test fun `fd set matches the x64 Win32 header and keeps full socket values`() {
        WindowsBluetoothAbi.fdSet(0x1234567800000042).use { set ->
            assertEquals(520L, set.size())
            assertEquals(1, set.getInt(0))
            assertEquals(0, set.getInt(4))
            assertEquals(0x1234567800000042L, set.getLong(8))
            assertArrayEquals(ByteArray(504), set.getByteArray(16, 504))
        }
    }

    @Test fun `select timeout uses two Windows 32 bit longs`() {
        WindowsBluetoothAbi.timeval(2333).use { timeout ->
            assertEquals(8L, timeout.size())
            assertEquals(2, timeout.getInt(0))
            assertEquals(333000, timeout.getInt(4))
        }
    }

    @Test fun `self test binds only loopback with an ephemeral port`() {
        WindowsBluetoothAbi.loopbackAddress().use { address ->
            assertEquals(16L, address.size())
            assertArrayEquals(byteArrayOf(2, 0, 0, 0, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), address.getByteArray(0, 16))
        }
    }

    @Test fun `SPP socket address is packed and delegates channel discovery to SDP`() {
        WindowsBluetoothAbi.socketAddress(0x001122334455).use { address ->
            assertEquals(30L, address.size())
            // USHORT family, unaligned ULONGLONG address, GUID, zero ULONG port.
            assertArrayEquals(byteArrayOf(
                32, 0, 0x55, 0x44, 0x33, 0x22, 0x11, 0, 0, 0,
                1, 0x11, 0, 0, 0, 0, 0, 0x10, 0x80.toByte(), 0, 0, 0x80.toByte(),
                0x5f, 0x9b.toByte(), 0x34, 0xfb.toByte(), 0, 0, 0, 0
            ), address.getByteArray(0, 30))
        }
    }

    @Test fun `paired search uses Windows BOOL and pointer layouts without inquiry`() {
        WindowsBluetoothAbi.searchParams().use { search ->
            assertEquals(40L, search.size())
            assertEquals(40, search.getInt(0))
            assertEquals(1, search.getInt(4))
            assertEquals(1, search.getInt(8))
            assertArrayEquals(ByteArray(28), search.getByteArray(12, 28))
            assertNull(search.getPointer(32))
        }
    }

    @Test fun `device layout decodes bounded Windows UTF16 and excludes unpaired records`() {
        WindowsBluetoothAbi.deviceInfo().use { info ->
            assertEquals(560L, info.size())
            assertEquals(560, info.getInt(0))
            info.setLong(8, 0x001122334455)
            writeName(info, "Printer \uD83D\uDCBE")
            assertNull(WindowsBluetoothAbi.pairedDevice(info))
            info.setInt(28, 1)
            assertEquals(WindowsPairedDevice(0x001122334455, "Printer \uD83D\uDCBE"), WindowsBluetoothAbi.pairedDevice(info))
            info.setInt(28, 0)
            info.setInt(24, 1)
            assertNotNull(WindowsBluetoothAbi.pairedDevice(info))
            info.setLong(8, -1)
            assertNull(WindowsBluetoothAbi.pairedDevice(info))
        }
    }

    @Test fun `enumeration filters paired records deduplicates addresses and closes handle`() {
        val backend = Devices(listOf(Record(1, "unknown", false), Record(2, "paired", true), Record(2, "duplicate", true)))
        assertEquals(listOf(WindowsPairedDevice(2, "paired")), WindowsBluetoothDevices(backend).pairedDevices())
        assertEquals(1, backend.closes)
    }

    @Test fun `no cached paired devices returns empty without closing absent handle`() {
        val backend = Devices(emptyList())
        assertTrue(WindowsBluetoothDevices(backend).pairedDevices().isEmpty())
        assertEquals(0, backend.closes)
    }

    @Test fun `native enumeration errors are sanitized and release the handle`() {
        val backend = Devices(listOf(Record(2, "sensitive name", true)), finalError = 12345)
        val failure = assertThrows(WindowsBluetoothFailure::class.java) { WindowsBluetoothDevices(backend).pairedDevices() }
        assertEquals(WindowsBluetoothFailureCode.ENUMERATION_FAILED, failure.code)
        assertEquals("ENUMERATION_FAILED", failure.message)
        assertNull(failure.cause)
        assertEquals(1, backend.closes)
    }

    @Test fun `unsupported platform fails before loading Windows libraries`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        listOf<() -> Any?>({ WindowsBluetooth.pairedDevices() }, { WindowsBluetooth.nativeSelfTest() },
            { WindowsBluetooth.connect(WindowsPairedDevice(1, "private")) }).forEach { operation ->
            val failure = assertThrows(WindowsBluetoothFailure::class.java) { operation() }
            assertEquals(WindowsBluetoothFailureCode.UNSUPPORTED_PLATFORM, failure.code)
            assertEquals("UNSUPPORTED_PLATFORM", failure.message)
        }
    }

    private data class Record(val address: Long, val name: String, val paired: Boolean)
    private class Devices(private val records: List<Record>, private val finalError: Int = 259) : WindowsDeviceBackend {
        var closes = 0
        var index = 0
        override fun first(search: Pointer, info: Pointer): Pointer? {
            assertEquals(40, search.getInt(0))
            assertEquals(0, search.getInt(20))
            assertNull(search.getPointer(32))
            if (records.isEmpty()) return null
            writeRecord(info)
            return Pointer(7)
        }
        override fun next(handle: Pointer, info: Pointer): Boolean {
            assertEquals(7L, Pointer.nativeValue(handle))
            index++
            if (index == records.size) return false
            writeRecord(info)
            return true
        }
        override fun lastError() = finalError
        override fun close(handle: Pointer) { assertEquals(7L, Pointer.nativeValue(handle)); closes++ }
        private fun writeRecord(info: Pointer) {
            assertEquals(560, info.getInt(0))
            info.setLong(8, records[index].address)
            info.setInt(28, if (records[index].paired) 1 else 0)
            writeName(info, records[index].name)
        }
    }

    companion object {
        private fun writeName(info: Pointer, name: String) {
            repeat(248) { info.setShort(64L + it * 2, 0) }
            name.forEachIndexed { i, char -> info.setShort(64L + i * 2, char.code.toShort()) }
        }
    }
}
