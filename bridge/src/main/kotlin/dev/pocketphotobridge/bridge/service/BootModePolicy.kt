package dev.pocketphotobridge.bridge.service

/** Reboot opens setup again; only authenticated interaction holds the window. */
class BootModePolicy(private var startedAt: Long, private val windowMs: Long = 60_000) {
    private var held = false
    private var requested = false
    @Synchronized fun beginWindow(now: Long) { startedAt = now }
    @Synchronized fun holdSetup() { held = true }
    @Synchronized fun requestOperation() { requested = true }
    @Synchronized fun shouldOperate(now: Long, enabled: Boolean): Boolean =
        enabled && (requested || !held && now - startedAt >= windowMs)
}
