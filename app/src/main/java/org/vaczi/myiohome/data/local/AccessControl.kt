package org.vaczi.myiohome.data.local

/**
 * Pure decisions of the app lock and the per-output protection (no Android, no clock of its own — every
 * function takes a monotonic `nowMs`, e.g. `SystemClock.elapsedRealtime()`). The actual authentication is
 * done by the system (`BiometricPrompt`, see `ui/BiometricAuth.kt`); nothing here stores a secret.
 */
object AccessControl {
    /** After one successful authentication ALL protected outputs stay unlocked this long. */
    const val UNLOCK_WINDOW_MS = 30_000L

    /** Returning to the foreground after at least this long in the background re-locks the app. */
    const val BACKGROUND_LOCK_MS = 60_000L

    /** End of the unlock window opened by an authentication that succeeded at [nowMs]. */
    fun unlockedUntil(nowMs: Long): Long = nowMs + UNLOCK_WINDOW_MS

    fun isUnlocked(unlockedUntilMs: Long, nowMs: Long): Boolean = nowMs < unlockedUntilMs

    /** True when the app went to the background at [backgroundedAtMs] and has been away long enough to lock. */
    fun shouldLockAfterBackground(backgroundedAtMs: Long?, nowMs: Long): Boolean =
        backgroundedAtMs != null && nowMs - backgroundedAtMs >= BACKGROUND_LOCK_MS

    /**
     * Does an action touching [refs] (`serverId|outputKey`, see `memberRef`) need an authentication now?
     * Only when at least one ref is protected and the unlock window is not open.
     */
    fun needsAuth(refs: Collection<String>, protectedRefs: Set<String>, unlockedUntilMs: Long, nowMs: Long): Boolean =
        protectedRefs.isNotEmpty() && !isUnlocked(unlockedUntilMs, nowMs) && refs.any { it in protectedRefs }

    /**
     * Refs a scene run touches: its entries, plus — when it also switches the others off — every protected
     * output of the servers it touches (those would be turned off too, so they count as affected). Only
     * [allowedRefs] ("Jelenetbe kerülhet") count: the run skips every other output.
     */
    fun sceneRefs(entryRefs: List<String>, turnOthersOff: Boolean, protectedRefs: Set<String>, allowedRefs: Set<String>): List<String> {
        val servers = entryRefs.map(::memberRefServer).toSet()
        val refs = if (!turnOthersOff) entryRefs else entryRefs + protectedRefs.filter { memberRefServer(it) in servers }
        return refs.filter { it in allowedRefs }
    }

    /** The output keys of [refs] (protected or scene-allowed set) that belong to [serverId]. */
    fun keysOf(refs: Set<String>, serverId: String): Set<String> =
        refs.filter { memberRefServer(it) == serverId }.map(::memberRefKey).toSet()
}
