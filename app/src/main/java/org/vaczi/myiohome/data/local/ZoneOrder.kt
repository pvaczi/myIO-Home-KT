package org.vaczi.myiohome.data.local

/**
 * A zone member reference that is unique across servers: `"<serverId>|<outputKey>"` (output keys such as
 * `R3` repeat on every server). Covers use their `C…` cover key like any other output key.
 */
fun memberRef(serverId: String, outputKey: String): String = "$serverId|$outputKey"

fun memberRefServer(ref: String): String = ref.substringBefore('|')

fun memberRefKey(ref: String): String = ref.substringAfter('|')

/**
 * Pure logic of the **global per-zone member order** (`zoneId → ordered member refs`, see [memberRef]).
 * One order per zone serves the aggregate view (all servers interleaved) and each single-server view
 * (the same order filtered to that server), so both always agree.
 */
object ZoneOrder {

    /** [refs] sorted by [order] (saved refs first in saved order, the rest appended in their given order). Stable. */
    fun sort(order: List<String>?, refs: List<String>): List<String> {
        if (order.isNullOrEmpty()) return refs
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { i, r -> rank.putIfAbsent(r, i) }
        return refs.sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    /**
     * Move the member at [index] of the currently [displayed] refs one step up/down relative to its
     * displayed neighbour, and return the new global order (null at the ends = no-op). [displayed] is the
     * visible, already-sorted list of the view (the whole zone in aggregate, one server's slice in a
     * single-server view): the moved ref lands right before/after that neighbour in the global list, so
     * refs of other servers (or hidden/offline ones) keep their places. Displayed refs missing from
     * [saved] are appended in display order first, so persisting never reshuffles what was shown.
     */
    fun move(saved: List<String>?, displayed: List<String>, index: Int, up: Boolean): List<String>? {
        val ref = displayed.getOrNull(index) ?: return null
        val neighbour = displayed.getOrNull(if (up) index - 1 else index + 1) ?: return null
        val base = saved.orEmpty().distinct().let { s ->
            val known = s.toSet()
            s + displayed.filter { it !in known }
        }.toMutableList()
        base.remove(ref)
        val n = base.indexOf(neighbour)
        base.add(if (up) n else n + 1, ref)
        return base
    }

    /**
     * One-time migration of the legacy per-server `memberOrder`s: for every zone that has **no** global
     * order yet, concatenate the servers' legacy orders (servers in [serverIds] slot order, each server's
     * members in its legacy order). Zones that already have an order are left untouched.
     */
    fun migrate(
        existing: Map<String, List<String>>,
        serverIds: List<String>,
        legacy: Map<String, Map<String, List<String>>>,
    ): Map<String, List<String>> {
        val zoneIds = serverIds.flatMap { legacy[it]?.keys.orEmpty() }.distinct().filter { it !in existing }
        if (zoneIds.isEmpty()) return existing
        val result = existing.toMutableMap()
        for (z in zoneIds) {
            val merged = serverIds.flatMap { sid -> legacy[sid]?.get(z).orEmpty().map { memberRef(sid, it) } }.distinct()
            if (merged.isNotEmpty()) result[z] = merged
        }
        return result
    }

    /** Drop every ref matching [drop] from all zone orders (empty lists are kept: "has an order" persists). */
    fun prune(order: Map<String, List<String>>, drop: (String) -> Boolean): Map<String, List<String>> =
        order.mapValues { (_, refs) -> refs.filterNot(drop) }

    /** The global [order] as seen by one server: refs of [serverId] only, with the server prefix stripped. */
    fun forServer(order: Map<String, List<String>>, serverId: String): Map<String, List<String>> =
        order.mapValues { (_, refs) -> refs.filter { memberRefServer(it) == serverId }.map(::memberRefKey) }
}
