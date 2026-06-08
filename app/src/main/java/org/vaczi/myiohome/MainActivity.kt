package org.vaczi.myiohome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.ui.AppSettingsScreen
import org.vaczi.myiohome.ui.MainScreen
import org.vaczi.myiohome.ui.MyioActions
import org.vaczi.myiohome.ui.MyioViewModel
import org.vaczi.myiohome.ui.ServerSettingsScreen
import org.vaczi.myiohome.ui.theme.MyioHomeTheme

class MainActivity : ComponentActivity() {

    // Server slots live in DataStore (SettingsStore). A fresh install starts empty (discovery-first):
    // the user scans the LAN or adds slots by hand. The active one is switchable from the top-bar title.
    private val viewModel: MyioViewModel by viewModels {
        MyioViewModel.factory(
            SettingsStore(applicationContext),
            StateCacheStore(applicationContext),
            ZoneStore(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            MyioHomeTheme(theme = theme) {
                val navController = rememberNavController()
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val servers by viewModel.servers.collectAsStateWithLifecycle()
                val currentServer by viewModel.currentServer.collectAsStateWithLifecycle()
                val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                val zoneConfig by viewModel.zoneConfig.collectAsStateWithLifecycle()
                val hideZoned by viewModel.hideZoned.collectAsStateWithLifecycle()
                // Aggregate ("Otthon (összes)") state: global zones + every polled server's live state.
                val isAggregate by viewModel.isAggregate.collectAsStateWithLifecycle()
                val aggregate by viewModel.aggregate.collectAsStateWithLifecycle()
                val globalZones by viewModel.globalZones.collectAsStateWithLifecycle()
                val allAssignments by viewModel.allAssignments.collectAsStateWithLifecycle()
                val aggregateActionsFor: (String) -> MyioActions = { serverId ->
                    MyioActions(
                        onToggleRelay = { viewModel.aggToggleRelay(serverId, it) },
                        onTogglePca = { viewModel.aggTogglePca(serverId, it) },
                        onSetPcaBrightness = { p, v -> viewModel.aggSetPcaBrightness(serverId, p, v) },
                        onTogglePwm = { viewModel.aggTogglePwm(serverId, it) },
                        onSetPwmBrightness = { d, v -> viewModel.aggSetPwmBrightness(serverId, d, v) },
                    )
                }

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
                            zoneConfig = zoneConfig,
                            hideZoned = hideZoned,
                            onSetHideZoned = viewModel::setHideZoned,
                            onToggleZoneAssignment = viewModel::toggleZoneAssignment,
                            onAddZone = viewModel::addZone,
                            onRenameZone = viewModel::renameZone,
                            onDeleteZone = viewModel::deleteZone,
                            onMoveZone = viewModel::moveZone,
                            onReorderZoneMembers = viewModel::setZoneMemberOrder,
                            isAggregate = isAggregate,
                            aggregatePerServer = aggregate.perServer,
                            aggregateAssignments = allAssignments,
                            globalZones = globalZones,
                            aggregateActionsFor = aggregateActionsFor,
                        )
                    }
                    composable("settings") {
                        AppSettingsScreen(
                            theme = theme,
                            onSelectTheme = viewModel::setTheme,
                            onEditServers = { navController.navigate("servers") },
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
                            discovery = discovery,
                            onStartDiscovery = viewModel::startDiscovery,
                            onResetDiscovery = viewModel::resetDiscovery,
                            // myIO factory default credentials (admin/admin) — generic, not site-specific.
                            defaultUser = currentServer?.user ?: "admin",
                            defaultPassword = currentServer?.password ?: "admin",
                        )
                    }
                }
            }
        }
    }
}
