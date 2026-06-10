package org.vaczi.myiohome.data.repo

import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.net.MyioHttpClient
import org.vaczi.myiohome.data.net.ServerConfig

/**
 * Talks to one myIO server over the port-80 HTTP API.
 *
 * Names live only in the heavier `d_sens_out.json` (+ `/p_desc.xml` for the PCA name bug), so we
 * keep them out of the hot path: [loadCachedSnapshot] returns the last persisted [ServerState] and
 * seeds the in-memory name cache from it instantly, the fast [poll] uses lightweight `sens_out.json`
 * with those cached names re-applied, and [refreshNames] fetches fresh names in the background.
 * [saveSnapshot] persists a labeled state for the next launch.
 */
class MyioRepository(
    val config: ServerConfig,
    private val stateCache: StateCacheStore? = null,
    private val serverId: String = config.host,
) {

    private val client = MyioHttpClient(config)

    @Volatile private var relayNames: Map<Int, String> = emptyMap()
    @Volatile private var pcaNames: Map<Int, String> = emptyMap()
    @Volatile private var pwmNames: Map<Int, String> = emptyMap()
    @Volatile private var sensorNames: Map<Int, String> = emptyMap()

    // turnON/turnOFF (device-configured on/off levels) also live only in d_sens_out, so cache them
    // alongside names and re-apply to the lightweight poll.
    @Volatile private var pcaTurnOn: Map<Int, Int> = emptyMap()
    @Volatile private var pcaTurnOff: Map<Int, Int> = emptyMap()
    @Volatile private var pwmTurnOn: Map<Int, Int> = emptyMap()
    @Volatile private var pwmTurnOff: Map<Int, Int> = emptyMap()

    /** Instant (no network): return the cached snapshot and seed name maps so the first poll labels. */
    suspend fun loadCachedSnapshot(): ServerState? {
        val cached = stateCache?.load(serverId) ?: return null
        cacheNames(cached)
        return cached
    }

    /** Persist a labeled state snapshot for instant rendering on the next launch. */
    suspend fun saveSnapshot(state: ServerState) {
        stateCache?.save(serverId, state)
    }

    /** Server metadata (`/info.json`). */
    suspend fun getInfo(): ServerInfo = client.getInfo()

    /**
     * Background refresh: fetch the named state (+ p_desc PCA-name workaround) and update the
     * in-memory cache, so subsequent [poll]s show the fresh names.
     */
    suspend fun refreshNames() {
        val named = client.getNamedState()
        cacheNames(named)
        supplementPcaNames()
    }

    /**
     * Firmware bug workaround: PCA names for id > 2100 come back empty from d_sens_out.json.
     * Fetch them from `/p_desc.xml` and fill in any blanks. Best-effort: if the endpoint is
     * unavailable (e.g. older firmware), keep the auto/blank names.
     */
    private suspend fun supplementPcaNames() {
        val extra = runCatching { client.fetchPcaNames() }.getOrNull() ?: return
        if (extra.isEmpty()) return
        pcaNames = pcaNames.toMutableMap().apply {
            for ((id, name) in extra) if (this[id].isNullOrBlank()) this[id] = name
        }
    }

    /** Lightweight state poll with cached names re-applied. */
    suspend fun poll(): ServerState = applyNames(client.pollState())

    suspend fun sendCommand(body: String) = client.sendCommand(body)

    private fun cacheNames(s: ServerState) {
        // Merge, don't clobber: keep previously cached names for ids whose fresh name is blank or
        // missing. The PCA name-bug (id > 2100) returns blank names from d_sens_out — they're only
        // filled from the flaky HTTP/0.9 /p_desc.xml. Without this merge, a single failed p_desc fetch
        // (or an empty d_sens response) would wipe good names and the affected tiles vanish from the
        // grid (name.isNotBlank() filter) until a later refresh happens to succeed.
        relayNames = mergeNames(relayNames, s.relays.associate { it.id to it.name })
        pcaNames = mergeNames(pcaNames, s.pcas.associate { it.id to it.name })
        pwmNames = mergeNames(pwmNames, s.pwms.associate { it.id to it.name })
        sensorNames = mergeNames(sensorNames, s.sensors.associate { it.id to it.name })
        pcaTurnOn = s.pcas.associate { it.id to it.turnOn }
        pcaTurnOff = s.pcas.associate { it.id to it.turnOff }
        pwmTurnOn = s.pwms.associate { it.id to it.turnOn }
        pwmTurnOff = s.pwms.associate { it.id to it.turnOff }
    }

    /** Overlay [fresh] onto [old], but never replace an existing name with a blank/missing one. */
    private fun mergeNames(old: Map<Int, String>, fresh: Map<Int, String>): Map<Int, String> =
        old + fresh.filterValues { it.isNotBlank() }

    private fun applyNames(s: ServerState): ServerState = s.copy(
        relays = s.relays.map { it.copy(name = relayNames[it.id] ?: it.name) },
        pcas = s.pcas.map {
            it.copy(
                name = pcaNames[it.id] ?: it.name,
                turnOn = pcaTurnOn[it.id] ?: it.turnOn,
                turnOff = pcaTurnOff[it.id] ?: it.turnOff,
            )
        },
        pwms = s.pwms.map {
            it.copy(
                name = pwmNames[it.id] ?: it.name,
                turnOn = pwmTurnOn[it.id] ?: it.turnOn,
                turnOff = pwmTurnOff[it.id] ?: it.turnOff,
            )
        },
        sensors = s.sensors.map { it.copy(name = sensorNames[it.id] ?: it.name) },
    )
}
