package org.vaczi.myiohome.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One output's role in a scene: [outputKey] (see `outputKey()`) on server [serverId], switched on at [level]
 * (relay: 1 = on; PCA/PWM: the captured 1..255 level). Covers are not part of scenes (no position feedback).
 */
@Serializable
data class SceneEntry(val serverId: String, val outputKey: String, val level: Int)

/**
 * A user-named scene: a stored set of "on" outputs, possibly across several servers. [turnOthersOff] = running
 * it also switches off every other eligible output that is currently on (on the servers the scene touches).
 */
@Serializable
data class Scene(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val entries: List<SceneEntry> = emptyList(),
    val turnOthersOff: Boolean = false,
)

private val Context.scenesDataStore: DataStore<Preferences> by preferencesDataStore(name = "scenes")

/** Persists the **global** scene list (one JSON blob, shared across servers). Preferences DataStore → Auto Backup. */
class SceneStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.scenesDataStore)

    val scenes: Flow<List<Scene>> =
        dataStore.data.map { it[SCENES] }.distinctUntilChanged().map { parseScenes(it) }

    /** Atomically read-modify-write the scene list (transform runs inside the transaction). */
    suspend fun update(transform: (List<Scene>) -> List<Scene>) {
        dataStore.edit { it[SCENES] = serialize(transform(parseScenes(it[SCENES]))) }
    }

    /** Replace the whole list (used when importing a shared settings backup). */
    suspend fun replaceAll(list: List<Scene>) {
        dataStore.edit { it[SCENES] = serialize(list) }
    }

    companion object {
        private val SCENES = stringPreferencesKey("scenes_global")
        private val serializer = ListSerializer(Scene.serializer())

        private fun serialize(list: List<Scene>): String = StoreJson.encodeToString(serializer, list)

        /** Pure parse — null/blank/corrupt → empty list. Unit-testable without DataStore. */
        fun parseScenes(blob: String?): List<Scene> = decodeOrDefault(blob, emptyList())

        /** Pure encode counterpart of [parseScenes] (round-trip tests). */
        fun encodeScenes(list: List<Scene>): String = serialize(list)
    }
}
