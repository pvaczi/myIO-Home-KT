package org.vaczi.myiohome.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.Scene
import org.vaczi.myiohome.data.local.SceneEntry

/** Scene callbacks + data for the chip row. [entryLabel] resolves a stored entry to "Szerver · Név" (or a stale hint). */
data class SceneActions(
    val scenes: List<Scene> = emptyList(),
    val onRun: (Scene) -> Unit = {},
    /** Snapshot of what is on right now (empty entries = nothing on). */
    val onPrepareCapture: () -> SceneCapture = { SceneCapture(emptyList(), emptySet()) },
    val defaultName: () -> String = { "Jelenet 1" },
    val onAdd: (name: String, capture: SceneCapture) -> Unit = { _, _ -> },
    val onRename: (Scene, String) -> Unit = { _, _ -> },
    val onSetTurnOthersOff: (Scene, Boolean) -> Unit = { _, _ -> },
    val onRemoveEntry: (Scene, SceneEntry) -> Unit = { _, _ -> },
    val onOverwrite: (Scene) -> Unit = {},
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
    var pending by remember { mutableStateOf<SceneCapture?>(null) }

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
                    DropdownMenuItem(text = { Text("Felülírás a mostani állapottal") }, onClick = { menuFor = null; actions.onOverwrite(scene) })
                    DropdownMenuItem(text = { Text("Törlés") }, onClick = { menuFor = null; deleting = scene })
                }
            }
        }
        if (expert) {
            SceneChip(
                label = null,
                onClick = {
                    val capture = actions.onPrepareCapture()
                    if (capture.entries.isEmpty()) actions.onMessage("Nincs bekapcsolt kimenet, nincs mit felvenni.")
                    else pending = capture
                },
            )
        }
    }

    pending?.let { capture ->
        SceneNameDialog(
            title = "Új jelenet (${capture.entries.size} kimenet)",
            confirmLabel = "Felvesz",
            initial = remember(capture) { actions.defaultName() },
            onConfirm = { name -> actions.onAdd(name, capture); pending = null },
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
