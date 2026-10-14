package org.vaczi.myiohome.data.net

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide serialization point for one physical myIO device (host:port).
 *
 * The device (W5100, 4 sockets) can't take concurrent connections, and more than one client object may
 * target the same device at once: the single-server and aggregate poll loops overlap briefly on a switch,
 * two slots may share a host, and discovery's name lookup hits port 80 of devices that are being polled.
 * A per-repository lock covers none of these, so every connection to a device goes through the one gate
 * of its host:port.
 */
class DeviceGate private constructor() {

    /** Held for every network call to the device. */
    val net = Mutex()

    /** Serializes POST pacing only, so a poll can still take [net] during the cooldown wait. */
    val postPace = Mutex()

    /** When the last POST to this device finished (ms), shared by every client of the device. */
    @Volatile var lastPostAtMs = 0L

    companion object {
        private val gates = ConcurrentHashMap<String, DeviceGate>()

        /** The gate of [host]:[port]; the host is matched case-insensitively. */
        fun of(host: String, port: Int): DeviceGate =
            gates.computeIfAbsent("${host.trim().lowercase()}:$port") { DeviceGate() }
    }
}
