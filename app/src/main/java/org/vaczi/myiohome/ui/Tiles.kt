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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Uniform tile shown in visibility mode (replaces the control tiles): icon + name + an eye/eye-off badge.
 * Hidden outputs render struck-through and dimmed; one tap toggles the hidden flag. Used on the type tabs
 * only (the home/aggregate dashboards just filter hidden outputs out in non-expert mode).
 */
@Composable
private fun HideableTile(name: String, icon: ImageVector, outputKey: String, hideUi: HideUi) {
    val hidden = outputKey in hideUi.hiddenKeys
    Surface(
        color = if (hidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
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

internal fun percent(state: Int): Int = (state / 255.0 * 100).roundToInt()

internal fun formatSensor(s: Sensor): String = when (s.kind) {
    SensorKind.TEMPERATURE -> "%.1f °C".format(s.value)
    SensorKind.HUMIDITY -> "%.0f%%".format(s.value)
    SensorKind.ENERGY -> "%.2f kWh".format(s.value)
}
