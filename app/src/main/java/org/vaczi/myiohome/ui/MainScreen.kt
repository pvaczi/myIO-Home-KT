package org.vaczi.myiohome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.model.outputKey
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Otthon", Icons.Filled.Home),
    RELAYS("Relék", Icons.Filled.Power),
    PCA("PCA", Icons.Filled.Lightbulb),
    DIMMERS("Dimmerek", Icons.Filled.Tune),
    SENSORS("Szenzorok", Icons.Filled.Thermostat),
    CONTROLLED("Termosztát", Icons.Filled.DeviceThermostat),
}

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

/**
 * Per-tile zone state passed to the type-tab lists. While [assignMode] is on, tiles render as uniform
 * [AssignableTile]s (icon + name + check) that toggle the output's membership in [activeZoneId] on a
 * single tap, and (when [hideZoned]) outputs that already have any zone are hidden so only the
 * unassigned remainder shows. Outside assign mode this is inert and the normal control tiles render.
 */
data class ZoneUi(
    val config: ZoneConfig = ZoneConfig(),
    val assignMode: Boolean = false,
    val activeZoneId: String? = null,
    val hideZoned: Boolean = false,
    /** Toggle the given output key in/out of the active zone (no-op if no active zone). */
    val onToggleAssignment: (String) -> Unit = {},
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
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    zoneConfig: ZoneConfig = ZoneConfig(),
    hideZoned: Boolean = false,
    onSetHideZoned: (Boolean) -> Unit = {},
    onToggleZoneAssignment: (outputKey: String, zoneId: String) -> Unit = { _, _ -> },
    onAddZone: (String) -> String? = { null },
    onRenameZone: (id: String, name: String) -> Unit = { _, _ -> },
    onDeleteZone: (String) -> Unit = {},
    onMoveZone: (id: String, up: Boolean) -> Unit = { _, _ -> },
    onReorderZoneMembers: (zoneId: String, orderedKeys: List<String>) -> Unit = { _, _ -> },
    // Aggregate ("Otthon (összes)") mode: render every polled server's zoned outputs in one dashboard.
    isAggregate: Boolean = false,
    aggregatePerServer: Map<String, MyioUiState> = emptyMap(),
    aggregateAssignments: Map<String, ZoneAssignments> = emptyMap(),
    globalZones: List<Zone> = emptyList(),
    aggregateActionsFor: (serverId: String) -> MyioActions = { MyioActions() },
) {
    // Assignment mode is ephemeral UI state (not persisted): a tag toggle in the top bar turns it on,
    // a chip row picks the active target zone, and every tile becomes a one-tap assign toggle.
    var assignMode by remember { mutableStateOf(false) }
    var activeZoneId by remember { mutableStateOf<String?>(null) }
    // Reorder mode (HOME tab only): a separate top-bar toggle showing up/down arrows on zones + members.
    var reorderMode by remember { mutableStateOf(false) }
    // Keep the active zone valid as zones change (e.g. one gets deleted); default to the first zone.
    LaunchedEffect(zoneConfig.zones, assignMode) {
        if (assignMode && zoneConfig.zones.none { it.id == activeZoneId }) {
            activeZoneId = zoneConfig.zones.firstOrNull()?.id
        }
    }
    val zoneUi = ZoneUi(
        config = zoneConfig,
        assignMode = assignMode,
        activeZoneId = activeZoneId,
        hideZoned = hideZoned,
        onToggleAssignment = { key -> activeZoneId?.let { onToggleZoneAssignment(key, it) } },
    )
    // Only tabs that actually have something to show appear (empty Relék on a lighting controller,
    // empty Dimmerek everywhere, etc. stay hidden — mirrors the factory app). [selectedTab] is the
    // persisted Tab *ordinal* (stable regardless of which tabs are visible), mapped here to an index
    // into the visible list.
    val visibleTabs = Tab.entries.filter { it.hasContent(ui.state) }
    val controlsEnabled = ui.status == ConnectionStatus.Connected
    val currentTab = Tab.entries.getOrNull(selectedTab)?.takeIf { it in visibleTabs } ?: visibleTabs.firstOrNull()
    val selectedIndex = visibleTabs.indexOf(currentTab).coerceAtLeast(0)
    // Reorder mode only exists on HOME; leave it when the user swipes/taps to another tab.
    LaunchedEffect(currentTab) { if (currentTab != Tab.HOME) reorderMode = false }

    // Swipe between tabs, kept in sync with the bottom nav and the persisted selection.
    val tabsState = rememberUpdatedState(visibleTabs)
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { tabsState.value.size })
    LaunchedEffect(selectedIndex) {
        if (visibleTabs.isNotEmpty() && pagerState.currentPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(pagerState) {
        // drop(1): ignore the initial settle so we don't clobber the restored tab on launch.
        snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
            tabsState.value.getOrNull(page)?.let { onSelectTab(it.ordinal) }
        }
    }

    // Aggregate ("Otthon (összes)") tabs live in the bottom nav like the per-server type tabs: page 0 is
    // the "Összes" summary, pages 1..n are one global zone each. Swiping the pager and tapping the nav
    // stay in sync (not persisted — the aggregate is a single virtual view). [scope] drives tab taps.
    val scope = rememberCoroutineScope()
    val aggregatePagerState = rememberPagerState(pageCount = { globalZones.size + 1 })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    ServerTitle(
                        ui = ui,
                        servers = servers,
                        current = currentServer,
                        onSelect = onSelectServer,
                        isAggregate = isAggregate,
                        showAggregate = showAggregate,
                        onSelectAggregate = onSelectAggregate,
                        aggregateStatus = aggregateStatus(aggregatePerServer),
                    )
                },
                actions = {
                    // Reorder toggle: HOME tab only (zones + within-zone members get up/down arrows).
                    if (!isAggregate && currentServer != null && currentTab == Tab.HOME && zoneConfig.zones.isNotEmpty()) {
                        IconButton(onClick = {
                            reorderMode = !reorderMode
                            if (reorderMode) assignMode = false
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
                    if (!isAggregate && currentServer != null && visibleTabs.isNotEmpty()) {
                        IconButton(onClick = {
                            val entering = !assignMode
                            assignMode = entering
                            if (entering) {
                                reorderMode = false
                                if (activeZoneId == null) activeZoneId = zoneConfig.zones.firstOrNull()?.id
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Label,
                                contentDescription = if (assignMode) "Besorolás mód kikapcsolása" else "Besorolás zónákba",
                                tint = if (assignMode) MaterialTheme.colorScheme.primary
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
                if (globalZones.isNotEmpty()) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = aggregatePagerState.currentPage == 0,
                            onClick = { scope.launch { aggregatePagerState.animateScrollToPage(0) } },
                            icon = { Icon(Icons.Filled.Home, contentDescription = "Összes") },
                            label = { Text("Összes") },
                        )
                        globalZones.forEachIndexed { i, zone ->
                            NavigationBarItem(
                                selected = aggregatePagerState.currentPage == i + 1,
                                onClick = { scope.launch { aggregatePagerState.animateScrollToPage(i + 1) } },
                                icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = zone.name) },
                                label = { Text(zone.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            } else if (visibleTabs.isNotEmpty()) {
                NavigationBar {
                    visibleTabs.forEachIndexed { index, t ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onSelectTab(t.ordinal) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                isAggregate -> AggregateHomeScreen(
                    zones = globalZones,
                    servers = servers,
                    perServer = aggregatePerServer,
                    assignments = aggregateAssignments,
                    actionsFor = aggregateActionsFor,
                    pagerState = aggregatePagerState,
                    onAddZone = onAddZone,
                    onRenameZone = onRenameZone,
                    onDeleteZone = onDeleteZone,
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
                // names arrive only with refreshNames — show a loading hint, not "nothing here".
                visibleTabs.isEmpty() && !ui.state.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Nevek betöltése…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                visibleTabs.isEmpty() ->
                    Text(
                        "Nincs megjeleníthető elem",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> Column(Modifier.fillMaxSize()) {
                    // While assigning, a tonal bar (distinct colour = MD mode feedback) holds the active
                    // target zone chips + the "show only unassigned" toggle. It sits above all tabs.
                    if (assignMode) {
                        AssignmentBar(
                            zones = zoneConfig.zones,
                            activeZoneId = activeZoneId,
                            onSelectZone = { activeZoneId = it },
                            onAddZone = { name -> onAddZone(name)?.let { activeZoneId = it } },
                            hideZoned = hideZoned,
                            onSetHideZoned = onSetHideZoned,
                        )
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (visibleTabs[page]) {
                            Tab.HOME -> HomeScreen(
                                state = ui.state,
                                zoneConfig = zoneConfig,
                                actions = actions,
                                enabled = controlsEnabled,
                                reorderMode = reorderMode,
                                onAddZone = onAddZone,
                                onRenameZone = onRenameZone,
                                onDeleteZone = onDeleteZone,
                                onMoveZone = onMoveZone,
                                onReorderMembers = onReorderZoneMembers,
                            )
                            Tab.RELAYS -> RelayList(ui.state.relays, actions, controlsEnabled, zoneUi)
                            Tab.PCA -> PcaList(ui.state.pcas, actions, controlsEnabled, zoneUi)
                            Tab.DIMMERS -> DimmerList(ui.state.pwms, actions, controlsEnabled, zoneUi)
                            Tab.SENSORS -> SensorList(ui.state.sensors, zoneUi)
                            Tab.CONTROLLED -> ControlledList(ui.state, actions, controlsEnabled)
                        }
                    }
                }
            }
        }
    }
}

private fun ServerState.isEmpty() =
    relays.isEmpty() && pcas.isEmpty() && pwms.isEmpty() && sensors.isEmpty()

/** A tab is shown only if it has something to display (named items, or controlled outputs). */
private fun Tab.hasContent(state: ServerState): Boolean = when (this) {
    Tab.HOME -> true // the zone dashboard is always available (it can host the "add first zone" prompt)
    Tab.RELAYS -> state.relays.any { it.name.isNotBlank() }
    Tab.PCA -> state.pcas.any { it.name.isNotBlank() }
    Tab.DIMMERS -> state.pwms.any { it.name.isNotBlank() }
    Tab.SENSORS -> state.sensors.any { it.name.isNotBlank() }
    Tab.CONTROLLED -> state.hasControlledOutputs()
}

private fun statusLine(ui: MyioUiState): String = when (ui.status) {
    ConnectionStatus.Connecting -> "Kapcsolódás…"
    ConnectionStatus.Connected -> ui.info?.let { "${it.serial} · v${it.version}" } ?: "Kapcsolódva"
    ConnectionStatus.Error -> "Hiba: ${ui.error ?: "ismeretlen"}"
}

@Composable
private fun RelayList(items: List<RelayOutput>, actions: MyioActions, enabled: Boolean, zoneUi: ZoneUi) =
    TileGrid(items.filter { it.name.isNotBlank() }.filterZoned(zoneUi) { it.outputKey() }) { r ->
        if (zoneUi.assignMode) AssignableTile(r.name, Icons.Filled.Power, r.outputKey(), zoneUi)
        else RelayControlTile(r, actions, enabled)
    }

@Composable
private fun PcaList(items: List<PcaOutput>, actions: MyioActions, enabled: Boolean, zoneUi: ZoneUi) =
    TileGrid(items.filter { it.name.isNotBlank() }.filterZoned(zoneUi) { it.outputKey() }) { p ->
        if (zoneUi.assignMode) AssignableTile(p.name, Icons.Filled.Lightbulb, p.outputKey(), zoneUi)
        else PcaControlTile(p, actions, enabled)
    }

@Composable
private fun DimmerList(items: List<PwmOutput>, actions: MyioActions, enabled: Boolean, zoneUi: ZoneUi) =
    TileGrid(items.filter { it.name.isNotBlank() }.filterZoned(zoneUi) { it.outputKey() }) { d ->
        if (zoneUi.assignMode) AssignableTile(d.name, Icons.Filled.Lightbulb, d.outputKey(), zoneUi)
        else PwmControlTile(d, actions, enabled)
    }

@Composable
private fun SensorList(items: List<Sensor>, zoneUi: ZoneUi) =
    TileGrid(items.filter { it.name.isNotBlank() }.filterZoned(zoneUi) { it.outputKey() }) { s ->
        if (zoneUi.assignMode) AssignableTile(s.name, Icons.Filled.Thermostat, s.outputKey(), zoneUi)
        else SensorTile(s.name, formatSensor(s))
    }

/** In assign mode with "hide already-zoned" on, drop outputs that already belong to any zone. */
private fun <T> List<T>.filterZoned(zoneUi: ZoneUi, key: (T) -> String): List<T> =
    if (zoneUi.assignMode && zoneUi.hideZoned) filter { !zoneUi.config.isAssigned(key(it)) } else this

// Live control tiles, shared by the type tabs and the Otthon (zone) dashboard so both render an
// output identically. Each maps a domain output to its appropriate tile + control callback.

@Composable
internal fun RelayControlTile(r: RelayOutput, actions: MyioActions, enabled: Boolean) =
    LampTile(r.name, r.on, if (r.on) "BE" else "KI", Icons.Filled.Power, enabled) { actions.onToggleRelay(r) }

@Composable
internal fun PcaControlTile(p: PcaOutput, actions: MyioActions, enabled: Boolean) =
    if (p.isPwm) {
        DimmableLampTile(p.name, p.state, Icons.Filled.Lightbulb, enabled,
            onToggle = { actions.onTogglePca(p) }, onSet = { actions.onSetPcaBrightness(p, it) })
    } else {
        LampTile(p.name, p.on, if (p.on) "BE" else "KI", Icons.Filled.Lightbulb, enabled) { actions.onTogglePca(p) }
    }

@Composable
internal fun PwmControlTile(d: PwmOutput, actions: MyioActions, enabled: Boolean) =
    DimmableLampTile(d.name, d.state, Icons.Filled.Lightbulb, enabled,
        onToggle = { actions.onTogglePwm(d) }, onSet = { actions.onSetPwmBrightness(d, it) })

/** Two-column grid of compact tiles, shared by the lamp tabs and the read-only sensor tab. */
@Composable
private fun <T> TileGrid(items: List<T>, tile: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nincs megjeleníthető elem", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(items) { tile(it) }
    }
}

@Composable
private fun lampContainerColor(on: Boolean) =
    if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun lampContentColor(on: Boolean) =
    if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

/** Compact tap-to-toggle lamp tile (grid cell), vividly coloured when on. Read-only until connected. */
@Composable
private fun LampTile(
    name: String,
    on: Boolean,
    valueLabel: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = lampContainerColor(on),
        contentColor = lampContentColor(on),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.weight(1f))
                Text(valueLabel, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact dimmable lamp tile (grid cell): tap the header to toggle on/off, drag the slider for
 * brightness. While dragging we show the local position; on release we commit and fall back to the
 * polled [state]. The tile colour reflects on/off (live during a drag). Read-only until connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DimmableLampTile(
    name: String,
    state: Int,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var pos by remember(state) { mutableFloatStateOf(state.toFloat()) }
    val shownState = if (dragging) pos.roundToInt() else state
    val on = shownState != 0
    Surface(
        color = lampContainerColor(on),
        contentColor = lampContentColor(on),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.weight(1f))
                Text("${percent(shownState)}%", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val interaction = remember { MutableInteractionSource() }
            Slider(
                value = if (dragging) pos else state.toFloat(),
                enabled = enabled,
                onValueChange = { dragging = true; pos = it },
                onValueChangeFinished = { dragging = false; onSet(pos.roundToInt()) },
                valueRange = 0f..255f,
                interactionSource = interaction,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    // Visible even on the dim "off" tile background.
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
                // Smaller, round thumb instead of the bulky default bar.
                thumb = {
                    Box(
                        Modifier.size(14.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                },
                modifier = Modifier.height(28.dp),
            )
        }
    }
}

/** Dense read-only sensor tile (grid cell): name and reading on one compact row. */
@Composable
internal fun SensorTile(name: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Uniform tile shown in assign mode (replaces the type-specific control tiles): icon + name + a check
 * when the output is in the active zone. One tap toggles membership; disabled until a zone is active.
 */
@Composable
private fun AssignableTile(name: String, icon: ImageVector, outputKey: String, zoneUi: ZoneUi) {
    val active = zoneUi.activeZoneId
    val assigned = active != null && active in zoneUi.config.zonesOf(outputKey)
    Surface(
        color = lampContainerColor(assigned),
        contentColor = lampContentColor(assigned),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (active != null) Modifier.clickable { zoneUi.onToggleAssignment(outputKey) } else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (assigned) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Check, contentDescription = "Hozzárendelve", modifier = Modifier.size(20.dp))
            }
        }
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
    onAddZone: (String) -> Unit,
    hideZoned: Boolean,
    onSetHideZoned: (Boolean) -> Unit,
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
            onConfirm = { name -> onAddZone(name); showAddDialog = false },
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Zóna neve") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
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
    aggregateStatus: ConnectionStatus = ConnectionStatus.Connected,
) {
    var open by remember { mutableStateOf(false) }
    // Openable if there's more than one real server, or the aggregate is offered as an extra choice.
    val switchable = servers.size > 1 || showAggregate
    val dotStatus = if (isAggregate) aggregateStatus else ui.status
    val titleText = if (isAggregate) "Otthon (összes)" else current?.name ?: "myIO Home KT"
    val subtitle = if (isAggregate) "Összevont vezérlés" else statusLine(ui)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (switchable) Modifier.clickable { open = true } else Modifier,
        ) {
            StatusDot(dotStatus)
            Spacer(Modifier.width(8.dp))
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
 * Error if any errored, otherwise Connecting (empty map → Connecting until the first poll lands).
 */
private fun aggregateStatus(perServer: Map<String, MyioUiState>): ConnectionStatus = when {
    perServer.isEmpty() -> ConnectionStatus.Connecting
    perServer.values.any { it.status == ConnectionStatus.Error } -> ConnectionStatus.Error
    perServer.values.all { it.status == ConnectionStatus.Connected } -> ConnectionStatus.Connected
    else -> ConnectionStatus.Connecting
}

@Composable
private fun StatusDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.Connecting -> Color(0xFFFFB300)
        ConnectionStatus.Connected -> Color(0xFF2E7D32)
        ConnectionStatus.Error -> MaterialTheme.colorScheme.error
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp).clip(CircleShape)) {}
}

internal fun percent(state: Int): Int = (state / 255.0 * 100).roundToInt()

internal fun formatSensor(s: Sensor): String = when (s.kind) {
    SensorKind.TEMPERATURE -> "%.1f °C".format(s.value)
    SensorKind.HUMIDITY -> "%.0f%%".format(s.value)
    SensorKind.ENERGY -> "%.2f kWh".format(s.value)
}
