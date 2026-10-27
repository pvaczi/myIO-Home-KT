package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A user-named zone (tag). Zones are **global** (shared across servers); outputs are assigned to them
 * per server (see [ZoneAssignments]). Outputs are assigned many-to-many. */
@Serializable
data class Zone(val id: String = java.util.UUID.randomUUID().toString(), val name: String)

/**
 * One server's output→zones assignment. [assignments] maps an output key (see `outputKey()`) to the
 * global zone ids it belongs to; an output may be in several zones. [memberOrder] holds, per zone id,
 * a custom ordering of that server's member output keys. **Legacy**: read only to migrate into the
 * global order ([ZoneStore.zoneOrder]) and no longer written. Zone ids reference the **global** zone
 * registry ([Zone]).
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

    // distinctUntilChanged on the raw blob so unrelated writes into the same Preferences file (another
    // server's assignment, the hideZoned toggle) don't force a re-decode + downstream recomposition.
    /** The global zone registry (id + name + display order). Shared across all servers. */
    val globalZones: Flow<List<Zone>> =
        dataStore.data.map { it[ZONES_GLOBAL] }.distinctUntilChanged().map { parseZones(it) }

    /** Full-replace write of the global zone list. For in-place mutations prefer [updateZones]. */
    suspend fun saveZones(list: List<Zone>) {
        dataStore.edit { it[ZONES_GLOBAL] = StoreJson.encodeToString(zonesSerializer, list) }
    }

    /** Atomically read-modify-write the global zone list (transform runs inside the transaction). */
    suspend fun updateZones(transform: (List<Zone>) -> List<Zone>) {
        dataStore.edit {
            it[ZONES_GLOBAL] = StoreJson.encodeToString(zonesSerializer, transform(parseZones(it[ZONES_GLOBAL])))
        }
    }

    /** One server's output→zone assignment + member order. */
    fun assignments(serverId: String): Flow<ZoneAssignments> =
        dataStore.data.map { it[assignKey(serverId)] }.distinctUntilChanged().map { parseAssignments(it) }

    /** Full-replace write of one server's assignment. For in-place mutations prefer [updateAssignments]. */
    suspend fun saveAssignments(serverId: String, value: ZoneAssignments) {
        dataStore.edit { it[assignKey(serverId)] = StoreJson.encodeToString(ZoneAssignments.serializer(), value) }
    }

    /** Atomically read-modify-write one server's assignment (transform runs inside the transaction). */
    suspend fun updateAssignments(serverId: String, transform: (ZoneAssignments) -> ZoneAssignments) {
        dataStore.edit {
            it[assignKey(serverId)] =
                StoreJson.encodeToString(ZoneAssignments.serializer(), transform(parseAssignments(it[assignKey(serverId)])))
        }
    }

    /**
     * The **global** per-zone member order (`zoneId → ordered "<serverId>|<outputKey>" refs`, see
     * [memberRef]). Source of truth for the in-zone order in both the aggregate and the single-server view.
     */
    val zoneOrder: Flow<Map<String, List<String>>> =
        dataStore.data.map { it[ZONE_ORDER] }.distinctUntilChanged().map { parseOrder(it) }

    /** Atomically read-modify-write the global zone order. */
    suspend fun updateOrder(transform: (Map<String, List<String>>) -> Map<String, List<String>>) {
        dataStore.edit { writeOrder(it, transform(parseOrder(it[ZONE_ORDER]))) }
    }

    /**
     * Toggle whether [outputKey] belongs to [zoneId] on [serverId] (many-to-many). Removing the member
     * also prunes its ref from the zone's global order — in the same transaction.
     */
    suspend fun toggleAssignment(serverId: String, outputKey: String, zoneId: String) {
        dataStore.edit { prefs ->
            val a = parseAssignments(prefs[assignKey(serverId)])
            val current = a.assignments[outputKey] ?: emptyList()
            val removing = zoneId in current
            val updated = if (removing) current - zoneId else current + zoneId
            val assignments = a.assignments.toMutableMap().apply {
                if (updated.isEmpty()) remove(outputKey) else put(outputKey, updated)
            }
            prefs[assignKey(serverId)] =
                StoreJson.encodeToString(ZoneAssignments.serializer(), a.copy(assignments = assignments))
            if (removing) {
                val ref = memberRef(serverId, outputKey)
                val order = parseOrder(prefs[ZONE_ORDER])
                if (order[zoneId]?.contains(ref) == true) {
                    writeOrder(prefs, order + (zoneId to order.getValue(zoneId).filterNot { it == ref }))
                }
            }
        }
    }

    /** Drop every order ref of a deleted server (assignments stay, so an undo of the delete still applies). */
    suspend fun pruneServerOrder(serverId: String) {
        updateOrder { ZoneOrder.prune(it) { ref -> memberRefServer(ref) == serverId } }
    }

    /**
     * One-time, idempotent migration of the legacy per-server `memberOrder`s into the global order (see
     * [ZoneOrder.migrate]); [serverIds] are the configured slots in slot order. The legacy field is cleared
     * afterwards (it is no longer written), so it cannot resurrect a pruned order.
     */
    suspend fun migrateLegacyOrder(serverIds: List<String>) {
        dataStore.edit { migrateInto(it, serverIds) }
    }

    private fun migrateInto(prefs: androidx.datastore.preferences.core.MutablePreferences, serverIds: List<String>) {
        val legacy = serverIds.associateWith { parseAssignments(prefs[assignKey(it)]) }
        val withLegacy = legacy.filterValues { it.memberOrder.isNotEmpty() }
        if (withLegacy.isEmpty()) return
        val existing = parseOrder(prefs[ZONE_ORDER])
        val merged = ZoneOrder.migrate(existing, serverIds, withLegacy.mapValues { it.value.memberOrder })
        if (merged != existing) writeOrder(prefs, merged)
        for ((sid, a) in withLegacy) {
            prefs[assignKey(sid)] = StoreJson.encodeToString(ZoneAssignments.serializer(), a.copy(memberOrder = emptyMap()))
        }
    }

    private fun writeOrder(prefs: androidx.datastore.preferences.core.MutablePreferences, order: Map<String, List<String>>) {
        prefs[ZONE_ORDER] = StoreJson.encodeToString(order)
    }

    /**
     * Delete a global zone and strip it from **every** server's assignments (and its global member order),
     * atomically. [serverIds] are the configured server slots whose assignment blobs must be cleaned.
     */
    suspend fun deleteZone(zoneId: String, serverIds: List<String>) {
        dataStore.edit { prefs ->
            prefs[ZONES_GLOBAL] = StoreJson.encodeToString(
                zonesSerializer,
                parseZones(prefs[ZONES_GLOBAL]).filterNot { it.id == zoneId },
            )
            writeOrder(prefs, parseOrder(prefs[ZONE_ORDER]) - zoneId)
            for (id in serverIds) {
                val a = parseAssignments(prefs[assignKey(id)])
                val assignments = a.assignments
                    .mapValues { (_, ids) -> ids - zoneId }
                    .filterValues { it.isNotEmpty() }
                prefs[assignKey(id)] = StoreJson.encodeToString(
                    ZoneAssignments.serializer(),
                    ZoneAssignments(assignments = assignments, memberOrder = a.memberOrder - zoneId),
                )
            }
        }
    }

    /**
     * Replace the entire zone configuration atomically (used when importing a shared settings backup):
     * write the global zone list, the global member [order] and each server's assignments, after dropping
     * **every** existing `assign_*` blob so assignments belonging to no-longer-present servers don't linger
     * as orphans. An old backup (no [order], legacy per-server `memberOrder`s) is migrated in the same
     * transaction using [serverIds] as the slot order.
     */
    suspend fun replaceAll(
        zones: List<Zone>,
        assignmentsByServer: Map<String, ZoneAssignments>,
        order: Map<String, List<String>> = emptyMap(),
        serverIds: List<String> = assignmentsByServer.keys.toList(),
    ) {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("assign_") }.forEach { prefs.remove(it) }
            prefs[ZONES_GLOBAL] = StoreJson.encodeToString(zonesSerializer, zones)
            writeOrder(prefs, order)
            for ((serverId, value) in assignmentsByServer) {
                prefs[assignKey(serverId)] = StoreJson.encodeToString(ZoneAssignments.serializer(), value)
            }
            migrateInto(prefs, serverIds)
        }
    }

    /** Hide outputs that already have a zone (so only the unassigned remainder shows). Global toggle. */
    val hideZoned: Flow<Boolean> = dataStore.data.map { it[HIDE_ZONED] ?: false }.distinctUntilChanged()

    suspend fun setHideZoned(value: Boolean) {
        dataStore.edit { it[HIDE_ZONED] = value }
    }

    private fun assignKey(serverId: String) = stringPreferencesKey("assign_$serverId")

    companion object {
        private val ZONES_GLOBAL = stringPreferencesKey("zones_global")
        private val HIDE_ZONED = booleanPreferencesKey("hide_zoned")
        private val ZONE_ORDER = stringPreferencesKey("zone_order")
        private val zonesSerializer = kotlinx.serialization.builtins.ListSerializer(Zone.serializer())

        /** Pure parse of the global member order — null/blank/corrupt → empty map. */
        fun parseOrder(blob: String?): Map<String, List<String>> = decodeOrDefault(blob, emptyMap())

        /** Pure parse — null/blank/corrupt → empty list. Unit-testable without DataStore. */
        fun parseZones(blob: String?): List<Zone> = decodeOrDefault(blob, emptyList())

        /** Pure parse — null/blank/corrupt → empty assignment. Unit-testable without DataStore. */
        fun parseAssignments(blob: String?): ZoneAssignments = decodeOrDefault(blob, ZoneAssignments())
    }
}
