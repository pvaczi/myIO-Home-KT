package org.vaczi.myiohome.data.repo

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.net.DeviceGate
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
 *
 * **Concurrency:** the myIO device (W5100, 4 sockets) can't take concurrent TCP connections and a POST
 * storm freezes it for ~1 minute, so every network call is serialized on the device's [DeviceGate] — shared
 * by every repository (and discovery) talking to the same host:port, not just this instance — and
 * consecutive POSTs to the device are paced ≥ [POST_COOLDOWN_MS] apart. The pacing delay is held on the
 * gate's separate `postPace` lock (not `net`), so a poll can still slip in during the cooldown.
 */
class MyioRepository(
    val config: ServerConfig,
    private val stateCache: StateCacheStore? = null,
    private val serverId: String = config.host,
) {

    private val client = MyioHttpClient(config)

    // Serializes ALL network I/O to this device (poll / getInfo / refreshMetadata / sendCommand), across
    // every repository instance, so no two connections to the device overlap.
    private val gate = DeviceGate.of(config.host, config.port)
    private val netMutex = gate.net

    /**
     * All index/snapshot-derived metadata as one immutable snapshot, replaced atomically. Keeping the maps
     * in a single @Volatile ref avoids torn reads (half-old/half-new names vs. perms) that separate
     * per-field volatiles allowed.
     */
    private data class CachedMeta(
        val relayNames: Map<Int, String> = emptyMap(),
        val pcaNames: Map<Int, String> = emptyMap(),
        val pwmNames: Map<Int, String> = emptyMap(),
        val sensorNames: Map<Int, String> = emptyMap(),
        // turnON/turnOFF (device-configured on/off levels) also live only in the index.
        val pcaTurnOn: Map<Int, Int> = emptyMap(),
        val pcaTurnOff: Map<Int, Int> = emptyMap(),
        val pwmTurnOn: Map<Int, Int> = emptyMap(),
        val pwmTurnOff: Map<Int, Int> = emptyMap(),
        // Per-user permissions (sparse: a missing id is unknown → fail open, keep + writable).
        val relayPerms: Map<Int, Perm> = emptyMap(),
        val pcaPerms: Map<Int, Perm> = emptyMap(),
        val pwmPerms: Map<Int, Perm> = emptyMap(),
    )

    @Volatile private var meta = CachedMeta()

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
    suspend fun getInfo(): ServerInfo = netMutex.withLock { client.getInfo() }

    /**
     * Background refresh: fetch the index page and update the in-memory caches (names, on/off levels,
     * per-user permissions), so subsequent [poll]s reflect them. One standard HTTP/1.1 fetch replaces
     * the former d_sens_out.json + HTTP/0.9 p_desc.xml pair; it also carries PCA names for id > 2100.
     */
    suspend fun refreshMetadata() {
        val idx = netMutex.withLock { MyioIndexParser.parse(client.getIndex()) }
        meta = merge(
            meta,
            CachedMeta(
                relayNames = idx.relayNames,
                pcaNames = idx.pcaNames,
                pwmNames = idx.pwmNames,
                sensorNames = idx.sensorNames,
                pcaTurnOn = idx.pcaTurnOn,
                pcaTurnOff = idx.pcaTurnOff,
                pwmTurnOn = idx.pwmTurnOn,
                pwmTurnOff = idx.pwmTurnOff,
                relayPerms = idx.relayPerms,
                pcaPerms = idx.pcaPerms,
                pwmPerms = idx.pwmPerms,
            ),
        )
    }

    /** Lightweight state poll with cached names/levels/permissions re-applied. */
    suspend fun poll(): ServerState = applyCached(netMutex.withLock { client.pollState() })

    /**
     * POST a control command. Paces consecutive POSTs ≥ [POST_COOLDOWN_MS] apart (the device freezes under
     * a burst of writes). The wait is on the gate's `postPace` lock, so a concurrent [poll] can still take
     * [netMutex] during the cooldown; only the actual POST holds [netMutex].
     */
    suspend fun sendCommand(body: String) = gate.postPace.withLock {
        val wait = POST_COOLDOWN_MS - (System.currentTimeMillis() - gate.lastPostAtMs)
        if (wait > 0) delay(wait)
        netMutex.withLock { client.sendCommand(body) }
        gate.lastPostAtMs = System.currentTimeMillis()
    }

    private fun cacheNames(s: ServerState) {
        // Merge, don't clobber: keep previously cached names for ids whose fresh name is blank or
        // missing. The PCA name-bug (id > 2100) returns blank names from d_sens_out — they're only
        // filled from the flaky HTTP/0.9 /p_desc.xml. Without this merge, a single failed p_desc fetch
        // (or an empty d_sens response) would wipe good names and the affected tiles vanish from the
        // grid (name.isNotBlank() filter) until a later refresh happens to succeed.
        //
        // The snapshot only holds outputs the user can read (non-readable ones were dropped before it was
        // saved), so seed permissions as readable, carrying each output's last-known writable flag. This
        // keeps locked tiles locked on the instant cached render, until the fresh index refines them.
        meta = merge(
            meta,
            CachedMeta(
                relayNames = s.relays.associate { it.id to it.name },
                pcaNames = s.pcas.associate { it.id to it.name },
                pwmNames = s.pwms.associate { it.id to it.name },
                sensorNames = s.sensors.associate { it.id to it.name },
                pcaTurnOn = s.pcas.associate { it.id to it.turnOn },
                pcaTurnOff = s.pcas.associate { it.id to it.turnOff },
                pwmTurnOn = s.pwms.associate { it.id to it.turnOn },
                pwmTurnOff = s.pwms.associate { it.id to it.turnOff },
                relayPerms = s.relays.associate { it.id to Perm(readable = true, writable = it.writable) },
                pcaPerms = s.pcas.associate { it.id to Perm(readable = true, writable = it.writable) },
                pwmPerms = s.pwms.associate { it.id to Perm(readable = true, writable = it.writable) },
            ),
        )
    }

    /**
     * Merge [fresh] onto [old] with the established policy: names overlay (a blank/missing fresh name never
     * replaces an existing one); levels + permissions replace wholesale but only when [fresh] carries them
     * (an empty fresh map — parse miss / older fw — keeps the previous values rather than wiping them).
     */
    private fun merge(old: CachedMeta, fresh: CachedMeta): CachedMeta = CachedMeta(
        relayNames = mergeNames(old.relayNames, fresh.relayNames),
        pcaNames = mergeNames(old.pcaNames, fresh.pcaNames),
        pwmNames = mergeNames(old.pwmNames, fresh.pwmNames),
        sensorNames = mergeNames(old.sensorNames, fresh.sensorNames),
        pcaTurnOn = fresh.pcaTurnOn.ifEmpty { old.pcaTurnOn },
        pcaTurnOff = fresh.pcaTurnOff.ifEmpty { old.pcaTurnOff },
        pwmTurnOn = fresh.pwmTurnOn.ifEmpty { old.pwmTurnOn },
        pwmTurnOff = fresh.pwmTurnOff.ifEmpty { old.pwmTurnOff },
        relayPerms = fresh.relayPerms.ifEmpty { old.relayPerms },
        pcaPerms = fresh.pcaPerms.ifEmpty { old.pcaPerms },
        pwmPerms = fresh.pwmPerms.ifEmpty { old.pwmPerms },
    )

    /** Overlay [fresh] onto [old], but never replace an existing name with a blank/missing one. */
    private fun mergeNames(old: Map<Int, String>, fresh: Map<Int, String>): Map<Int, String> =
        old + fresh.filterValues { it.isNotBlank() }

    /**
     * Re-apply cached metadata to a fresh poll: overlay names + on/off levels, set each output's
     * [writable] flag, and drop outputs the user has no read permission for (a known-unreadable id),
     * mirroring the device's own page. A missing permission entry is "unknown" → kept and writable.
     */
    private fun applyCached(s: ServerState): ServerState {
        val m = meta
        return s.copy(
            relays = s.relays.mapNotNull { r ->
                val perm = m.relayPerms[r.id]
                if (perm?.readable == false) null
                else r.copy(name = m.relayNames[r.id] ?: r.name, writable = perm?.writable ?: true)
            },
            pcas = s.pcas.mapNotNull { p ->
                val perm = m.pcaPerms[p.id]
                if (perm?.readable == false) null
                else p.copy(
                    name = m.pcaNames[p.id] ?: p.name,
                    turnOn = m.pcaTurnOn[p.id] ?: p.turnOn,
                    turnOff = m.pcaTurnOff[p.id] ?: p.turnOff,
                    writable = perm?.writable ?: true,
                )
            },
            pwms = s.pwms.mapNotNull { w ->
                val perm = m.pwmPerms[w.id]
                if (perm?.readable == false) null
                else w.copy(
                    name = m.pwmNames[w.id] ?: w.name,
                    turnOn = m.pwmTurnOn[w.id] ?: w.turnOn,
                    turnOff = m.pwmTurnOff[w.id] ?: w.turnOff,
                    writable = perm?.writable ?: true,
                )
            },
            sensors = s.sensors.map { it.copy(name = m.sensorNames[it.id] ?: it.name) },
        )
    }

    companion object {
        /** Minimum spacing between consecutive POSTs (the device freezes under a burst of writes). */
        private const val POST_COOLDOWN_MS = 1_000L
    }
}
