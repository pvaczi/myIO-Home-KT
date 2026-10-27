package org.vaczi.myiohome.data.net

/** A non-2xx reply; [retryAfterSec] is the parsed `Retry-After` (seconds form only), if the server sent one. */
class HttpStatusException(
    val code: Int,
    message: String,
    val retryAfterSec: Long? = null,
) : Exception(message)

/** Pure logic for the unreachable-server backoff and the Retry-After header. */
object PollBackoff {
    const val BASE_MS = 5_000L
    const val CAP_MS = 300_000L

    /** Consecutive failures after which the server counts as offline (shown as an error). */
    const val OFFLINE_AFTER = 3

    fun isOffline(failures: Int): Boolean = failures >= OFFLINE_AFTER

    /** Wait before the next attempt after [failures] consecutive failures (>= 1): 5 s·2^(n-1), capped at 300 s. */
    fun backoffMs(failures: Int): Long {
        if (failures < 1) return 0L
        val shift = (failures - 1).coerceAtMost(16) // beyond this the cap applies anyway; avoids Long overflow
        return (BASE_MS shl shift).coerceAtMost(CAP_MS)
    }

    /** `Retry-After` in seconds → ms (capped); null for a missing, non-numeric (HTTP-date) or negative value. */
    fun parseRetryAfterMs(header: String?): Long? {
        val sec = header?.trim()?.toLongOrNull() ?: return null
        if (sec < 0) return null
        return (sec.coerceAtMost(CAP_MS / 1_000) * 1_000L)
    }

    /** The next delay after a failure: a server-given Retry-After wins over the computed backoff. */
    fun nextDelayMs(failures: Int, retryAfterMs: Long?): Long =
        retryAfterMs?.coerceAtMost(CAP_MS) ?: backoffMs(failures)

    /**
     * Join command fragments with '&' into bodies of at most [maxLen] characters; a fragment is never
     * split, and one longer than [maxLen] goes out alone in its own body.
     */
    fun chunk(fragments: List<String>, maxLen: Int = MAX_BODY_CHARS): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (f in fragments) {
            if (f.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + 1 + f.length > maxLen) {
                out += cur.toString()
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append('&')
            cur.append(f)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    const val MAX_BODY_CHARS = 200
}
