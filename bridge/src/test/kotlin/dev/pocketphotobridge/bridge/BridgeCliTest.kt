package dev.pocketphotobridge.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class BridgeCliTest {
    private fun root(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("bridge-cli-test-")
        try { test(directory) } finally { directory.toFile().deleteRecursively() }
    }
    private fun run(root: Path, vararg args: String): Pair<Int, String> {
        val bytes = ByteArrayOutputStream()
        val code = PrintStream(bytes, true, "UTF-8").use { BridgeCli.run(arrayOf(*args), it, root) }
        return code to bytes.toString("UTF-8")
    }

    @Test fun helpAndHardwareRefusalNeverCreateStateOrReadConfiguration() = root { directory ->
        val state = directory.resolve("unused")
        assertEquals(0, run(state, "--help").first)
        val (code, text) = run(state, "--hardware", "--config=private-token-file-does-not-exist")
        assertEquals(64, code)
        assertTrue(text.contains("BRIDGE_HARDWARE_NOT_IMPLEMENTED"))
        assertFalse(text.contains("private-token-file"))
        assertFalse(Files.exists(state))
    }

    @Test fun normalFailureAndUnknownHaveDifferentExitCodesAndSafeReports() = root { directory ->
        val normal = run(directory.resolve("normal"), "--simulate")
        assertEquals(0, normal.first); assertTrue(normal.second.contains("SIMULATED_PRINTS count=1"))
        val busy = run(directory.resolve("busy"), "--simulate-failure=printer-busy")
        assertEquals(2, busy.first); assertTrue(busy.second.contains("category=BUSY"))
        val unknown = run(directory.resolve("unknown"), "--simulate-failure=dropped-after-submit")
        assertEquals(3, unknown.first); assertTrue(unknown.second.contains("BRIDGE_UNKNOWN_OUTCOME"))
        val report = Files.readString(directory.resolve("unknown/diagnostics.txt"))
        assertTrue(report.contains("SIMULATION_ONLY")); assertTrue(report.contains("NO_PHYSICAL_PRINT"))
        assertFalse(report.contains(directory.toString()))
        assertFalse(report.contains("000102030405060708090a0b"))
        assertFalse(report.contains("{\""))
    }

    @Test fun acknowledgementNeverReplaysUnknownSelection() = root { directory ->
        assertEquals(3, run(directory, "--simulate-failure=dropped-after-submit").first)
        assertEquals(3, run(directory, "--simulate").first)
        assertEquals(64, run(directory, "--acknowledge-checked-printer").first)
        val acknowledged = run(directory, "--acknowledge-checked-printer", "--confirm")
        assertEquals(0, acknowledged.first); assertTrue(acknowledged.second.contains("SIMULATED_PRINTS count=0"))
        val duplicate = run(directory, "--simulate")
        assertEquals(0, duplicate.first); assertTrue(duplicate.second.contains("BRIDGE_DUPLICATE_SUPPRESSED"))
        assertTrue(duplicate.second.contains("SIMULATED_PRINTS count=0"))
    }

    @Test fun explorerDefaultAndExplicitSuiteProduceCheckedSuccessAndPermitRepeatRuns() = root { directory ->
        val first = run(directory)
        assertEquals(0, first.first); assertTrue(first.second.contains("SIMULATION_SUITE_PASS checks=14"))
        val second = run(directory, "--simulate-all")
        assertEquals(0, second.first); assertTrue(second.second.contains("SIMULATION_SUITE_PASS checks=14"))
    }

    @Test fun arbitraryArgumentAndDamagedStateNeverLeakExceptionMessagesOrResetGuard() = root { directory ->
        val bad = run(directory, "--token=do-not-share-this-secret")
        assertEquals(64, bad.first); assertFalse(bad.second.contains("do-not-share"))
        run(directory, "--simulate")
        val original = Files.readAllBytes(directory.resolve("journal.state"))
        val corrupt = original.copyOf().also { it[it.lastIndex - 1] = 'x'.code.toByte() }
        Files.write(directory.resolve("journal.state"), corrupt)
        val stopped = run(directory, "--simulate")
        assertEquals(2, stopped.first); assertTrue(stopped.second.contains("PERSISTENCE_ERROR"))
        assertArrayEquals(corrupt, Files.readAllBytes(directory.resolve("journal.state")))
    }
}
