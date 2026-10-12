package dev.pocketphotobridge.printing

import android.content.Context
import dev.pocketphotobridge.printer.PrintJobGuard
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import java.util.UUID

/** Only a random unresolved reservation is persisted; it contains no printer or photo identity. */
class StoredPrintJobGuard(context: Context) : PrintJobGuard {
    private val preferences = context.applicationContext.getSharedPreferences("printer_job_guard", Context.MODE_PRIVATE)

    override val unresolved: Boolean
        get() = synchronized(lock) { persistenceUncertain || preferences.contains(RESERVATION) }

    override fun reserve(): String = synchronized(lock) {
        if (persistenceUncertain || preferences.contains(RESERVATION)) throw PrinterFailure(PrinterFailureCode.UNRESOLVED_JOB)
        val reservation = UUID.randomUUID().toString()
        if (!preferences.edit().putString(RESERVATION, reservation).commit()) {
            persistenceUncertain = true
            throw PrinterFailure(PrinterFailureCode.PERSISTENCE_ERROR)
        }
        reservation
    }

    override fun resolve(reservation: String) = synchronized(lock) {
        // An old worker cannot clear a later reservation after the user acknowledged its job.
        if (preferences.getString(RESERVATION, null) == reservation) remove()
    }

    override fun acknowledgeCheckedPrinter() = synchronized(lock) { remove() }

    private fun remove() {
        if (!preferences.edit().remove(RESERVATION).commit()) {
            // commit() publishes memory even if writing to disk fails. Keep this process blocked.
            persistenceUncertain = true
            throw PrinterFailure(PrinterFailureCode.PERSISTENCE_ERROR)
        }
        persistenceUncertain = false
    }

    companion object {
        private const val RESERVATION = "unresolved_reservation"
        private val lock = Any()
        private var persistenceUncertain = false
    }
}
