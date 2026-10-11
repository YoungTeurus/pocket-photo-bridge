package dev.pocketphotobridge.printing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StoredPrintJobGuardTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { StoredPrintJobGuard(context).acknowledgeCheckedPrinter() }

    @Test fun reservationSurvivesGuardRecreation() {
        StoredPrintJobGuard(context).reserve()
        assertTrue(StoredPrintJobGuard(context).unresolved)
    }

    @Test fun duplicateReservationCannotReplaceUnresolvedJob() {
        val guard = StoredPrintJobGuard(context)
        val reservation = guard.reserve()
        val failure = assertThrows(PrinterFailure::class.java) { StoredPrintJobGuard(context).reserve() }
        assertEquals(PrinterFailureCode.UNRESOLVED_JOB, failure.code)
        guard.resolve(reservation)
        assertFalse(guard.unresolved)
    }

    @Test fun staleWorkerCannotResolveNewReservation() {
        val guard = StoredPrintJobGuard(context)
        val old = guard.reserve()
        guard.acknowledgeCheckedPrinter()
        val current = StoredPrintJobGuard(context).reserve()
        guard.resolve(old)
        assertTrue(guard.unresolved)
        guard.resolve(current)
        assertFalse(StoredPrintJobGuard(context).unresolved)
    }

    @Test fun acknowledgmentExplicitlyAllowsAnotherReservation() {
        val guard = StoredPrintJobGuard(context)
        val old = guard.reserve()
        guard.acknowledgeCheckedPrinter()
        assertNotEquals(old, guard.reserve())
    }

    @Test fun failedResolutionCommitStillBlocksAnotherPrintInThisProcess() {
        val guard = StoredPrintJobGuard(context)
        val reservation = guard.reserve()
        val preferences = context.getSharedPreferences("printer_job_guard", Context.MODE_PRIVATE)
        val failedDiskContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun remove(key: String): SharedPreferences.Editor {
                                editor.remove(key)
                                return this
                            }
                            override fun commit(): Boolean {
                                // A real SharedPreferences commit changes memory before the disk result.
                                editor.commit()
                                return false
                            }
                        }
                    }
                }
        }
        val failure = assertThrows(PrinterFailure::class.java) { StoredPrintJobGuard(failedDiskContext).resolve(reservation) }
        assertEquals(PrinterFailureCode.PERSISTENCE_ERROR, failure.code)
        assertTrue(StoredPrintJobGuard(context).unresolved)
        assertEquals(PrinterFailureCode.UNRESOLVED_JOB,
            assertThrows(PrinterFailure::class.java) { StoredPrintJobGuard(context).reserve() }.code)
    }
}
