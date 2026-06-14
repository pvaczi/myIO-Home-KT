package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.vaczi.myiohome.data.net.ServerConfig

/**
 * Selectable app theme. [GREEN] is the classic myIO greenish palette (the original look);
 * [DYNAMIC] is the Material You dynamic/blue scheme. Persisted by name; defaults to [GREEN].
 */
enum class AppTheme { GREEN, DYNAMIC }

/**
 * A user-configured myIO server slot (display name + connection params).
 *
 * [id] is a stable, unique identity (not the host) so two slots may legitimately share a host
 * without breaking list keys, selection, or edit/delete. Slots persisted before [id] existed get a
 * fresh one on decode (the default runs per missing field), so old data self-heals into unique ids.
 */
@Serializable
data class MyioServer(
    val name: String,
    val host: String,
    val port: Int = 80,
    val user: String = "admin",
    val password: String = "admin",
    /** How often this server is polled, in seconds (per-server; defaults to [SettingsStore.DEFAULT_POLL_SEC]). */
    val pollIntervalSec: Int = SettingsStore.DEFAULT_POLL_SEC,
    /**
     * Hidden from **display** in non-expert mode: the slot drops out of the server switcher so a
     * simplified (e.g. family) view can't open its per-server control screen. Its zoned outputs still
     * surface in the "Otthon (összes)" aggregate. No effect in expert mode. (Default false → unchanged.)
     */
    val hidden: Boolean = false,
    /**
     * Output keys (see `outputKey()`) hidden from display in non-expert mode — dropped **everywhere**,
     * including the home/aggregate dashboards. No effect in expert mode. (Default empty → unchanged.)
     */
    val hiddenOutputs: List<String> = emptyList(),
    /**
     * Optional credentials substituted into this slot **only when the config is exported** for sharing
     * (see [SettingsBackup.encode] / [forSharing]). Lets you stay logged in as admin yourself while the
     * shared copy carries a restricted (e.g. family) account — no swapping your own login before every
     * export. Null/blank → the slot's own [user]/[password] are shared unchanged.
     */
    val shareUser: String? = null,
    val sharePassword: String? = null,
    val id: String = newId(),
) {
    fun toConfig(): ServerConfig = ServerConfig(host = host, port = port, user = user, password = password)

    /**
     * The copy that goes into a shared backup: if [shareUser] is set, its credentials replace [user]/
     * [password] so the recipient logs in restricted; the share-cred fields are always dropped from the
     * exported slot (they're the exporter's secret — useless and leaky downstream).
     */
    fun forSharing(): MyioServer =
        if (shareUser.isNullOrBlank()) copy(shareUser = null, sharePassword = null)
        else copy(user = shareUser, password = sharePassword.orEmpty(), shareUser = null, sharePassword = null)

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persists the list of server slots and the currently selected one (DataStore Preferences).
 * The server list is stored as a JSON blob under a single key; selection is the chosen id.
 * Discovery-first: an unconfigured app starts with **no** servers (see [parseServers]); the user
 * adds them by scanning the LAN or by hand.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    val servers: Flow<List<MyioServer>> = dataStore.data.map { parseServers(it[SERVERS]) }

    /** The raw selected id (may be a server id or the [AGGREGATE_ID] sentinel; null if never set). */
    val selectedId: Flow<String?> = dataStore.data.map { it[SELECTED] }

    /**
     * The selected **real** server (matched by id); first slot as fallback, or null when there are no
     * slots **or** the virtual aggregate ([AGGREGATE_ID]) is selected — callers detect the aggregate via
     * [selectedId] == [AGGREGATE_ID]. (Falling back to the first slot would otherwise mask the sentinel.)
     */
    val selected: Flow<MyioServer?> = combine(servers, dataStore.data.map { it[SELECTED] }) { list, id ->
        if (id == AGGREGATE_ID) null else list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    suspend fun selectServer(id: String) {
        dataStore.edit { it[SELECTED] = id }
    }

    /** Select the virtual "Otthon (összes)" aggregate (all zones across all servers). */
    suspend fun selectAggregate() {
        dataStore.edit { it[SELECTED] = AGGREGATE_ID }
    }

    /** The selected app theme (defaults to [AppTheme.GREEN] — the original greenish look). */
    val theme: Flow<AppTheme> = dataStore.data.map { parseTheme(it[THEME]) }

    suspend fun setTheme(value: AppTheme) {
        dataStore.edit { it[THEME] = value.name }
    }

    /**
     * Expert mode (defaults to **true** → existing behaviour unchanged). When false the app shows a
     * locked, simplified view: servers/outputs marked hidden are filtered from display, and the
     * per-server editing tools (zone assign / reorder / visibility) are hidden.
     */
    val expertMode: Flow<Boolean> = dataStore.data.map { it[EXPERT] ?: false }

    suspend fun setExpertMode(value: Boolean) {
        dataStore.edit { it[EXPERT] = value }
    }

    /** Last-open tab index for a server (defaults to 0). */
    fun lastTab(serverId: String): Flow<Int> = dataStore.data.map { it[tabKey(serverId)] ?: 0 }

    suspend fun setLastTab(serverId: String, index: Int) {
        dataStore.edit { it[tabKey(serverId)] = index }
    }

    private fun tabKey(serverId: String) = intPreferencesKey("tab_$serverId")

    suspend fun saveServers(list: List<MyioServer>) {
        dataStore.edit { it[SERVERS] = json.encodeToString(list) }
    }

    companion object {
        private val SERVERS = stringPreferencesKey("servers")
        private val SELECTED = stringPreferencesKey("selected_host")
        private val THEME = stringPreferencesKey("theme")
        private val EXPERT = booleanPreferencesKey("expert_mode")
        const val DEFAULT_POLL_SEC = 2
        /** Sentinel selection id for the virtual "Otthon (összes)" aggregate (not a real server). */
        const val AGGREGATE_ID = "__all_zones__"
        // encodeDefaults so the (randomly-defaulted) id is always written back, not skipped.
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Pure parse — unit-testable without DataStore. Discovery-first: a fresh install / cleared
         * storage starts **empty** (no hardcoded servers — the app ships with no site-specific config),
         * and the user adds slots by scanning the LAN or by hand. So an absent (null/blank) or corrupt
         * blob → empty list, as does an explicit `"[]"`.
         */
        fun parseServers(blob: String?): List<MyioServer> {
            if (blob.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<MyioServer>>(blob) }.getOrDefault(emptyList())
        }

        /** Pure parse with fallback — unknown/missing names default to [AppTheme.GREEN]. */
        fun parseTheme(name: String?): AppTheme =
            runCatching { AppTheme.valueOf(name ?: "") }.getOrDefault(AppTheme.GREEN)
    }
}
