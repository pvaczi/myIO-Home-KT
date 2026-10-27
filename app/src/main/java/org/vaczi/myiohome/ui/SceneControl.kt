package org.vaczi.myiohome.ui

import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneOrder
import org.vaczi.myiohome.data.local.memberRef
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.outputKey
import org.vaczi.myiohome.data.net.MyioCommands

/**
 * Scenes (pure): capture the currently-on outputs, turn a scene into per-server command fragments (batched by
 * the ViewModel via `sendCommands`) plus the optimistic state, and word the result summary. No Android, no I/O.
 * Member eligibility is [groupMembers]' (writable, not a cover endpoint, not thermostat-controlled), minus the
 * slot's hidden outputs, and only outputs the user marked "Jelenetbe kerülhet" ([SceneServer.allowedKeys]).
 */

/**
 * One server as the scene logic sees it. [connected] = polled and Connected right now (commandable).
 * [allowedKeys] = output keys marked "Jelenetbe kerülhet" (empty = nothing may be captured, run or switched off).
 */
data class SceneServer(
    val id: String,
    val name: String,
    val state: ServerState,
    val covers: List<Cover> = emptyList(),
    val hiddenKeys: Set<String> = emptySet(),
    val connected: Boolean = true,
    val allowedKeys: Set<String> = emptySet(),
)

/** A snapshot of the on-state: the entries and the servers it was taken from (only those are replaced on overwrite). */
data class SceneCapture(val entries: List<SceneEntry>, val serverIds: Set<String>)

/** One row of the capture checklist: [ref] = `serverId|outputKey` (selection key), [label] = display text. */
data class SceneCandidate(val entry: SceneEntry, val ref: String, val label: String)

/** One zone section of the capture checklist ([zoneId] null = "Zóna nélkül"). */
data class SceneCandidateGroup(val zoneId: String?, val title: String, val items: List<SceneCandidate>)

/** What running a scene does on ONE server: the batched fragments and the optimistic state transform. */
class SceneServerPlan(
    val serverId: String,
    val serverName: String,
    val fragments: List<String>,
    val optimistic: (ServerState) -> ServerState,
)

/**
 * The whole run: per-server plans, servers that cannot be reached, entries whose output no longer exists, and
 * entries skipped because their output is not marked "Jelenetbe kerülhet" ([notAllowed]).
 */
data class ScenePlan(
    val servers: List<SceneServerPlan>,
    val unavailable: List<String>,
    val missing: Int,
    val notAllowed: Int = 0,
)

/** Outcome of sending one server's plan. */
enum class SceneOutcome { OK, FAILED }

object SceneControl {

    private fun outputKeys(state: ServerState): Set<String> =
        (state.relays.map { it.outputKey() } + state.pcas.map { it.outputKey() } + state.pwms.map { it.outputKey() }).toSet()

    private fun eligible(s: SceneServer): GroupMembers =
        groupMembers(s.state, s.covers, (outputKeys(s.state) - s.hiddenKeys) intersect s.allowedKeys)

    /** Snapshot every on, eligible (incl. allowed) output of every connected server. */
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

    /**
     * The capture checklist: [capture]'s entries grouped by zone. Zones follow the global [zones] order; an
     * output in several zones is listed under the first of them; unzoned outputs go to a trailing "Zóna nélkül"
     * group; empty groups are dropped. Inside a zone the global member order ([zoneOrder], refs) applies, the
     * rest keeps the capture order. A name that occurs on more than one server is prefixed "Szerver · ".
     */
    fun candidateGroups(
        capture: SceneCapture,
        servers: List<SceneServer>,
        zones: List<Zone>,
        assignments: Map<String, ZoneAssignments>,
        zoneOrder: Map<String, List<String>>,
    ): List<SceneCandidateGroup> {
        val byId = servers.associateBy { it.id }
        val named = capture.entries.map { e ->
            val server = byId[e.serverId]
            e to (server?.state?.let { entryName(it, e.outputKey) } ?: e.outputKey)
        }
        val ambiguous = named.groupBy({ it.second }, { it.first.serverId }).filterValues { it.toSet().size > 1 }.keys
        val zoneRank = zones.withIndex().associate { (i, z) -> z.id to i }
        val byZone = LinkedHashMap<String?, MutableList<SceneCandidate>>()
        for ((e, name) in named) {
            val serverName = byId[e.serverId]?.name ?: e.serverId
            val label = if (name in ambiguous) "$serverName · $name" else name
            val zoneId = assignments[e.serverId]?.assignments?.get(e.outputKey).orEmpty()
                .filter { it in zoneRank }.minByOrNull { zoneRank.getValue(it) }
            byZone.getOrPut(zoneId) { mutableListOf() } += SceneCandidate(e, memberRef(e.serverId, e.outputKey), label)
        }
        val zoned = zones.mapNotNull { z ->
            val items = byZone[z.id] ?: return@mapNotNull null
            val sorted = ZoneOrder.sort(zoneOrder[z.id], items.map { it.ref })
            val itemByRef = items.associateBy { it.ref }
            SceneCandidateGroup(z.id, z.name, sorted.map { itemByRef.getValue(it) })
        }
        val unzoned = byZone[null]?.let { listOf(SceneCandidateGroup(null, "Zóna nélkül", it)) }.orEmpty()
        return zoned + unzoned
    }

    /** Default checklist selection: everything checked. */
    fun defaultSelection(groups: List<SceneCandidateGroup>): Set<String> =
        groups.flatMap { g -> g.items.map { it.ref } }.toSet()

    /** Group header checkbox: all checked → uncheck the whole group, otherwise check all of it. */
    fun toggleGroup(selected: Set<String>, group: SceneCandidateGroup): Set<String> {
        val refs = group.items.map { it.ref }
        return if (selected.containsAll(refs)) selected - refs.toSet() else selected + refs
    }

    /** [capture] narrowed to the checked [selected] refs (the captured server set is kept for overwrite). */
    fun selectedCapture(capture: SceneCapture, selected: Set<String>): SceneCapture =
        capture.copy(entries = capture.entries.filter { memberRef(it.serverId, it.outputKey) in selected })

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
        var notAllowed = 0
        for ((serverId, all) in scene.entries.groupBy { it.serverId }) {
            val server = byId[serverId]
            if (server == null) { missing += all.size; continue }
            if (!server.connected) { unavailable += server.name; continue }
            // Entries whose (still existing) output is not marked "Jelenetbe kerülhet" are skipped and reported;
            // a vanished output stays "missing" below.
            val existing = outputKeys(server.state)
            val entries = all.filter { it.outputKey in server.allowedKeys || it.outputKey !in existing }
            notAllowed += all.size - entries.size
            // Members (and so the "rest off" set) are allowed outputs only.
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
        return ScenePlan(plans, unavailable, missing, notAllowed)
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
            if (plan.notAllowed > 0) add("${plan.notAllowed} kimenet nincs jelenetbe jelölve, kihagyva")
            if (isEmpty()) add("nincs mit végrehajtani")
        }
        return "„$sceneName”: ${parts.joinToString(", ")}"
    }
}
