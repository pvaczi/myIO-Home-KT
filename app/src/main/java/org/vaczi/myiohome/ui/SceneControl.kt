package org.vaczi.myiohome.ui

import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.outputKey
import org.vaczi.myiohome.data.net.MyioCommands

/**
 * Scenes (pure): capture the currently-on outputs, turn a scene into per-server command fragments (batched by
 * the ViewModel via `sendCommands`) plus the optimistic state, and word the result summary. No Android, no I/O.
 * Member eligibility is [groupMembers]' (writable, not a cover endpoint, not thermostat-controlled), minus the
 * slot's hidden outputs.
 */

/** One server as the scene logic sees it. [connected] = polled and Connected right now (commandable). */
data class SceneServer(
    val id: String,
    val name: String,
    val state: ServerState,
    val covers: List<Cover> = emptyList(),
    val hiddenKeys: Set<String> = emptySet(),
    val connected: Boolean = true,
)

/** A snapshot of the on-state: the entries and the servers it was taken from (only those are replaced on overwrite). */
data class SceneCapture(val entries: List<SceneEntry>, val serverIds: Set<String>)

/** What running a scene does on ONE server: the batched fragments and the optimistic state transform. */
class SceneServerPlan(
    val serverId: String,
    val serverName: String,
    val fragments: List<String>,
    val optimistic: (ServerState) -> ServerState,
)

/** The whole run: per-server plans, servers that cannot be reached, and entries whose output no longer exists. */
data class ScenePlan(
    val servers: List<SceneServerPlan>,
    val unavailable: List<String>,
    val missing: Int,
)

/** Outcome of sending one server's plan. */
enum class SceneOutcome { OK, FAILED }

object SceneControl {

    private fun eligible(s: SceneServer): GroupMembers {
        val all = (s.state.relays.map { it.outputKey() } + s.state.pcas.map { it.outputKey() } +
            s.state.pwms.map { it.outputKey() }).toSet()
        return groupMembers(s.state, s.covers, all - s.hiddenKeys)
    }

    /** Snapshot every on, eligible output of every connected server. */
    fun capture(servers: List<SceneServer>): SceneCapture {
        val live = servers.filter { it.connected }
        val entries = live.flatMap { s ->
            val m = eligible(s)
            m.relays.filter { it.on }.map { SceneEntry(s.id, it.outputKey(), 1) } +
                m.pcas.filter { it.on }.map { SceneEntry(s.id, it.outputKey(), it.state) } +
                m.pwms.filter { it.on }.map { SceneEntry(s.id, it.outputKey(), it.state) }
        }
        return SceneCapture(entries, live.map { it.id }.toSet())
    }

    /** Overwrite: [old] entries of servers not in the capture are kept (unreachable now), the rest is replaced. */
    fun mergeCapture(old: List<SceneEntry>, capture: SceneCapture): List<SceneEntry> =
        old.filter { it.serverId !in capture.serverIds } + capture.entries

    /** "Jelenet N" with the smallest N not yet used. */
    fun defaultName(existing: List<Scene>): String {
        val used = existing.map { it.name }.toSet()
        var n = existing.size + 1
        while ("Jelenet $n" in used) n++
        return "Jelenet $n"
    }

    /** Display name of the output behind [key] (null if it no longer exists). */
    fun entryName(state: ServerState, key: String): String? =
        state.relays.firstOrNull { it.outputKey() == key }?.name
            ?: state.pcas.firstOrNull { it.outputKey() == key }?.name
            ?: state.pwms.firstOrNull { it.outputKey() == key }?.name

    /** Does [scene] have any entry on [serverId]? */
    fun touches(scene: Scene, serverId: String): Boolean = scene.entries.any { it.serverId == serverId }

    fun plan(scene: Scene, servers: List<SceneServer>): ScenePlan {
        val byId = servers.associateBy { it.id }
        val plans = mutableListOf<SceneServerPlan>()
        val unavailable = mutableListOf<String>()
        var missing = 0
        for ((serverId, entries) in scene.entries.groupBy { it.serverId }) {
            val server = byId[serverId]
            if (server == null) { missing += entries.size; continue }
            if (!server.connected) { unavailable += server.name; continue }
            val members = eligible(server)
            val keys = entries.associateBy { it.outputKey }
            val relays = members.relays.filter { it.outputKey() in keys }
            val pcas = members.pcas.filter { it.outputKey() in keys }
            val pwms = members.pwms.filter { it.outputKey() in keys }
            missing += entries.size - (relays.size + pcas.size + pwms.size)

            // "The rest off": eligible outputs outside the scene that are on now (off ones need no command).
            val others = if (scene.turnOthersOff) GroupMembers(
                relays = members.relays.filter { it.outputKey() !in keys && it.on },
                pcas = members.pcas.filter { it.outputKey() !in keys && it.on },
                pwms = members.pwms.filter { it.outputKey() !in keys && it.on },
            ) else GroupMembers()

            val pcaLevels = pcas.associate { it.id to if (it.isPwm) keys.getValue(it.outputKey()).level else it.turnOn }
            val pwmLevels = pwms.associate { it.id to keys.getValue(it.outputKey()).level }
            val fragments = GroupControl.onOffFragments(others, on = false) +
                relays.map { MyioCommands.relay(it.id, true) } +
                pcas.map { if (it.isPwm) MyioCommands.pcaBrightness(it.id, pcaLevels.getValue(it.id)) else MyioCommands.pcaOnOff(it.id, true) } +
                pwms.map { MyioCommands.fetBrightness(it.id, pwmLevels.getValue(it.id)) }
            if (fragments.isEmpty()) continue

            val relayIds = relays.map { it.id }.toSet()
            plans += SceneServerPlan(server.id, server.name, fragments) { st ->
                val off = with(GroupControl) { st.withOnOff(others, false) }
                off.copy(
                    relays = off.relays.map { if (it.id in relayIds) it.copy(on = true) else it },
                    pcas = off.pcas.map { p -> pcaLevels[p.id]?.let { p.copy(state = it) } ?: p },
                    pwms = off.pwms.map { p -> pwmLevels[p.id]?.let { p.copy(state = it) } ?: p },
                )
            }
        }
        return ScenePlan(plans, unavailable, missing)
    }

    /** One Hungarian snackbar line, e.g. `„Este”: 2 szerver kész, kihagyva: Redőny (nem elérhető)`. */
    fun summary(sceneName: String, plan: ScenePlan, outcomes: List<Pair<String, SceneOutcome>>): String {
        val ok = outcomes.count { it.second == SceneOutcome.OK }
        val failed = outcomes.filter { it.second == SceneOutcome.FAILED }.map { it.first }
        val parts = buildList {
            if (ok > 0) add("$ok szerver kész")
            if (plan.unavailable.isNotEmpty()) add("kihagyva: ${plan.unavailable.joinToString()} (nem elérhető)")
            if (failed.isNotEmpty()) add("nem sikerült: ${failed.joinToString()}")
            if (plan.missing > 0) add("${plan.missing} kimenet már nem létezik")
            if (isEmpty()) add("nincs mit végrehajtani")
        }
        return "„$sceneName”: ${parts.joinToString(", ")}"
    }
}
