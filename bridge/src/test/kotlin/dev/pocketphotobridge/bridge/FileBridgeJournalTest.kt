package dev.pocketphotobridge.bridge

import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class FileBridgeJournalTest {
    private val photoA = "a".repeat(64)
    private val photoB = "b".repeat(64)

    private fun directory(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("bridge-journal-test-")
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun reservationAndBothDeduplicationKeysSurviveRestart() = directory { root ->
        FileBridgeJournal(root).use { journal ->
            assertTrue(journal.consumeEvent("synthetic-selection"))
            journal.selectPhoto(photoA)
            journal.reserve()
            assertTrue(journal.unresolved)
        }
        FileBridgeJournal(root).use { journal ->
            assertTrue(journal.unresolved)
            assertFalse(journal.consumeEvent("synthetic-selection"))
            assertTrue(journal.wasSubmitted(photoA))
        }
    }

    @Test fun finishedReplyCrashWindowStillSuppressesSubmittedPhoto() = directory { root ->
        FileBridgeJournal(root).use { journal ->
            journal.consumeEvent("first")
            journal.selectPhoto(photoA)
            val reservation = journal.reserve()
            journal.resolve(reservation)
            assertFalse(journal.unresolved)
            // Crash before the coordinator could add any separate 'printed' record.
        }
        FileBridgeJournal(root).use { journal ->
            assertFalse(journal.unresolved)
            assertTrue(journal.wasSubmitted(photoA))
            assertFalse(journal.consumeEvent("first"))
        }
    }

    @Test fun staleResolutionCannotClearNewReservation() = directory { root ->
        FileBridgeJournal(root).use { journal ->
            journal.selectPhoto(photoA)
            val first = journal.reserve()
            journal.resolve(first)
            journal.selectPhoto(photoB)
            journal.reserve()
            journal.resolve(first)
            assertTrue(journal.unresolved)
        }
        FileBridgeJournal(root).use { assertTrue(it.unresolved) }
    }

    @Test fun manualAcknowledgementKeepsPhotoAndEventTombstones() = directory { root ->
        FileBridgeJournal(root).use { journal ->
            journal.consumeEvent("first")
            journal.selectPhoto(photoA)
            journal.reserve()
            journal.acknowledgeCheckedPrinter()
        }
        FileBridgeJournal(root).use { journal ->
            assertFalse(journal.unresolved)
            assertTrue(journal.wasSubmitted(photoA))
            assertFalse(journal.consumeEvent("first"))
            assertTrue(journal.consumeEvent("fresh-different-selection"))
        }
    }

    @Test fun simultaneousInstanceFailsWithoutChangingOwnersState() = directory { root ->
        FileBridgeJournal(root).use { owner ->
            owner.selectPhoto(photoA)
            owner.reserve()
            try { FileBridgeJournal(root).use { fail("Second owner must not open") } }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.INSTANCE_RUNNING, failure.code) }
            assertTrue(owner.unresolved)
        }
        FileBridgeJournal(root).use { assertTrue(it.unresolved) }
    }

    @Test fun rejectedLocalInstanceDoesNotReleaseOwnersCrossProcessLock() = directory { root ->
        FileBridgeJournal(root).use {
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val classes = listOf(JournalLockProbe::class.java, FileBridgeJournal::class.java,
                dev.pocketphotobridge.printer.PrintJobGuard::class.java, kotlin.Unit::class.java)
            val classpath = classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct().joinToString(java.io.File.pathSeparator)
            fun probe(): Int {
                val child = ProcessBuilder(javaExecutable.toString(), "-cp", classpath, JournalLockProbe::class.java.name, root.toString())
                    .redirectErrorStream(true).start()
                assertTrue("Lock probe must terminate", child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
                return child.exitValue()
            }
            assertEquals("First owner must exclude another process", 12, probe())
            try { FileBridgeJournal(root).close(); fail("Local duplicate must not open") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.INSTANCE_RUNNING, failure.code) }
            assertEquals("Rejected local open must not unlock the owner for another process", 12, probe())
        }
    }

    @Test fun corruptSnapshotFailsClosedAndRetainsEvidence() = directory { root ->
        FileBridgeJournal(root).use { it.consumeEvent("first") }
        val snapshot = root.resolve("journal.state")
        val damaged = Files.readAllBytes(snapshot).also { it[it.lastIndex - 2] = 'z'.code.toByte() }
        Files.write(snapshot, damaged)
        try { FileBridgeJournal(root).use { fail("Corrupt state must not reset") } }
        catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
        assertArrayEquals(damaged, Files.readAllBytes(snapshot))
    }

    @Test fun missingSnapshotFromPriorInstanceFailsClosed() = directory { root ->
        FileBridgeJournal(root).use { it.consumeEvent("first") }
        Files.delete(root.resolve("journal.state"))
        try { FileBridgeJournal(root).use { fail("Lost state must not initialize as new") } }
        catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
    }

    @Test fun noReservationWithoutPhotoAndNoSecondSubmissionForSamePhoto() = directory { root ->
        FileBridgeJournal(root).use { journal ->
            try { journal.reserve(); fail("Missing photo context must stop") }
            catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
            journal.selectPhoto(photoA)
            val reservation = journal.reserve()
            try { journal.reserve(); fail("Unresolved reservation must stop") }
            catch (failure: PrinterFailure) { assertEquals(PrinterFailureCode.UNRESOLVED_JOB, failure.code) }
            journal.resolve(reservation)
            journal.selectPhoto(photoA)
            try { journal.reserve(); fail("Submitted content must stay suppressed") }
            catch (failure: PrinterFailure) { assertEquals(PrinterFailureCode.UNRESOLVED_JOB, failure.code) }
        }
    }

    @Test fun consumedEventsAreHashedAndClosedGuardCannotBeUsed() = directory { root ->
        val journal = FileBridgeJournal(root)
        assertTrue(journal.consumeEvent("private-camera-name-and-selection"))
        assertFalse(Files.readString(root.resolve("journal.state")).contains("private-camera-name"))
        journal.close()
        try { journal.consumeEvent("another"); fail("Closed guard must stop") }
        catch (failure: BridgeFailure) { assertEquals(BridgeFailureCode.PERSISTENCE_ERROR, failure.code) }
    }
}

object JournalLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        val code = try { FileBridgeJournal(Path.of(args[0])).use { 0 } }
        catch (failure: BridgeFailure) { if (failure.code == BridgeFailureCode.INSTANCE_RUNNING) 12 else 13 }
        kotlin.system.exitProcess(code)
    }
}
