package org.vaczi.myiohome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.net.DiscoveredServer
import org.vaczi.myiohome.data.net.localIpv4Subnets

/** Manage the configured server slots: add, edit, delete, or discover them on the LAN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    servers: List<MyioServer>,
    onBack: () -> Unit,
    onSave: (original: MyioServer?, updated: MyioServer) -> Unit,
    onDelete: (MyioServer) -> Unit,
    /** Undo of a delete: re-insert the slot at its original index. Null falls back to appending via [onSave]. */
    onRestore: ((MyioServer, Int) -> Unit)? = null,
    onMoveServer: (MyioServer, Boolean) -> Unit = { _, _ -> },
    discovery: DiscoveryState = DiscoveryState(),
    onStartDiscovery: (subnet: String, user: String, password: String) -> Unit = { _, _, _ -> },
    onResetDiscovery: () -> Unit = {},
    defaultUser: String = "admin",
    defaultPassword: String = "admin",
    /** Expert mode gates the per-server "hide from display" switch in the editor. */
    expertMode: Boolean = true,
    /** (server id, info) of the currently connected server; shown in that server's editor only. */
    currentServerInfo: Pair<String, ServerInfo>? = null,
) {
    // null = dialog closed; Editing(null) = adding; Editing(server) = editing that slot.
    var editing by remember { mutableStateOf<Editing?>(null) }
    var discovering by remember { mutableStateOf(false) }

    // /24 prefix candidates for the pickers — derived, nothing hardcoded site-specific: the myIO
    // factory default (192.168.1), the phone's own /24(s), and the /24 of any already-added server.
    val localSubnets = remember { localIpv4Subnets() }
    val subnetCandidates = remember(servers, localSubnets) {
        (servers.map { it.host.substringBeforeLast('.') } + localSubnets + "192.168.1")
            .filter { it.isNotBlank() && it.count { c -> c == '.' } == 2 }
            .distinct()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Szerverek") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
                actions = {
                    IconButton(onClick = { discovering = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Keresés a hálózaton")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Editing(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Új szerver")
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Még nincs szerver", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keresd meg a myIO eszközöket a hálózaton, vagy vedd fel őket kézzel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = { discovering = true }) { Text("Keresés a hálózaton") }
                TextButton(onClick = { editing = Editing(null) }) { Text("Kézi felvétel") }
            }
        } else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(servers, key = { _, s -> s.id }) { index, server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    supportingContent = {
                        Text(
                            "${server.host}:${server.port}" +
                                (if (server.hidden) " · rejtett (nem-szakértő nézetben)" else "") +
                                (server.shareUser?.takeIf { it.isNotBlank() }?.let { " · megoszt: $it" } ?: ""),
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onMoveServer(server, true) }, enabled = index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Feljebb")
                            }
                            IconButton(onClick = { onMoveServer(server, false) }, enabled = index < servers.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Lejjebb")
                            }
                            IconButton(onClick = { editing = Editing(server) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Szerkesztés")
                            }
                            IconButton(onClick = {
                                val at = servers.indexOfFirst { it.id == server.id }
                                onDelete(server)
                                // Replace any still-showing snackbar so a stale Undo can't outlive its message.
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar(
                                        message = "${server.name} törölve",
                                        actionLabel = "Visszavonás",
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        if (onRestore != null) onRestore(server, at) else onSave(null, server)
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Törlés")
                            }
                        }
                    },
                    modifier = Modifier.clickable { editing = Editing(server) },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { target ->
        ServerEditDialog(
            initial = target.server,
            subnetCandidates = subnetCandidates,
            expertMode = expertMode,
            serverInfo = currentServerInfo?.takeIf { it.first == target.server?.id }?.second,
            onDismiss = { editing = null },
            onConfirm = { updated -> onSave(target.server, updated); editing = null },
        )
    }

    if (discovering) {
        DiscoveryDialog(
            state = discovery,
            knownHosts = servers.map { it.host }.toSet(),
            subnetCandidates = subnetCandidates,
            defaultUser = defaultUser,
            defaultPassword = defaultPassword,
            onScan = onStartDiscovery,
            onAdd = { found, user, password ->
                onSave(null, MyioServer(name = found.name ?: found.host, host = found.host, user = user, password = password))
            },
            onDismiss = { discovering = false; onResetDiscovery() },
        )
    }
}

/**
 * LAN scan dialog. Probes a /24 for myIO devices (auth-free, via the 843 connect; see [MyioDiscovery])
 * and offers the hits as new slots. The supplied credentials are also used to label hits with their
 * `MYIOname` where they work, and become the new slot's credentials when added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryDialog(
    state: DiscoveryState,
    knownHosts: Set<String>,
    subnetCandidates: List<String>,
    defaultUser: String,
    defaultPassword: String,
    onScan: (subnet: String, user: String, password: String) -> Unit,
    onAdd: (DiscoveredServer, user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var subnet by remember { mutableStateOf(subnetCandidates.firstOrNull() ?: "192.168.1") }
    var user by remember { mutableStateOf(defaultUser) }
    var password by remember { mutableStateOf(defaultPassword) }
    var subnetMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keresés a hálózaton") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "A megadott /24 alháló (.1–.254) pásztázása a 843-as porton. A jelszó a nevek " +
                        "lekéréséhez kell — ahol nem jó, az eszköz IP-vel jelenik meg.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubnetDropdown(
                    candidates = subnetCandidates,
                    expanded = subnetMenuOpen,
                    onExpandedChange = { subnetMenuOpen = it },
                    label = "Alháló (első 3 oktett)",
                    value = subnet,
                    onValueChange = { subnet = it },
                    editable = true,
                    enabled = !state.running,
                    onPick = { subnet = it },
                )
                OutlinedTextField(user, { user = it }, label = { Text("Felhasználó") }, singleLine = true, enabled = !state.running)
                PasswordField(password, { password = it }, "Jelszó", enabled = !state.running)

                if (state.running) {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Keresés… ${state.done}/${state.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.finished && state.results.isEmpty()) {
                    Text("Nem található myIO ezen az alhálón.", style = MaterialTheme.typography.bodyMedium)
                }

                state.results.forEach { found ->
                    val already = found.host in knownHosts
                    ListItem(
                        headlineContent = { Text(found.name ?: found.host) },
                        supportingContent = found.name?.let { { Text(found.host) } },
                        trailingContent = {
                            if (already) {
                                Text("hozzáadva", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                IconButton(onClick = { onAdd(found, user, password) }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Hozzáadás")
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onScan(subnet.trim(), user, password) },
                enabled = !state.running && subnet.isNotBlank(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.finished) "Újra" else "Keresés")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kész") } },
    )
}

/**
 * /24-subnet picker shared by the discovery dialog (editable, typed value drives the scan) and the
 * server editor (read-only, picking a candidate only fills in the host field's prefix). [editable]
 * switches between the two text-field modes; [onPick] fires when a candidate is chosen from the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubnetDropdown(
    candidates: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    editable: Boolean,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) onExpandedChange(it) },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editable,
            label = { Text(label) },
            placeholder = { Text(if (editable) "192.168.1" else "válassz…") },
            singleLine = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(
                if (editable) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable,
            ).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            candidates.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text("$candidate.x") },
                    onClick = { onPick(candidate); onExpandedChange(false) },
                )
            }
        }
    }
}

/** Password field: masked by default, with an eye toggle to reveal it. */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Jelszó elrejtése" else "Jelszó megjelenítése",
                )
            }
        },
    )
}

/** Wrapper so a closed dialog (null) is distinct from "add new" (Editing(server = null)). */
private data class Editing(val server: MyioServer?)

/** Preset poll intervals (seconds) offered in the server editor. */
private val POLL_PRESETS = listOf(1, 2, 5, 10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditDialog(
    initial: MyioServer?,
    subnetCandidates: List<String>,
    expertMode: Boolean,
    serverInfo: ServerInfo?,
    onDismiss: () -> Unit,
    onConfirm: (MyioServer) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // New slot: prefill the host with the first candidate /24 prefix so only the last octet is typed.
    var host by remember {
        mutableStateOf(initial?.host ?: subnetCandidates.firstOrNull()?.let { "$it." } ?: "")
    }
    var port by remember { mutableStateOf((initial?.port ?: 80).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "admin") }
    var password by remember { mutableStateOf(initial?.password ?: "admin") }
    var pollSec by remember { mutableStateOf(initial?.pollIntervalSec ?: SettingsStore.DEFAULT_POLL_SEC) }
    var hidden by remember { mutableStateOf(initial?.hidden ?: false) }
    var shareUser by remember { mutableStateOf(initial?.shareUser ?: "") }
    var sharePassword by remember { mutableStateOf(initial?.sharePassword ?: "") }
    var prefixMenuOpen by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Új szerver" else "Szerver szerkesztése") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (serverInfo != null) {
                    Text(
                        "Sorozatszám: ${serverInfo.serial} · Firmware: v${serverInfo.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Név") }, singleLine = true)
                if (subnetCandidates.isNotEmpty()) {
                    SubnetDropdown(
                        candidates = subnetCandidates,
                        expanded = prefixMenuOpen,
                        onExpandedChange = { prefixMenuOpen = it },
                        label = "Alháló",
                        value = "",
                        onValueChange = {},
                        editable = false,
                        // Keep the last octet if already typed; otherwise just set the prefix.
                        onPick = { candidate -> host = "$candidate.${host.substringAfterLast('.', "")}" },
                    )
                }
                OutlinedTextField(
                    host,
                    { host = it },
                    label = { Text("IP / host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    user,
                    { user = it },
                    label = { Text("Felhasználó") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                PasswordField(password, { password = it }, "Jelszó")
                Text(
                    "Frissítési időköz",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    POLL_PRESETS.forEachIndexed { i, sec ->
                        SegmentedButton(
                            selected = pollSec == sec,
                            onClick = { pollSec = sec },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = POLL_PRESETS.size),
                            icon = {}, // no check mark: it would squeeze the equal-width cells
                            label = { Text("$sec mp", maxLines = 1) },
                        )
                    }
                }
                if (expertMode) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Elrejtés a megjelenítésből", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Nem-szakértő nézetben nem választható; a zónázott kimenetei az " +
                                    "„Otthon (összes)” nézetben így is megjelennek.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = hidden, onCheckedChange = { hidden = it })
                    }
                    HorizontalDivider()
                    Text("Megosztási hitelesítés (opcionális)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Beállítások exportjakor a megosztott másolat EZT a fiókot kapja a saját belépésed " +
                            "helyett (pl. „family”). Így nem kell minden megosztás előtt admin↔family-re " +
                            "kapcsolgatnod. Üresen hagyva a saját felhasználó/jelszó megy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(shareUser, { shareUser = it }, label = { Text("Megosztási felhasználó") }, singleLine = true)
                    PasswordField(sharePassword, { sharePassword = it }, "Megosztási jelszó")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Editing keeps the original slot's id; adding gets a fresh one via the default.
                    val base = initial ?: MyioServer(name = "", host = "")
                    // Blank share-user → no override; drop the password too so it can't linger orphaned.
                    val su = shareUser.trim().ifBlank { null }
                    onConfirm(
                        base.copy(
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toInt(),
                            user = user,
                            password = password,
                            pollIntervalSec = pollSec,
                            hidden = hidden,
                            shareUser = su,
                            sharePassword = if (su == null) null else sharePassword.ifBlank { null },
                        ),
                    )
                },
                enabled = valid,
            ) { Text("Mentés") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
