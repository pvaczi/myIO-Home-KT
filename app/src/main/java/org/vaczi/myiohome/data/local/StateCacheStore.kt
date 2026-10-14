package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.vaczi.myiohome.data.model.ServerState

private val Context.stateCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "state_cache")

/**
 * Persists the last known [ServerState] (incl. device names) per server, so a relaunch can render
 * the cached lists instantly — before the connection turns green — while the first poll refreshes
 * the live values. Keyed by [cacheKey]: the slot id plus its host:port, so a slot re-pointed at
 * another device never starts from the previous device's names and rights.
 */
class StateCacheStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.stateCacheDataStore)

    suspend fun load(cacheKey: String): ServerState? {
        // Not decodeOrDefault: a corrupt/absent cache must stay null (skip the instant render), not fall
        // back to an empty ServerState that would look like a real, empty device.
        val blob = dataStore.data.map { it[key(cacheKey)] }.first() ?: return null
        return runCatching { StoreJson.decodeFromString(ServerState.serializer(), blob) }.getOrNull()
    }

    suspend fun save(cacheKey: String, state: ServerState) {
        dataStore.edit { it[key(cacheKey)] = StoreJson.encodeToString(ServerState.serializer(), state) }
    }

    /** Drop every cached snapshot whose key is not in [keep] (deleted slots, re-pointed hosts, old key form). */
    suspend fun prune(keep: Set<String>) {
        val present = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        val stale = staleEntries(present, keep)
        if (stale.isEmpty()) return
        dataStore.edit { prefs -> stale.forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    private fun key(cacheKey: String) = stringPreferencesKey(PREFIX + cacheKey)

    companion object {
        private const val PREFIX = "state_"

        /** The cache key of [server]: its id plus the device it points at. */
        fun cacheKey(server: MyioServer): String =
            "${server.id}@${server.host.trim().lowercase()}:${server.port}"

        /** Preference names among [present] that hold a snapshot not in [keep]. Pure → unit-testable. */
        fun staleEntries(present: Set<String>, keep: Set<String>): Set<String> {
            val keepNames = keep.map { PREFIX + it }.toSet()
            return present.filter { it.startsWith(PREFIX) && it !in keepNames }.toSet()
        }
    }
}
