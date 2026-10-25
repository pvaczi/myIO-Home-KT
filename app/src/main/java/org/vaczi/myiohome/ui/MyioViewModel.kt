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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.vaczi.myiohome.data.local.CoverStore
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsBackup
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.local.Zone
import org.vaczi.myiohome.data.local.ZoneAssignments
import org.vaczi.myiohome.data.local.ZoneConfig
import org.vaczi.myiohome.data.local.ZoneStore
import org.vaczi.myiohome.data.model.Cover
import org.vaczi.myiohome.data.model.CoverDetection
import org.vaczi.myiohome.data.model.CoverOutputType
import org.vaczi.myiohome.data.model.CoverOverride
import org.vaczi.myiohome.data.model.PcaOutput
import org.vaczi.myiohome.data.model.PwmOutput
import org.vaczi.myiohome.data.model.RelayOutput
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import org.vaczi.myiohome.data.net.DiscoveredServer
import org.vaczi.myiohome.data.net.MyioCommands
import org.vaczi.myiohome.data.net.MyioDiscovery
import org.vaczi.myiohome.data.net.ServerConfig
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
    private val coverStore: CoverStore,
    private val repoFactory: (MyioServer) -> MyioRepository = { MyioRepository(it.toConfig()) },
    private val stateCache: StateCacheStore? = null,
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

    /** Expert mode (persisted; defaults false). True = full edit access; false = locked/simplified view (see [SettingsStore.expertMode]). */
    val expertMode: StateFlow<Boolean> =
        settings.expertMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setExpertMode(value: Boolean) {
        viewModelScope.launch { settings.setExpertMode(value) }
    }

    /**
     * Toggle whether [outputKey] is hidden from non-expert display on the **current** server. Stored on the
     * server slot ([MyioServer.hiddenOutputs]) so it travels with the slot (and the shared backup). Editing
     * the slot's content (not its id) doesn't re-point the poll loop.
     */
    fun toggleOutputHidden(outputKey: String) {
        val id = currentServer.value?.id ?: return
        // Atomic transform inside the store so a second quick hide/show (or any other slot edit) can't be
        // lost by writing back a stale StateFlow snapshot.
        viewModelScope.launch {
            settings.updateServers { list ->
                list.map { s ->
                    if (s.id != id) s
                    else s.copy(
                        hiddenOutputs = if (outputKey in s.hiddenOutputs) s.hiddenOutputs - outputKey
                        else s.hiddenOutputs + outputKey,
                    )
                }
            }
        }
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

    /** Every server's assignment, keyed by server id — drives the aggregate view + relevant-server filter. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allAssignments: StateFlow<Map<String, ZoneAssignments>> = settings.servers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyMap())
            else combine(list.map { s -> zones.assignments(s.id).map { s.id to it } }) { it.toMap() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The selected server's assignment, derived from [allAssignments] (no extra store subscription). Keyed
     * on the *effective* selected server (incl. the first-slot fallback), the same id edits are written
     * under — the raw selected id may be unset or stale.
     */
    private val selectedAssignments: StateFlow<ZoneAssignments> =
        combine(settings.selected, allAssignments) { s, all -> s?.let { all[it.id] } ?: ZoneAssignments() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ZoneAssignments())

    /** Combined zone view for the selected server's UI (global zones + that server's assignment). */
    val zoneConfig: StateFlow<ZoneConfig> = combine(globalZones, selectedAssignments) { zs, a ->
        ZoneConfig(zones = zs, assignments = a.assignments, memberOrder = a.memberOrder)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ZoneConfig())

    // --- Covers (shutters): detected from the selected server's protection pairs + per-server overrides ---

    /** Every server's cover overrides, keyed by server id — included in the shareable settings backup. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allCoverOverrides: StateFlow<Map<String, Map<String, CoverOverride>>> = settings.servers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyMap())
            else combine(list.map { s -> coverStore.overrides(s.id).map { s.id to it } }) { it.toMap() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The selected server's cover overrides, derived from [allCoverOverrides] (no extra subscription); keyed
     * on the effective selected server like [selectedAssignments]. */
    private val selectedCoverOverrides: StateFlow<Map<String, CoverOverride>> =
        combine(settings.selected, allCoverOverrides) { s, all -> s?.let { all[it.id] } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Full detection for the selected server (active covers + disabled-pair members), recomputed on each poll. */
    private val detection: StateFlow<CoverDetection.Detection> = combine(_ui, selectedCoverOverrides) { u, ov ->
        CoverDetection.detectAll(u.state, ov)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CoverDetection.Detection(emptyList(), emptyMap()))

    /** The detected covers for the selected server (structural detection + user overrides). */
    val covers: StateFlow<List<Cover>> =
        detection.map { it.covers }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Endpoint output keys of the selected server's **disabled** covers, keyed by cover key — so the UI can
     * extend a hidden/zoned cover's `C…` key to its now-plain P/R outputs (see [expandHiddenForDisabledCovers]).
     */
    val disabledCoverMembers: StateFlow<Map<String, List<String>>> =
        detection.map { it.disabledMembers }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Edit one cover's override on the current server (disable / swap orientation / rename). */
    private fun editCover(coverKey: String, transform: (CoverOverride) -> CoverOverride) {
        val server = currentServer.value ?: return
        viewModelScope.launch { coverStore.edit(server.id, coverKey, transform) }
    }

    fun setCoverDisabled(c: Cover, disabled: Boolean) =
        editCover(c.outputKey()) { it.copy(disabled = disabled) }
    fun swapCover(c: Cover) = editCover(c.outputKey()) { it.copy(swapped = !it.swapped) }
    fun renameCover(c: Cover, name: String) =
        editCover(c.outputKey()) { it.copy(name = name.trim().ifBlank { null }) }

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
        viewModelScope.launch { zones.updateZones { it + zone } }
        return zone.id
    }

    fun renameZone(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        viewModelScope.launch {
            zones.updateZones { list -> list.map { if (it.id == id) it.copy(name = n) else it } }
        }
    }

    /** Remove a global zone and strip it from every server's assignment (handled atomically in the store). */
    fun deleteZone(id: String) {
        val serverIds = servers.value.map { it.id }
        viewModelScope.launch { zones.deleteZone(id, serverIds) }
    }

    /** Return a copy with the element matching [predicate] moved one step up (or down), or null at the ends. */
    private fun <T> List<T>.movedOneStep(up: Boolean, predicate: (T) -> Boolean): List<T>? {
        val i = indexOfFirst(predicate)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in indices) return null
        return toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Move a server slot one step up (or down) in the list. Affects the aggregate tile order. No-op at the ends. */
    fun moveServer(server: MyioServer, up: Boolean) {
        viewModelScope.launch {
            settings.updateServers { list -> list.movedOneStep(up) { it.id == server.id } ?: list }
        }
    }

    /** Move a zone one step up (or down) in the global order. No-op at the ends. */
    fun moveZone(id: String, up: Boolean) {
        viewModelScope.launch { zones.updateZones { list -> list.movedOneStep(up) { it.id == id } ?: list } }
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

    private fun editAssignments(transform: (ZoneAssignments) -> ZoneAssignments) {
        val id = currentServer.value?.id ?: return
        // Atomic transform inside the store (reads the freshly-persisted value) so two quick assignment
        // edits — a common tap-tap in assign mode — don't clobber each other via a stale snapshot.
        viewModelScope.launch { zones.updateAssignments(id, transform) }
    }

    // --- LAN discovery --------------------------------------------------------------------------
    private val discovery = MyioDiscovery()
    private val _discoveryState = MutableStateFlow(DiscoveryState())
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()
    private var discoveryJob: Job? = null

    // Identifies the current scan run. A cancelled run's late callbacks (a probe already past its blocking
    // connect reports progress) must not touch the state of the run that replaced it. Read off-main by
    // the progress callback → @Volatile.
    @Volatile private var discoveryRun = 0

    /** Scan [subnet] (first three octets) for myIO servers; [user]/[password] try to fetch names. */
    fun startDiscovery(subnet: String, user: String, password: String) {
        discoveryJob?.cancel()
        val run = ++discoveryRun
        _discoveryState.value = DiscoveryState(running = true, total = 254)
        discoveryJob = viewModelScope.launch {
            val found = try {
                discovery.scan(subnet, user, password) { done, total ->
                    // Check inside update: its CAS retries if a restart/reset swaps the state meanwhile, and
                    // those bump discoveryRun first, so a superseded run can never write into the new state.
                    _discoveryState.update { if (discoveryRun == run) it.copy(done = done, total = total) else it }
                }
            } catch (e: CancellationException) {
                throw e // superseded or reset: the newer run owns the state
            } catch (e: Exception) {
                null
            }
            _discoveryState.update {
                if (discoveryRun == run) it.copy(running = false, finished = true, results = found ?: it.results) else it
            }
        }
    }

    /** Reset discovery (also cancels a run in progress) — call when the dialog closes. */
    fun resetDiscovery() {
        discoveryJob?.cancel()
        discoveryRun++
        _discoveryState.value = DiscoveryState()
    }

    /** Last-open tab (by stable name) for the active server (remembered per server). Updated instantly on
     * tap. Null until a server's saved value loads; the UI resolves legacy ordinals via [SettingsStore.resolveTab]. */
    private val _selectedTab = MutableStateFlow<String?>(null)
    val selectedTab: StateFlow<String?> = _selectedTab.asStateFlow()

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

    /**
     * Commands still on their way to the device, per repository instance (identity: one poll loop's
     * repo). A poll that completes while one is pending may have read the device before the paced POST
     * landed, so its state is dropped too; the generation is bumped again once the command finishes.
     * Keyed by the repo, not the target, so a command still in flight to the previous server never
     * suppresses the newly selected server's polls. Entries are removed at zero (no stale repo refs).
     * Touched only on the main dispatcher (viewModelScope).
     */
    private val pendingSends = mutableMapOf<MyioRepository, Int>()

    private fun isSending(repo: MyioRepository): Boolean = (pendingSends[repo] ?: 0) > 0

    @Volatile private var repo: MyioRepository? = null
    private var pollJob: Job? = null
    private var activeId: String? = null
    // The connection config the live poll loop was built with. Compared alongside the id so a host/port/
    // credential edit (same slot id) re-points the loop onto a fresh repo, not just an id change.
    private var activeConfig: ServerConfig? = null

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
                val newConfig = server?.toConfig()
                // Re-point on id change OR connection-config change (host/port/credentials edited in place).
                if (server?.id == activeId && newConfig == activeConfig) return@collect
                activeId = server?.id
                activeConfig = newConfig
                pollJob?.cancelAndJoin() // wait out the old loop's in-flight (blocking) call → no overlapping connection
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
        // In non-expert (locked) mode the active slot may be one the owner hid: the switcher won't list
        // it, so steer off it to the safe aggregate (or the first visible slot) rather than leave its
        // per-server control screen showing. Settles immediately once redirected (aggregate → sel null).
        viewModelScope.launch {
            combine(settings.expertMode, settings.selected, settings.servers) { e, sel, list -> Triple(e, sel, list) }
                .collect { (expert, sel, list) ->
                    if (!expert && sel != null && sel.hidden) {
                        // Steer off a hidden slot to the safe aggregate (selected → null, so no per-server
                        // controls of a hidden server show). Covers the single-hidden-slot case too, where
                        // there's no visible alternative: selectServer("") would fall back to the (hidden)
                        // first slot, so the aggregate is the only genuine "deselect" here.
                        settings.selectAggregate()
                    }
                }
        }
        // Keep the snapshot cache to the configured slots: a deleted slot or one re-pointed at another host
        // leaves its old entry behind otherwise (also runs once per launch on the first emission).
        stateCache?.let { cache ->
            viewModelScope.launch {
                settings.servers.collect { list ->
                    runCatching { cache.prune(list.map { StateCacheStore.cacheKey(it) }.toSet()) }
                }
            }
        }
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

    /** Remember the active tab (by name) for the current server (instant locally, persisted for next launch). */
    fun selectTab(tabName: String) {
        _selectedTab.value = tabName
        val id = currentServer.value?.id ?: return
        viewModelScope.launch { settings.setLastTab(id, tabName) }
    }

    /**
     * Add a new slot ([original] = null) or replace an existing one (matched by [original]'s id).
     * Editing preserves the id, so the active selection stays valid even if the host changed.
     */
    // --- Settings backup (share / import) -------------------------------------------------------
    //
    // Export the whole user configuration (servers + theme + global zones + per-server assignments) as a
    // single JSON blob for the share sheet; import replaces all of it wholesale on confirmation.

    /** Build a shareable backup of the current settings (servers, theme, zones, assignments). */
    fun buildSettingsBackup(): String = SettingsBackup.encode(
        theme = theme.value,
        servers = servers.value,
        zones = globalZones.value,
        assignments = allAssignments.value,
        coverOverrides = allCoverOverrides.value,
    )

    /**
     * Replace **all** settings with [backup] (servers, theme, zones, assignments). The previous config is
     * discarded. Selection is reset to the first imported server so [currentServer] stays valid; the poll
     * loop re-points on the resulting emissions.
     */
    fun importSettings(backup: SettingsBackup) {
        viewModelScope.launch {
            settings.setTheme(backup.theme)
            settings.saveServers(backup.servers)
            zones.replaceAll(backup.zones, backup.assignments)
            coverStore.replaceAll(backup.coverOverrides)
            settings.selectServer(backup.servers.firstOrNull()?.id ?: "")
        }
    }

    fun upsertServer(original: MyioServer?, updated: MyioServer) {
        viewModelScope.launch {
            settings.updateServers { list ->
                val idx = original?.let { o -> list.indexOfFirst { it.id == o.id } } ?: -1
                if (idx >= 0) list.toMutableList().apply { set(idx, updated) } else list + updated
            }
        }
    }

    /** Remove a slot (by id). The list may go empty; re-selects the first remaining if the active one went. */
    fun deleteServer(server: MyioServer) {
        viewModelScope.launch {
            var remaining: List<MyioServer> = emptyList()
            settings.updateServers { list -> list.filterNot { it.id == server.id }.also { remaining = it } }
            if (currentServer.value?.id == server.id) settings.selectServer(remaining.firstOrNull()?.id ?: "")
        }
    }

    /**
     * Undo of [deleteServer]: put the same slot (same id, so zone/cover config keyed by it reapplies) back
     * at [index]. No-op if it is already there; re-selects it if nothing is selected after the delete.
     */
    fun restoreServer(server: MyioServer, index: Int) {
        viewModelScope.launch {
            settings.updateServers { list ->
                if (list.any { it.id == server.id }) list
                else list.toMutableList().apply { add(index.coerceIn(0, size), server) }
            }
            if (currentServer.value == null) settings.selectServer(server.id)
        }
    }

    private suspend fun pollLoop(serverId: String) {
        val repo = repo ?: return
        runPollLoop(repo, serverId, currentGen = { pollGeneration }, sending = { isSending(repo) }) {
            transform -> _ui.update(transform)
        }
    }

    /**
     * One server's poll loop, parameterized so the single-server and aggregate paths share it.
     * [currentGen] reads the relevant generation counter (to drop polls predating a command); [sending]
     * tells whether a command of this target is still in flight (its poll result is dropped too); [emit]
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
        sending: () -> Boolean,
        emit: (transform: (MyioUiState) -> MyioUiState) -> Unit,
    ) {
        runCatching { repo.loadCachedSnapshot() }.getOrNull()?.let { snapshot ->
            emit { it.copy(state = snapshot) }
        }
        var refreshed = false
        var lastMetaAttemptMs = 0L
        var lastSnapshotMs = 0L
        while (true) {
            foreground.first { it } // park while backgrounded → no polling when nobody's watching
            try {
                val gen = currentGen()
                val state = repo.poll()
                if (gen == currentGen() && !sending()) {
                    emit { it.copy(status = ConnectionStatus.Connected, state = state, error = null) }
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshotMs > snapshotSaveIntervalMs) {
                        lastSnapshotMs = now
                        runCatching { repo.saveSnapshot(state) }
                    }
                } else {
                    // A command landed (or is still pending) while this poll was in flight; keep the
                    // optimistic state.
                    emit { it.copy(status = ConnectionStatus.Connected, error = null) }
                }
                // One-time info + name/permission refresh once connected. Only mark done on SUCCESS, so a
                // transient failure doesn't permanently strand names/perms; retry no more than once a minute
                // so a persistent failure can't hammer the device on every tick.
                if (!refreshed && System.currentTimeMillis() - lastMetaAttemptMs >= META_RETRY_INTERVAL_MS) {
                    lastMetaAttemptMs = System.currentTimeMillis()
                    runCatching { repo.getInfo() }.onSuccess { info -> emit { it.copy(info = info) } }
                    if (runCatching { repo.refreshMetadata() }.isSuccess) {
                        refreshed = true
                        lastSnapshotMs = 0L // force the next poll to persist a snapshot with the fresh names
                    }
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

    // Per-server memo for cover detection: one polled server shouldn't re-run detect for every other
    // server. detect is pure, so reuse the previous result while a server's (state, overrides) inputs are
    // the same instances (state gets a new instance only when that server actually polls). Touched only
    // from viewModelScope collectors (the aggregateCovers combine + stopAggregate's clear), all on the
    // main dispatcher → no extra synchronization needed.
    private val coverMemo = mutableMapOf<String, Triple<ServerState, Map<String, CoverOverride>, CoverDetection.Detection>>()

    /** Full cover detection per server (id → Detection) for the aggregate dashboard — same structural
     * detection + per-server overrides as the single-server path, recomputed as each server polls. */
    private val aggregateDetection: StateFlow<Map<String, CoverDetection.Detection>> =
        combine(_aggregate, allCoverOverrides) { agg, allOv ->
            agg.perServer.mapValues { (id, ui) ->
                val ov = allOv[id] ?: emptyMap()
                val cached = coverMemo[id]
                if (cached != null && cached.first === ui.state && cached.second === ov) cached.third
                else CoverDetection.detectAll(ui.state, ov).also { coverMemo[id] = Triple(ui.state, ov, it) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Detected covers per server (id → covers) for the aggregate dashboard. */
    val aggregateCovers: StateFlow<Map<String, List<Cover>>> =
        aggregateDetection.map { m -> m.mapValues { it.value.covers } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Disabled-cover endpoint keys per server (id → coverKey → endpoint output keys) for the aggregate
     * hidden/zone expansion (see [expandHiddenForDisabledCovers]). */
    val aggregateDisabledMembers: StateFlow<Map<String, Map<String, List<String>>>> =
        aggregateDetection.map { m -> m.mapValues { it.value.disabledMembers } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var aggregateJobs: List<Job> = emptyList()
    private val aggregateRepos = mutableMapOf<String, MyioRepository>()
    private val aggregateGen = mutableMapOf<String, Int>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAggregate() {
        viewModelScope.launch {
            settings.selectedId.flatMapLatest { id ->
                if (id != SettingsStore.AGGREGATE_ID) flowOf(emptyList())
                else combine(settings.servers, allAssignments) { list, amap ->
                    list.filter { amap[it.id]?.assignments?.isNotEmpty() == true }
                }
            }
                // Restart only when the relevant servers' set OR any of their connection configs change,
                // not on every assignment edit (name/interval/hidden edits keep the same configs → no restart).
                .distinctUntilChanged { a, b ->
                    a.associate { it.id to it.toConfig() } == b.associate { it.id to it.toConfig() }
                }
                .collect { relevant ->
                    if (relevant.isEmpty()) stopAggregate() else restartAggregate(relevant)
                }
        }
    }

    private suspend fun restartAggregate(relevant: List<MyioServer>) {
        stopAggregate()
        _aggregate.value = AggregateUiState(perServer = relevant.associate { it.id to MyioUiState() })
        aggregateJobs = relevant.map { server ->
            val repo = repoFactory(server)
            aggregateRepos[server.id] = repo
            viewModelScope.launch {
                runPollLoop(
                    repo, server.id,
                    currentGen = { aggregateGen[server.id] ?: 0 },
                    sending = { isSending(repo) },
                ) { transform ->
                    updateServer(server.id, transform)
                }
            }
        }
    }

    private suspend fun stopAggregate() {
        // Join, don't just cancel: wait out each loop's in-flight (blocking) call so a restart never
        // overlaps a fresh connection to the same device.
        aggregateJobs.forEach { it.cancelAndJoin() }
        aggregateJobs = emptyList()
        aggregateRepos.clear()
        aggregateGen.clear()
        coverMemo.clear()
        _aggregate.value = AggregateUiState()
    }

    private fun updateServer(serverId: String, transform: (MyioUiState) -> MyioUiState) {
        _aggregate.update { agg ->
            val cur = agg.perServer[serverId] ?: MyioUiState()
            agg.copy(perServer = agg.perServer + (serverId to transform(cur)))
        }
    }

    // --- Control (POST) with optimistic updates -------------------------------------------------
    //
    // The single-server and aggregate paths share one implementation via [ControlTarget]: each handler
    // flips local state immediately (so the UI feels instant), then fires the POST. The optimistic step
    // also bumps the relevant generation counter (see [pollGeneration] / [aggregateGen]) so a poll that
    // carries the pre-command state can't briefly revert the control; the next poll confirms reality and
    // a failed/ignored command self-heals on that tick. Commands always go out sequentially on a single
    // coroutine (never concurrently — the device can't handle concurrent connections).

    /** A control destination: where the optimistic state lands and which repo the command is POSTed on. */
    private interface ControlTarget {
        fun optimistic(transform: (ServerState) -> ServerState)
        fun send(vararg bodies: String)
    }

    /** The active single server: [ui] state guarded by [pollGeneration], on the current [repo]. */
    private val singleTarget = object : ControlTarget {
        override fun optimistic(transform: (ServerState) -> ServerState) {
            pollGeneration++
            _ui.update { it.copy(state = transform(it.state)) }
        }
        // Capture the current repo at tap time and bind the guard to it: if the selected server is
        // re-pointed (server switch / config edit) before/while the command fires, [repo] no longer equals
        // the captured instance → drop the rest, so a command can never land on the wrong device.
        override fun send(vararg bodies: String) {
            val captured = repo
            sendOn(captured, bodies, bumpGen = { if (captured === repo) pollGeneration++ }) { captured === repo }
        }
    }

    /** One aggregate server: its [aggregate] slot guarded by [aggregateGen], on its own repo. */
    private fun aggregateTarget(serverId: String) = object : ControlTarget {
        override fun optimistic(transform: (ServerState) -> ServerState) {
            aggregateGen[serverId] = (aggregateGen[serverId] ?: 0) + 1
            updateServer(serverId) { it.copy(state = transform(it.state)) }
        }
        // Route by server id; guard against an aggregate restart re-pointing this server's repo mid-command.
        override fun send(vararg bodies: String) {
            val captured = aggregateRepos[serverId]
            sendOn(captured, bodies, bumpGen = {
                if (aggregateRepos[serverId] === captured) aggregateGen[serverId] = (aggregateGen[serverId] ?: 0) + 1
            }) { aggregateRepos[serverId] === captured }
        }
    }

    /**
     * Fire-and-forget: POST every body sequentially on one coroutine; the poll loop reconciles. [stillValid]
     * is re-checked before each POST so a server switch mid-send drops the remaining commands instead of
     * POSTing them to a now-different device. The first failed POST ends the sequence: a later body
     * assumes the earlier one landed (e.g. a thermostat on/off threshold pair).
     *
     * While the sequence runs, [repo] counts as sending (see [pendingSends]); when it ends, [bumpGen]
     * invalidates any poll that started before the last POST landed.
     */
    private fun sendOn(
        repo: MyioRepository?,
        bodies: Array<out String>,
        bumpGen: () -> Unit,
        stillValid: () -> Boolean,
    ) {
        val r = repo ?: return
        pendingSends[r] = (pendingSends[r] ?: 0) + 1
        viewModelScope.launch {
            try {
                for (b in bodies) {
                    if (!stillValid()) return@launch
                    try {
                        r.sendCommand(b)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch
                    }
                }
            } finally {
                bumpGen()
                val left = (pendingSends[r] ?: 1) - 1
                if (left > 0) pendingSends[r] = left else pendingSends.remove(r)
            }
        }
    }

    // Shared control operations, used by both the single-server and aggregate public handlers below.

    /** Toggle a relay (r_ON / r_OFF; id is 1-based, matches the device id). */
    private fun ControlTarget.relayToggle(relay: RelayOutput) {
        val target = !relay.on
        optimistic { st -> st.copy(relays = st.relays.map { if (it.id == relay.id) it.copy(on = target) else it }) }
        send(MyioCommands.relay(relay.id, target))
    }

    /**
     * Toggle a PCA output (PCA_ON / PCA_OFF; real 2001+ id). The device drives the level to its configured
     * [PcaOutput.turnOn] / [PcaOutput.turnOff], so the optimistic value uses those (not a hard 100%) to
     * avoid a misleading flash before the poll confirms.
     */
    private fun ControlTarget.pcaToggle(pca: PcaOutput) {
        val turningOn = !pca.on
        val target = if (turningOn) pca.turnOn else pca.turnOff
        optimistic { st -> st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = target) else it }) }
        send(MyioCommands.pcaOnOff(pca.id, turningOn))
    }

    /** Set a PWM-capable PCA's brightness (PCA*<id>=<0..255>). */
    private fun ControlTarget.pcaSet(pca: PcaOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        optimistic { st -> st.copy(pcas = st.pcas.map { if (it.id == pca.id) it.copy(state = v) else it }) }
        send(MyioCommands.pcaBrightness(pca.id, v))
    }

    /** Toggle a FET/PWM output (f_ON / f_OFF). On turn-on the device uses the configured turn-on level. */
    private fun ControlTarget.pwmToggle(pwm: PwmOutput) {
        val turningOn = !pwm.on
        val target = if (turningOn) pwm.turnOn else pwm.turnOff
        optimistic { st -> st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = target) else it }) }
        send(MyioCommands.fetOnOff(pwm.id, turningOn))
    }

    /** Set a FET/PWM output's value (fet*<index>=<0..255>; index is 1-based = domain id − 100). */
    private fun ControlTarget.pwmSet(pwm: PwmOutput, value: Int) {
        val v = value.coerceIn(0, 255)
        optimistic { st -> st.copy(pwms = st.pwms.map { if (it.id == pwm.id) it.copy(state = v) else it }) }
        send(MyioCommands.fetBrightness(pwm.id, v))
    }

    // Thermostat setpoints (×10 °C): the on + off commands go out sequentially. The device persists them
    // and sens_out.json reports them back, so we optimistically update the local thresholds. Currently
    // only wired for the single server, but defined on the target for symmetry.
    private fun ControlTarget.relayThermostat(relay: RelayOutput, onTenths: Int, offTenths: Int) {
        optimistic { st -> st.copy(relays = st.relays.map {
            if (it.id == relay.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
        }) }
        send(MyioCommands.relayThermostatOn(relay.id, onTenths), MyioCommands.relayThermostatOff(relay.id, offTenths))
    }

    private fun ControlTarget.pcaThermostat(pca: PcaOutput, onTenths: Int, offTenths: Int) {
        optimistic { st -> st.copy(pcas = st.pcas.map {
            if (it.id == pca.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
        }) }
        send(MyioCommands.pcaThermostatOn(pca.id, onTenths), MyioCommands.pcaThermostatOff(pca.id, offTenths))
    }

    private fun ControlTarget.pwmThermostat(pwm: PwmOutput, onTenths: Int, offTenths: Int) {
        optimistic { st -> st.copy(pwms = st.pwms.map {
            if (it.id == pwm.id) it.copy(sensorOnTenths = onTenths, sensorOffTenths = offTenths) else it
        }) }
        send(MyioCommands.fetThermostatOn(pwm.id, onTenths), MyioCommands.fetThermostatOff(pwm.id, offTenths))
    }

    // Cover (shutter) motor: drive up / down / stop. The optimistic step reflects the running direction so
    // the indicator updates instantly; the next poll confirms (and a stopped motor self-heals on its tick).
    private fun ServerState.driveCover(c: Cover, up: Boolean, down: Boolean): ServerState =
        if (c.type == CoverOutputType.PCA) copy(pcas = pcas.map {
            when (it.id) {
                c.upId -> it.copy(state = if (up) it.turnOn else 0)
                c.downId -> it.copy(state = if (down) it.turnOn else 0)
                else -> it
            }
        }) else copy(relays = relays.map {
            when (it.id) {
                c.upId -> it.copy(on = up)
                c.downId -> it.copy(on = down)
                else -> it
            }
        })

    private fun ControlTarget.coverOpen(c: Cover) {
        optimistic { it.driveCover(c, up = true, down = false) }
        send(MyioCommands.coverOpen(c.type, c.upId, c.downId))
    }

    private fun ControlTarget.coverClose(c: Cover) {
        optimistic { it.driveCover(c, up = false, down = true) }
        send(MyioCommands.coverClose(c.type, c.upId, c.downId))
    }

    private fun ControlTarget.coverStop(c: Cover) {
        optimistic { it.driveCover(c, up = false, down = false) }
        send(MyioCommands.coverStop(c.type, c.upId, c.downId))
    }

    // Public single-server handlers (MainActivity wires these to the UI callbacks).
    fun toggleRelay(relay: RelayOutput) = singleTarget.relayToggle(relay)
    fun togglePca(pca: PcaOutput) = singleTarget.pcaToggle(pca)
    fun setPcaBrightness(pca: PcaOutput, value: Int) = singleTarget.pcaSet(pca, value)
    fun togglePwm(pwm: PwmOutput) = singleTarget.pwmToggle(pwm)
    fun setPwmBrightness(pwm: PwmOutput, value: Int) = singleTarget.pwmSet(pwm, value)
    fun setRelayThermostat(relay: RelayOutput, onTenths: Int, offTenths: Int) = singleTarget.relayThermostat(relay, onTenths, offTenths)
    fun setPcaThermostat(pca: PcaOutput, onTenths: Int, offTenths: Int) = singleTarget.pcaThermostat(pca, onTenths, offTenths)
    fun setPwmThermostat(pwm: PwmOutput, onTenths: Int, offTenths: Int) = singleTarget.pwmThermostat(pwm, onTenths, offTenths)
    fun coverOpen(cover: Cover) = singleTarget.coverOpen(cover)
    fun coverClose(cover: Cover) = singleTarget.coverClose(cover)
    fun coverStop(cover: Cover) = singleTarget.coverStop(cover)

    // Public aggregate handlers (route to the owning server's target, per-server optimistic + gen guard).
    fun aggToggleRelay(serverId: String, relay: RelayOutput) = aggregateTarget(serverId).relayToggle(relay)
    fun aggTogglePca(serverId: String, pca: PcaOutput) = aggregateTarget(serverId).pcaToggle(pca)
    fun aggSetPcaBrightness(serverId: String, pca: PcaOutput, value: Int) = aggregateTarget(serverId).pcaSet(pca, value)
    fun aggTogglePwm(serverId: String, pwm: PwmOutput) = aggregateTarget(serverId).pwmToggle(pwm)
    fun aggSetPwmBrightness(serverId: String, pwm: PwmOutput, value: Int) = aggregateTarget(serverId).pwmSet(pwm, value)
    fun aggCoverOpen(serverId: String, cover: Cover) = aggregateTarget(serverId).coverOpen(cover)
    fun aggCoverClose(serverId: String, cover: Cover) = aggregateTarget(serverId).coverClose(cover)
    fun aggCoverStop(serverId: String, cover: Cover) = aggregateTarget(serverId).coverStop(cover)

    companion object {
        /** Don't retry the one-time metadata fetch more than once a minute while it keeps failing. */
        private const val META_RETRY_INTERVAL_MS = 60_000L

        fun factory(settings: SettingsStore, stateCache: StateCacheStore, zones: ZoneStore, covers: CoverStore) = viewModelFactory {
            initializer {
                MyioViewModel(
                    settings = settings,
                    zones = zones,
                    coverStore = covers,
                    repoFactory = { MyioRepository(it.toConfig(), stateCache, StateCacheStore.cacheKey(it)) },
                    stateCache = stateCache,
                )
            }
        }
    }
}
