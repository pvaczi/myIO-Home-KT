package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.model.Cover as ModelCover
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.memberIds
import org.vaczi.myiohome.data.model.outputKey

/**
 * The "Otthon" dashboard: one section per zone, each holding its assigned outputs/sensors as the same
 * inline live controls used on the type tabs (reused via the shared control tiles). An output may be in
 * several zones (it shows in each). An optional "Besorolatlan" section lists everything not yet zoned.
 * Empty state (no zones) offers to create the first zone.
 */
@Composable
fun HomeScreen(
    state: ServerState,
    zoneConfig: ZoneConfig,
    actions: MyioActions,
    enabled: Boolean,
    reorderMode: Boolean,
    /** Detected covers for this server; rendered as shutter tiles, their member outputs excluded. */
    covers: List<ModelCover> = emptyList(),
    coverActions: CoverActions = CoverActions(),
    /** Output keys to exclude from display (non-expert hidden set); empty in expert mode. */
    hiddenKeys: Set<String> = emptySet(),
    onAddZone: (String) -> String?,
    onRenameZone: (id: String, name: String) -> Unit,
    onDeleteZone: (String) -> Unit,
    onMoveZone: (id: String, up: Boolean) -> Unit,
    onReorderMembers: (zoneId: String, orderedKeys: List<String>) -> Unit,
    /** Active server's id + group-action callbacks (zone "Mind be/ki…"); gated by [enabled] like the tiles. */
    serverId: String = "",
    groupActions: GroupActions = GroupActions(),
    /** Scene chips (only scenes touching this server are shown); editing needs [expertMode]. */
    sceneActions: SceneActions = SceneActions(),
    expertMode: Boolean = false,
) {
    val serverScenes = remember(sceneActions.scenes, serverId) { sceneActions.scenes.filter { SceneControl.touches(it, serverId) } }
    if (zoneConfig.zones.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (!reorderMode) SceneBar(serverScenes, expertMode, sceneActions, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Box(Modifier.weight(1f)) { EmptyZones(onAddZone) }
        }
        return
    }

    // Collapsed sections by id (zone id, or UNASSIGNED_KEY for the "Besorolatlan" section).
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val zones = zoneConfig.zones
    // Build every eligible tile once, then split it into per-zone member lists + the "Besorolatlan"
    // remainder by indexing this map — instead of re-scanning the whole state once per zone.
    val (perZoneMembers, unassigned) = remember(state, covers, zoneConfig, hiddenKeys) {
        val allTiles = homeTilesFor(state, covers) { it !in hiddenKeys }
        val byKey = allTiles.associateBy { it.key }
        val perZone = zones.associate { zone ->
            val raw = allTiles.filter { zone.id in zoneConfig.zonesOf(it.key) }
            zone.id to zoneConfig.orderedMembers(zone.id, raw.map { it.key }).mapNotNull { byKey[it] }
        }
        val remainder = allTiles.filter { !zoneConfig.isAssigned(it.key) }
        perZone to remainder
    }
    // Eligible group-control members per zone (writable, non-cover-endpoint, not thermostat-driven).
    val groupParts = remember(perZoneMembers, state, covers, serverId, enabled) {
        zones.associate { zone ->
            val keys = perZoneMembers[zone.id].orEmpty().map { it.key }.toSet()
            zone.id to listOf(ZoneGroupPart(serverId, groupMembers(state, covers, keys), enabled))
        }
    }
    TwoColumnLazyGrid {
        if (!reorderMode && (serverScenes.isNotEmpty() || expertMode)) {
            item(key = "scenes", span = { GridItemSpan(maxLineSpan) }) { SceneBar(serverScenes, expertMode, sceneActions) }
        }
        zones.forEachIndexed { index, zone ->
            val members = perZoneMembers[zone.id].orEmpty()
            // While reordering, sections are forced open so members are visible to move.
            val isCollapsed = !reorderMode && collapsed[zone.id] == true
            zoneHeaderItem(
                zone = zone,
                index = index,
                zones = zones,
                reorderMode = reorderMode,
                collapsed = isCollapsed,
                onToggleCollapse = { collapsed[zone.id] = !isCollapsed },
                onRenameZone = onRenameZone,
                onDeleteZone = onDeleteZone,
                onMoveZone = onMoveZone,
                groupParts = groupParts[zone.id].orEmpty(),
                groupActions = groupActions,
            )
            if (!isCollapsed) {
                if (members.isEmpty()) {
                    item(key = "e/${zone.id}", span = { GridItemSpan(maxLineSpan) }) {
                        ZoneEmptyText()
                    }
                } else if (reorderMode) {
                    // Full-width rows with up/down arrows so the order is unambiguous (no left/right).
                    members.forEachIndexed { i, tile ->
                        item(key = "r/${zone.id}/${tile.key}", span = { GridItemSpan(maxLineSpan) }) {
                            ReorderMemberRow(
                                name = tile.name,
                                isFirst = i == 0,
                                isLast = i == members.lastIndex,
                                onMove = { up ->
                                    val keys = members.map { it.key }.toMutableList()
                                    keys.add(if (up) i - 1 else i + 1, keys.removeAt(i))
                                    onReorderMembers(zone.id, keys)
                                },
                            )
                        }
                    }
                } else {
                    gridItems(members, key = { "${zone.id}/${it.key}" }) { HomeTileCell(it, actions, coverActions, enabled) }
                }
            }
        }
        // The "Besorolatlan" remainder isn't zoned, so it has no order to edit — hide it while reordering.
        if (!reorderMode && unassigned.isNotEmpty()) {
            val isCollapsed = collapsed[UNASSIGNED_KEY] == true
            item(key = "h/unassigned", span = { GridItemSpan(maxLineSpan) }) {
                CollapsibleSectionTitle(
                    text = "Besorolatlan",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    collapsed = isCollapsed,
                    onToggleCollapse = { collapsed[UNASSIGNED_KEY] = !isCollapsed },
                )
            }
            if (!isCollapsed) {
                gridItems(unassigned, key = { "u/${it.key}" }) { HomeTileCell(it, actions, coverActions, enabled) }
            }
        }
    }
}

private const val UNASSIGNED_KEY = "__unassigned__"

/** Inline "no devices in this zone" hint spanning a grid row. */
@Composable
private fun ZoneEmptyText() {
    Text(
        "Nincs eszköz ebben a zónában",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/**
 * The aggregate "Otthon (összes)" dashboard — a swipeable pager driven by the bottom nav (see
 * [MainScreen]): page 0 is the **summary** (every global zone in one scrolling grid, just like the
 * per-server Otthon tab), and each following page is a single zone's devices. Members merge the assigned
 * outputs/sensors from **every** polled server — deliberately server-agnostic, so the home is controlled
 * by room, not by which physical device a switch lives on. Each tile routes its commands to its owning
 * server ([actionsFor]) and is enabled only while that server is connected. No "Besorolatlan" section.
 *
 * [reorderMode] turns the summary page into a compact zone-order editor (up/down arrows on the global
 * zone headers, member tiles hidden). Only the **global zone order** is editable here — within-zone
 * member order is stored per server and can't be interleaved across servers, so that stays on the
 * per-server Otthon tab.
 */
@Composable
fun AggregateHomeScreen(
    zones: List<Zone>,
    servers: List<MyioServer>,
    perServer: Map<String, MyioUiState>,
    assignments: Map<String, ZoneAssignments>,
    actionsFor: (serverId: String) -> MyioActions,
    pagerState: PagerState,
    reorderMode: Boolean,
    onAddZone: (String) -> String?,
    onRenameZone: (id: String, name: String) -> Unit,
    onDeleteZone: (String) -> Unit,
    onMoveZone: (id: String, up: Boolean) -> Unit,
    /** Detected covers per server (id → covers); rendered as shutter tiles, member outputs excluded. */
    coversByServer: Map<String, List<ModelCover>> = emptyMap(),
    coverActionsFor: (serverId: String) -> CoverActions = { CoverActions() },
    /** Per-server output keys to exclude (non-expert hidden sets); empty in expert mode. */
    hiddenByServer: Map<String, Set<String>> = emptyMap(),
    groupActions: GroupActions = GroupActions(),
    sceneActions: SceneActions = SceneActions(),
    expertMode: Boolean = false,
) {
    if (zones.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (!reorderMode) SceneBar(sceneActions.scenes, expertMode, sceneActions, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Box(Modifier.weight(1f)) { EmptyZones(onAddZone) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // The active page's name above the tiles: the bottom nav only windows a few zones at a time, so
        // the current zone isn't always legible there. Reads currentPage → updates live while swiping.
        val pageZone = zones.getOrNull(pagerState.currentPage - 1)
        val pageParts = remember(pageZone, servers, perServer, assignments, coversByServer, hiddenByServer) {
            pageZone?.let {
                aggregateGroupParts(aggregateZoneMembers(it, servers, perServer, assignments, coversByServer, hiddenByServer), perServer, coversByServer)
            }.orEmpty()
        }
        AggregatePageTitle(
            if (pagerState.currentPage == 0) "Összes" else pageZone?.name ?: "",
            pageParts,
            groupActions,
        )
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                AggregateSummaryGrid(zones, servers, perServer, assignments, coversByServer, hiddenByServer, actionsFor, coverActionsFor, reorderMode, onRenameZone, onDeleteZone, onMoveZone, groupActions, sceneActions, expertMode)
            } else {
                // Page index 1..n maps to zones[0..n-1]; guard against a transient out-of-range page while
                // a zone is being deleted (the pager's page count updates a frame later).
                zones.getOrNull(page - 1)?.let { zone ->
                    AggregateZoneGrid(zone, servers, perServer, assignments, coversByServer, hiddenByServer, actionsFor, coverActionsFor)
                }
            }
        }
    }
}

/** Title above the aggregate tiles naming the current page ("Összes" or the zone) — mirrors a zone header. */
@Composable
private fun AggregatePageTitle(title: String, groupParts: List<ZoneGroupPart>, groupActions: GroupActions) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A zone page carries the group controls (the "Összes" page has none: its zone headers do).
        ZoneGroupBar(groupParts, groupActions, menuDescription = "Csoportos vezérlés")
    }
}

/**
 * Summary page: every global zone in one scrolling grid with collapsible headers (rename/delete menu).
 * While [reorderMode] is on, the headers become up/down arrow rows (global zone order) and the member
 * tiles are hidden — only the zone order is editable in the aggregate view.
 */
@Composable
private fun AggregateSummaryGrid(
    zones: List<Zone>,
    servers: List<MyioServer>,
    perServer: Map<String, MyioUiState>,
    assignments: Map<String, ZoneAssignments>,
    coversByServer: Map<String, List<ModelCover>>,
    hiddenByServer: Map<String, Set<String>>,
    actionsFor: (serverId: String) -> MyioActions,
    coverActionsFor: (serverId: String) -> CoverActions,
    reorderMode: Boolean,
    onRenameZone: (id: String, name: String) -> Unit,
    onDeleteZone: (String) -> Unit,
    onMoveZone: (id: String, up: Boolean) -> Unit,
    groupActions: GroupActions,
    sceneActions: SceneActions,
    expertMode: Boolean,
) {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // Per-zone server groups, computed once so re-collapsing/expanding a section doesn't re-merge state.
    val membersByZone = remember(zones, servers, perServer, assignments, coversByServer, hiddenByServer) {
        zones.associate { zone ->
            zone.id to aggregateZoneMembers(zone, servers, perServer, assignments, coversByServer, hiddenByServer)
        }
    }
    TwoColumnLazyGrid {
        if (!reorderMode && (sceneActions.scenes.isNotEmpty() || expertMode)) {
            item(key = "scenes", span = { GridItemSpan(maxLineSpan) }) { SceneBar(sceneActions.scenes, expertMode, sceneActions) }
        }
        zones.forEachIndexed { index, zone ->
            val isCollapsed = !reorderMode && collapsed[zone.id] == true
            zoneHeaderItem(
                zone = zone,
                index = index,
                zones = zones,
                reorderMode = reorderMode,
                collapsed = isCollapsed,
                onToggleCollapse = { collapsed[zone.id] = !isCollapsed },
                onRenameZone = onRenameZone,
                onDeleteZone = onDeleteZone,
                onMoveZone = onMoveZone,
                groupParts = aggregateGroupParts(membersByZone[zone.id].orEmpty(), perServer, coversByServer),
                groupActions = groupActions,
            )
            // Member tiles hidden while reordering zones (only the global zone order is editable here).
            if (!reorderMode && !isCollapsed) {
                val groups = membersByZone[zone.id].orEmpty()
                if (groups.isEmpty()) {
                    item(key = "e/${zone.id}", span = { GridItemSpan(maxLineSpan) }) {
                        ZoneEmptyText()
                    }
                } else {
                    aggregateMemberItems(zone.id, groups, actionsFor, coverActionsFor)
                }
            }
        }
    }
}

/** A single zone's page: just that zone's merged devices in a 2-column grid (the zone name is the tab). */
@Composable
private fun AggregateZoneGrid(
    zone: Zone,
    servers: List<MyioServer>,
    perServer: Map<String, MyioUiState>,
    assignments: Map<String, ZoneAssignments>,
    coversByServer: Map<String, List<ModelCover>>,
    hiddenByServer: Map<String, Set<String>>,
    actionsFor: (serverId: String) -> MyioActions,
    coverActionsFor: (serverId: String) -> CoverActions,
) {
    val groups = remember(zone, servers, perServer, assignments, coversByServer, hiddenByServer) {
        aggregateZoneMembers(zone, servers, perServer, assignments, coversByServer, hiddenByServer)
    }
    if (groups.isEmpty()) {
        EmptyListHint("Nincs eszköz ebben a zónában")
        return
    }
    TwoColumnLazyGrid {
        aggregateMemberItems(zone.id, groups, actionsFor, coverActionsFor)
    }
}

/**
 * Merge a zone's members across every server (slot order), each server's own saved order applied within
 * its slice. Grouped **per server** (one non-empty list per contributing server) so callers can render a
 * divider between servers without re-deriving the grouping from a flat list. Tile keys carry the server
 * id — output keys aren't unique across servers (two devices can both have R3).
 */
private fun aggregateZoneMembers(
    zone: Zone,
    servers: List<MyioServer>,
    perServer: Map<String, MyioUiState>,
    assignments: Map<String, ZoneAssignments>,
    coversByServer: Map<String, List<ModelCover>>,
    hiddenByServer: Map<String, Set<String>>,
): List<List<AggMember>> = servers.mapNotNull { server ->
    val ui = perServer[server.id] ?: return@mapNotNull null
    val asn = assignments[server.id] ?: return@mapNotNull null
    val hidden = hiddenByServer[server.id] ?: emptySet()
    val covers = coversByServer[server.id] ?: emptyList()
    val raw = homeTilesFor(ui.state, covers) { zone.id in (asn.assignments[it] ?: emptyList()) && it !in hidden }
    val byKey = raw.associateBy { it.key }
    val ordered = ZoneConfig(memberOrder = asn.memberOrder)
        .orderedMembers(zone.id, raw.map { it.key })
        .mapNotNull { byKey[it] }
    val connected = ui.status == ConnectionStatus.Connected
    ordered.map { AggMember(server.id, it, connected) }
}.filter { it.isNotEmpty() }

/** Per-server group-control slices of one zone's merged [groups] (eligible members of each server). */
private fun aggregateGroupParts(
    groups: List<List<AggMember>>,
    perServer: Map<String, MyioUiState>,
    coversByServer: Map<String, List<ModelCover>>,
): List<ZoneGroupPart> = groups.mapNotNull { g ->
    val sid = g.firstOrNull()?.serverId ?: return@mapNotNull null
    val st = perServer[sid]?.state ?: return@mapNotNull null
    ZoneGroupPart(sid, groupMembers(st, coversByServer[sid].orEmpty(), g.map { it.tile.key }.toSet()), g.first().enabled)
}

/** One aggregate-dashboard tile: a zone member tagged with its owning server + that server's reachability. */
private data class AggMember(val serverId: String, val tile: HomeTile, val enabled: Boolean)

@Composable
private fun EmptyZones(onAddZone: (String) -> String?) {
    var showAdd by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Még nincs zóna",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Hozz létre zónákat. Az eszközök besorolásához kapcsold be a Szakértő módot a " +
                    "Beállításokban, majd használd a címke-ikont.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { showAdd = true }) { Text("Zóna hozzáadása") }
        }
    }
    if (showAdd) {
        ZoneNameDialog(
            title = "Új zóna",
            confirmLabel = "Hozzáad",
            onConfirm = { name -> onAddZone(name); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

/** Zone section header: collapse chevron + name (tap to collapse) + an overflow menu for rename/delete. */
@Composable
private fun ZoneHeader(
    zone: Zone,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onRenameZone: (id: String, name: String) -> Unit,
    onDeleteZone: (String) -> Unit,
    groupParts: List<ZoneGroupPart>,
    groupActions: GroupActions,
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onToggleCollapse).padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CollapseChevron(collapsed)
            Text(
                zone.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ZoneGroupBar(groupParts, groupActions) { close ->
            DropdownMenuItem(
                text = { Text("Átnevezés") },
                onClick = { close(); showRename = true },
            )
            DropdownMenuItem(
                text = { Text("Törlés") },
                onClick = { close(); showDelete = true },
            )
        }
    }
    if (showRename) {
        ZoneNameDialog(
            title = "Zóna átnevezése",
            confirmLabel = "Mentés",
            initial = zone.name,
            onConfirm = { name -> onRenameZone(zone.id, name); showRename = false },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Zóna törlése") },
            text = { Text("A(z) „${zone.name}” zóna és minden hozzárendelése törlődik. Az eszközök megmaradnak.") },
            confirmButton = {
                TextButton(onClick = { onDeleteZone(zone.id); showDelete = false }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Mégse") } },
        )
    }
}

/** A section header that can be collapsed by tapping it (chevron + title). */
@Composable
private fun CollapsibleSectionTitle(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggleCollapse).padding(top = 8.dp, start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollapseChevron(collapsed, tint = color)
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Up/down chevron indicating collapsed state. */
@Composable
private fun CollapseChevron(
    collapsed: Boolean,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Icon(
        imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
        contentDescription = if (collapsed) "Kibontás" else "Összecsukás",
        tint = tint,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** Zone header while reordering: name + up/down arrows (disabled at the ends). No collapse/menu here. */
@Composable
private fun ReorderZoneHeader(zone: Zone, isFirst: Boolean, isLast: Boolean, onMove: (up: Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            zone.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MoveArrows(isFirst = isFirst, isLast = isLast, onMove = onMove)
    }
}

/** A zone member while reordering: a full-width row (icon-free, name only) + up/down arrows. */
@Composable
private fun ReorderMemberRow(name: String, isFirst: Boolean, isLast: Boolean, onMove: (up: Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MoveArrows(isFirst = isFirst, isLast = isLast, onMove = onMove)
    }
}

/** Up/down move buttons; the up arrow is disabled on the first item, the down arrow on the last. */
@Composable
private fun MoveArrows(isFirst: Boolean, isLast: Boolean, onMove: (up: Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(true) }, enabled = !isFirst) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Feljebb")
        }
        IconButton(onClick = { onMove(false) }, enabled = !isLast) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Lejjebb")
        }
    }
}

/** A renderable member of a zone section, tagged by domain type; [key] is stable within a server. */
internal sealed interface HomeTile {
    val key: String
    val name: String
    data class Relay(val o: RelayOutput) : HomeTile { override val key get() = o.outputKey(); override val name get() = o.name }
    data class Pca(val o: PcaOutput) : HomeTile { override val key get() = o.outputKey(); override val name get() = o.name }
    data class Pwm(val o: PwmOutput) : HomeTile { override val key get() = o.outputKey(); override val name get() = o.name }
    data class Sens(val o: Sensor) : HomeTile { override val key get() = o.outputKey(); override val name get() = o.name }
    data class Cover(val o: ModelCover) : HomeTile { override val key get() = o.outputKey(); override val name get() = o.name }
}

/**
 * Named outputs/sensors whose key satisfies [include], in a stable order (relays, PCA, PWM, covers,
 * sensors). [covers] are added as their own tiles, and their member up/down outputs are excluded from the
 * relay/PCA tiles (a shutter shows once, not as its two raw motors — mirrors the dedicated Redőny tab).
 */
internal fun homeTilesFor(
    state: ServerState,
    covers: List<ModelCover> = emptyList(),
    include: (String) -> Boolean,
): List<HomeTile> = buildList {
    val coverMemberIds = covers.memberIds()
    state.relays.forEach { if (it.id !in coverMemberIds && it.name.isNotBlank() && include(it.outputKey())) add(HomeTile.Relay(it)) }
    state.pcas.forEach { if (it.id !in coverMemberIds && it.name.isNotBlank() && include(it.outputKey())) add(HomeTile.Pca(it)) }
    state.pwms.forEach { if (it.name.isNotBlank() && include(it.outputKey())) add(HomeTile.Pwm(it)) }
    covers.forEach { if (include(it.outputKey())) add(HomeTile.Cover(it)) }
    state.sensors.forEach { if (it.name.isNotBlank() && include(it.outputKey())) add(HomeTile.Sens(it)) }
}

@Composable
internal fun HomeTileCell(tile: HomeTile, actions: MyioActions, coverActions: CoverActions, enabled: Boolean) = when (tile) {
    is HomeTile.Relay -> RelayControlTile(tile.o, actions, enabled)
    is HomeTile.Pca -> PcaControlTile(tile.o, actions, enabled)
    is HomeTile.Pwm -> PwmControlTile(tile.o, actions, enabled)
    is HomeTile.Sens -> SensorTile(tile.o.name, formatSensor(tile.o))
    // Control-only on the dashboard (expert=false → no ⋮ menu); orientation/rename live on the Redőny tab.
    is HomeTile.Cover -> CoverTile(tile.o, enabled, expert = false, coverActions)
}

/**
 * Zone section header item: a collapsible [ZoneHeader] normally, or an up/down [ReorderZoneHeader] row
 * while [reorderMode] is on. Shared by the per-server Otthon tab and the aggregate summary page so both
 * render a zone's header identically.
 */
private fun LazyGridScope.zoneHeaderItem(
    zone: Zone,
    index: Int,
    zones: List<Zone>,
    reorderMode: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onRenameZone: (id: String, name: String) -> Unit,
    onDeleteZone: (String) -> Unit,
    onMoveZone: (id: String, up: Boolean) -> Unit,
    groupParts: List<ZoneGroupPart>,
    groupActions: GroupActions,
) {
    item(key = "h/${zone.id}", span = { GridItemSpan(maxLineSpan) }) {
        if (reorderMode) {
            ReorderZoneHeader(
                zone = zone,
                isFirst = index == 0,
                isLast = index == zones.lastIndex,
                onMove = { up -> onMoveZone(zone.id, up) },
            )
        } else {
            ZoneHeader(
                zone = zone,
                collapsed = collapsed,
                onToggleCollapse = onToggleCollapse,
                onRenameZone = onRenameZone,
                onDeleteZone = onDeleteZone,
                groupParts = groupParts,
                groupActions = groupActions,
            )
        }
    }
}

/**
 * Render a zone's per-server member [groups]: a divider between servers, then each group's tiles.
 * Shared by the aggregate summary page (one section per zone) and a single zone's page. [keyPrefix]
 * (the zone id) keeps grid-item keys unique when this is called once per zone in the same [LazyGridScope]
 * (the summary page renders every zone in one grid).
 */
private fun LazyGridScope.aggregateMemberItems(
    keyPrefix: String,
    groups: List<List<AggMember>>,
    actionsFor: (serverId: String) -> MyioActions,
    coverActionsFor: (serverId: String) -> CoverActions,
) {
    groups.forEachIndexed { i, group ->
        if (i > 0) item(key = "$keyPrefix/div/$i", span = { GridItemSpan(maxLineSpan) }) {
            HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        gridItems(group, key = { "$keyPrefix/${it.serverId}/${it.tile.key}" }) { m ->
            HomeTileCell(m.tile, actionsFor(m.serverId), coverActionsFor(m.serverId), m.enabled)
        }
    }
}
