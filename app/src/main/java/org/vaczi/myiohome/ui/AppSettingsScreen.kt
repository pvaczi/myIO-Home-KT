package org.vaczi.myiohome.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.vaczi.myiohome.data.local.AppTheme

/**
 * App-level settings: choose the theme (persisted) and jump to the server-slot editor.
 * Reached from the top-bar gear icon. Server *switching* lives on the main screen (tap the title);
 * this screen is for configuration that isn't part of day-to-day control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    theme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
    onEditServers: () -> Unit,
    onShareSettings: () -> Unit,
    onFullBackup: () -> Unit,
    expertMode: Boolean,
    onSetExpertMode: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    var showFullBackupWarning by remember { mutableStateOf(false) }
    if (showFullBackupWarning) {
        AlertDialog(
            onDismissRequest = { showFullBackupWarning = false },
            title = { Text("Teljes mentés") },
            text = { Text("A teljes mentés a szerverek jelszavait is tartalmazza. Csak biztonságos helyre mentsd, ne oszd meg.") },
            confirmButton = {
                TextButton(onClick = {
                    showFullBackupWarning = false
                    onFullBackup()
                }) { Text("Mentés") }
            },
            dismissButton = { TextButton(onClick = { showFullBackupWarning = false }) { Text("Mégse") } },
        )
    }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Beállítások") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Téma")
            ThemeOption("Zöld (eredeti)", "Mindig sötét, fekete háttér – a klasszikus myIO-színek", AppTheme.GREEN, theme, onSelectTheme)
            ThemeOption("Dinamikus / kék", "A háttérképből vett színek, követi a telefon világos/sötét módját (Android 12+)", AppTheme.DYNAMIC, theme, onSelectTheme)

            HorizontalDivider()

            SectionHeader("Szerverek")
            ListItem(
                headlineContent = { Text("Szerverek szerkesztése…") },
                supportingContent = { Text("Szerverek felvétele, módosítása, törlése") },
                leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = onEditServers,
                ),
            )

            HorizontalDivider()

            SectionHeader("Megosztás és mentés")
            ListItem(
                headlineContent = { Text("Megosztás (családtagnak)…") },
                supportingContent = { Text("Szerverek és zónák küldése e-mailben, SMS-ben stb. (a megadott családi belépéssel)") },
                leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = onShareSettings,
                ),
            )
            ListItem(
                headlineContent = { Text("Teljes mentés…") },
                supportingContent = { Text("Minden beállításod fájlba mentése a saját belépéseiddel (telefoncsere, újratelepítés)") },
                leadingContent = { Icon(Icons.Filled.Save, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = { showFullBackupWarning = true },
                ),
            )

            HorizontalDivider()

            SectionHeader("Speciális")
            ListItem(
                headlineContent = { Text("Szakértő mód") },
                supportingContent = {
                    Text(
                        "Zónák szerkesztése és elemek elrejtése. Kikapcsolva egyszerű, zárolt nézet.",
                    )
                },
                leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                trailingContent = { Switch(checked = expertMode, onCheckedChange = onSetExpertMode) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = expertMode,
                    role = Role.Switch,
                    onClick = { onSetExpertMode(!expertMode) },
                ),
            )

            HorizontalDivider()

            SectionHeader("Súgó / Névjegy")
            ListItem(
                headlineContent = { Text("Súgó") },
                supportingContent = { Text("Rövid kezdő útmutató az app használatához") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = { showHelp = true },
                ),
            )
            ListItem(
                headlineContent = { Text("Névjegy") },
                supportingContent = { Text("Verzió és app-információ") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = false,
                    role = Role.Button,
                    onClick = { showAbout = true },
                ),
            )
        }
    }
}

/** Rövid kezdő útmutató — a fő funkciók pár bekezdésben, scrollozható dialógusban. */
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
        title = { Text("Súgó") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpParagraph(
                    "Szerver felvétele",
                    "Beállítások → „Szerverek szerkesztése…”. Add meg a myIO vezérlő IP-címét, " +
                        "portját és belépését (gyári alap: admin / admin), vagy nyomd meg a " +
                        "„Keresés a hálózaton” gombot, és az app végigpásztázza a helyi hálózatot.",
                )
                HelpParagraph(
                    "Szerverváltás",
                    "A felső sávban a cím (szervernév ▾) a szerverváltó. Több szerver esetén az " +
                        "„Otthon (összes)” virtuális nézet az összes szervert egyben mutatja.",
                )
                HelpParagraph(
                    "Fülek",
                    "Az alsó sávban a kimenet-típusok között válthatsz: Relé, PCA, Redőny, " +
                        "Szenzor stb. — csak a nem-üres fülek látszanak. A csempére koppintva " +
                        "kapcsolsz; a fényerő-szabályozható kimeneteknél a szintet is állíthatod.",
                )
                HelpParagraph(
                    "Zónák és Otthon nézet",
                    "Az „Otthon” fülön szobákba / zónákba rendezheted a kimeneteket, és onnan " +
                        "közvetlenül vezérelheted őket. A zónák a szerverek között megosztottak.",
                )
                HelpParagraph(
                    "Szakértő mód",
                    "Bekapcsolva szerkesztheted a zónákat, átnevezheted a redőnyöket és " +
                        "elrejthetsz eszközöket. Kikapcsolva egyszerűsített, zárolt nézetet kapsz.",
                )
                HelpParagraph(
                    "Beállítások megosztása",
                    "A szerverek és zónák egy fájlba menthetők és elküldhetők (pl. e-mailben) " +
                        "másik telefonra, ahol az app importálja őket.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Bezár") }
        },
    )
}

@Composable
private fun HelpParagraph(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    Text(body, style = MaterialTheme.typography.bodyMedium)
}

/** Névjegy — alkalmazás neve és a csomagból kiolvasott verzió. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text("myIO Home KT") },
        text = {
            Column {
                Text(
                    if (versionName.isNotEmpty()) "Verzió: $versionName" else "Verzió: ismeretlen",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "myIO okosotthon-vezérlő kliens. Közvetlenül a LAN-on, a myIO eszközök " +
                        "80-as HTTP portján kommunikál — felhő nélkül.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Bezár") }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A single-choice theme row (M3 RadioButton list pattern). The whole row is the touch target. */
@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    value: AppTheme,
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    val isSelected = value == selected
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            RadioButton(selected = isSelected, onClick = null) // row handles the click
        },
        modifier = Modifier.fillMaxWidth().selectable(
            selected = isSelected,
            role = Role.RadioButton,
            onClick = { onSelect(value) },
        ),
    )
}
