package org.vaczi.myiohome.data.net

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException

/**
 * Pure decision logic of the small same-address retry: before fw 3.12 the device serves ONE connection
 * at a time, so a concurrent second one (another app, the web UI) may get a TCP reset. A GET is idempotent
 * and is retried on any connection-level failure; a POST only when the request certainly never reached
 * the device (a failed connect) — never after it may have been sent, to avoid executing a toggle twice.
 */
object ConnectRetry {
    /** Extra attempts after the first one. */
    const val MAX_EXTRA_ATTEMPTS = 2
    const val PAUSE_MS = 300L

    /** [extraAttemptsDone] = retries already made for this call. */
    fun shouldRetry(e: Throwable, post: Boolean, extraAttemptsDone: Int): Boolean {
        if (extraAttemptsDone >= MAX_EXTRA_ATTEMPTS) return false
        if (e is HttpStatusException) return false
        if (e is ConnectException) return true
        if (post) return false
        // Reset / broken pipe before a response, or the peer closing without a status line.
        return e is SocketException ||
            (e is IOException && e.message?.contains("unexpected end of stream", ignoreCase = true) == true)
    }
}
