package org.vaczi.myiohome.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry

/** Scene callbacks + data for the chip row. [entryLabel] resolves a stored entry to "Szerver · Név" (or a stale hint). */
data class SceneActions(
    val scenes: List<Scene> = emptyList(),
    val onRun: (Scene) -> Unit = {},
    /** Snapshot of what is on right now and allowed in scenes (empty entries = no candidate). */
    val onPrepareCapture: () -> SceneCapture = { SceneCapture(emptyList(), emptySet()) },
    /** The capture checklist: the snapshot grouped by zone. */
    val candidateGroups: (SceneCapture) -> List<SceneCandidateGroup> = { emptyList() },
    val defaultName: () -> String = { "Jelenet 1" },
    val onAdd: (name: String, capture: SceneCapture) -> Unit = { _, _ -> },
    val onRename: (Scene, String) -> Unit = { _, _ -> },
    val onSetTurnOthersOff: (Scene, Boolean) -> Unit = { _, _ -> },
    val onRemoveEntry: (Scene, SceneEntry) -> Unit = { _, _ -> },
    /** Replace the scene's entries with the checked capture (servers outside the capture keep theirs). */
    val onOverwrite: (Scene, name: String, capture: SceneCapture) -> Unit = { _, _, _ -> },
    val onDelete: (Scene) -> Unit = {},
    val onMessage: (String) -> Unit = {},
    val entryLabel: (SceneEntry) -> String = { it.outputKey },
)

/**
 * Horizontal row of scene chips: tap = run; in expert mode long-press = edit menu and a trailing "+" captures
 * the current on-state. Hidden when there is nothing to show (no [scenes] and not [expert]).
 */
@Composable
fun SceneBar(scenes: List<Scene>, expert: Boolean, actions: SceneActions, modifier: Modifier = Modifier) {
    if (scenes.isEmpty() && !expert) return
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Scene?>(null) }
    var membersOf by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Scene?>(null) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }
    // Capture for a new scene ([target] null) or an overwrite; no candidate → a hint instead of the checklist.
    fun startCapture(target: Scene?) {
        val capture = actions.onPrepareCapture()
        if (capture.entries.isEmpty()) actions.onMessage("Nincs jelenetbe jelölt bekapcsolt kimenet — szakértő módban jelöld meg")
        else pending = PendingCapture(capture, actions.candidateGroups(capture), target)
    }

    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scenes.forEach { scene ->
            Box {
                SceneChip(
                    label = scene.name,
                    onClick = { actions.onRun(scene) },
                    onLongClick = if (expert) ({ menuFor = scene.id }) else null,
                )
                DropdownMenu(expanded = menuFor == scene.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Átnevezés") }, onClick = { menuFor = null; renaming = scene })
                    DropdownMenuItem(
                        text = { Text(if (scene.turnOthersOff) "A többit kikapcsolja: be" else "A többit kikapcsolja: ki") },
                        onClick = { menuFor = null; actions.onSetTurnOthersOff(scene, !scene.turnOthersOff) },
                    )
                    DropdownMenuItem(text = { Text("Tagok…") }, onClick = { menuFor = null; membersOf = scene.id })
                    DropdownMenuItem(text = { Text("Felülírás a mostani állapottal") }, onClick = { menuFor = null; startCapture(scene) })
                    DropdownMenuItem(text = { Text("Törlés") }, onClick = { menuFor = null; deleting = scene })
                }
            }
        }
        if (expert) {
            SceneChip(label = null, onClick = { startCapture(null) })
        }
    }

    pending?.let { p ->
        SceneCaptureDialog(
            title = if (p.target == null) "Új jelenet" else "Felülírás: ${p.target.name}",
            confirmLabel = if (p.target == null) "Mentés" else "Felülír",
            initialName = remember(p) { p.target?.name ?: actions.defaultName() },
            groups = p.groups,
            onConfirm = { name, selected ->
                val chosen = SceneControl.selectedCapture(p.capture, selected)
                if (p.target == null) actions.onAdd(name, chosen) else actions.onOverwrite(p.target, name, chosen)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
    renaming?.let { scene ->
        SceneNameDialog(
            title = "Jelenet átnevezése",
            confirmLabel = "Átnevez",
            initial = scene.name,
            onConfirm = { name -> actions.onRename(scene, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { scene ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Jelenet törlése") },
            text = { Text("Törlöd a(z) „${scene.name}” jelenetet?") },
            confirmButton = { TextButton(onClick = { actions.onDelete(scene); deleting = null }) { Text("Törlés") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Mégse") } },
        )
    }
    // Resolve from the live list so a removal updates the open dialog (and a deleted scene closes it).
    scenes.firstOrNull { it.id == membersOf }?.let { scene ->
        AlertDialog(
            onDismissRequest = { membersOf = null },
            title = { Text("${scene.name} – tagok") },
            text = {
                if (scene.entries.isEmpty()) Text("A jelenetnek nincs tagja.")
                else androidx.compose.foundation.lazy.LazyColumn {
                    items(scene.entries.size) { i ->
                        val e = scene.entries[i]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                actions.entryLabel(e),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { actions.onRemoveEntry(scene, e) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Eltávolítás")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { membersOf = null }) { Text("Kész") } },
        )
    }
}

/** An open capture checklist: the snapshot, its zone groups, and the scene to overwrite (null = new scene). */
private data class PendingCapture(val capture: SceneCapture, val groups: List<SceneCandidateGroup>, val target: Scene?)

/**
 * Capture checklist before saving: name field + the candidates grouped by zone, all checked by default; a group
 * header's tri-state checkbox toggles the whole group. Confirm is disabled with no name or nothing checked.
 */
@Composable
private fun SceneCaptureDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    groups: List<SceneCandidateGroup>,
    onConfirm: (name: String, selected: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var selected by remember(groups) { mutableStateOf(SceneControl.defaultSelection(groups)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Jelenet neve") })
                Text(
                    "${selected.size} kimenet kijelölve",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    groups.forEach { group ->
                        item(key = "g:${group.zoneId}") {
                            val count = group.items.count { it.ref in selected }
                            val state = when (count) {
                                0 -> ToggleableState.Off
                                group.items.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = SceneControl.toggleGroup(selected, group) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(state = state, onClick = { selected = SceneControl.toggleGroup(selected, group) })
                                Text(group.title, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        items(group.items, key = { it.ref }) { c ->
                            val checked = c.ref in selected
                            val toggle = { selected = if (checked) selected - c.ref else selected + c.ref }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 24.dp).clickable(onClick = toggle),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { toggle() })
                                Text(
                                    c.label + if (c.entry.level in 2..254) " (${c.entry.level * 100 / 255}%)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, selected) }, enabled = name.isNotBlank() && selected.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

/** A chip-shaped surface with tap + (optional) long-press; [label] null = the "+" capture chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SceneChip(label: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label == null) {
                Icon(Icons.Filled.Add, contentDescription = "Bekapcsoltak felvétele jelenetként")
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
        }
    }
}

@Composable
private fun SceneNameDialog(title: String, confirmLabel: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Jelenet neve") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
