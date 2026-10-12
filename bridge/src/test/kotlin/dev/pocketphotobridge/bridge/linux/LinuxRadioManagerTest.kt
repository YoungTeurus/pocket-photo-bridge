package dev.pocketphotobridge.bridge.linux

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LinuxRadioManagerTest {
    @Before fun requireLinuxFilesystem() {
        assumeTrue("NetworkManager keyfiles require a POSIX filesystem",
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
    }
    @Test fun `inactive device is verified and permits first boot setup and camera transition`() {
        val files = Files.createTempDirectory("linux-inactive")
        val base = cameraCommands()
        val commands = object : LinuxCommandRunner {
            override fun run(command: List<String>, timeoutMillis: Long, input: String?): LinuxCommandResult = when {
                command.contains("disconnect") -> LinuxCommandResult(10, "Device is not active")
                command.contains("-g") && command.contains("GENERAL.STATE") -> LinuxCommandResult(0, "30 (disconnected)\n")
                else -> base.run(command, timeoutMillis, input)
            }
        }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.startSetup("setup", "hotspot-password")
        manager.connectCamera("camera", "camera-password").close()
        manager.close()
    }

    @Test fun `external deactivation permits teardown and a later camera capture`() {
        val files = Files.createTempDirectory("linux-disappeared")
        val base = cameraCommands()
        val commands = object : LinuxCommandRunner {
            override fun run(command: List<String>, timeoutMillis: Long, input: String?): LinuxCommandResult = when {
                command.contains("down") -> LinuxCommandResult(10, "Connection is not active")
                command.contains("-g") && command.contains("GENERAL.STATE") -> LinuxCommandResult(0, "30 (disconnected)\n")
                else -> base.run(command, timeoutMillis, input)
            }
        }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.connectCamera("camera", "camera-password").close()
        manager.connectCamera("camera", "camera-password").close()
        manager.close()
    }

    @Test fun `refused active hotspot teardown still prevents camera mode`() {
        val files = Files.createTempDirectory("linux-active-refused")
        val commands = FakeCommands { args ->
            when {
                args.contains("down") -> LinuxCommandResult(10, "Stop refused")
                args.contains("-g") && args.contains("GENERAL.STATE") -> LinuxCommandResult(0, "100 (connected)\n")
                else -> LinuxCommandResult(0, "")
            }
        }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.startSetup("setup", "hotspot-password")
        val upCount = commands.calls.count { it.contains("up") }
        assertThrows(LinuxRadioFailure::class.java) { manager.connectCamera("camera", "camera-password") }
        assertEquals(upCount, commands.calls.count { it.contains("up") })
    }

    @Test fun `valid raw WPA hex key is accepted just like the saved setup configuration`() {
        val files = Files.createTempDirectory("linux-wpa")
        val commands = FakeCommands { LinuxCommandResult(0, "") }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.startSetup("setup", "a".repeat(64)); manager.close()
    }
    @Test fun `hotspot credentials exist only in an owner private keyfile`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val events = mutableListOf<String>()
        val command = FakeCommands { args ->
            if (args.contains("load")) {
                val profile = Path.of(args.last())
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(profile))
                val data = Files.readString(profile)
                assertTrue(data.contains("psk=private-password"))
                assertTrue(data.contains("ssid=80;80;66;59;32;84;101;115;116;"))
                assertTrue(data.contains("mode=ap"))
                assertTrue(data.contains("method=shared"))
                assertFalse(data.contains("autoconnect=true"))
            }
            LinuxCommandResult(0, "")
        }
        val manager = LinuxRadioManager(files, "wlan0", events::add, command, files.resolve("profiles"))
        assertEquals("192.168.50.1", manager.startSetup("PPB; Test", "private-password").hostAddress)
        assertTrue(command.calls.all { !it.joinToString().contains("private-password") && !it.joinToString().contains("PPB; Test") })
        assertTrue(events.none { it.contains("private-password") || it.contains("PPB; Test") })
        manager.stopSetup()
        assertFalse(Files.exists(files.resolve("profiles").resolve(manager.setupProfileName)))
    }

    @Test fun `camera mode stops hotspot before profile activation and uses only device gateway`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val command = cameraCommands()
        val manager = LinuxRadioManager(files, "wlan0", {}, command, files.resolve("profiles"))
        manager.startSetup("setup", "hotspot-password")
        val link = manager.connectCamera("DIRECT-camera", "camera-password")
        assertEquals("192.168.122.1", link.endpoint.host)
        val down = command.calls.indexOfFirst { it.contains("down") }
        val up = command.calls.indexOfLast { it.contains("up") }
        assertTrue(down in 0 until up)
        assertTrue(command.calls.any { it.contains("192.168.122.1") && it.contains("from") })
        assertFalse("route verification must match the actual source-bound socket, without forcing an interface", command.calls.any { it.contains("oif") })
        assertFalse(command.calls.any { it.contains("camera-password") })
        link.close()
        manager.close()
    }

    @Test fun `gateway from another interface or public or missing is rejected`() {
        listOf("8.8.8.8", "", "192.168.2.1").forEach { gateway ->
            val files = Files.createTempDirectory("linux-radio-test")
            val command = cameraCommands(gateway = gateway)
            val manager = LinuxRadioManager(files, "wlan0", {}, command, files.resolve("profiles"))
            assertThrows(LinuxRadioFailure::class.java) { manager.connectCamera("camera", "camera-password") }
            manager.close()
        }
        val files = Files.createTempDirectory("linux-radio-test")
        val command = cameraCommands(routeDevice = "eth0")
        val manager = LinuxRadioManager(files, "wlan0", {}, command, files.resolve("profiles"))
        assertThrows(LinuxRadioFailure::class.java) { manager.connectCamera("camera", "camera-password") }
        manager.close()
    }

    @Test fun `SSID interface password and printer address injection fail before commands`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val commands = FakeCommands { LinuxCommandResult(0, "") }
        assertThrows(LinuxRadioFailure::class.java) { LinuxRadioManager(files, "--help", {}, commands, files.resolve("profiles")) }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        listOf("", "bad\nssid", "x".repeat(33)).forEach { ssid ->
            assertThrows(LinuxRadioFailure::class.java) { manager.startSetup(ssid, "private-password") }
        }
        assertThrows(LinuxRadioFailure::class.java) { manager.startSetup("setup", "bad\npassword") }
        assertThrows(LinuxRadioFailure::class.java) { manager.pairPrinter("AA:BB:CC:DD:EE:FF\nquit") }
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun `escaped scan SSIDs are compared literally and never interpreted as options`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val commands = FakeCommands { LinuxCommandResult(0, "one\nDIRECT\\:camera\\\\x\nother\n") }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        assertTrue(manager.isCameraPresent("DIRECT:camera\\x"))
        assertFalse(manager.isCameraPresent("DIRECT"))
        assertFalse(commands.calls.any { it.contains("DIRECT:camera\\x") })
    }

    @Test fun `pairing accepts only validated address with fixed bounded input`() {
        val files = Files.createTempDirectory("linux-radio-test")
        var paired = false
        val commands = FakeCommands { args ->
            if (args.contains("pair")) paired = true
            LinuxCommandResult(0, if (args.contains("info")) "Device AA:BB:CC:DD:EE:FF\n\tPaired: ${if (paired) "yes" else "no"}\n" else "")
        }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.pairPrinter("aa:bb:cc:dd:ee:ff")
        assertTrue(commands.calls.contains(listOf("/usr/bin/bluetoothctl", "--timeout", "30", "--agent", "NoInputNoOutput", "pair", "AA:BB:CC:DD:EE:FF")))
        assertTrue(commands.inputs.all { it == null })
        assertTrue(commands.timeouts.all { it in 1..45_000 })
    }

    @Test fun `paired listing is local UI data and diagnostic callbacks are redacted`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val events = mutableListOf<String>()
        val commands = FakeCommands { LinuxCommandResult(0, "Device AA:BB:CC:DD:EE:FF Local printer\nDevice invalid secret\n") }
        val manager = LinuxRadioManager(files, "wlan0", events::add, commands, files.resolve("profiles"))
        assertEquals(listOf(LinuxPrinterDevice("AA:BB:CC:DD:EE:FF", "Local printer")), manager.pairedPrinters())
        assertTrue(events.none { it.contains("AA:") || it.contains("Local printer") })
    }

    @Test fun `failed commands are sanitized and failed hotspot removes profile`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val events = mutableListOf<String>()
        val commands = FakeCommands { args -> LinuxCommandResult(if (args.contains("up")) 10 else 0, "private-password secret address") }
        val manager = LinuxRadioManager(files, "wlan0", events::add, commands, files.resolve("profiles"))
        val failure = assertThrows(LinuxRadioFailure::class.java) { manager.startSetup("setup", "private-password") }
        assertEquals("COMMAND_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(events.none { it.contains("private-password") || it.contains("secret address") })
        assertFalse(Files.exists(files.resolve("profiles").resolve(manager.setupProfileName)))
    }

    @Test fun `startup deactivates stale radio connection before activating a new profile`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val commands = FakeCommands { LinuxCommandResult(0, "") }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.startSetup("setup", "private-password")
        val disconnect = commands.calls.indexOfFirst { it.takeLast(3) == listOf("device", "disconnect", "wlan0") }
        val activate = commands.calls.indexOfFirst { it.contains("up") }
        assertTrue("a restarted manager must disconnect any stale single-radio connection", disconnect in 0 until activate)
        manager.close()
    }

    @Test fun `camera accepts DHCP router only on its own private subnet with no default route`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val command = FakeCommands { args -> LinuxCommandResult(0, when {
            args.contains("show") -> "IP4.ADDRESS[1]:192.168.122.4/24\nIP4.GATEWAY:\nDHCP4.OPTION[1]:routers = 192.168.122.1\nGENERAL.STATE:100 (connected)\n"
            args.contains("route") -> "[{\"dst\":\"192.168.122.1\",\"from\":\"192.168.122.4\",\"dev\":\"wlan0\"}]"
            else -> ""
        }) }
        val manager = LinuxRadioManager(files, "wlan0", {}, command, files.resolve("profiles"))
        manager.connectCamera("camera", "camera-password").close()
        manager.close()
        assertTrue(command.calls.none { it.contains("default") })
    }

    @Test fun `new printer is discovered with a bounded classic scan before pairing`() {
        val files = Files.createTempDirectory("linux-radio-test")
        var known = false
        var paired = false
        val commands = FakeCommands { args ->
            if (args.contains("scan")) known = true
            if (args.contains("pair")) paired = true
            if (args.contains("info")) LinuxCommandResult(if (known) 0 else 1, if (paired) "Paired: yes\n" else "Paired: no\n")
            else LinuxCommandResult(0, "")
        }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.pairPrinter("AA:BB:CC:DD:EE:FF")
        val scan = commands.calls.indexOfFirst { it == listOf("/usr/bin/bluetoothctl", "--timeout", "10", "scan", "bredr") }
        val pair = commands.calls.indexOfFirst { it.contains("pair") }
        assertTrue(scan in 0 until pair)
    }

    @Test fun `already paired printer is trusted without attempting another pair`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val commands = FakeCommands { args -> LinuxCommandResult(0, if (args.contains("info")) "Paired: yes\n" else "") }
        val manager = LinuxRadioManager(files, "wlan0", {}, commands, files.resolve("profiles"))
        manager.pairPrinter("AA:BB:CC:DD:EE:FF")
        assertFalse(commands.calls.any { it.contains("pair") || it.contains("scan") })
        assertTrue(commands.calls.any { it.contains("trust") })
    }

    @Test fun `route check rejects actual alternate interface even when forcing the desired interface would work`() {
        val files = Files.createTempDirectory("linux-radio-test")
        val command = FakeCommands { args -> LinuxCommandResult(0, when {
            args.contains("show") -> "IP4.ADDRESS[1]:192.168.122.4/24\nIP4.GATEWAY:192.168.122.1\nGENERAL.STATE:100 (connected)\n"
            args.contains("route") -> "[{\"dst\":\"192.168.122.1\",\"from\":\"192.168.122.4\",\"dev\":\"${if (args.contains("oif")) "wlan0" else "eth0"}\"}]"
            else -> ""
        }) }
        val manager = LinuxRadioManager(files, "wlan0", {}, command, files.resolve("profiles"))
        assertThrows(LinuxRadioFailure::class.java) { manager.connectCamera("camera", "camera-password") }
        manager.close()
    }

    private fun cameraCommands(gateway: String = "192.168.122.1", routeDevice: String = "wlan0") = FakeCommands { args ->
        LinuxCommandResult(0, when {
            args.contains("show") -> "IP4.ADDRESS[1]:192.168.122.4/24\nIP4.GATEWAY:$gateway\nGENERAL.STATE:100 (connected)\n"
            args.contains("route") -> "[{\"dst\":\"192.168.122.1\",\"from\":\"192.168.122.4\",\"dev\":\"$routeDevice\"}]"
            else -> ""
        })
    }

    private class FakeCommands(private val answer: (List<String>) -> LinuxCommandResult) : LinuxCommandRunner {
        val calls = mutableListOf<List<String>>()
        val inputs = mutableListOf<String?>()
        val timeouts = mutableListOf<Long>()
        override fun run(command: List<String>, timeoutMillis: Long, input: String?): LinuxCommandResult {
            calls += command; inputs += input; timeouts += timeoutMillis
            return answer(command)
        }
    }
}
