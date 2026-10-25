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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverState
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.Sensor
import org.vaczi.myiohome.data.model.SensorKind
import kotlin.math.roundToInt

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
    /** Live, case-insensitive name filter typed in assign mode; blank shows everything. */
    val nameFilter: String = "",
    /** Toggle the given output key in/out of the active zone (no-op if no active zone). */
    val onToggleAssignment: (String) -> Unit = {},
)

/**
 * Per-tile visibility state for the type-tab lists (expert-only "visibility mode"). While [hideMode] is
 * on, tiles render as uniform [HideableTile]s (icon + name + eye/eye-off) that toggle the output's hidden
 * flag on a single tap; [hiddenKeys] marks which are currently hidden. Outside hide mode this is inert.
 * Hidden outputs are filtered out only in **non-expert** mode (in expert mode everything stays visible).
 */
data class HideUi(
    val hideMode: Boolean = false,
    val hiddenKeys: Set<String> = emptySet(),
    val onToggleHidden: (String) -> Unit = {},
)

/**
 * One type tab (Relék / PCA / Dimmerek / Szenzorok): the named items in a 2-column grid. Each tile is a
 * live [control] normally, a uniform [HideableTile] in visibility mode, or an [AssignableTile] in assign
 * mode. Hidden (non-expert) and already-zoned (assign + hideZoned) items are filtered out first.
 */
@Composable
internal fun <T> TypeTabList(
    items: List<T>,
    icon: ImageVector,
    key: (T) -> String,
    name: (T) -> String,
    zoneUi: ZoneUi,
    hideUi: HideUi,
    expertMode: Boolean,
    control: @Composable (T) -> Unit,
) = TileGrid(
    items.filter { name(it).isNotBlank() }
        .filterHidden(hideUi, expertMode, key)
        .filterZoned(zoneUi, key)
        .filterByName(zoneUi, name),
) { item ->
    when {
        hideUi.hideMode -> HideableTile(name(item), icon, key(item), hideUi)
        zoneUi.assignMode -> AssignableTile(name(item), icon, key(item), zoneUi)
        else -> control(item)
    }
}

/** In assign mode with "hide already-zoned" on, drop outputs that already belong to any zone. */
private fun <T> List<T>.filterZoned(zoneUi: ZoneUi, key: (T) -> String): List<T> =
    if (zoneUi.assignMode && zoneUi.hideZoned) filter { !zoneUi.config.isAssigned(key(it)) } else this

/** In assign mode, keep only items whose name contains the typed filter (case-insensitive). */
private fun <T> List<T>.filterByName(zoneUi: ZoneUi, name: (T) -> String): List<T> {
    val query = zoneUi.nameFilter.trim()
    return if (zoneUi.assignMode && query.isNotEmpty()) filter { name(it).contains(query, ignoreCase = true) } else this
}

/**
 * Drop hidden outputs in non-expert mode (they vanish from the locked view). In expert mode they stay —
 * the visibility mode shows them (struck-through) so the owner can toggle them back on.
 */
private fun <T> List<T>.filterHidden(hideUi: HideUi, expertMode: Boolean, key: (T) -> String): List<T> =
    if (expertMode) this else filter { key(it) !in hideUi.hiddenKeys }

// Live control tiles, shared by the type tabs and the Otthon (zone) dashboard so both render an
// output identically. Each maps a domain output to its appropriate tile + control callback.

// A tile is interactive only when both connected ([enabled]) and writable by the logged-in user. When the
// user lacks write permission it renders [locked] (a padlock + greyed control) so the restriction is
// visible rather than a command that silently bounces off the device.

@Composable
internal fun RelayControlTile(r: RelayOutput, actions: MyioActions, enabled: Boolean) =
    LampTile(r.name, r.on, if (r.on) "BE" else "KI", Icons.Filled.Power, enabled && r.writable, !r.writable) {
        actions.onToggleRelay(r)
    }

@Composable
internal fun PcaControlTile(p: PcaOutput, actions: MyioActions, enabled: Boolean) =
    if (p.isPwm) {
        DimmableLampTile(p.name, p.state, Icons.Filled.Lightbulb, enabled && p.writable, !p.writable,
            onToggle = { actions.onTogglePca(p) }, onSet = { actions.onSetPcaBrightness(p, it) })
    } else {
        LampTile(p.name, p.on, if (p.on) "BE" else "KI", Icons.Filled.Lightbulb, enabled && p.writable, !p.writable) {
            actions.onTogglePca(p)
        }
    }

@Composable
internal fun PwmControlTile(d: PwmOutput, actions: MyioActions, enabled: Boolean) =
    DimmableLampTile(d.name, d.state, Icons.Filled.Lightbulb, enabled && d.writable, !d.writable,
        onToggle = { actions.onTogglePwm(d) }, onSet = { actions.onSetPwmBrightness(d, it) })

/** Callbacks for a cover tile. Editing callbacks are only invoked when [expert] and act via the ⋮ menu. */
data class CoverActions(
    val onOpen: (Cover) -> Unit = {},
    val onClose: (Cover) -> Unit = {},
    val onStop: (Cover) -> Unit = {},
    val onSwap: (Cover) -> Unit = {},
    val onRename: (Cover, String) -> Unit = { _, _ -> },
    val onDisable: (Cover) -> Unit = {},
)

/**
 * Roller-shutter tile: up / stop / down momentary buttons + a running-direction indicator. There is no
 * real position feedback (the device's motor timer stops at the end stop), so no slider. [locked]
 * (no write permission) greys the buttons and shows a padlock. In [expert] mode a ⋮ menu offers
 * fel/le swap, rename and "not a shutter" (disable) — the user's corrections to auto-detection.
 */
@Composable
internal fun CoverTile(cover: Cover, enabled: Boolean, expert: Boolean, actions: CoverActions) {
    val active = cover.writable && enabled
    val moving = cover.state != CoverState.STOPPED
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Surface(
        color = if (moving) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (moving) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Blinds, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    cover.name.ifBlank { "Redőny ${cover.upId}" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!cover.writable) LockBadge()
                // Only shown while moving; a stopped shutter is already told by the container colour.
                if (moving) {
                    Text(
                        coverStateLabel(cover.state),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (expert) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Beállítások", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Fel/le csere") },
                                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                                onClick = { menuOpen = false; actions.onSwap(cover) },
                            )
                            DropdownMenuItem(
                                text = { Text("Átnevezés…") },
                                onClick = { menuOpen = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Nem redőny") },
                                onClick = { menuOpen = false; actions.onDisable(cover) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CoverButton(Icons.Filled.KeyboardArrowUp, "Fel", active, cover.state == CoverState.OPENING, Modifier.weight(1f)) { actions.onOpen(cover) }
                CoverButton(Icons.Filled.Stop, "Állj", active, false, Modifier.weight(1f)) { actions.onStop(cover) }
                CoverButton(Icons.Filled.KeyboardArrowDown, "Le", active, cover.state == CoverState.CLOSING, Modifier.weight(1f)) { actions.onClose(cover) }
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(cover.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Redőny átnevezése") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; actions.onRename(cover, text) }) { Text("Mentés") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Mégse") } },
        )
    }
}

@Composable
private fun CoverButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        colors = if (active) IconButtonDefaults.filledIconButtonColors()
        else IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(icon, contentDescription = label)
    }
}

private fun coverStateLabel(state: CoverState): String = when (state) {
    CoverState.OPENING -> "Nyílik"
    CoverState.CLOSING -> "Záródik"
    CoverState.STOPPED -> "Áll"
}

/** A 2-column lazy grid with the app's standard padding/spacing, shared by every tile grid. */
@Composable
internal fun TwoColumnLazyGrid(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Centered "nothing to show" hint filling the available space. */
@Composable
internal fun EmptyListHint(text: String = "Nincs megjeleníthető elem") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two-column grid of compact tiles, shared by the lamp tabs and the read-only sensor tab. */
@Composable
internal fun <T> TileGrid(items: List<T>, tile: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        EmptyListHint()
        return
    }
    TwoColumnLazyGrid { gridItems(items) { tile(it) } }
}

@Composable
private fun lampContainerColor(on: Boolean) =
    if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun lampContentColor(on: Boolean) =
    if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Compact tap-to-toggle lamp tile (grid cell), vividly coloured when on. Read-only until connected.
 * [locked] = the user has no write permission → a padlock replaces the toggle affordance.
 */
@Composable
private fun LampTile(
    name: String,
    on: Boolean,
    valueLabel: String,
    icon: ImageVector,
    enabled: Boolean,
    locked: Boolean = false,
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
                if (locked) LockBadge()
                Text(valueLabel, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
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
    locked: Boolean = false,
    onToggle: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(state.toFloat()) }
    // Sync the local drag position to the polled state, but only while not actively dragging — otherwise
    // a poll landing mid-drag would yank the thumb out from under the user's finger.
    LaunchedEffect(state) { if (!dragging) pos = state.toFloat() }
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
                if (locked) LockBadge()
                Text("${percent(shownState)}%", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
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
                // Thin line track (like the original app) instead of the bulky M3 default.
                track = { sliderState ->
                    val fraction = sliderState.value / 255f
                    Box(
                        Modifier.fillMaxWidth().height(3.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    CircleShape,
                                ),
                        )
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
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
                maxLines = 2,
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
        // Edit-mode highlight uses tertiary, not the primary "ON" green of the live tiles.
        color = if (assigned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (assigned) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
 * Uniform tile shown in visibility mode (replaces the control tiles): icon + name + an eye/eye-off badge.
 * Hidden outputs render struck-through and dimmed; one tap toggles the hidden flag. Used on the type tabs
 * only (the home/aggregate dashboards just filter hidden outputs out in non-expert mode).
 */
@Composable
private fun HideableTile(name: String, icon: ImageVector, outputKey: String, hideUi: HideUi) {
    val hidden = outputKey in hideUi.hiddenKeys
    Surface(
        // Edit-mode highlight uses tertiary, not the primary "ON" green of the live tiles.
        color = if (hidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { hideUi.onToggleHidden(outputKey) },
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
                textDecoration = if (hidden) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (hidden) "Rejtett" else "Látható",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Small padlock shown on a tile the logged-in user may see but not control (no write permission). */
@Composable
internal fun LockBadge() {
    Icon(
        Icons.Filled.Lock,
        contentDescription = "Csak megtekintés (nincs vezérlési jog)",
        modifier = Modifier.size(16.dp).padding(end = 4.dp),
    )
}

internal fun percent(state: Int): Int = (state / 255.0 * 100).roundToInt()

internal fun formatSensor(s: Sensor): String = when (s.kind) {
    SensorKind.TEMPERATURE -> "%.1f °C".format(s.value)
    SensorKind.HUMIDITY -> "%.0f%%".format(s.value)
    SensorKind.ENERGY -> "%.2f kWh".format(s.value)
}
