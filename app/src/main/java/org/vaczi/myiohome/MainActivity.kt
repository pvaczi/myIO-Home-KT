package org.vaczi.myiohome

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.SettingsBackup
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.CoverStore
import org.vaczi.myiohome.data.local.SceneStore
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.ui.AppSettingsScreen
import org.vaczi.myiohome.ui.CoverActions
import org.vaczi.myiohome.ui.GroupActions
import org.vaczi.myiohome.ui.MainScreen
import org.vaczi.myiohome.ui.MyioActions
import org.vaczi.myiohome.ui.MyioViewModel
import org.vaczi.myiohome.ui.SceneActions
import org.vaczi.myiohome.ui.SceneControl
import org.vaczi.myiohome.ui.ServerSettingsScreen
import org.vaczi.myiohome.ui.SettingsSharing
import org.vaczi.myiohome.ui.theme.MyioHomeTheme

/** A pending "import shared settings" prompt: a valid decoded backup awaiting confirmation, or invalid input. */
private sealed interface ImportPrompt {
    data class Ready(val backup: SettingsBackup) : ImportPrompt
    object Invalid : ImportPrompt
}

class MainActivity : ComponentActivity() {

    // Set when the app is opened on a shared settings file (ACTION_VIEW); drives the replace dialog.
    private var importPrompt by mutableStateOf<ImportPrompt?>(null)

    // The running shared-file read; a newer VIEW intent cancels it, so a slower earlier read can't overwrite
    // the newer file's dialog.
    private var importJob: Job? = null

    // Server slots live in DataStore (SettingsStore). A fresh install starts empty (discovery-first):
    // the user scans the LAN or adds slots by hand. The active one is switchable from the top-bar title.
    private val viewModel: MyioViewModel by viewModels {
        MyioViewModel.factory(
            SettingsStore(applicationContext),
            StateCacheStore(applicationContext),
            ZoneStore(applicationContext),
            CoverStore(applicationContext),
            SceneStore(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start — on recreate (rotation) the same VIEW intent would re-prompt otherwise.
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            // System bar icons follow the app's theme, not just the system mode: GREEN is always dark, so
            // the default auto style would draw dark icons on its black bar in system light mode.
            val darkBars = theme == AppTheme.GREEN || isSystemInDarkTheme()
            DisposableEffect(darkBars) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkBars },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkBars },
                )
                onDispose {}
            }
            MyioHomeTheme(theme = theme) {
                val navController = rememberNavController()
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val servers by viewModel.servers.collectAsStateWithLifecycle()
                val currentServer by viewModel.currentServer.collectAsStateWithLifecycle()
                val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                val expertMode by viewModel.expertMode.collectAsStateWithLifecycle()
                val zoneConfig by viewModel.zoneConfig.collectAsStateWithLifecycle()
                val hideZoned by viewModel.hideZoned.collectAsStateWithLifecycle()
                val covers by viewModel.covers.collectAsStateWithLifecycle()
                val disabledCoverMembers by viewModel.disabledCoverMembers.collectAsStateWithLifecycle()
                // Aggregate ("Otthon (összes)") state: global zones + every polled server's live state.
                val isAggregate by viewModel.isAggregate.collectAsStateWithLifecycle()
                val aggregate by viewModel.aggregate.collectAsStateWithLifecycle()
                val globalZones by viewModel.globalZones.collectAsStateWithLifecycle()
                val zoneOrder by viewModel.zoneOrder.collectAsStateWithLifecycle()
                val allAssignments by viewModel.allAssignments.collectAsStateWithLifecycle()
                val aggregateCovers by viewModel.aggregateCovers.collectAsStateWithLifecycle()
                val aggregateDisabledMembers by viewModel.aggregateDisabledMembers.collectAsStateWithLifecycle()
                val aggregateCoverActionsFor: (String) -> CoverActions = { serverId ->
                    CoverActions(
                        onOpen = { viewModel.aggCoverOpen(serverId, it) },
                        onClose = { viewModel.aggCoverClose(serverId, it) },
                        onStop = { viewModel.aggCoverStop(serverId, it) },
                    )
                }
                val aggregateActionsFor: (String) -> MyioActions = { serverId ->
                    MyioActions(
                        onToggleRelay = { viewModel.aggToggleRelay(serverId, it) },
                        onTogglePca = { viewModel.aggTogglePca(serverId, it) },
                        onSetPcaBrightness = { p, v -> viewModel.aggSetPcaBrightness(serverId, p, v) },
                        onTogglePwm = { viewModel.aggTogglePwm(serverId, it) },
                        onSetPwmBrightness = { d, v -> viewModel.aggSetPwmBrightness(serverId, d, v) },
                    )
                }

                val groupActions = GroupActions(
                    onSwitch = { sid, m, on -> viewModel.groupSwitch(sid, m, on) },
                    onCovers = { sid, c, move -> viewModel.groupCovers(sid, c, move) },
                    onLevel = { sid, m, level -> viewModel.groupLevel(sid, m, level) },
                )

                val scenes by viewModel.scenes.collectAsStateWithLifecycle()
                val sceneActions = SceneActions(
                    scenes = scenes,
                    onRun = viewModel::runScene,
                    onPrepareCapture = viewModel::prepareSceneCapture,
                    defaultName = viewModel::defaultSceneName,
                    onAdd = viewModel::addScene,
                    onRename = viewModel::renameScene,
                    onSetTurnOthersOff = viewModel::setSceneTurnOthersOff,
                    onRemoveEntry = viewModel::removeSceneEntry,
                    onOverwrite = viewModel::overwriteScene,
                    onDelete = viewModel::deleteScene,
                    onMessage = viewModel::postSceneMessage,
                    entryLabel = { e ->
                        val server = servers.firstOrNull { it.id == e.serverId }
                        val st = if (isAggregate) aggregate.perServer[e.serverId]?.state
                        else if (e.serverId == currentServer?.id) ui.state else null
                        val name = st?.let { SceneControl.entryName(it, e.outputKey) } ?: e.outputKey
                        "${server?.name ?: "(törölt szerver)"} · $name" + if (e.level in 2..254) " (${e.level * 100 / 255}%)" else ""
                    },
                )

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        MainScreen(
                            ui = ui,
                            actions = MyioActions(
                                onToggleRelay = viewModel::toggleRelay,
                                onTogglePca = viewModel::togglePca,
                                onSetPcaBrightness = viewModel::setPcaBrightness,
                                onTogglePwm = viewModel::togglePwm,
                                onSetPwmBrightness = viewModel::setPwmBrightness,
                                onSetRelayThermostat = viewModel::setRelayThermostat,
                                onSetPcaThermostat = viewModel::setPcaThermostat,
                                onSetPwmThermostat = viewModel::setPwmThermostat,
                            ),
                            servers = servers,
                            currentServer = currentServer,
                            onSelectServer = viewModel::selectServer,
                            showAggregate = servers.size > 1,
                            onSelectAggregate = viewModel::selectAggregate,
                            onOpenSettings = { navController.navigate("settings") },
                            selectedTab = selectedTab,
                            onSelectTab = viewModel::selectTab,
                            expertMode = expertMode,
                            onToggleOutputHidden = viewModel::toggleOutputHidden,
                            disabledCoverMembers = disabledCoverMembers,
                            aggregateDisabledMembers = aggregateDisabledMembers,
                            zoneConfig = zoneConfig,
                            hideZoned = hideZoned,
                            onSetHideZoned = viewModel::setHideZoned,
                            onToggleZoneAssignment = viewModel::toggleZoneAssignment,
                            onAddZone = viewModel::addZone,
                            onRenameZone = viewModel::renameZone,
                            onDeleteZone = viewModel::deleteZone,
                            onMoveZone = viewModel::moveZone,
                            onReorderZoneMembers = viewModel::moveZoneMember,
                            zoneMemberOrder = zoneOrder,
                            onMoveAggregateMember = viewModel::moveZoneMemberRefs,
                            covers = covers,
                            onCoverOpen = viewModel::coverOpen,
                            onCoverClose = viewModel::coverClose,
                            onCoverStop = viewModel::coverStop,
                            onCoverSwap = viewModel::swapCover,
                            onCoverRename = viewModel::renameCover,
                            onCoverDisable = { viewModel.setCoverDisabled(it, true) },
                            isAggregate = isAggregate,
                            aggregatePerServer = aggregate.perServer,
                            aggregateAssignments = allAssignments,
                            globalZones = globalZones,
                            aggregateActionsFor = aggregateActionsFor,
                            aggregateCovers = aggregateCovers,
                            aggregateCoverActionsFor = aggregateCoverActionsFor,
                            groupActions = groupActions,
                            sceneActions = sceneActions,
                            messages = viewModel.sceneMessages,
                        )
                    }
                    composable("settings") {
                        AppSettingsScreen(
                            theme = theme,
                            onSelectTheme = viewModel::setTheme,
                            onEditServers = { navController.navigate("servers") },
                            onShareSettings = {
                                SettingsSharing.share(this@MainActivity, viewModel.buildSettingsBackup())
                            },
                            expertMode = expertMode,
                            onSetExpertMode = viewModel::setExpertMode,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("servers") {
                        val discovery by viewModel.discoveryState.collectAsStateWithLifecycle()
                        ServerSettingsScreen(
                            servers = servers,
                            onBack = { navController.popBackStack() },
                            onSave = viewModel::upsertServer,
                            onDelete = viewModel::deleteServer,
                            onRestore = viewModel::restoreServer,
                            onMoveServer = viewModel::moveServer,
                            discovery = discovery,
                            onStartDiscovery = viewModel::startDiscovery,
                            onResetDiscovery = viewModel::resetDiscovery,
                            // myIO factory default credentials (admin/admin) — generic, not site-specific.
                            defaultUser = currentServer?.user ?: "admin",
                            defaultPassword = currentServer?.password ?: "admin",
                            expertMode = expertMode,
                            currentServerInfo = currentServer?.let { s -> ui.info?.let { s.id to it } },
                        )
                    }
                }

                // Opened on a shared settings file → confirm before replacing the current config.
                when (val prompt = importPrompt) {
                    is ImportPrompt.Ready -> AlertDialog(
                        onDismissRequest = { importPrompt = null },
                        title = { Text("Beállítások importálása") },
                        text = {
                            Text(
                                "A megosztott fájl ${prompt.backup.servers.size} szervert és " +
                                    "${prompt.backup.zones.size} zónát tartalmaz. Lecseréled vele a jelenlegi " +
                                    "beállításaidat? A mostani szervereid és zónáid elvesznek.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.importSettings(prompt.backup)
                                importPrompt = null
                            }) { Text("Lecserélés") }
                        },
                        dismissButton = {
                            TextButton(onClick = { importPrompt = null }) { Text("Mégse") }
                        },
                    )
                    ImportPrompt.Invalid -> AlertDialog(
                        onDismissRequest = { importPrompt = null },
                        title = { Text("Nem megnyitható") },
                        text = { Text("Ez a fájl nem egy érvényes myIO Home beállításfájl.") },
                        confirmButton = {
                            TextButton(onClick = { importPrompt = null }) { Text("OK") }
                        },
                    )
                    null -> {}
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** If [intent] opened a shared file, read + validate it (off the main thread) and arm the replace dialog (or an error). */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importJob?.cancel()
        importPrompt = null // the previous file's dialog must not stay actionable while the new one loads
        importJob = lifecycleScope.launch {
            // withContext re-checks cancellation on return, so a superseded read never publishes.
            val backup = withContext(Dispatchers.IO) {
                SettingsSharing.readText(this@MainActivity, uri)?.let { SettingsBackup.decode(it) }
            }
            importPrompt = if (backup != null) ImportPrompt.Ready(backup) else ImportPrompt.Invalid
        }
    }

    private companion object {
        // The navigation-bar scrims enableEdgeToEdge() uses by default (androidx.activity).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
