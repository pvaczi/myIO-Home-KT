package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.vaczi.myiohome.data.model.CoverOverride

private val Context.coversDataStore: DataStore<Preferences> by preferencesDataStore(name = "covers")

/**
 * Persists per-server cover overrides (keyed by `coverKey()`): disable a detected pair, swap its
 * up/down orientation, or rename it. Detection itself is automatic (structural) — these are the user's
 * corrections on top. Mirrors [ZoneStore]'s per-server, pure-parse pattern. Empty map = pure auto-detect.
 */
class CoverStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.coversDataStore)

    /** One server's overrides, keyed by `coverKey()`. */
    fun overrides(serverId: String): Flow<Map<String, CoverOverride>> =
        dataStore.data.map { parse(it[key(serverId)]) }

    suspend fun saveOverrides(serverId: String, value: Map<String, CoverOverride>) {
        dataStore.edit { it[key(serverId)] = json.encodeToString(value) }
    }

    /** Apply [transform] to one cover's override (creating it if absent), dropping it again if it becomes default. */
    suspend fun edit(serverId: String, coverKey: String, transform: (CoverOverride) -> CoverOverride) {
        dataStore.edit { prefs ->
            val map = parse(prefs[key(serverId)]).toMutableMap()
            val next = transform(map[coverKey] ?: CoverOverride())
            if (next == CoverOverride()) map.remove(coverKey) else map[coverKey] = next
            prefs[key(serverId)] = json.encodeToString(map)
        }
    }

    /** Replace every server's overrides atomically (used when importing a shared settings backup). */
    suspend fun replaceAll(byServer: Map<String, Map<String, CoverOverride>>) {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("cover_") }.forEach { prefs.remove(it) }
            for ((serverId, value) in byServer) {
                prefs[key(serverId)] = json.encodeToString(value)
            }
        }
    }

    private fun key(serverId: String) = stringPreferencesKey("cover_$serverId")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Pure parse — null/blank/corrupt → empty map. Unit-testable without DataStore. */
        fun parse(blob: String?): Map<String, CoverOverride> {
            if (blob.isNullOrBlank()) return emptyMap()
            return runCatching { json.decodeFromString<Map<String, CoverOverride>>(blob) }.getOrDefault(emptyMap())
        }
    }
}
