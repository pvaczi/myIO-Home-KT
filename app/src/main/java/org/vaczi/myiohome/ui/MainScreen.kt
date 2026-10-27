package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.expandAssignmentsForDisabledCovers
import org.vaczi.myiohome.data.model.expandHiddenForDisabledCovers
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.memberIds
import org.vaczi.myiohome.data.model.outputKey

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Otthon", Icons.Filled.Home),
    RELAYS("Relék", Icons.Filled.Power),
    PCA("PCA", Icons.Filled.Lightbulb),
    COVERS("Redőny", Icons.Filled.Blinds),
    DIMMERS("Dimmerek", Icons.Filled.Tune),
    SENSORS("Szenzor", Icons.Filled.Sensors),
    CONTROLLED("Termosztát", Icons.Filled.DeviceThermostat),
}

/** Assign and hide mode act only on the type tabs; Otthon and Termosztát keep their live controls. */
private val Tab.isEditable get() = this != Tab.HOME && this != Tab.CONTROLLED

/** The single active editing mode (mutually exclusive by construction). Expert-only; HOME-only for [REORDER]. */
private enum class EditMode { NONE, ASSIGN, REORDER, HIDE }

/** Control callbacks wired to [MyioViewModel]; default no-ops keep previews/tests trivial. */
data class MyioActions(
    val onToggleRelay: (RelayOutput) -> Unit = {},
    val onTogglePca: (PcaOutput) -> Unit = {},
    val onSetPcaBrightness: (PcaOutput, Int) -> Unit = { _, _ -> },
    val onTogglePwm: (PwmOutput) -> Unit = {},
    val onSetPwmBrightness: (PwmOutput, Int) -> Unit = { _, _ -> },
    // Thermostat setpoints (onTenths, offTenths in ×10 °C):
    val onSetRelayThermostat: (RelayOutput, Int, Int) -> Unit = { _, _, _ -> },
    val onSetPcaThermostat: (PcaOutput, Int, Int) -> Unit = { _, _, _ -> },
    val onSetPwmThermostat: (PwmOutput, Int, Int) -> Unit = { _, _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ui: MyioUiState,
    actions: MyioActions = MyioActions(),
    servers: List<MyioServer> = emptyList(),
    currentServer: MyioServer? = null,
    onSelectServer: (String) -> Unit = {},
    showAggregate: Boolean = false,
    onSelectAggregate: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // Persisted last tab as a stable Tab *name* (null = none yet); legacy ordinals resolve via resolveTab.
    selectedTab: String? = null,
    onSelectTab: (String) -> Unit = {},
    // Expert mode: when false, hidden servers/outputs are filtered from display and the per-server
    // editing tools (assign / reorder / visibility) are hidden — a locked, simplified view.
    expertMode: Boolean = true,
    onToggleOutputHidden: (outputKey: String) -> Unit = {},
    // Disabled-cover endpoint keys (coverKey → P/R keys) so a hidden/zoned cover that's been demoted to
    // "not a cover" keeps hiding/zoning its now-plain outputs. Single server; per-server for the aggregate.
    disabledCoverMembers: Map<String, List<String>> = emptyMap(),
    aggregateDisabledMembers: Map<String, Map<String, List<String>>> = emptyMap(),
    zoneConfig: ZoneConfig = ZoneConfig(),
    hideZoned: Boolean = false,
    onSetHideZoned: (Boolean) -> Unit = {},
    onToggleZoneAssignment: (outputKey: String, zoneId: String) -> Unit = { _, _ -> },
    onAddZone: (String, String?) -> String? = { _, _ -> null },
    onRenameZone: (id: String, name: String, icon: String?) -> Unit = { _, _, _ -> },
    onDeleteZone: (String) -> Unit = {},
    onMoveZone: (id: String, up: Boolean) -> Unit = { _, _ -> },
    onReorderZoneMembers: (zoneId: String, displayedKeys: List<String>, index: Int, up: Boolean) -> Unit = { _, _, _, _ -> },
    // Global per-zone member order (zoneId → "<serverId>|<outputKey>" refs) + the aggregate-view mover.
    zoneMemberOrder: Map<String, List<String>> = emptyMap(),
    onMoveAggregateMember: (zoneId: String, displayedRefs: List<String>, index: Int, up: Boolean) -> Unit = { _, _, _, _ -> },
    // Covers (shutters): detected from protection pairs; their member outputs are hidden from the PCA/Relé tabs.
    covers: List<Cover> = emptyList(),
    onCoverOpen: (Cover) -> Unit = {},
    onCoverClose: (Cover) -> Unit = {},
    onCoverStop: (Cover) -> Unit = {},
    onCoverSwap: (Cover) -> Unit = {},
    onCoverRename: (Cover, String) -> Unit = { _, _ -> },
    onCoverDisable: (Cover) -> Unit = {},
    // Aggregate ("Otthon (összes)") mode: render every polled server's zoned outputs in one dashboard.
    isAggregate: Boolean = false,
    aggregatePerServer: Map<String, MyioUiState> = emptyMap(),
    aggregateAssignments: Map<String, ZoneAssignments> = emptyMap(),
    globalZones: List<Zone> = emptyList(),
    aggregateActionsFor: (serverId: String) -> MyioActions = { MyioActions() },
    // Per-server detected covers + their open/close/stop callbacks for the aggregate dashboard.
    aggregateCovers: Map<String, List<Cover>> = emptyMap(),
    aggregateCoverActionsFor: (serverId: String) -> CoverActions = { CoverActions() },
    // Zone group control (Mind be/ki/fel/le/stop, közös fényerő), routed by server id.
    groupActions: GroupActions = GroupActions(),
    // Scenes (chip row on Home / "Összes") and the one-shot snackbar lines (scene run summary).
    sceneActions: SceneActions = SceneActions(),
    messages: Flow<String> = emptyFlow(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(messages) {
        messages.collect {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }
    // The active editing mode is ephemeral UI state (not persisted). One enum keeps the three modes
    // (assign / reorder / visibility) mutually exclusive by construction.
    var editMode by remember { mutableStateOf(EditMode.NONE) }
    // Full-screen output search (name/type/id + "Csak a bekapcsoltak"); Back closes it.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var activeZoneId by remember { mutableStateOf<String?>(null) }
    // Live name filter for assign mode; cleared whenever assign mode is left so it never lingers.
    var assignFilter by remember { mutableStateOf("") }
    val assignMode = editMode == EditMode.ASSIGN
    LaunchedEffect(assignMode) { if (!assignMode) assignFilter = "" }
    val reorderMode = editMode == EditMode.REORDER
    val hideMode = editMode == EditMode.HIDE
    // Leaving expert mode drops every editing mode so the locked view shows only controls + the gear.
    LaunchedEffect(expertMode) { if (!expertMode) editMode = EditMode.NONE }
    // Keep the active zone valid as zones change (e.g. one gets deleted); default to the first zone.
    LaunchedEffect(zoneConfig.zones, assignMode) {
        if (assignMode && zoneConfig.zones.none { it.id == activeZoneId }) {
            activeZoneId = zoneConfig.zones.firstOrNull()?.id
        }
    }
    // Hidden-output sets, derived from the slots. In expert mode nothing is filtered (full visibility);
    // in non-expert mode hidden outputs disappear from every view, including the home/aggregate dashboards.
    // A hidden cover that was later marked "not a cover" (disabled) also hides its two now-plain endpoints
    // (its C-key hidden flag extends to the P/R keys) — otherwise the deliberately-hidden device would
    // resurface as controllable. Read-time only, so re-enabling the cover restores the pair automatically.
    val hiddenKeys = remember(currentServer, disabledCoverMembers) {
        expandHiddenForDisabledCovers(currentServer?.hiddenOutputs?.toSet() ?: emptySet(), disabledCoverMembers)
    }
    val hideUi = HideUi(hideMode = hideMode, hiddenKeys = hiddenKeys, onToggleHidden = onToggleOutputHidden)
    val homeExclude = if (expertMode) emptySet() else hiddenKeys
    val aggregateHiddenByServer = remember(expertMode, servers, aggregateDisabledMembers) {
        if (expertMode) emptyMap()
        else servers.associate { s ->
            s.id to expandHiddenForDisabledCovers(s.hiddenOutputs.toSet(), aggregateDisabledMembers[s.id].orEmpty())
        }
    }
    // Same extension for zone assignments: a disabled cover's C-key zone membership propagates to its
    // endpoint keys so the demoted outputs stay in their zone / on the home dashboard instead of orphaning.
    val effectiveZoneConfig = remember(zoneConfig, disabledCoverMembers) {
        zoneConfig.copy(assignments = expandAssignmentsForDisabledCovers(zoneConfig.assignments, disabledCoverMembers))
    }
    val effectiveAggregateAssignments = remember(aggregateAssignments, aggregateDisabledMembers) {
        aggregateAssignments.mapValues { (id, a) ->
            a.copy(assignments = expandAssignmentsForDisabledCovers(a.assignments, aggregateDisabledMembers[id].orEmpty()))
        }
    }
    // The switcher only lists hidden servers in expert mode; non-expert sees just the enabled slots.
    val switcherServers = if (expertMode) servers else servers.filterNot { it.hidden }
    val zoneUi = ZoneUi(
        config = effectiveZoneConfig,
        assignMode = assignMode,
        activeZoneId = activeZoneId,
        hideZoned = hideZoned,
        nameFilter = assignFilter,
        onToggleAssignment = { key -> activeZoneId?.let { onToggleZoneAssignment(key, it) } },
    )
    // Only tabs that actually have something to show appear (empty Relék on a lighting controller,
    // empty Dimmerek everywhere, etc. stay hidden — mirrors the factory app). [selectedTab] is the
    // persisted Tab *ordinal* (stable regardless of which tabs are visible), mapped here to an index
    // into the visible list.
    // Outputs that belong to a detected cover are shown only on the Redőny tab, never as standalone
    // PCA/relay tiles (mirrors the bridge's cover_output_ids()).
    val coverMemberIds = remember(covers) { covers.memberIds() }
    val displayPcas = remember(ui.state.pcas, coverMemberIds) { ui.state.pcas.filterNot { it.id in coverMemberIds } }
    val displayRelays = remember(ui.state.relays, coverMemberIds) { ui.state.relays.filterNot { it.id in coverMemberIds } }
    val visibleTabs = remember(displayRelays, displayPcas, covers, ui.state) {
        val tabHasContent: (Tab) -> Boolean = { tab ->
            when (tab) {
                Tab.HOME -> true
                Tab.RELAYS -> displayRelays.any { it.name.isNotBlank() }
                Tab.PCA -> displayPcas.any { it.name.isNotBlank() }
                Tab.COVERS -> covers.isNotEmpty()
                Tab.DIMMERS -> ui.state.pwms.any { it.name.isNotBlank() }
                Tab.SENSORS -> ui.state.sensors.any { it.name.isNotBlank() }
                Tab.CONTROLLED -> ui.state.hasControlledOutputs()
            }
        }
        Tab.entries.filter(tabHasContent)
    }
    val controlsEnabled = ui.status == ConnectionStatus.Connected
    val coverActions = CoverActions(
        onOpen = onCoverOpen, onClose = onCoverClose, onStop = onCoverStop,
        onSwap = onCoverSwap, onRename = onCoverRename, onDisable = onCoverDisable,
    )
    // Resolve the persisted tab: a Tab name directly, or a legacy ordinal migrated via the current enum
    // order (see SettingsStore.resolveTab). Falls back to the first visible tab.
    val resolvedTabName = remember(selectedTab) { SettingsStore.resolveTab(selectedTab, Tab.entries.map { it.name }) }
    val currentTab = Tab.entries.firstOrNull { it.name == resolvedTabName }?.takeIf { it in visibleTabs }
        ?: visibleTabs.firstOrNull()
    val selectedIndex = visibleTabs.indexOf(currentTab).coerceAtLeast(0)
    // Reorder mode only exists on HOME (per-server); leave it when the user swipes/taps to another tab.
    // In aggregate mode reorder lives on the "Összes" summary page instead, so this guard skips it.
    LaunchedEffect(currentTab, isAggregate) {
        if (!isAggregate && currentTab != Tab.HOME && reorderMode) editMode = EditMode.NONE
    }

    // Swipe between tabs, kept in sync with the bottom nav and the persisted selection.
    val tabsState = rememberUpdatedState(visibleTabs)
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { tabsState.value.size })
    LaunchedEffect(selectedIndex) {
        if (visibleTabs.isNotEmpty() && pagerState.currentPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    // While a server switch is loading, [ui.state] is momentarily empty and [visibleTabs] collapses to
    // just HOME — settling on page 0 there would persist HOME over the new server's actually-saved tab.
    // Only persist once real data has loaded.
    val stateLoaded = rememberUpdatedState(!ui.state.isEmpty())
    LaunchedEffect(pagerState) {
        // drop(1): ignore the initial settle so we don't clobber the restored tab on launch.
        snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
            if (stateLoaded.value) {
                tabsState.value.getOrNull(page)?.let { onSelectTab(it.name) }
            }
        }
    }
    // Assign/hide mode only work on the type tabs: entering from Otthon/Termosztát jumps to the first one,
    // and while the shown page is not one, the mode bar is replaced by a hint.
    val onEditableTab = visibleTabs.getOrNull(pagerState.currentPage)?.isEditable == true
    val moveToEditableTab = {
        if (!onEditableTab) visibleTabs.firstOrNull { it.isEditable }?.let { onSelectTab(it.name) }
    }

    // Aggregate ("Otthon (összes)") tabs live in the bottom nav like the per-server type tabs: page 0 is
    // the "Összes" summary, pages 1..n are one global zone each. Swiping the pager and tapping the nav
    // stay in sync (not persisted — the aggregate is a single virtual view). [scope] drives tab taps.
    val scope = rememberCoroutineScope()
    val aggregatePagerState = rememberPagerState(pageCount = { globalZones.size + 1 })
    // Aggregate zone-reorder happens on the "Összes" summary page (page 0): jump there when it turns on
    // so the up/down arrows on the zone headers are actually visible.
    LaunchedEffect(reorderMode, isAggregate) {
        if (isAggregate && reorderMode && aggregatePagerState.currentPage != 0) {
            aggregatePagerState.animateScrollToPage(0)
        }
    }

    // Landscape (wider than tall): the per-server type tabs / the aggregate zones move to a side rail.
    val config = LocalConfiguration.current
    val useRail = config.screenWidthDp > config.screenHeightDp && (
        if (isAggregate) globalZones.isNotEmpty() else currentServer != null && visibleTabs.isNotEmpty()
        )

    if (searchOpen) {
        // Hidden outputs/servers are excluded regardless of expert mode; aggregate = every polled server.
        val searchSources = if (isAggregate) {
            servers.filter { !it.hidden && it.id in aggregatePerServer }.map { s ->
                val per = aggregatePerServer.getValue(s.id)
                SearchSource(
                    s.id, per.state, aggregateCovers[s.id].orEmpty(),
                    expandHiddenForDisabledCovers(s.hiddenOutputs.toSet(), aggregateDisabledMembers[s.id].orEmpty()),
                    per.status == ConnectionStatus.Connected,
                )
            }
        } else {
            listOfNotNull(currentServer?.let { SearchSource(it.id, ui.state, covers, hiddenKeys, controlsEnabled) })
        }
        SearchScreen(
            sources = searchSources,
            actionsFor = if (isAggregate) aggregateActionsFor else { _ -> actions },
            coverActionsFor = if (isAggregate) aggregateCoverActionsFor else { _ -> coverActions },
            onClose = { searchOpen = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    ServerTitle(
                        ui = ui,
                        servers = switcherServers,
                        current = currentServer,
                        onSelect = onSelectServer,
                        isAggregate = isAggregate,
                        showAggregate = showAggregate,
                        onSelectAggregate = onSelectAggregate,
                        aggregateStatus = aggregateStatus(aggregatePerServer),
                    )
                },
                actions = {
                    if ((isAggregate && aggregatePerServer.isNotEmpty()) || (!isAggregate && currentServer != null)) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Keresés")
                        }
                    }
                    // Editing tools (reorder / assign / visibility) are expert-only — a non-expert locked
                    // view shows just the controls and the gear, so nothing can be rearranged by accident.
                    // Reorder toggle: per-server HOME tab (zones + within-zone members get up/down arrows),
                    // or the aggregate "Otthon (összes)" view (global zone order only — members are stored
                    // per server and can't be interleaved across servers).
                    val canReorderZones = expertMode && (
                        (!isAggregate && currentServer != null && currentTab == Tab.HOME && zoneConfig.zones.isNotEmpty()) ||
                            (isAggregate && globalZones.isNotEmpty())
                        )
                    if (canReorderZones) {
                        IconButton(onClick = {
                            editMode = if (reorderMode) EditMode.NONE else EditMode.REORDER
                        }) {
                            Icon(
                                Icons.Filled.SwapVert,
                                contentDescription = if (reorderMode) "Átrendezés mód kikapcsolása" else "Átrendezés",
                                tint = if (reorderMode) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current,
                            )
                        }
                    }
                    // Tag toggle: enter/leave zone-assignment mode (only meaningful when tabs exist).
                    if (expertMode && !isAggregate && currentServer != null && visibleTabs.isNotEmpty()) {
                        IconButton(onClick = {
                            val entering = !assignMode
                            editMode = if (entering) EditMode.ASSIGN else EditMode.NONE
                            if (entering && activeZoneId == null) activeZoneId = zoneConfig.zones.firstOrNull()?.id
                            if (entering) moveToEditableTab()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Label,
                                contentDescription = if (assignMode) "Besorolás mód kikapcsolása" else "Besorolás zónákba",
                                tint = if (assignMode) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current,
                            )
                        }
                    }
                    // Visibility toggle: enter/leave hide mode (tap a tile on a type tab to hide/show it).
                    if (expertMode && !isAggregate && currentServer != null && visibleTabs.isNotEmpty()) {
                        IconButton(onClick = {
                            if (!hideMode) moveToEditableTab()
                            editMode = if (hideMode) EditMode.NONE else EditMode.HIDE
                        }) {
                            Icon(
                                if (hideMode) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (hideMode) "Láthatóság mód kikapcsolása" else "Eszközök elrejtése",
                                tint = if (hideMode) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Beállítások")
                    }
                },
            )
        },
        bottomBar = {
            if (isAggregate) {
                if (globalZones.isNotEmpty() && !useRail) {
                    AggregateBottomBar(
                        zones = globalZones,
                        pagerState = aggregatePagerState,
                        onSelectPage = { page -> scope.launch { aggregatePagerState.animateScrollToPage(page) } },
                    )
                }
            } else if (currentServer != null && visibleTabs.isNotEmpty() && !useRail) {
                NavigationBar {
                    visibleTabs.forEachIndexed { index, t ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onSelectTab(t.name) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
        if (useRail && isAggregate) {
            AggregateNavRail(
                zones = globalZones,
                pagerState = aggregatePagerState,
                onSelectPage = { page -> scope.launch { aggregatePagerState.animateScrollToPage(page) } },
            )
        } else if (useRail) {
            // Landscape: a side rail instead of the bottom bar, so the content keeps the full height.
            // Scrolls because seven items can exceed the short landscape height.
            NavigationRail(
                modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                visibleTabs.forEachIndexed { index, t ->
                    NavigationRailItem(
                        selected = selectedIndex == index,
                        onClick = { onSelectTab(t.name) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                isAggregate -> AggregateHomeScreen(
                    zones = globalZones,
                    servers = servers,
                    perServer = aggregatePerServer,
                    assignments = effectiveAggregateAssignments,
                    coversByServer = aggregateCovers,
                    coverActionsFor = aggregateCoverActionsFor,
                    hiddenByServer = aggregateHiddenByServer,
                    groupActions = groupActions,
                    sceneActions = sceneActions,
                    expertMode = expertMode,
                    actionsFor = aggregateActionsFor,
                    pagerState = aggregatePagerState,
                    reorderMode = reorderMode,
                    onAddZone = onAddZone,
                    onRenameZone = onRenameZone,
                    onDeleteZone = onDeleteZone,
                    onMoveZone = onMoveZone,
                    memberOrder = zoneMemberOrder,
                    onMoveMember = onMoveAggregateMember,
                )

                currentServer == null ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Nincs beállított szerver.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = onOpenSettings) {
                            Text("Beállítások / keresés a hálózaton")
                        }
                    }

                ui.status == ConnectionStatus.Connecting && ui.state.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                // Connected but no named tabs yet (fresh server, no cache): outputs exist but their
                // names arrive only with refreshMetadata — show a loading hint, not "nothing here".
                visibleTabs.isEmpty() && !ui.state.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Nevek betöltése…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                visibleTabs.isEmpty() -> EmptyListHint()

                else -> Column(Modifier.fillMaxSize()) {
                    // While assigning, a tonal bar (distinct colour = MD mode feedback) holds the active
                    // target zone chips + the "show only unassigned" toggle. On a non-editable tab (live
                    // controls) it gives way to a hint pointing at the type tabs.
                    if ((assignMode || hideMode) && !onEditableTab) {
                        EditTabHint(visibleTabs.filter { it.isEditable }.map { it.label })
                    } else if (assignMode) {
                        AssignmentBar(
                            zones = zoneConfig.zones,
                            activeZoneId = activeZoneId,
                            onSelectZone = { activeZoneId = it },
                            onAddZone = { name, icon -> onAddZone(name, icon)?.let { activeZoneId = it } },
                            hideZoned = hideZoned,
                            onSetHideZoned = onSetHideZoned,
                            nameFilter = assignFilter,
                            onSetNameFilter = { assignFilter = it },
                        )
                    } else if (hideMode) {
                        HideModeBar()
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (visibleTabs[page]) {
                            Tab.HOME -> HomeScreen(
                                state = ui.state,
                                zoneConfig = effectiveZoneConfig,
                                actions = actions,
                                enabled = controlsEnabled,
                                reorderMode = reorderMode,
                                covers = covers,
                                coverActions = coverActions,
                                hiddenKeys = homeExclude,
                                onAddZone = onAddZone,
                                onRenameZone = onRenameZone,
                                onDeleteZone = onDeleteZone,
                                onMoveZone = onMoveZone,
                                onReorderMembers = onReorderZoneMembers,
                                serverId = currentServer?.id.orEmpty(),
                                groupActions = groupActions,
                                sceneActions = sceneActions,
                                expertMode = expertMode,
                            )
                            Tab.RELAYS -> TypeTabList(displayRelays, Icons.Filled.Power, { it.outputKey() }, { it.name }, zoneUi, hideUi, expertMode) {
                                RelayControlTile(it, actions, controlsEnabled)
                            }
                            Tab.PCA -> TypeTabList(displayPcas, Icons.Filled.Lightbulb, { it.outputKey() }, { it.name }, zoneUi, hideUi, expertMode) {
                                PcaControlTile(it, actions, controlsEnabled)
                            }
                            // Like the other type tabs: a live CoverTile normally, but assignable into
                            // zones (key = coverKey) / hideable in those edit modes.
                            Tab.COVERS -> TypeTabList(covers, Icons.Filled.Blinds, { it.outputKey() }, { it.name }, zoneUi, hideUi, expertMode) {
                                CoverTile(it, controlsEnabled, expertMode, coverActions)
                            }
                            Tab.DIMMERS -> TypeTabList(ui.state.pwms, Icons.Filled.Lightbulb, { it.outputKey() }, { it.name }, zoneUi, hideUi, expertMode) {
                                PwmControlTile(it, actions, controlsEnabled)
                            }
                            Tab.SENSORS -> TypeTabList(ui.state.sensors, Icons.Filled.Sensors, { it.outputKey() }, { it.name }, zoneUi, hideUi, expertMode) {
                                SensorTile(it.name, formatSensor(it))
                            }
                            Tab.CONTROLLED -> ControlledList(ui.state, actions, controlsEnabled, expertMode, homeExclude)
                        }
                    }
                }
            }
        }
        }
    }
}

private fun ServerState.isEmpty() =
    relays.isEmpty() && pcas.isEmpty() && pwms.isEmpty() && sensors.isEmpty()

private fun statusLine(ui: MyioUiState): String = when (ui.status) {
    ConnectionStatus.Connecting -> "Kapcsolódás…"
    ConnectionStatus.Connected -> "Kapcsolódva"
    ConnectionStatus.Error -> "Hiba: ${ui.error ?: "ismeretlen"}"
}

// Aggregate bottom nav: with few zones all fit; beyond [MAX_INLINE_ZONES] the bar would cram the labels
// unreadably small, so we show only the [ZONE_CHUNK]-sized window containing the current selection and
// page through the rest with ‹/› items. The active zone's name is still shown above the tiles.
private const val MAX_INLINE_ZONES = 4
private const val ZONE_CHUNK = 3

/**
 * The "Otthon (összes)" bottom nav: a fixed "Összes" summary item plus the global zones. Many zones are
 * windowed (see above) so labels stay legible. [onSelectPage] takes a pager page index (0 = summary,
 * i+1 = zones[i]).
 */
@Composable
private fun AggregateBottomBar(
    zones: List<Zone>,
    pagerState: PagerState,
    onSelectPage: (Int) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = pagerState.currentPage == 0,
            onClick = { onSelectPage(0) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Összes") },
            label = { Text("Összes") },
        )
        if (zones.size <= MAX_INLINE_ZONES) {
            zones.forEachIndexed { i, zone ->
                ZoneNavItem(zone, selected = pagerState.currentPage == i + 1, onClick = { onSelectPage(i + 1) })
            }
        } else {
            // Window = the chunk of zones holding the current selection; ‹/› jump a chunk at a time.
            val selectedZone = (pagerState.currentPage - 1).coerceAtLeast(0)
            val start = (selectedZone / ZONE_CHUNK) * ZONE_CHUNK
            val end = minOf(start + ZONE_CHUNK, zones.size)
            if (start > 0) {
                NavigationBarItem(
                    selected = false,
                    onClick = { onSelectPage(start - ZONE_CHUNK + 1) },
                    icon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Előző zónák") },
                    label = { Text("…") },
                )
            }
            for (i in start until end) {
                ZoneNavItem(zones[i], selected = pagerState.currentPage == i + 1, onClick = { onSelectPage(i + 1) })
            }
            if (end < zones.size) {
                NavigationBarItem(
                    selected = false,
                    onClick = { onSelectPage(end + 1) },
                    icon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "További zónák") },
                    label = { Text("…") },
                )
            }
        }
    }
}

/** A single zone destination in the aggregate bottom nav. */
@Composable
private fun RowScope.ZoneNavItem(zone: Zone, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(zoneIconVector(zone.icon), contentDescription = zone.name) },
        label = { Text(zone.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * Landscape counterpart of [AggregateBottomBar]: the same destinations on a side rail. The rail scrolls,
 * so every zone is listed — no ‹/› windowing needed. Page indices as in [AggregateBottomBar].
 */
@Composable
private fun AggregateNavRail(
    zones: List<Zone>,
    pagerState: PagerState,
    onSelectPage: (Int) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        NavigationRailItem(
            selected = pagerState.currentPage == 0,
            onClick = { onSelectPage(0) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Összes") },
            label = { Text("Összes") },
        )
        zones.forEachIndexed { i, zone ->
            NavigationRailItem(
                selected = pagerState.currentPage == i + 1,
                onClick = { onSelectPage(i + 1) },
                icon = { Icon(zoneIconVector(zone.icon), contentDescription = zone.name) },
                // Rail labels are not width-bound: without the cap a long zone name runs past the rail.
                label = {
                    Text(
                        zone.name,
                        modifier = Modifier.widthIn(max = 72.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** Shown instead of the assign/hide bar on a tab whose tiles stay live controls (Otthon, Termosztát). */
@Composable
private fun EditTabHint(editableTabs: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (editableTabs.isEmpty()) "Ezen a fülön a csempék kapcsolnak, itt nincs mit szerkeszteni."
            else "Ezen a fülön a csempék kapcsolnak. Lapozz egy típus-fülre: ${editableTabs.joinToString()}.",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** A thin tonal hint bar shown above the tabs while visibility mode is on (MD mode feedback). */
@Composable
private fun HideModeBar() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Koppints egy csempére, hogy elrejtsd vagy megmutasd nem-szakértő nézetben.",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The assign-mode header: pick the active target zone (FilterChips + "Új zóna"), and toggle whether
 * only unassigned outputs show. Sits above the tabs while [MainScreen]'s assign mode is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignmentBar(
    zones: List<Zone>,
    activeZoneId: String?,
    onSelectZone: (String) -> Unit,
    onAddZone: (String, String?) -> Unit,
    hideZoned: Boolean,
    onSetHideZoned: (Boolean) -> Unit,
    nameFilter: String,
    onSetNameFilter: (String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                if (zones.isEmpty()) "Hozz létre egy zónát a besoroláshoz" else "Besorolás zónába (koppints a csempére):",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                zones.forEach { zone ->
                    FilterChip(
                        selected = zone.id == activeZoneId,
                        onClick = { onSelectZone(zone.id) },
                        label = { Text(zone.name) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showAddDialog = true },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                    label = { Text("Új zóna") },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nameFilter,
                onValueChange = onSetNameFilter,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Szűrés név szerint (pl. terasz)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (nameFilter.isNotEmpty()) {
                        IconButton(onClick = { onSetNameFilter("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Szűrő törlése")
                        }
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Csak besorolatlanok", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = hideZoned, onCheckedChange = onSetHideZoned)
            }
        }
    }
    if (showAddDialog) {
        ZoneNameDialog(
            title = "Új zóna",
            confirmLabel = "Hozzáad",
            onConfirm = { name, icon -> onAddZone(name, icon); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
}

/** Name-entry dialog for creating a zone (empty initial) or renaming one (prefilled). */
@Composable
internal fun ZoneNameDialog(
    title: String,
    confirmLabel: String,
    initial: String = "",
    initialIcon: String? = null,
    onConfirm: (name: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var icon by remember { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Zóna neve") },
                )
                ZoneIconPicker(selected = icon, onSelect = { icon = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, icon) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

/**
 * Top-bar title that doubles as the server switcher: tapping the server name opens a dropdown of
 * configured slots. The dropdown caret (▾) is the visible affordance that signals it's interactive
 * (Material guidance: an interactive title needs a discoverable cue). Editing slots moved to Settings.
 */
@Composable
private fun ServerTitle(
    ui: MyioUiState,
    servers: List<MyioServer>,
    current: MyioServer?,
    onSelect: (String) -> Unit,
    isAggregate: Boolean = false,
    showAggregate: Boolean = false,
    onSelectAggregate: () -> Unit = {},
    aggregateStatus: ConnectionStatus? = ConnectionStatus.Connected,
) {
    var open by remember { mutableStateOf(false) }
    // Openable if there's more than one real server, or the aggregate is offered as an extra choice.
    val switchable = servers.size > 1 || showAggregate
    // null = nothing to connect to (no server / no polled server yet): no dot, no fake "connecting".
    val dotStatus: ConnectionStatus? = when {
        isAggregate -> aggregateStatus
        current == null -> null
        else -> ui.status
    }
    val titleText = if (isAggregate) "Otthon (összes)" else current?.name ?: "myIO Home KT"
    val subtitle = when {
        isAggregate -> if (aggregateStatus == null) "Még nincs zónázott eszköz" else "Összevont vezérlés"
        current == null -> "Nincs szerver beállítva"
        else -> statusLine(ui)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (switchable) Modifier.clickable { open = true } else Modifier,
        ) {
            if (dotStatus != null) {
                StatusDot(dotStatus)
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (switchable) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "Szerver választása",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showAggregate) {
                DropdownMenuItem(
                    text = { Text("Otthon (összes)") },
                    onClick = { open = false; onSelectAggregate() },
                    leadingIcon = {
                        if (isAggregate) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
                HorizontalDivider()
            }
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = { open = false; onSelect(server.id) },
                    leadingIcon = {
                        if (!isAggregate && server.id == current?.id) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

/**
 * A single connection dot for the aggregate header: Connected only if every polled server is connected,
 * Error if any errored, otherwise Connecting (empty map → null: nothing to show a status for).
 */
private fun aggregateStatus(perServer: Map<String, MyioUiState>): ConnectionStatus? = when {
    perServer.isEmpty() -> null
    perServer.values.any { it.status == ConnectionStatus.Error } -> ConnectionStatus.Error
    perServer.values.all { it.status == ConnectionStatus.Connected } -> ConnectionStatus.Connected
    else -> ConnectionStatus.Connecting
}

@Composable
private fun StatusDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.Connecting -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
        ConnectionStatus.Error -> MaterialTheme.colorScheme.error
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp).clip(CircleShape)) {}
}
