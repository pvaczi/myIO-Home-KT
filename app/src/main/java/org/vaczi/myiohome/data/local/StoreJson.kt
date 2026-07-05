package org.vaczi.myiohome.data.local

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The one shared JSON codec for every DataStore-backed store (SettingsStore, ZoneStore, CoverStore,
 * StateCacheStore). Previously each store spun up its own identical `Json { ... }`; a single instance
 * keeps the configuration in one place.
 *
 * - [Json.ignoreUnknownKeys]: forward-compatible — a newer field in a persisted blob won't crash an
 *   older parse.
 * - [Json.encodeDefaults]: defaulted fields (e.g. a randomly-generated id) are always written back, not
 *   skipped, so old data self-heals on the next save.
 *
 * [SettingsBackup] deliberately keeps its **own** codec (derived from this one, +prettyPrint) because
 * the shareable file is human-readable and its envelope parse needs `parseToJsonElement` for the magic
 * guard — see the comment there.
 */
internal val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Decode [blob] as [T] via [StoreJson], returning [default] on a null/blank blob or any parse failure.
 * Collapses the `if (blob.isNullOrBlank()) ... runCatching { ... }.getOrDefault(...)` pattern the stores
 * repeated. Pure — unit-testable without DataStore.
 */
internal inline fun <reified T> decodeOrDefault(blob: String?, default: T): T {
    if (blob.isNullOrBlank()) return default
    return runCatching { StoreJson.decodeFromString<T>(blob) }.getOrDefault(default)
}
