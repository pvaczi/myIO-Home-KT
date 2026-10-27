package org.vaczi.myiohome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Group-control affordances of one zone: visible "Mind be" / "Mind ki" icon buttons + a ⋮ menu with the
 * rest (covers, common brightness) and any [extraMenu] items (rename/delete on the zone header). Actions
 * without eligible members are hidden; each action fans out to the connected [parts] only. Meant to sit
 * at the end of a header [Row].
 */
@Composable
internal fun ZoneGroupBar(
    parts: List<ZoneGroupPart>,
    actions: GroupActions,
    menuDescription: String = "Zóna kezelése",
    extraMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val live = parts.filter { it.enabled }
    val hasSwitch = parts.any { it.members.switchCount > 0 }
    val hasCovers = parts.any { it.members.covers.isNotEmpty() }
    val hasDim = parts.sumOf { it.members.dimCount } >= 2
    val canAct = live.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(false) }

    fun switchAll(on: Boolean) =
        live.filter { it.members.switchCount > 0 }.forEach { actions.onSwitch(it.serverId, it.members, on) }
    fun coversAll(move: CoverMove) =
        live.filter { it.members.covers.isNotEmpty() }.forEach { actions.onCovers(it.serverId, it.members.covers, move) }

    if (hasSwitch) {
        IconButton(onClick = { switchAll(true) }, enabled = canAct) {
            Icon(Icons.Filled.ToggleOn, contentDescription = "Mind be")
        }
        IconButton(onClick = { switchAll(false) }, enabled = canAct) {
            Icon(Icons.Filled.ToggleOff, contentDescription = "Mind ki")
        }
    }
    if (hasSwitch || hasCovers || hasDim || extraMenu != null) {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = menuDescription)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val close = { menuOpen = false }
                if (hasSwitch) {
                    DropdownMenuItem(text = { Text("Mind be") }, enabled = canAct,
                        onClick = { close(); switchAll(true) })
                    DropdownMenuItem(text = { Text("Mind ki") }, enabled = canAct,
                        onClick = { close(); switchAll(false) })
                }
                if (hasCovers) {
                    DropdownMenuItem(text = { Text("Mind fel") }, enabled = canAct,
                        onClick = { close(); coversAll(CoverMove.OPEN) })
                    DropdownMenuItem(text = { Text("Mind le") }, enabled = canAct,
                        onClick = { close(); coversAll(CoverMove.CLOSE) })
                    DropdownMenuItem(text = { Text("Mind stop") }, enabled = canAct,
                        onClick = { close(); coversAll(CoverMove.STOP) })
                }
                if (hasDim) {
                    DropdownMenuItem(text = { Text("Közös fényerő…") }, enabled = canAct,
                        onClick = { close(); showLevel = true })
                }
                if (extraMenu != null) {
                    if (hasSwitch || hasCovers || hasDim) HorizontalDivider()
                    extraMenu(close)
                }
            }
        }
    }
    if (showLevel) {
        GroupLevelDialog(
            onConfirm = { level ->
                showLevel = false
                live.filter { it.members.dimCount > 0 }.forEach { actions.onLevel(it.serverId, it.members, level) }
            },
            onDismiss = { showLevel = false },
        )
    }
}

/** One slider (0–100 %) → a common brightness (0..255) for every dimmable member of the zone. */
@Composable
private fun GroupLevelDialog(onConfirm: (level: Int) -> Unit, onDismiss: () -> Unit) {
    var pct by remember { mutableFloatStateOf(50f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Közös fényerő") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("${pct.roundToInt()} %", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = pct,
                    onValueChange = { pct = it },
                    valueRange = 0f..100f,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm((pct / 100f * 255f).roundToInt()) }) { Text("Beállít") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
