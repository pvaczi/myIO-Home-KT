package org.vaczi.myiohome.ui

import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.memberIds
import org.vaczi.myiohome.data.model.outputKey
import org.vaczi.myiohome.data.net.MyioCommands

/**
 * Zone group control (pure): which of a zone's members take part in "Mind be / ki / fel / le / stop" and
 * "Közös fényerő", plus the command fragments and the optimistic state for them. No Android, no I/O —
 * the ViewModel sends the fragments (batched) and the UI only decides which buttons to show.
 */

/** Cover motion for a group action. */
enum class CoverMove { OPEN, CLOSE, STOP }

/**
 * The eligible members of one server's slice of a zone. Eligible = writable (never command an output the
 * user may not switch), not a cover endpoint (the cover is the member), and not thermostat-controlled
 * (sensor-driven, `sensorId` 1..254: the thermostat would override the command on its next cycle; the sun, 255, is a normal output).
 */
data class GroupMembers(
    val relays: List<RelayOutput> = emptyList(),
    val pcas: List<PcaOutput> = emptyList(),
    val pwms: List<PwmOutput> = emptyList(),
    val covers: List<Cover> = emptyList(),
) {
    /** Dimmable = brightness-capable PCA or any PWM/FET. */
    val dimPcas: List<PcaOutput> get() = pcas.filter { it.isPwm }
    val switchCount: Int get() = relays.size + pcas.size + pwms.size
    val dimCount: Int get() = dimPcas.size + pwms.size
}

/** Pick the eligible members of [state] whose output key is in [keys] (the zone's member keys on this server). */
fun groupMembers(state: ServerState, covers: List<Cover>, keys: Set<String>): GroupMembers {
    val coverIds = covers.memberIds()
    return GroupMembers(
        relays = state.relays.filter { it.id !in coverIds && it.writable && !it.isSensorDriven && it.outputKey() in keys },
        pcas = state.pcas.filter { it.id !in coverIds && it.writable && !it.isSensorDriven && it.outputKey() in keys },
        pwms = state.pwms.filter { it.writable && !it.isSensorDriven && it.outputKey() in keys },
        covers = covers.filter { it.writable && it.outputKey() in keys },
    )
}

/** One server's slice of a zone for the group UI; [enabled] = that server is connected. */
data class ZoneGroupPart(val serverId: String, val members: GroupMembers, val enabled: Boolean)

/** Group-action callbacks, routed by server id (single mode passes the active server's id). */
data class GroupActions(
    val onSwitch: (serverId: String, members: GroupMembers, on: Boolean) -> Unit = { _, _, _ -> },
    val onCovers: (serverId: String, covers: List<Cover>, move: CoverMove) -> Unit = { _, _, _ -> },
    val onLevel: (serverId: String, members: GroupMembers, level: Int) -> Unit = { _, _, _ -> },
)

object GroupControl {

    /** Group on/off: the same per-output commands as a single toggle (the device applies its configured levels). */
    fun onOffFragments(m: GroupMembers, on: Boolean): List<String> =
        m.relays.map { MyioCommands.relay(it.id, on) } +
            m.pcas.map { MyioCommands.pcaOnOff(it.id, on) } +
            m.pwms.map { MyioCommands.fetOnOff(it.id, on) }

    /** Common brightness (0..255) for every dimmable member. */
    fun levelFragments(m: GroupMembers, level: Int): List<String> =
        m.dimPcas.map { MyioCommands.pcaBrightness(it.id, level) } +
            m.pwms.map { MyioCommands.fetBrightness(it.id, level) }

    fun coverFragments(covers: List<Cover>, move: CoverMove): List<String> = covers.map {
        when (move) {
            CoverMove.OPEN -> MyioCommands.coverOpen(it.type, it.upId, it.downId)
            CoverMove.CLOSE -> MyioCommands.coverClose(it.type, it.upId, it.downId)
            CoverMove.STOP -> MyioCommands.coverStop(it.type, it.upId, it.downId)
        }
    }

    /** Optimistic state after a group on/off — same targets a single toggle uses (configured turn-on/off level). */
    fun ServerState.withOnOff(m: GroupMembers, on: Boolean): ServerState {
        val relayIds = m.relays.map { it.id }.toSet()
        val pcaIds = m.pcas.map { it.id }.toSet()
        val pwmIds = m.pwms.map { it.id }.toSet()
        return copy(
            relays = relays.map { if (it.id in relayIds) it.copy(on = on) else it },
            pcas = pcas.map { if (it.id in pcaIds) it.copy(state = if (on) it.turnOn else it.turnOff) else it },
            pwms = pwms.map { if (it.id in pwmIds) it.copy(state = if (on) it.turnOn else it.turnOff) else it },
        )
    }

    /** Optimistic state after a group brightness set. */
    fun ServerState.withLevel(m: GroupMembers, level: Int): ServerState {
        val v = level.coerceIn(0, 255)
        val pcaIds = m.dimPcas.map { it.id }.toSet()
        val pwmIds = m.pwms.map { it.id }.toSet()
        return copy(
            pcas = pcas.map { if (it.id in pcaIds) it.copy(state = v) else it },
            pwms = pwms.map { if (it.id in pwmIds) it.copy(state = v) else it },
        )
    }
}
