package dev.pocketphotobridge.bridge.service

import dev.pocketphotobridge.bridge.BridgeCli
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class ServiceBootstrapTest {
    @Test fun bootstrapIsLocalOnlyAndRepeatedGenerationPreservesCredentials() {
        val state = Files.createTempDirectory("bootstrap-test-")
        fun generate(): String {
            val output = ByteArrayOutputStream()
            val code = BridgeCli.run(arrayOf("--make-bootstrap", "--state-dir=$state"), PrintStream(output))
            assertEquals(0, code)
            return output.toString(Charsets.UTF_8)
        }
        val first = generate(); val second = generate()
        assertEquals(first, second)
        assertTrue(first.contains("Setup SSID: PocketPhoto-"))
        assertTrue(first.contains("Setup administrator password: "))
        assertFalse(Files.exists(state.resolve("diagnostics.txt")))
        assertFalse(Files.exists(state.resolve("print-journal")))
    }
}
