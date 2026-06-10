package org.vaczi.myiohome.data.repo

import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.net.MyioHttpClient
import org.vaczi.myiohome.data.net.MyioIndexParser
import org.vaczi.myiohome.data.net.Perm
import org.vaczi.myiohome.data.net.ServerConfig

/**
 * Talks to one myIO server over the port-80 HTTP API.
 *
 * Metadata (names, on/off levels, per-user permissions) lives only in the heavier index page (`GET /`),
 * so we keep it out of the hot path: [loadCachedSnapshot] returns the last persisted [ServerState] and
 * seeds the in-memory caches from it instantly, the fast [poll] uses lightweight `sens_out.json` with
 * those cached names/levels/permissions re-applied, and [refreshMetadata] fetches a fresh index in the
 * background. Outputs the logged-in user can't read are dropped on apply (they vanish like on the device's
 * own page); ones they can't write keep [org.vaczi.myiohome.data.model.RelayOutput.writable] = false so
 * the UI locks them. [saveSnapshot] persists a labeled state for the next launch.
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

    // turnON/turnOFF (device-configured on/off levels) also live only in the index, so cache them
    // alongside names and re-apply to the lightweight poll.
    @Volatile private var pcaTurnOn: Map<Int, Int> = emptyMap()
    @Volatile private var pcaTurnOff: Map<Int, Int> = emptyMap()
    @Volatile private var pwmTurnOn: Map<Int, Int> = emptyMap()
    @Volatile private var pwmTurnOff: Map<Int, Int> = emptyMap()

    // Per-user permissions from the index (sparse: a missing id is unknown → fail open, keep + writable).
    @Volatile private var relayPerms: Map<Int, Perm> = emptyMap()
    @Volatile private var pcaPerms: Map<Int, Perm> = emptyMap()
    @Volatile private var pwmPerms: Map<Int, Perm> = emptyMap()

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
     * Background refresh: fetch the index page and update the in-memory caches (names, on/off levels,
     * per-user permissions), so subsequent [poll]s reflect them. One standard HTTP/1.1 fetch replaces
     * the former d_sens_out.json + HTTP/0.9 p_desc.xml pair; it also carries PCA names for id > 2100.
     */
    suspend fun refreshMetadata() {
        val meta = MyioIndexParser.parse(client.getIndex())
        relayNames = mergeNames(relayNames, meta.relayNames)
        pcaNames = mergeNames(pcaNames, meta.pcaNames)
        pwmNames = mergeNames(pwmNames, meta.pwmNames)
        sensorNames = mergeNames(sensorNames, meta.sensorNames)
        if (meta.pcaTurnOn.isNotEmpty()) pcaTurnOn = meta.pcaTurnOn
        if (meta.pcaTurnOff.isNotEmpty()) pcaTurnOff = meta.pcaTurnOff
        if (meta.pwmTurnOn.isNotEmpty()) pwmTurnOn = meta.pwmTurnOn
        if (meta.pwmTurnOff.isNotEmpty()) pwmTurnOff = meta.pwmTurnOff
        if (meta.relayPerms.isNotEmpty()) relayPerms = meta.relayPerms
        if (meta.pcaPerms.isNotEmpty()) pcaPerms = meta.pcaPerms
        if (meta.pwmPerms.isNotEmpty()) pwmPerms = meta.pwmPerms
    }

    /** Lightweight state poll with cached names/levels/permissions re-applied. */
    suspend fun poll(): ServerState = applyCached(client.pollState())

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
        // The snapshot only holds outputs the user can read (non-readable ones were dropped before it was
        // saved), so seed permissions as readable, carrying each output's last-known writable flag. This
        // keeps locked tiles locked on the instant cached render, until the fresh index refines them.
        relayPerms = s.relays.associate { it.id to Perm(readable = true, writable = it.writable) }
        pcaPerms = s.pcas.associate { it.id to Perm(readable = true, writable = it.writable) }
        pwmPerms = s.pwms.associate { it.id to Perm(readable = true, writable = it.writable) }
    }

    /** Overlay [fresh] onto [old], but never replace an existing name with a blank/missing one. */
    private fun mergeNames(old: Map<Int, String>, fresh: Map<Int, String>): Map<Int, String> =
        old + fresh.filterValues { it.isNotBlank() }

    /**
     * Re-apply cached metadata to a fresh poll: overlay names + on/off levels, set each output's
     * [writable] flag, and drop outputs the user has no read permission for (a known-unreadable id),
     * mirroring the device's own page. A missing permission entry is "unknown" → kept and writable.
     */
    private fun applyCached(s: ServerState): ServerState = s.copy(
        relays = s.relays.mapNotNull { r ->
            val perm = relayPerms[r.id]
            if (perm?.readable == false) null
            else r.copy(name = relayNames[r.id] ?: r.name, writable = perm?.writable ?: true)
        },
        pcas = s.pcas.mapNotNull { p ->
            val perm = pcaPerms[p.id]
            if (perm?.readable == false) null
            else p.copy(
                name = pcaNames[p.id] ?: p.name,
                turnOn = pcaTurnOn[p.id] ?: p.turnOn,
                turnOff = pcaTurnOff[p.id] ?: p.turnOff,
                writable = perm?.writable ?: true,
            )
        },
        pwms = s.pwms.mapNotNull { w ->
            val perm = pwmPerms[w.id]
            if (perm?.readable == false) null
            else w.copy(
                name = pwmNames[w.id] ?: w.name,
                turnOn = pwmTurnOn[w.id] ?: w.turnOn,
                turnOff = pwmTurnOff[w.id] ?: w.turnOff,
                writable = perm?.writable ?: true,
            )
        },
        sensors = s.sensors.map { it.copy(name = sensorNames[it.id] ?: it.name) },
    )
}
