package dev.pocketphotobridge.bridge.linux

import org.junit.Assert.*
import org.junit.Test

class LinuxSppDiscoveryTest {
    @Test fun `channel is taken only from RFCOMM in a serial port service`() {
        assertEquals(7, LinuxSppDiscovery.parse(SPP))
        assertEquals(27, LinuxSppDiscovery.parse(SPP.replace("Channel: 7", "Channel: 27")))
    }

    @Test fun `standard base UUID32 and UUID128 representations retain the discovered channel`() {
        assertEquals(7, LinuxSppDiscovery.parse(SPP.replace("0x1101", "0x00001101").replace("0x0003", "0x00000003")))
        assertEquals(7, LinuxSppDiscovery.parse(SPP.replace("0x1101", "0x00001101-0000-1000-8000-00805f9b34fb")
            .replace("0x0003", "0x00000003-0000-1000-8000-00805f9b34fb")))
    }

    @Test fun `missing ambiguous out of range and unrelated channels fail closed`() {
        listOf("", SPP + SPP, SPP.replace("0x1101", "0x111e"), SPP.replace("Channel: 7", "Channel: 0"),
            SPP.replace("Channel: 7", "Channel: 31"), SPP.replace("RFCOMM", "L2CAP"),
            SPP.replace("Channel: 7", "Channel: 7\n    Channel: 9")).forEach {
            assertThrows(LinuxRadioFailure::class.java) { LinuxSppDiscovery.parse(it) }
        }
    }

    @Test fun `discovery is bounded address specific and sanitized`() {
        val calls = mutableListOf<List<String>>()
        val events = mutableListOf<String>()
        val runner = object : LinuxCommandRunner {
            override fun run(command: List<String>, timeoutMillis: Long, input: String?): LinuxCommandResult {
                calls += command
                assertEquals(15_000L, timeoutMillis)
                assertNull(input)
                return LinuxCommandResult(0, SPP)
            }
        }
        assertEquals(7, LinuxSppDiscovery(runner, events::add).channel("aa:bb:cc:dd:ee:ff"))
        assertEquals(listOf("/usr/bin/sdptool", "search", "--bdaddr", "AA:BB:CC:DD:EE:FF", "SP"), calls.single())
        assertTrue(events.none { it.contains("AA:") || it.contains("private") })
    }

    companion object {
        val SPP = """
            Searching for SP on AA:BB:CC:DD:EE:FF ...
            Service Name: private printer
            Service RecHandle: 0x10001
            Service Class ID List:
              "Serial Port" (0x1101)
            Protocol Descriptor List:
              "L2CAP" (0x0100)
              "RFCOMM" (0x0003)
                Channel: 7
            Language Base Attr List:
              code_ISO639: 0x656e
            Profile Descriptor List:
              "Serial Port" (0x1101)
                Version: 0x0100

        """.trimIndent()
    }
}
