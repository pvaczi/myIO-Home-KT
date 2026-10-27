package org.vaczi.myiohome.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vaczi.myiohome.data.model.CoverOverride

/**
 * A self-contained, shareable snapshot of the app's user configuration: the server slots (incl. their
 * per-server poll interval), the chosen [AppTheme], the global [Zone] registry, and each server's
 * [ZoneAssignments]. This is the payload of the "share settings" feature — exported to a JSON file and
 * sent via the Android share sheet, then imported (wholesale replace) on the receiving device.
 *
 * [format] is a magic discriminator so [decode] can reject arbitrary JSON that happens to parse; [version]
 * allows future migrations. Ids inside one backup are internally consistent (server ids referenced by the
 * assignment keys, zone ids referenced inside the assignments), so a wholesale replace stays valid.
 */
@Serializable
data class SettingsBackup(
    val format: String = MAGIC,
    val version: Int = CURRENT_VERSION,
    val theme: AppTheme = AppTheme.GREEN,
    val servers: List<MyioServer> = emptyList(),
    val zones: List<Zone> = emptyList(),
    val assignments: Map<String, ZoneAssignments> = emptyMap(),
    // Per-server cover overrides (serverId → coverKey → override). Additive/optional: older backups
    // that lack it decode to an empty map (pure auto-detection on the receiving device).
    val coverOverrides: Map<String, Map<String, CoverOverride>> = emptyMap(),
    // Global scenes. Additive/optional: older backups that lack it decode to an empty list.
    val scenes: List<Scene> = emptyList(),
    // Global per-zone member order (zoneId → "<serverId>|<outputKey>" refs). Additive/optional: older
    // backups lack it and carry the legacy per-server `memberOrder`s instead, migrated on import.
    val zoneOrder: Map<String, List<String>> = emptyMap(),
    // True for a "full backup" (own credentials kept, device-state extras below present); false for a
    // sharing export. Additive: old files lack it and decode as sharing files.
    val full: Boolean = false,
    // Full-backup-only extras (null/empty in a sharing file → import leaves the receiver's own untouched).
    val expertMode: Boolean? = null,
    val hideZoned: Boolean? = null,
    /** The selected server id or [SettingsStore.AGGREGATE_ID]. */
    val selectedId: String? = null,
    /** serverId → last-open tab name. */
    val lastTabs: Map<String, String> = emptyMap(),
    /** App lock switch (full backup only; null = absent → import leaves it untouched). */
    val appLock: Boolean? = null,
    /** Protected outputs as `serverId|outputKey` refs (full backup only; null = absent → untouched). */
    val protectedOutputs: List<String>? = null,
) {
    companion object {
        const val MAGIC = "myio-home-settings"
        const val CURRENT_VERSION = 1

        // Derived from the shared [StoreJson] (same ignoreUnknownKeys + encodeDefaults) with prettyPrint
        // added: unlike the internal store blobs, a shared file is meant to be human-readable. Kept as its
        // own instance (not folded into StoreJson) because only this format is pretty-printed and its
        // [decode] additionally needs parseToJsonElement for the magic-field guard below.
        private val json = Json(from = StoreJson) { prettyPrint = true }

        fun encode(
            theme: AppTheme,
            servers: List<MyioServer>,
            zones: List<Zone>,
            assignments: Map<String, ZoneAssignments>,
            coverOverrides: Map<String, Map<String, CoverOverride>> = emptyMap(),
            scenes: List<Scene> = emptyList(),
            zoneOrder: Map<String, List<String>> = emptyMap(),
            full: Boolean = false,
            expertMode: Boolean? = null,
            hideZoned: Boolean? = null,
            selectedId: String? = null,
            lastTabs: Map<String, String> = emptyMap(),
            appLock: Boolean? = null,
            protectedOutputs: List<String>? = null,
        ): String = json.encodeToString(
            serializer(),
            // Sharing: forSharing() swaps in each slot's optional share credentials (e.g. family) so the
            // exporter never has to change their own admin login to share a restricted copy.
            // Full backup: slots verbatim (own login + share fields kept) and the extras are written.
            SettingsBackup(
                theme = theme,
                servers = if (full) servers else servers.map { it.forSharing() },
                zones = zones,
                assignments = assignments,
                coverOverrides = coverOverrides,
                scenes = scenes,
                zoneOrder = zoneOrder,
                full = full,
                expertMode = if (full) expertMode else null,
                hideZoned = if (full) hideZoned else null,
                selectedId = if (full) selectedId else null,
                lastTabs = if (full) lastTabs else emptyMap(),
                appLock = if (full) appLock else null,
                protectedOutputs = if (full) protectedOutputs else null,
            ),
        )

        /**
         * Parse a shared backup. Returns null if [blob] isn't a myIO Home settings file — i.e. it doesn't
         * parse as a JSON object, or its [format] field is missing/wrong (guards against arbitrary JSON,
         * which would otherwise decode to an all-defaults backup since every field has a default), or it's
         * otherwise corrupt. Unit-testable without Android.
         */
        fun decode(blob: String?): SettingsBackup? {
            if (blob.isNullOrBlank()) return null
            val obj: JsonObject = runCatching { json.parseToJsonElement(blob).jsonObject }.getOrNull() ?: return null
            if (obj["format"]?.jsonPrimitive?.contentOrNull != MAGIC) return null
            return runCatching { json.decodeFromJsonElement(serializer(), obj) }.getOrNull()
        }
    }
}
