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
 * the live values. Keyed by the server's stable id.
 */
class StateCacheStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.stateCacheDataStore)

    suspend fun load(serverId: String): ServerState? {
        // Not decodeOrDefault: a corrupt/absent cache must stay null (skip the instant render), not fall
        // back to an empty ServerState that would look like a real, empty device.
        val blob = dataStore.data.map { it[key(serverId)] }.first() ?: return null
        return runCatching { StoreJson.decodeFromString(ServerState.serializer(), blob) }.getOrNull()
    }

    suspend fun save(serverId: String, state: ServerState) {
        dataStore.edit { it[key(serverId)] = StoreJson.encodeToString(ServerState.serializer(), state) }
    }

    private fun key(serverId: String) = stringPreferencesKey("state_$serverId")
}
