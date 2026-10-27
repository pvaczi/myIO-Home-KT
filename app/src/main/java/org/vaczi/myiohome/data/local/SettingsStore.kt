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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
    /**
     * Optional second address (e.g. a DDNS name for use outside the LAN), used when [host] is unreachable
     * (connection-level failure only). Additive: old data decodes as null.
     */
    val altHost: String? = null,
    val altPort: Int? = null,
) {
    fun toConfig(): ServerConfig = ServerConfig(
        host = host, port = port, user = user, password = password,
        altHost = altHost?.trim()?.takeIf { it.isNotEmpty() }, altPort = altPort ?: 80, serverId = id,
    )

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

    // distinctUntilChanged on the raw blob so unrelated writes (a tab switch, theme, selection — all
    // separate keys in the same Preferences file) don't force a re-decode + downstream recomposition.
    val servers: Flow<List<MyioServer>> =
        dataStore.data.map { it[SERVERS] }.distinctUntilChanged().map { parseServers(it) }

    /** The raw selected id (may be a server id or the [AGGREGATE_ID] sentinel; null if never set). */
    val selectedId: Flow<String?> = dataStore.data.map { it[SELECTED] }.distinctUntilChanged()

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
    val theme: Flow<AppTheme> = dataStore.data.map { it[THEME] }.distinctUntilChanged().map { parseTheme(it) }

    suspend fun setTheme(value: AppTheme) {
        dataStore.edit { it[THEME] = value.name }
    }

    /**
     * Expert mode (defaults to **false** → the locked, simplified view). When false the app shows a
     * locked, simplified view: servers/outputs marked hidden are filtered from display, and the
     * per-server editing tools (zone assign / reorder / visibility) are hidden. Expert mode (true)
     * unlocks full visibility + those editing tools.
     */
    val expertMode: Flow<Boolean> = dataStore.data.map { it[EXPERT] ?: false }.distinctUntilChanged()

    suspend fun setExpertMode(value: Boolean) {
        dataStore.edit { it[EXPERT] = value }
    }

    /**
     * Last-open tab for a server, as a **stable tab name** (null if never set). Stored by name rather
     * than by the tab's enum ordinal so that inserting/reordering tabs in the UI can't silently shift a
     * saved selection onto a different tab. Legacy installs stored a raw ordinal [Int] under the old key;
     * that's surfaced here as its decimal string and the caller migrates it once via [resolveTab] (the
     * current enum order), after which [setLastTab] rewrites it as a name and drops the legacy key.
     */
    fun lastTab(serverId: String): Flow<String?> =
        dataStore.data
            .map { it[tabNameKey(serverId)] ?: it[tabOrdinalKey(serverId)]?.toString() }
            .distinctUntilChanged()

    suspend fun setLastTab(serverId: String, tabName: String) {
        dataStore.edit {
            it[tabNameKey(serverId)] = tabName
            it.remove(tabOrdinalKey(serverId)) // migrated → drop the legacy ordinal so it can't resurface
        }
    }

    /** Snapshot of every stored last-open tab by server id (name-keyed entries only; for the full backup). */
    suspend fun allLastTabs(): Map<String, String> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(TAB_NAME_PREFIX) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.name.removePrefix(TAB_NAME_PREFIX) to it } }
            .toMap()

    private fun tabNameKey(serverId: String) = stringPreferencesKey("$TAB_NAME_PREFIX$serverId")
    private fun tabOrdinalKey(serverId: String) = intPreferencesKey("tab_$serverId")

    /** Full-replace write of the whole server list (used by import). For in-place mutations that must not
     * lose a concurrent edit, prefer [updateServers] (transform read+written atomically inside `edit`). */
    suspend fun saveServers(list: List<MyioServer>) {
        dataStore.edit { it[SERVERS] = StoreJson.encodeToString(list) }
    }

    /**
     * Atomically read-modify-write the server list: [transform] runs **inside** the DataStore transaction
     * on the freshly-decoded persisted value, so two quick edits can't clobber each other (the earlier one
     * is no longer read from a stale StateFlow snapshot). All in-place mutations (hide toggle, reorder,
     * upsert, delete) go through this.
     */
    suspend fun updateServers(transform: (List<MyioServer>) -> List<MyioServer>) {
        dataStore.edit { it[SERVERS] = StoreJson.encodeToString(transform(parseServers(it[SERVERS]))) }
    }

    companion object {
        private val SERVERS = stringPreferencesKey("servers")
        private val SELECTED = stringPreferencesKey("selected_host")
        private val THEME = stringPreferencesKey("theme")
        private val EXPERT = booleanPreferencesKey("expert_mode")
        private const val TAB_NAME_PREFIX = "tabname_"
        const val DEFAULT_POLL_SEC = 2
        /** Sentinel selection id for the virtual "Otthon (összes)" aggregate (not a real server). */
        const val AGGREGATE_ID = "__all_zones__"

        /**
         * Pure parse — unit-testable without DataStore. Discovery-first: a fresh install / cleared
         * storage starts **empty** (no hardcoded servers — the app ships with no site-specific config),
         * and the user adds slots by scanning the LAN or by hand. So an absent (null/blank) or corrupt
         * blob → empty list, as does an explicit `"[]"`.
         */
        fun parseServers(blob: String?): List<MyioServer> = decodeOrDefault(blob, emptyList())

        /** Pure parse with fallback — unknown/missing names default to [AppTheme.GREEN]. */
        fun parseTheme(name: String?): AppTheme =
            runCatching { AppTheme.valueOf(name ?: "") }.getOrDefault(AppTheme.GREEN)

        /**
         * Resolve a persisted tab identifier ([stored]) against [order] — the current tab names in enum
         * order. A new-format name resolves directly; a legacy ordinal (decimal string) is mapped through
         * [order] once; anything matching nothing (a removed/renamed tab) → null. Pure — unit-testable.
         */
        fun resolveTab(stored: String?, order: List<String>): String? {
            if (stored.isNullOrBlank()) return null
            if (stored in order) return stored
            return stored.toIntOrNull()?.let { order.getOrNull(it) }
        }
    }
}
