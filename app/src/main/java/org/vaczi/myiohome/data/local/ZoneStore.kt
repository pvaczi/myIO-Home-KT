package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A user-named zone (tag). Zones are **global** (shared across servers); outputs are assigned to them
 * per server (see [ZoneAssignments]). Outputs are assigned many-to-many. */
@Serializable
data class Zone(val id: String = java.util.UUID.randomUUID().toString(), val name: String)

/**
 * One server's output→zones assignment. [assignments] maps an output key (see `outputKey()`) to the
 * global zone ids it belongs to; an output may be in several zones. [memberOrder] holds, per zone id,
 * a custom ordering of that server's member output keys (keys absent from it keep their default order
 * at the end). Zone ids reference the **global** zone registry ([Zone]).
 */
@Serializable
data class ZoneAssignments(
    val assignments: Map<String, List<String>> = emptyMap(),
    val memberOrder: Map<String, List<String>> = emptyMap(),
)

/**
 * The combined zone view for one server's UI (global zones + that server's assignment). Assembled in
 * the ViewModel from the global [Zone] list and the server's [ZoneAssignments]; it is **not** persisted
 * as a unit (storage is split: zones are global, assignments per server). Modeled after the web UI
 * (`myio-theme-2`): zones are tags, not containers.
 */
data class ZoneConfig(
    val zones: List<Zone> = emptyList(),
    val assignments: Map<String, List<String>> = emptyMap(),
    val memberOrder: Map<String, List<String>> = emptyMap(),
) {
    fun zonesOf(outputKey: String): List<String> = assignments[outputKey] ?: emptyList()
    fun isAssigned(outputKey: String): Boolean = !assignments[outputKey].isNullOrEmpty()

    /**
     * [keys] (the zone's members in default order) reordered by this zone's saved [memberOrder]:
     * saved keys first in their saved order, any remaining keys appended in default order. Stable.
     */
    fun orderedMembers(zoneId: String, keys: List<String>): List<String> {
        val saved = memberOrder[zoneId] ?: return keys
        val rank = saved.withIndex().associate { (i, k) -> k to i }
        return keys.sortedBy { rank[it] ?: Int.MAX_VALUE }
    }
}

private val Context.zonesDataStore: DataStore<Preferences> by preferencesDataStore(name = "zones")

/**
 * Persists the **global** zone list (one key, shared across servers) plus each server's [ZoneAssignments]
 * (keyed by stable server id), and the global "hide already-zoned" UI toggle used while assigning.
 * Preferences DataStore → covered by Android Auto Backup. No legacy migration: a fresh start has no
 * zones (the old per-server `zones_<id>` blobs are simply no longer read).
 */
class ZoneStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.zonesDataStore)

    /** The global zone registry (id + name + display order). Shared across all servers. */
    val globalZones: Flow<List<Zone>> = dataStore.data.map { parseZones(it[ZONES_GLOBAL]) }

    suspend fun saveZones(list: List<Zone>) {
        dataStore.edit { it[ZONES_GLOBAL] = json.encodeToString(zonesSerializer, list) }
    }

    /** One server's output→zone assignment + member order. */
    fun assignments(serverId: String): Flow<ZoneAssignments> =
        dataStore.data.map { parseAssignments(it[assignKey(serverId)]) }

    suspend fun saveAssignments(serverId: String, value: ZoneAssignments) {
        dataStore.edit { it[assignKey(serverId)] = json.encodeToString(ZoneAssignments.serializer(), value) }
    }

    /**
     * Delete a global zone and strip it from **every** server's assignments + member order, atomically.
     * [serverIds] are the configured server slots whose assignment blobs must be cleaned.
     */
    suspend fun deleteZone(zoneId: String, serverIds: List<String>) {
        dataStore.edit { prefs ->
            prefs[ZONES_GLOBAL] = json.encodeToString(
                zonesSerializer,
                parseZones(prefs[ZONES_GLOBAL]).filterNot { it.id == zoneId },
            )
            for (id in serverIds) {
                val a = parseAssignments(prefs[assignKey(id)])
                val assignments = a.assignments
                    .mapValues { (_, ids) -> ids - zoneId }
                    .filterValues { it.isNotEmpty() }
                prefs[assignKey(id)] = json.encodeToString(
                    ZoneAssignments.serializer(),
                    ZoneAssignments(assignments = assignments, memberOrder = a.memberOrder - zoneId),
                )
            }
        }
    }

    /** Hide outputs that already have a zone (so only the unassigned remainder shows). Global toggle. */
    val hideZoned: Flow<Boolean> = dataStore.data.map { it[HIDE_ZONED] ?: false }

    suspend fun setHideZoned(value: Boolean) {
        dataStore.edit { it[HIDE_ZONED] = value }
    }

    private fun assignKey(serverId: String) = stringPreferencesKey("assign_$serverId")

    companion object {
        private val ZONES_GLOBAL = stringPreferencesKey("zones_global")
        private val HIDE_ZONED = booleanPreferencesKey("hide_zoned")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val zonesSerializer = kotlinx.serialization.builtins.ListSerializer(Zone.serializer())

        /** Pure parse — null/blank/corrupt → empty list. Unit-testable without DataStore. */
        fun parseZones(blob: String?): List<Zone> {
            if (blob.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString(zonesSerializer, blob) }.getOrDefault(emptyList())
        }

        /** Pure parse — null/blank/corrupt → empty assignment. Unit-testable without DataStore. */
        fun parseAssignments(blob: String?): ZoneAssignments {
            if (blob.isNullOrBlank()) return ZoneAssignments()
            return runCatching {
                json.decodeFromString(ZoneAssignments.serializer(), blob)
            }.getOrDefault(ZoneAssignments())
        }
    }
}
