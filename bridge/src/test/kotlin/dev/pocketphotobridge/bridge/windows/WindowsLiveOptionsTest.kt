package dev.pocketphotobridge.bridge.windows
import dev.pocketphotobridge.bridge.*
import org.junit.Assert.*
import org.junit.Test
class WindowsLiveOptionsTest {
    @Test fun acceptsExplicitLiveModeAndReadOnlyNativeProbe() {
        assertEquals("WINDOWS_LIVE", CliOptions.parse(arrayOf("--windows-live")).mode.name)
        assertEquals("WINDOWS_NATIVE", CliOptions.parse(arrayOf("--windows-native-check")).mode.name)
    }
    @Test fun redirectedLiveConsoleRefusesBeforeDeviceActions() {
        val bytes=java.io.ByteArrayOutputStream()
        assertEquals(64,BridgeCli.run(arrayOf("--windows-live"),java.io.PrintStream(bytes)))
        val text=bytes.toString()
        assertTrue(text.contains("WINDOWS_REQUIRED") || text.contains("WINDOWS_LIVE_CONSOLE_REQUIRED"))
    }
    @Test fun tokenAndPrintConfirmationArgumentsRemainRejected() {
        for (args in listOf(arrayOf("--windows-live","--token=private"),arrayOf("--windows-live","--confirm"),arrayOf("--windows-live","--simulate"))) {
            try { CliOptions.parse(args); fail("unsafe arguments accepted") } catch (e:BridgeFailure) { assertEquals(BridgeFailureCode.ARGUMENT_ERROR,e.code) }
        }
    }
}
