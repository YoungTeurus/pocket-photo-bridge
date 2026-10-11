package dev.pocketphotobridge

import android.app.Application
import java.io.File

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Clean abandoned previews once per process, before any Activity starts a new session.
        File(cacheDir, "received").deleteRecursively()
    }
}
