package org.vaczi.myiohome.ui

import java.text.Normalizer
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.expandHiddenForDisabledCovers

/**
 * Output search + "on" filter (pure). Matches a case- and accent-insensitive substring of the output name
 * or its type label, or the exact numeric id; an empty query with [onlyOn] lists every output that is
 * currently on. Hidden outputs are excluded by the caller via [SearchSource.hidden].
 */

/** One server's contribution to a search: live state, its detected covers and keys the user hid. */
data class SearchSource(
    val serverId: String,
    val state: ServerState,
    val covers: List<Cover>,
    val hidden: Set<String>,
    val enabled: Boolean,
)

/**
 * Search sources of the aggregate "Otthon (összes)" view: every polled server in slot order. Output-level
 * hides ([MyioServer.hiddenOutputs], expanded for disabled covers) apply; [MyioServer.hidden] does NOT —
 * it only drops the slot from the server switcher, its outputs still belong to the aggregate (a family
 * setup typically hides every slot, which used to leave the aggregate search empty).
 */
internal fun aggregateSearchSources(
    servers: List<MyioServer>,
    perServer: Map<String, MyioUiState>,
    covers: Map<String, List<Cover>>,
    disabledMembers: Map<String, Map<String, List<String>>>,
): List<SearchSource> = servers.filter { it.id in perServer }.map { s ->
    val per = perServer.getValue(s.id)
    SearchSource(
        s.id, per.state, covers[s.id].orEmpty(),
        expandHiddenForDisabledCovers(s.hiddenOutputs.toSet(), disabledMembers[s.id].orEmpty()),
        per.status == ConnectionStatus.Connected,
    )
}

/** A search hit: the renderable tile, tagged with its owning server. */
internal data class SearchResult(val serverId: String, val tile: HomeTile, val enabled: Boolean)

/** Lower-case, accent-stripped form (á→a, ő→o, ű→u …) used on both sides of the comparison. */
fun foldText(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private val TYPE_LABELS = mapOf(
    "relay" to "relé",
    "pca" to "PCA",
    "pwm" to "PWM",
    "sensor" to "szenzor",
    "cover" to "redőny",
)

private fun typeLabel(t: HomeTile): String = when (t) {
    is HomeTile.Relay -> TYPE_LABELS.getValue("relay")
    is HomeTile.Pca -> TYPE_LABELS.getValue("pca")
    is HomeTile.Pwm -> TYPE_LABELS.getValue("pwm")
    is HomeTile.Sens -> TYPE_LABELS.getValue("sensor")
    is HomeTile.Cover -> TYPE_LABELS.getValue("cover")
}

private fun ids(t: HomeTile): List<Int> = when (t) {
    is HomeTile.Relay -> listOf(t.o.id)
    is HomeTile.Pca -> listOf(t.o.id)
    is HomeTile.Pwm -> listOf(t.o.id)
    is HomeTile.Sens -> listOf(t.o.id)
    is HomeTile.Cover -> listOf(t.o.upId, t.o.downId)
}

/** Currently on: relay on, PCA/PWM level > 0, cover moving. Sensors are never "on". */
internal fun isOn(t: HomeTile): Boolean = when (t) {
    is HomeTile.Relay -> t.o.on
    is HomeTile.Pca -> t.o.state > 0
    is HomeTile.Pwm -> t.o.state > 0
    is HomeTile.Sens -> false
    is HomeTile.Cover -> t.o.state != CoverState.STOPPED
}

/** True when [query] (already trimmed, non-empty) matches the tile by name, type label or numeric id. */
internal fun matchesQuery(t: HomeTile, query: String): Boolean {
    val q = foldText(query.trim())
    if (q.isEmpty()) return true
    if (foldText(t.name).contains(q)) return true
    if (foldText(typeLabel(t)).contains(q)) return true
    return q.all { it.isDigit() } && ids(t).any { it.toString() == q }
}

/**
 * Search across [sources] (slot order). Empty query + [onlyOn] off = nothing (the UI shows a hint);
 * empty query + [onlyOn] on = every output that is on.
 */
internal fun searchOutputs(sources: List<SearchSource>, query: String, onlyOn: Boolean): List<SearchResult> {
    val q = query.trim()
    if (q.isEmpty() && !onlyOn) return emptyList()
    return sources.flatMap { src ->
        homeTilesFor(src.state, src.covers) { it !in src.hidden }
            .filter { (!onlyOn || isOn(it)) && matchesQuery(it, q) }
            .map { SearchResult(src.serverId, it, src.enabled) }
    }
}
