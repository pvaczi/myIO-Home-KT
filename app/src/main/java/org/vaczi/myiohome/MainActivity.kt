package org.vaczi.myiohome

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.vaczi.myiohome.ui.LocalNetworkGate
import org.vaczi.myiohome.ui.LocalNetworkGateScreen
import org.vaczi.myiohome.ui.openAppSettings
import androidx.activity.SystemBarStyle
import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.vaczi.myiohome.data.local.AccessControl
import org.vaczi.myiohome.data.local.memberRef
import org.vaczi.myiohome.data.model.outputKey
import org.vaczi.myiohome.ui.BiometricAuth
import org.vaczi.myiohome.ui.GroupMembers
import org.vaczi.myiohome.ui.LockScreen
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : FragmentActivity() {

    // --- App lock + per-output protection (system authentication only; nothing is stored but the flags) ---
    // Cold start begins locked; the content stays covered until the first DataStore read says otherwise.
    private var locked by mutableStateOf(true)
    private var promptActive = false
    // SystemClock.elapsedRealtime() of the last successful authentication window end / of going to background.
    private var unlockedUntil = 0L
    private var backgroundedAt: Long? = null
    // Actions waiting for the authentication; keyed so a slider drag keeps only its latest value.
    private val pendingActions = LinkedHashMap<String, () -> Unit>()
    private var pendingSeq = 0

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { backgroundedAt = SystemClock.elapsedRealtime() }
        override fun onStart(owner: LifecycleOwner) {
            if (viewModel.appLock.value == true &&
                AccessControl.shouldLockAfterBackground(backgroundedAt, SystemClock.elapsedRealtime())
            ) locked = true
            backgroundedAt = null
        }
    }

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

    // Full backup goes through the system "create document" picker (not the share sheet), so a file with
    // passwords is not sent by chat by accident. A null uri = the user cancelled the picker.
    private val fullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val json = viewModel.buildFullBackup()
                val ok = withContext(Dispatchers.IO) { SettingsSharing.writeText(this@MainActivity, uri, json) }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Teljes mentés elmentve" else "A mentés nem sikerült",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
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
                val appLockPref by viewModel.appLock.collectAsStateWithLifecycle()
                LaunchedEffect(appLockPref) { if (appLockPref == false) locked = false }
                val showLock = appLockPref == null || (appLockPref == true && locked)
                // One automatic prompt per lock event; the button asks again after a cancel.
                LaunchedEffect(showLock) { if (appLockPref == true && locked) promptUnlock() }
                if (showLock) {
                    LockScreen(onUnlock = ::promptUnlock)
                    return@MyioHomeTheme
                }
                // Android 17 local-network permission: asked after the app lock, before any poll/discovery.
                var granted by remember { mutableStateOf(LocalNetworkGate.isGranted(this@MainActivity)) }
                var denied by rememberSaveable { mutableStateOf(false) }
                var showRationale by rememberSaveable { mutableStateOf(false) }
                // The user may grant it on the system settings page → re-check on every return.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LocalNetworkGate.isGranted(this@MainActivity) }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    granted = ok
                    if (!ok) {
                        denied = true
                        showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity, LocalNetworkGate.PERMISSION,
                        )
                    }
                }
                val gate = LocalNetworkGate.decide(Build.VERSION.SDK_INT, granted, denied, showRationale)
                LaunchedEffect(gate) { viewModel.setNetworkAllowed(gate.allowsNetwork) }
                if (!gate.allowsNetwork) {
                    LocalNetworkGateScreen(
                        gate = gate,
                        onRequest = { permissionLauncher.launch(LocalNetworkGate.PERMISSION) },
                        onOpenSettings = { openAppSettings(this@MainActivity) },
                    )
                    return@MyioHomeTheme
                }
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
                val protectedRefs by viewModel.protectedOutputs.collectAsStateWithLifecycle()
                val sceneAllowedRefs by viewModel.sceneAllowedOutputs.collectAsStateWithLifecycle()
                val aggregateCoverActionsFor: (String) -> CoverActions = { serverId ->
                    guardedCoverActions(
                        serverId, protectedRefs,
                        CoverActions(
                            onOpen = { viewModel.aggCoverOpen(serverId, it) },
                            onClose = { viewModel.aggCoverClose(serverId, it) },
                            onStop = { viewModel.aggCoverStop(serverId, it) },
                        ),
                    )
                }
                val aggregateActionsFor: (String) -> MyioActions = { serverId ->
                    guardedActions(
                        serverId, protectedRefs,
                        MyioActions(
                            onToggleRelay = { viewModel.aggToggleRelay(serverId, it) },
                            onTogglePca = { viewModel.aggTogglePca(serverId, it) },
                            onSetPcaBrightness = { p, v -> viewModel.aggSetPcaBrightness(serverId, p, v) },
                            onTogglePwm = { viewModel.aggTogglePwm(serverId, it) },
                            onSetPwmBrightness = { d, v -> viewModel.aggSetPwmBrightness(serverId, d, v) },
                        ),
                    )
                }

                val groupActions = GroupActions(
                    onSwitch = { sid, m, on ->
                        guarded(groupRefs(sid, m)) { viewModel.groupSwitch(sid, m, on) }
                    },
                    onCovers = { sid, c, move ->
                        guarded(c.map { memberRef(sid, it.outputKey()) }) { viewModel.groupCovers(sid, c, move) }
                    },
                    onLevel = { sid, m, level ->
                        guarded(groupRefs(sid, m)) { viewModel.groupLevel(sid, m, level) }
                    },
                )

                val scenes by viewModel.scenes.collectAsStateWithLifecycle()
                val sceneActions = SceneActions(
                    scenes = scenes,
                    onRun = { scene ->
                        val refs = AccessControl.sceneRefs(
                            scene.entries.map { memberRef(it.serverId, it.outputKey) }, scene.turnOthersOff, protectedRefs, sceneAllowedRefs,
                        )
                        guarded(refs) { viewModel.runScene(scene) }
                    },
                    onPrepareCapture = viewModel::prepareSceneCapture,
                    candidateGroups = viewModel::sceneCandidateGroups,
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
                        val singleId = currentServer?.id.orEmpty()
                        MainScreen(
                            ui = ui,
                            actions = guardedActions(
                                singleId, protectedRefs,
                                MyioActions(
                                    onToggleRelay = viewModel::toggleRelay,
                                    onTogglePca = viewModel::togglePca,
                                    onSetPcaBrightness = viewModel::setPcaBrightness,
                                    onTogglePwm = viewModel::togglePwm,
                                    onSetPwmBrightness = viewModel::setPwmBrightness,
                                    onSetRelayThermostat = viewModel::setRelayThermostat,
                                    onSetPcaThermostat = viewModel::setPcaThermostat,
                                    onSetPwmThermostat = viewModel::setPwmThermostat,
                                ),
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
                            protectedKeys = AccessControl.keysOf(protectedRefs, singleId),
                            onToggleOutputProtected = { key -> toggleProtected(singleId, key, protectedRefs) },
                            sceneAllowedKeys = AccessControl.keysOf(sceneAllowedRefs, singleId),
                            onToggleOutputSceneAllowed = { key ->
                                viewModel.setOutputSceneAllowed(singleId, key, memberRef(singleId, key) !in sceneAllowedRefs)
                            },
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
                            onCoverOpen = { c -> guarded(listOf(memberRef(singleId, c.outputKey()))) { viewModel.coverOpen(c) } },
                            onCoverClose = { c -> guarded(listOf(memberRef(singleId, c.outputKey()))) { viewModel.coverClose(c) } },
                            onCoverStop = { c -> guarded(listOf(memberRef(singleId, c.outputKey()))) { viewModel.coverStop(c) } },
                            onCoverSwap = viewModel::swapCover,
                            onCoverRename = viewModel::renameCover,
                            onCoverDisable = { viewModel.setCoverDisabled(it, true) },
                            onCoverToggleProtected = { c -> toggleProtected(singleId, c.outputKey(), protectedRefs) },
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
                            onFullBackup = { fullBackupLauncher.launch(SettingsSharing.fullBackupFileName()) },
                            expertMode = expertMode,
                            onSetExpertMode = viewModel::setExpertMode,
                            appLock = appLockPref == true,
                            onSetAppLock = ::setAppLockChecked,
                            lockUnavailableReason = BiometricAuth.unavailableReason(BiometricAuth.availability(this@MainActivity)),
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
                        title = {
                            Text(if (prompt.backup.full) "Teljes mentés visszaállítása" else "Beállítások importálása")
                        },
                        text = {
                            Text(
                                if (prompt.backup.full) {
                                    "Teljes mentés visszaállítása – a jelenlegi beállítások felülíródnak. " +
                                        "A fájl ${prompt.backup.servers.size} szervert és " +
                                        "${prompt.backup.zones.size} zónát tartalmaz. Folytatod?"
                                } else {
                                    "A megosztott fájl ${prompt.backup.servers.size} szervert és " +
                                        "${prompt.backup.zones.size} zónát tartalmaz. Lecseréled vele a jelenlegi " +
                                        "beállításaidat? A mostani szervereid és zónáid elvesznek."
                                },
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

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }

    /** Unlocks the app lock screen with the system authentication (no screen lock on the device → no lock). */
    private fun promptUnlock() {
        if (promptActive) return
        if (BiometricAuth.availability(this) != BiometricAuth.Availability.AVAILABLE) { locked = false; return }
        promptActive = true
        BiometricAuth.prompt(this, "Az alkalmazás zárolva van", null) { ok ->
            promptActive = false
            if (ok) locked = false
        }
    }

    /** App-lock switch: enabling needs a successful authentication first (and a device screen lock). */
    private fun setAppLockChecked(value: Boolean) {
        if (!value) { viewModel.setAppLock(false); return }
        val reason = BiometricAuth.unavailableReason(BiometricAuth.availability(this))
        if (reason != null) { Toast.makeText(this, reason, Toast.LENGTH_LONG).show(); return }
        if (promptActive) return
        promptActive = true
        BiometricAuth.prompt(this, "Alkalmazás zárolásának bekapcsolása", null) { ok ->
            promptActive = false
            if (ok) { locked = false; viewModel.setAppLock(true) }
        }
    }

    /**
     * Runs [action] now, or — when it touches a protected output outside the 30 s unlock window — after a
     * successful authentication (one prompt for everything queued meanwhile; [key] collapses repeats such as
     * a slider drag to the latest one). A device without screen lock cannot authenticate: run directly.
     */
    private fun guarded(refs: Collection<String>, key: String? = null, action: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (!AccessControl.needsAuth(refs, viewModel.protectedOutputs.value, unlockedUntil, now) ||
            BiometricAuth.availability(this) != BiometricAuth.Availability.AVAILABLE
        ) { action(); return }
        pendingActions[key ?: "#${pendingSeq++}"] = action
        if (promptActive) return
        promptActive = true
        BiometricAuth.prompt(this, "Védett kimenet", "Hitelesítés a vezérléshez") { ok ->
            promptActive = false
            val run = pendingActions.values.toList()
            pendingActions.clear()
            if (ok) {
                unlockedUntil = AccessControl.unlockedUntil(SystemClock.elapsedRealtime())
                run.forEach { it() }
            }
        }
    }

    private fun groupRefs(serverId: String, m: GroupMembers): List<String> =
        (m.relays.map { it.outputKey() } + m.pcas.map { it.outputKey() } + m.pwms.map { it.outputKey() } +
            m.covers.map { it.outputKey() }).map { memberRef(serverId, it) }

    /** [base] with every control going through [guarded] for [serverId], plus the server's protected keys for the badges. */
    private fun guardedActions(serverId: String, protectedRefs: Set<String>, base: MyioActions): MyioActions {
        fun ref(key: String) = listOf(memberRef(serverId, key))
        return MyioActions(
            onToggleRelay = { r -> guarded(ref(r.outputKey())) { base.onToggleRelay(r) } },
            onTogglePca = { p -> guarded(ref(p.outputKey())) { base.onTogglePca(p) } },
            onSetPcaBrightness = { p, v -> guarded(ref(p.outputKey()), "pca:${p.outputKey()}") { base.onSetPcaBrightness(p, v) } },
            onTogglePwm = { d -> guarded(ref(d.outputKey())) { base.onTogglePwm(d) } },
            onSetPwmBrightness = { d, v -> guarded(ref(d.outputKey()), "pwm:${d.outputKey()}") { base.onSetPwmBrightness(d, v) } },
            onSetRelayThermostat = { r, on, off -> guarded(ref(r.outputKey())) { base.onSetRelayThermostat(r, on, off) } },
            onSetPcaThermostat = { p, on, off -> guarded(ref(p.outputKey())) { base.onSetPcaThermostat(p, on, off) } },
            onSetPwmThermostat = { d, on, off -> guarded(ref(d.outputKey())) { base.onSetPwmThermostat(d, on, off) } },
            protectedKeys = AccessControl.keysOf(protectedRefs, serverId),
        )
    }

    private fun guardedCoverActions(serverId: String, protectedRefs: Set<String>, base: CoverActions): CoverActions {
        fun ref(c: org.vaczi.myiohome.data.model.Cover) = listOf(memberRef(serverId, c.outputKey()))
        return base.copy(
            onOpen = { c -> guarded(ref(c)) { base.onOpen(c) } },
            onClose = { c -> guarded(ref(c)) { base.onClose(c) } },
            onStop = { c -> guarded(ref(c)) { base.onStop(c) } },
            protectedKeys = AccessControl.keysOf(protectedRefs, serverId),
            onToggleProtected = { c -> toggleProtected(serverId, c.outputKey(), protectedRefs) },
        )
    }

    /** Expert toggle of one output's protection: enabling needs a device screen lock; disabling needs authentication. */
    private fun toggleProtected(serverId: String, outputKey: String, protectedRefs: Set<String>) {
        if (serverId.isEmpty()) return
        val ref = memberRef(serverId, outputKey)
        if (ref in protectedRefs) {
            guarded(listOf(ref)) { viewModel.setOutputProtected(serverId, outputKey, false) }
        } else {
            val reason = BiometricAuth.unavailableReason(BiometricAuth.availability(this))
            if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            else viewModel.setOutputProtected(serverId, outputKey, true)
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
