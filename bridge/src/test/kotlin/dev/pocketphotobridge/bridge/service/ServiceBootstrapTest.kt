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
        assertTrue(first.lines().contains("Setup URL: http://10.212.77.1:8080/"))
        // Neither a current run nor a rotated previous run.
        Files.list(state).use { files -> assertTrue(files.noneMatch { it.fileName.toString().startsWith("diagnostics") }) }
        assertFalse(Files.exists(state.resolve("print-journal")))
    }
}
