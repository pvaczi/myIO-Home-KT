package org.vaczi.myiohome.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
) {
    companion object {
        const val MAGIC = "myio-home-settings"
        const val CURRENT_VERSION = 1

        // encodeDefaults so the magic/version are always written; prettyPrint so a shared file is readable.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(
            theme: AppTheme,
            servers: List<MyioServer>,
            zones: List<Zone>,
            assignments: Map<String, ZoneAssignments>,
        ): String = json.encodeToString(
            serializer(),
            SettingsBackup(theme = theme, servers = servers, zones = zones, assignments = assignments),
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
