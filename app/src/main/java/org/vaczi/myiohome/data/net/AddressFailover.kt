package org.vaczi.myiohome.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Which of a server's two addresses a request goes to. */
enum class Which { Primary, Alt }

/**
 * Pure logic of the optional second address (e.g. a DDNS name for use outside the LAN): which failures
 * trigger a switch, and in which order the addresses are tried.
 */
object AddressFailover {

    /**
     * A connection-level failure: an [IOException] thrown before any HTTP status arrived (connect timeout,
     * refused, unknown host, no route, reset). An [HttpStatusException] — including a 401 — means the device
     * answered, so the address is fine and the other one would not help.
     */
    fun isConnectionFailure(e: Throwable): Boolean = e !is HttpStatusException && e is IOException

    /** Addresses to try, in order: the active one first, the other one as the single retry (only if there is one). */
    fun order(hasAlt: Boolean, activeAlt: Boolean): List<Which> = when {
        !hasAlt -> listOf(Which.Primary)
        activeAlt -> listOf(Which.Alt, Which.Primary)
        else -> listOf(Which.Primary, Which.Alt)
    }
}

/**
 * The address currently in use per server id (in memory, process lifetime). Absent = the first address.
 * Observable so the UI can hint that the second address is in use.
 */
object ActiveAddress {
    private val altIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the servers currently reached through their second address. */
    val altServerIds: StateFlow<Set<String>> = altIds

    fun isAlt(serverId: String): Boolean = serverId in altIds.value

    fun set(serverId: String, which: Which) {
        altIds.update { if (which == Which.Alt) it + serverId else it - serverId }
    }

    /** Test hook: forget every remembered address. */
    internal fun clear() { altIds.value = emptySet() }
}
