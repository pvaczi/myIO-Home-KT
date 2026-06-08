package org.vaczi.myiohome.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.net.DiscoveredServer
import org.vaczi.myiohome.data.net.MyioCommands
import org.vaczi.myiohome.data.net.MyioDiscovery
import org.vaczi.myiohome.data.repo.MyioRepository

enum class ConnectionStatus { Connecting, Connected, Error }

/** Progress + results of a LAN scan for myIO servers (see [MyioDiscovery]). */
data class DiscoveryState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<DiscoveredServer> = emptyList(),
    val finished: Boolean = false,
)

data class MyioUiState(
    val status: ConnectionStatus = ConnectionStatus.Connecting,
    val info: ServerInfo? = null,
    val state: ServerState = ServerState(),
    val error: String? = null,
)

/**
 * Aggregate ("Otthon (összes)") UI state: the live [MyioUiState] of each polled server, keyed by server
 * id. Only servers with at least one zoned output are polled (see [MyioViewModel.observeAggregate]).
 */
data class AggregateUiState(
    val perServer: Map<String, MyioUiState> = emptyMap(),
)

/**
 * Drives a continuous poll loop against the currently selected myIO server and exposes the latest
 * [MyioUiState]. The active server comes from [SettingsStore]; switching it cancels the running poll
 * loop and starts a fresh one (new repository → fresh name caches) against the new device.
 * On failure it surfaces the error and keeps retrying on the next tick.
 *
 * When the virtual aggregate ([SettingsStore.AGGREGATE_ID]) is selected, a separate set of poll loops
 * runs — one per server that has any zoned output — and feeds [aggregate]; control commands route to
 * the owning server's repository.
 */
class MyioViewModel(
    private val settings: SettingsStore,
    private val zones: ZoneStore,
    private val repoFactory: (MyioServer) -> MyioRepository = { MyioRepository(it.toConfig()) },
) : ViewModel() {

    private val _ui = MutableStateFlow(MyioUiState())
    val ui: StateFlow<MyioUiState> = _ui.asStateFlow()

    /** Configured server slots, for the picker. */
    val servers: StateFlow<List<MyioServer>> =
        settings.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The currently selected **real** server (null until the first emission, or in aggregate mode). */
    val currentServer: StateFlow<MyioServer?> =
        settings.selected.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while the virtual "Otthon (összes)" aggregate is selected. */
    val isAggregate: StateFlow<Boolean> =
        settings.selectedId.map { it == SettingsStore.AGGREGATE_ID }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The selected app theme (persisted; defaults to the original green). */
    val theme: StateFlow<AppTheme> =
        settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.GREEN)

    /** Persist the chosen app theme (applies reactively via [theme]). */
    fun setTheme(value: AppTheme) {
        viewModelScope.launch { settings.setTheme(value) }
    }

    // --- Zones (global registry; per-server many-to-many assignment) ----------------------------
    //
    // Zones are global (shared across servers); assignment + member order are per server. The
    // single-server UI gets a combined [ZoneConfig] (global zones + the selected server's assignment),
    // so MainScreen/HomeScreen stay unchanged. Zone-list edits write the global registry; assignment
    // edits write the selected server's blob.

    /** The global zone registry (shared order across servers). */
    val globalZones: StateFlow<List<Zone>> =
        zones.globalZones.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedAssignments: StateFlow<ZoneAssignments> = settings.selected
        .flatMapLatest { server -> if (server == null) flowOf(ZoneAssignments()) else zones.assignments(server.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ZoneAssignments())

    /** Combined zone view for the selected server's UI (global zones + that server's assignment). */
    val zoneConfig: StateFlow<ZoneConfig> = combine(globalZones, selectedAssignments) { zs, a ->
        ZoneConfig(zones = zs, assignments = a.assignments, memberOrder = a.memberOrder)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ZoneConfig())

    /** Every server's assignment, keyed by server id — drives the aggregate view + relevant-server filter. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allAssignmentsFlow: Flow<Map<String, ZoneAssignments>> = settings.servers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyMap())
            else combine(list.map { s -> zones.assignments(s.id).map { s.id to it } }) { it.toMap() }
        }

    val allAssignments: StateFlow<Map<String, ZoneAssignments>> =
        allAssignmentsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** "Hide already-zoned" toggle on the type tabs (so only unassigned outputs show while assigning). */
    val hideZoned: StateFlow<Boolean> =
        zones.hideZoned.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setHideZoned(value: Boolean) {
        viewModelScope.launch { zones.setHideZoned(value) }
    }

    /** Add a global zone; returns its id (so the caller can make it the active target), or null if blank. */
    fun addZone(name: String): String? {
        val n = name.trim().ifBlank { return null }
        val zone = Zone(name = n)
        viewModelScope.launch { zones.saveZones(globalZones.value + zone) }
        return zone.id
    }

    fun renameZone(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        viewModelScope.launch {
            zones.saveZones(globalZones.value.map { if (it.id == id) it.copy(name = n) else it })
        }
    }

    /** Remove a global zone and strip it from every server's assignment (handled atomically in the store). */
    fun deleteZone(id: String) {
        val serverIds = servers.value.map { it.id }
        viewModelScope.launch { zones.deleteZone(id, serverIds) }
    }

    /** Move a zone one step up (or down) in the global order. No-op at the ends. */
    fun moveZone(id: String, up: Boolean) {
        val list = globalZones.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        viewModelScope.launch { zones.saveZones(list) }
    }

    /** Persist a zone's member ordering for the selected server (the UI computes the new order). */
    fun setZoneMemberOrder(zoneId: String, orderedKeys: List<String>) {
        editAssignments { it.copy(memberOrder = it.memberOrder + (zoneId to orderedKeys)) }
    }

    /** Toggle whether [outputKey] belongs to [zoneId] on the selected server; many-to-many. */
    fun toggleZoneAssignment(outputKey: String, zoneId: String) {
        editAssignments { a ->
            val current = a.assignments[outputKey] ?: emptyList()
            val updated = if (zoneId in current) current - zoneId else current + zoneId
            val assignments = a.assignments.toMutableMap().apply {
                if (updated.isEmpty()) remove(outputKey) else put(outputKey, updated)
            }
            a.copy(assignments = assignments)
        }
    }

    private inline fun editAssignments(transform: (ZoneAssignments) -> ZoneAssignments) {
        val id = currentServer.value?.id ?: return
        val updated = transform(selectedAssignments.value)
        viewModelScope.launch { zones.saveAssignments(id, updated) }
    }

    // --- LAN discovery --------------------------------------------------------------------------
    private val discovery = MyioDiscovery()
    private val _discoveryState = MutableStateFlow(DiscoveryState())
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()
    private var discoveryJob: Job? = null

    /** Scan [subnet] (first three octets) for myIO servers; [user]/[password] try to fetch names. */
    fun startDiscovery(subnet: String, user: String, password: String) {
        discoveryJob?.cancel()
        _discoveryState.value = DiscoveryState(running = true, total = 254)
        discoveryJob = viewModelScope.launch {
            runCatching {
                discovery.scan(subnet, user, password) { done, total ->
                    _discoveryState.update { it.copy(done = done, total = total) }
                }
            }.onSuccess { found ->
                _discoveryState.update { it.copy(running = false, finished = true, results = found) }
            }.onFailure {
                _discoveryState.update { it.copy(running = false, finished = true) }
            }
        }
    }

    /** Reset discovery (also cancels a run in progress) — call when the dialog closes. */
    fun resetDiscovery() {
        discoveryJob?.cancel()
        _discoveryState.value = DiscoveryState()
    }

    /** Last-open tab index for the active server (remembered per server). Updated instantly on tap. */
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val snapshotSaveIntervalMs = 30_000L

    /**
     * The poll interval (ms) for [serverId], read live from the current slot so an edit applies on the
     * loop's next tick. Each server carries its own interval (per-server, not global); unknown ids and
     * out-of-range values fall back to / are clamped around [SettingsStore.DEFAULT_POLL_SEC].
     */
    private fun pollIntervalMsFor(serverId: String?): Long {
        val sec = servers.value.firstOrNull { it.id == serverId }?.pollIntervalSec ?: SettingsStore.DEFAULT_POLL_SEC
        return sec.coerceIn(1, 60) * 1_000L
    }

    /**
     * Bumped by every control command. A poll captures this before its network call; if it changed
     * by the time the response arrives, the poll was started before the command and carries the
     * pre-command state — so we drop it and keep the optimistic value. The next poll (started after
     * the command) reflects reality. The aggregate path keeps one counter per server ([aggregateGen]).
     */
    private var pollGeneration = 0

    @Volatile private var repo: MyioRepository? = null
    private var pollJob: Job? = null
    private var activeId: String? = null

    /**
     * Whether the app is in the foreground. Poll loops park while this is false so a backgrounded app
     * stops hammering the servers (the device dislikes load when nobody's watching); they resume on the
     * next tick when it returns. Driven by [ProcessLifecycleOwner] (app-level, ignores config changes).
     */
    private val foreground = MutableStateFlow(true)
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { foreground.value = true }
        override fun onStop(owner: LifecycleOwner) { foreground.value = false }
    }

    init {
        // Re-point the single-server poll loop whenever the selected slot changes (by id, so two slots
        // sharing a host still switch independently). Also load that server's last-open tab. In aggregate
        // mode [settings.selected] is null → this stops the single-server loop; [observeAggregate] drives
        // the aggregate loops instead.
        viewModelScope.launch {
            settings.selected.collect { server ->
                if (server?.id == activeId) return@collect
                activeId = server?.id
                pollJob?.cancel()
                if (server == null) {
                    repo = null
                    _ui.value = MyioUiState(status = ConnectionStatus.Connected, state = ServerState())
                    return@collect
                }
                _selectedTab.value = settings.lastTab(server.id).first()
                repo = repoFactory(server)
                _ui.value = MyioUiState() // back to Connecting for the new device
                pollJob = viewModelScope.launch { pollLoop(server.id) }
            }
        }
        observeAggregate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onCleared()
    }

    /** Switch the active server slot by [id] (persisted; the poll loop re-points on the emission). */
    fun selectServer(id: String) {
        viewModelScope.launch { settings.selectServer(id) }
    }

    /** Select the virtual "Otthon (összes)" aggregate. */
    fun selectAggregate() {
        viewModelScope.launch { settings.selectAggregate() }
    }

    /** Remember the active tab for the current server (instant locally, persisted for next launch). */
    fun selectTab(index: Int) {
        _selectedTab.value = index
        val id = currentServer.value?.id ?: return
        viewModelScope.launch { settings.setLastTab(id, index) }
    }

    /**
     * Add a new slot ([original] = null) or replace an existing one (matched by [original]'s id).
     * Editing preserves the id, so the active selection stays valid even if the host changed.
     */
    fun upsertServer(original: MyioServer?, updated: MyioServer) {
        val list = servers.value.toMutableList()
        val idx = original?.let { o -> list.indexOfFirst { it.id == o.id } } ?: -1
        if (idx >= 0) list[idx] = updated else list.add(updated)
        viewModelScope.launch { settings.saveServers(list) }
    }

    /** Remove a slot (by id). The list may go empty; re-selects the first remaining if the active one went. */
    fun deleteServer(server: MyioServer) {
        val list = servers.value.filterNot { it.id == server.id }
        viewModelScope.launch {
            settings.saveServers(list)
            if (currentServer.value?.id == server.id) settings.selectServer(list.firstOrNull()?.id ?: "")
        }
    }

    private suspend fun pollLoop(serverId: String) {
        val repo = repo ?: return
        runPollLoop(repo, serverId, currentGen = { pollGeneration }) { transform -> _ui.update(transform) }
    }

    /**
     * One server's poll loop, parameterized so the single-server and aggregate paths share it.
     * [currentGen] reads the relevant generation counter (to drop polls predating a command); [emit]
     * applies a transform to the relevant [MyioUiState] slot.
     *
     * Instant first: render the cached snapshot (still "Connecting", controls disabled) and seed the
     * name cache. The myIO device can't handle concurrent connections, so each server stays serial on
     * its own coroutine; different servers run in parallel. After the first successful poll we do a
     * one-time info + name refresh *between* polls.
     */
    private suspend fun runPollLoop(
        repo: MyioRepository,
        serverId: String,
        currentGen: () -> Int,
        emit: (transform: (MyioUiState) -> MyioUiState) -> Unit,
    ) {
        runCatching { repo.loadCachedSnapshot() }.getOrNull()?.let { snapshot ->
            emit { it.copy(state = snapshot) }
        }
        var refreshed = false
        var lastSnapshotMs = 0L
        while (true) {
            foreground.first { it } // park while backgrounded → no polling when nobody's watching
            try {
                val gen = currentGen()
                val state = repo.poll()
                if (gen == currentGen()) {
                    emit { it.copy(status = ConnectionStatus.Connected, state = state, error = null) }
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshotMs > snapshotSaveIntervalMs) {
                        lastSnapshotMs = now
                        runCatching { repo.saveSnapshot(state) }
                    }
                } else {
                    // A command landed while this poll was in flight; keep the optimistic state.
                    emit { it.copy(status = ConnectionStatus.Connected, error = null) }
                }
                if (!refreshed) {
                    refreshed = true
                    runCatching { repo.getInfo() }.onSuccess { info -> emit { it.copy(info = info) } }
                    runCatching { repo.refreshNames() } // fresh names land in the cache → next poll applies them
                    lastSnapshotMs = 0L // force the next poll to persist a snapshot with the fresh names
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit { it.copy(status = ConnectionStatus.Error, error = e.message ?: e.toString()) }
            }
            delay(pollIntervalMsFor(serverId))
        }
    }

    // --- Aggregate ("Otthon (összes)") ----------------------------------------------------------
    //
    // When the aggregate is selected, poll every server that has at least one zoned output (in parallel
    // across devices, serial within each). The relevant set is recomputed reactively; the loops restart
    // only when that *set* changes (not on every assignment edit).

    private val _aggregate = MutableStateFlow(AggregateUiState())
    val aggregate: StateFlow<AggregateUiState> = _aggregate.asStateFlow()

    private var aggregateJobs: List<Job> = emptyList()
    private val aggregateRepos = mutableMapOf<String, MyioRepository>()
    private val aggregateGen = mutableMapOf<String, Int>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAggregate() {
        viewModelScope.launch {
            settings.selectedId.flatMapLatest { id ->
                if (id != SettingsStore.AGGREGATE_ID) flowOf(emptyList())
                else combine(settings.servers, allAssignmentsFlow) { list, amap ->
                    list.filter { amap[it.id]?.assignments?.isNotEmpty() == true }
                }
            }
                // Restart only when the *set* of relevant servers changes, not on every assignment edit.
                .distinctUntilChanged { a, b -> a.map { it.id }.toSet() == b.map { it.id }.toSet() }
                .collect { relevant ->
                    if (relevant.isEmpty()) stopAggregate() else restartAggregate(relevant)
                }
        }
    }

    private fun restartAggregate(relevant: List<MyioServer>) {
        stopAggregate()
        _aggregate.value = AggregateUiState(perServer = relevant.associate { it.id to MyioUiState() })
        aggregateJobs = relevant.map { server ->
            val repo = repoFactory(server)
            aggregateRepos[server.id] = repo
            viewModelScope.launch {
                runPollLoop(repo, server.id, currentGen = { aggregateGen[server.id] ?: 0 }) { transform ->
                    updateServer(server.id, transform)
                }
            }
        }
    }

    private fun stopAggregate() {
        aggregateJobs.forEach { it.cancel() }
        aggregateJobs = emptyList()
        aggregateRepos.clear()
        aggregateGen.clear()
        _aggregate.value = AggregateUiState()
    }

    private fun updateServer(serverId: String, transform: (MyioUiState) -> MyioUiState) {
        _aggregate.update { agg ->
            val cur = agg.perServer[serverId] ?: MyioUiState()
            agg.copy(perServer = agg.perServer + (serverId to transform(cur)))
        }
    }

    // Aggregate control: route to the owning server's repo, with per-server optimistic update.
    fun aggToggleRelay(serverId: String, relay: RelayOutput) {
        val target = !relay.on
        aggregateOptimistic(serverId) { st ->
            st.copy(relays = st.relays.map { if (it.id == relay.id) it.copy(on = target) else it })
        }
        aggregateSend(serverId, MyioCommands.relay(relay.id, target))
    }

    fun aggTogglePca(serverId: String, pca: PcaOutput) {
        val turningOn = !pca.on
        val target = if (turningOn) pca.turnOn else pca.turnOff
        aggregateOptimistic(serverId) { st ->
            st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = target) else it })
        }
        aggregateSend(serverId, MyioCommands.pcaOnOff(pca.id, turningOn))
    }

    fun aggSetPcaBrightness(serverId: String, pca: PcaOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        aggregateOptimistic(serverId) { st ->
            st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = v) else it })
        }
        aggregateSend(serverId, MyioCommands.pcaBrightness(pca.id, v))
    }

    fun aggTogglePwm(serverId: String, pwm: PwmOutput) {
        val turningOn = !pwm.on
        val target = if (turningOn) pwm.turnOn else pwm.turnOff
        aggregateOptimistic(serverId) { st ->
            st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = target) else it })
        }
        aggregateSend(serverId, MyioCommands.fetOnOff(pwm.id, turningOn))
    }

    fun aggSetPwmBrightness(serverId: String, pwm: PwmOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        aggregateOptimistic(serverId) { st ->
            st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = v) else it })
        }
        aggregateSend(serverId, MyioCommands.fetBrightness(pwm.id, v))
    }

    private inline fun aggregateOptimistic(serverId: String, crossinline transform: (ServerState) -> ServerState) {
        aggregateGen[serverId] = (aggregateGen[serverId] ?: 0) + 1
        updateServer(serverId) { it.copy(state = transform(it.state)) }
    }

    private fun aggregateSend(serverId: String, body: String) {
        val repo = aggregateRepos[serverId] ?: return
        viewModelScope.launch { runCatching { repo.sendCommand(body) } }
    }

    // --- Control (POST) with optimistic updates -------------------------------------------------
    //
    // Each handler flips the local state immediately so the UI feels instant, then fires the POST.
    // [optimistic] also invalidates any in-flight poll (see [pollGeneration]) so a poll carrying the
    // pre-command state can't briefly revert the control. The next poll confirms the real state;
    // a failed/ignored command self-heals on that tick.

    /** Toggle a relay (r_ON / r_OFF by current state; id is 1-based, matches the device id). */
    fun toggleRelay(relay: RelayOutput) {
        val target = !relay.on
        optimistic { st ->
            st.copy(relays = st.relays.map { if (it.id == relay.id) it.copy(on = target) else it })
        }
        send(MyioCommands.relay(relay.id, target))
    }

    /**
     * Toggle a PCA output (PCA_ON / PCA_OFF; id is the real 2001+ device id). The device drives the
     * level to the configured [PcaOutput.turnOn] / [PcaOutput.turnOff], so the optimistic value uses
     * those (not a hard-coded 100%) to avoid a misleading flash before the poll confirms.
     */
    fun togglePca(pca: PcaOutput) {
        val turningOn = !pca.on
        val target = if (turningOn) pca.turnOn else pca.turnOff
        optimistic { st ->
            st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = target) else it })
        }
        send(MyioCommands.pcaOnOff(pca.id, turningOn))
    }

    /** Set a PWM-capable PCA's brightness (PCA*<id>=<0..255>). */
    fun setPcaBrightness(pca: PcaOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        optimistic { st ->
            st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = v) else it })
        }
        send(MyioCommands.pcaBrightness(pca.id, v))
    }

    /** Toggle a FET/PWM output (f_ON / f_OFF). On turn-on the device uses the configured turn-on level. */
    fun togglePwm(pwm: PwmOutput) {
        val turningOn = !pwm.on
        val target = if (turningOn) pwm.turnOn else pwm.turnOff
        optimistic { st ->
            st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = target) else it })
        }
        send(MyioCommands.fetOnOff(pwm.id, turningOn))
    }

    /** Set a FET/PWM output's value (fet*<index>=<0..255>; index is 1-based = domain id − 100). */
    fun setPwmBrightness(pwm: PwmOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        optimistic { st ->
            st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = v) else it })
        }
        send(MyioCommands.fetBrightness(pwm.id, v))
    }

    // --- Thermostat setpoints (sensor-controlled outputs) ---------------------------------------
    //
    // Each output's on/off temperatures are persisted on the device; sens_out.json reports them back,
    // so we optimistically update the local thresholds and let the next poll confirm. The two
    // commands (on + off) go out sequentially on one coroutine (the device dislikes concurrency).

    fun setRelayThermostat(relay: RelayOutput, onTenths: Int, offTenths: Int) {
        optimistic { st ->
            st.copy(relays = st.relays.map {
                if (it.id == relay.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
            })
        }
        sendAll(MyioCommands.relayThermostatOn(relay.id, onTenths), MyioCommands.relayThermostatOff(relay.id, offTenths))
    }

    fun setPcaThermostat(pca: PcaOutput, onTenths: Int, offTenths: Int) {
        optimistic { st ->
            st.copy(pcas = st.pcas.map {
                if (it.id == pca.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
            })
        }
        sendAll(MyioCommands.pcaThermostatOn(pca.id, onTenths), MyioCommands.pcaThermostatOff(pca.id, offTenths))
    }

    fun setPwmThermostat(pwm: PwmOutput, onTenths: Int, offTenths: Int) {
        optimistic { st ->
            st.copy(pwms = st.pwms.map {
                if (it.id == pwm.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
            })
        }
        sendAll(MyioCommands.fetThermostatOn(pwm.id, onTenths), MyioCommands.fetThermostatOff(pwm.id, offTenths))
    }

    private inline fun optimistic(transform: (ServerState) -> ServerState) {
        pollGeneration++
        _ui.update { it.copy(state = transform(it.state)) }
    }

    /** Fire-and-forget command; the poll loop reconciles, so failures self-heal on the next tick. */
    private fun send(body: String) {
        val repo = repo ?: return
        viewModelScope.launch {
            runCatching { repo.sendCommand(body) }
        }
    }

    /** Send several commands sequentially on one coroutine (never concurrently — device constraint). */
    private fun sendAll(vararg bodies: String) {
        val repo = repo ?: return
        viewModelScope.launch {
            for (body in bodies) runCatching { repo.sendCommand(body) }
        }
    }

    companion object {
        fun factory(settings: SettingsStore, stateCache: StateCacheStore, zones: ZoneStore) = viewModelFactory {
            initializer {
                MyioViewModel(
                    settings = settings,
                    zones = zones,
                    repoFactory = { MyioRepository(it.toConfig(), stateCache, it.id) },
                )
            }
        }
    }
}
