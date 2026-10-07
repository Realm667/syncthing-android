package com.nutomic.syncthingandroid.esdesync

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.RemoteNeed
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class EsdeSafeLaunchActivity : SyncthingActivity() {
    private val handler = Handler(android.os.Looper.getMainLooper())
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val settings by lazy { EsdeSyncSettings(preferences) }
    private val journalRepository by lazy { EsdeOfflineJournalRepository.get(File(filesDir, "esde-sync/offline-journal.json")) }
    private val journalEntry get() = journalRepository.state.value.entry
    private var state by mutableStateOf(EsdeSyncState.STARTING)
    private var statusDetail by mutableStateOf("")
    private var folderHealth by mutableStateOf<List<EsdeFolderHealth>>(emptyList())
    private var preSyncStarted = false
    private var postSyncStarted = false
    private var legacyConfigurationChecked = false
    private var sharedWarning by mutableStateOf("")
    private var valueConflicts by mutableStateOf<List<EsdeValueConflict>>(emptyList())
    private var decidingValue by mutableStateOf<EsdeValueConflict?>(null)
    private var bootstrapDiscoveryAttempts = 0
    private val freshFolderStatus = ConcurrentHashMap<String, FolderStatus>()
    private val freshRemoteCompletion = ConcurrentHashMap<String, CompletionInfo>()
    private val freshRemoteNeed = ConcurrentHashMap<String, RemoteNeed>()
    private val requests = EsdeRequestGeneration()
    private val pollPolicy = EsdePollPolicy()
    private val progressWatchdog = EsdeProgressWatchdog()
    private val transferMeter = EsdeTransferMeter()
    private var transferLabel by mutableStateOf("Measuring transfer speed…")
    private val transferHandler = Handler(android.os.Looper.getMainLooper())
    private var transferGeneration = 0L
    private val transferTick = object : Runnable {
        override fun run() {
            if (state !in TRANSFER_STATES) {
                transferMeter.reset()
                transferHandler.postDelayed(this, 1000)
                return
            }
            val currentApi = api
            if (currentApi == null) {
                transferLabel = "Transfer unavailable · waiting for Syncthing"
                transferHandler.postDelayed(this, 1000)
                return
            }
            val token = requests.current
            val generation = transferGeneration
            currentApi.getFreshTransferCounters({ connections ->
                if (generation != transferGeneration) return@getFreshTransferCounters
                if (accepts(token) && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    val total = connections?.total
                    val rate = total?.let { transferMeter.sample(it.inBytesTotal, it.outBytesTotal, android.os.SystemClock.elapsedRealtime()) }
                    transferLabel = if (rate == null) "Measuring transfer speed…" else
                        "Syncthing total · ↓ ${formatRate(rate.download)} · ↑ ${formatRate(rate.upload)}" +
                            if (rate.download == 0.0 && rate.upload == 0.0) " · checking or waiting" else ""
                }
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) transferHandler.postDelayed(this, 1000)
            }, {
                if (generation != transferGeneration) return@getFreshTransferCounters
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    transferMeter.reset()
                    transferLabel = "Transfer unavailable · waiting for Syncthing"
                    transferHandler.postDelayed(this, 1000)
                }
            })
        }
    }

    private fun accepts(token: Long): Boolean = requests.accepts(token) && !isFinishing && !isDestroyed

    private fun invalidateRequests() {
        requests.invalidate()
        handler.removeCallbacksAndMessages(null)
        freshFolderStatus.clear()
        freshRemoteCompletion.clear()
        freshRemoteNeed.clear()
        pollPolicy.reset()
        progressWatchdog.reset()
    }
    private val conflictExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ESDESync-ConflictResolver")
    }
    private var conflictFolder by mutableStateOf<EsdeFolderHealth?>(null)
    private var pendingConflictResolution by mutableStateOf<PendingConflictResolution?>(null)
    private var conflictFeedback by mutableStateOf("")
    private var showPowerOffConfirmation by mutableStateOf(false)
    private var powerOffFeedback by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (EsdeFirstSetupPolicy.shouldOpenAutomatically(
                offered = settings.firstSetupOffered,
                complete = settings.firstSetupComplete,
                deferred = settings.firstSetupDeferred,
            )
        ) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_START_DESTINATION, "GamingFirstSetup")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        if (settings.activeSessionId.isBlank()) {
            val previous = preferences.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP,
            )
            settings.beginSession(previous)
            preferences.edit().putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_START).apply()
        }
        updateJournal({ journal ->
            if (settings.pendingLocalChanges) journal.migratePending(settings.activeSessionId, settings.requiredFolderIds())
        }) { resumeSyncWhenReady() }
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        setContent { ApplicationTheme { SafeLaunchScreen() } }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        super.onServiceConnected(name, binder)
        resumeSyncWhenReady()
    }

    override fun onResume() {
        super.onResume()
        transferGeneration++
        transferHandler.removeCallbacksAndMessages(null)
        transferHandler.post(transferTick)
        // Only an Activity resume can end a play session. A late service/journal callback cannot.
        if (settings.esdeWasLaunched && settings.launchTimestamp > 0 && !postSyncStarted) {
            postSyncStarted = true
            updateJournal({}) {
                handler.postDelayed({ beginPostSync() }, RETURN_FLUSH_MS)
            }
        } else resumeSyncWhenReady()
    }

    override fun onPause() {
        transferGeneration++
        transferHandler.removeCallbacksAndMessages(null)
        transferMeter.reset()
        super.onPause()
    }

    private fun resumeSyncWhenReady() {
        if (!journalRepository.state.value.loaded || service == null ||
            !EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(state)) return
        if (!settings.esdeWasLaunched && state == EsdeSyncState.NOT_CONFIGURED && preSyncStarted) {
            handler.postDelayed({ if (!isFinishing) retry() }, SETTINGS_RETURN_DELAY_MS)
        } else if (!preSyncStarted && !settings.esdeWasLaunched) {
            if (journalEntry != null) beginPendingReconciliation() else beginPreSync()
        }
    }

    private fun updateJournal(action: (EsdeOfflineJournal) -> Unit, onSuccess: () -> Unit = {}) {
        val token = requests.current
        journalRepository.update(action) { result ->
            handler.post {
                if (!accepts(token)) return@post
                result.fold(onSuccess = { onSuccess() }, onFailure = {
                    invalidateRequests()
                    settings.pendingLocalChanges = true
                    state = EsdeSyncState.ERROR
                    statusDetail = "Could not persist offline synchronization state: ${it.message}. Retry before switching devices."
                })
            }
        }
    }

    private fun beginPreSync() {
        preSyncStarted = true
        val missingRequirements = settings.missingSafeLaunchRequirements()
        if (missingRequirements.isNotEmpty()) {
            if (
                missingRequirements == setOf(EsdeSetupRequirement.INITIAL_METADATA_SOURCE) &&
                bootstrapDiscoveryAttempts < BOOTSTRAP_DISCOVERY_ATTEMPTS
            ) {
                bootstrapDiscoveryAttempts++
                state = EsdeSyncState.STARTING
                statusDetail = "Checking for synchronized ES-DE metadata…"
                preSyncStarted = false
                handler.postDelayed({ if (!isFinishing) beginPreSync() }, POLL_MS)
                return
            }
            state = EsdeSyncState.NOT_CONFIGURED
            statusDetail = setupRequirementsMessage(missingRequirements)
            return
        }
        val api = api
        if (api == null) {
            state = EsdeSyncState.STARTING
            handler.postDelayed({ if (!isFinishing) beginPreSyncRetry() }, POLL_MS)
            return
        }
        if (!legacyConfigurationChecked) {
            val coordinator = service?.esdeSyncCoordinator
            if (coordinator == null) {
                state = EsdeSyncState.ERROR
                statusDetail = "Metadata bridge is not available."
                return
            }
            state = EsdeSyncState.STARTING
            statusDetail = "Checking required ES-DE settings…"
            val token = requests.current
            coordinator.ensureLegacyGamelistLocation { success, message ->
                if (!accepts(token)) return@ensureLegacyGamelistLocation
                if (!success) {
                    state = EsdeSyncState.ERROR
                    statusDetail = message
                } else {
                    legacyConfigurationChecked = true
                    preSyncStarted = false
                    beginPreSync()
                }
            }
            return
        }
        state = EsdeSyncState.RESCANNING
        statusDetail = "Refreshing selected gaming folders…"
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_PRE_SYNC, System.currentTimeMillis()).apply()
        rescanSelected { pollPreSync() }
    }

    private fun beginPreSyncRetry() {
        preSyncStarted = false
        beginPreSync()
    }

    private fun pollPreSync() {
        val token = requests.current
        refreshFreshGateData {
            val evaluated = evaluateGate()
            state = evaluated
            if (evaluated == EsdeSyncState.READY_TO_PLAY) {
                state = EsdeSyncState.IMPORTING_METADATA
                statusDetail = "Applying Shared Collections and ES-DE settings…"
                val coordinator = service?.esdeSyncCoordinator
                val importRevision = coordinator?.currentRemoteRevision()
                if (coordinator == null) {
                    state = EsdeSyncState.ERROR
                    statusDetail = "Metadata bridge is not available."
                } else coordinator.importSharedStateBeforeLaunch { shared ->
                    if (!accepts(token)) return@importSharedStateBeforeLaunch
                    if (!shared.successful) {
                        valueConflicts = shared.collections.decisions + shared.settings.decisions
                        state = EsdeSyncState.ERROR
                        statusDetail = shared.errorSummary()
                    } else {
                        sharedWarning = (shared.collections.warnings + shared.settings.warnings).joinToString("; ")
                        statusDetail = "Applying synchronized per-game metadata…"
                        coordinator.importNow(finalizeBootstrap = !settings.bootstrapComplete, expectedRevision = importRevision) { metadata ->
                            if (!accepts(token)) return@importNow
                            if (metadata.invalid > 0) {
                                state = EsdeSyncState.ERROR
                                statusDetail = metadata.errors.joinToString("; ").ifBlank { "Per-game metadata contains ${metadata.invalid} invalid sidecar(s)." }
                            } else if (settings.bootstrapPendingImport) {
                                state = EsdeSyncState.SYNCING
                                statusDetail = "New metadata arrived during import. Checking the latest state…"
                                handler.postDelayed(::pollPreSync, POLL_MS)
                            } else {
                                state = EsdeSyncState.READY_TO_PLAY
                                statusDetail = if (sharedWarning.isBlank()) "Everything is synchronized."
                                    else "Everything is synchronized. Warning: $sharedWarning"
                            }
                        }
                    }
                }
                return@refreshFreshGateData
            }
            if (progressWatchdog.stalled(evaluated to folderHealth, android.os.SystemClock.elapsedRealtime())) {
                statusDetail = "No visible progress for two minutes. Still checking; review network and folder details."
            }
            handler.postDelayed(::pollPreSync, pollPolicy.nextDelay(evaluated to folderHealth))
        }
    }

    private fun refreshFreshGateData(onComplete: () -> Unit) {
        val api = api ?: run { onComplete(); return }
        val ids = activeFolderIds()
        if (ids.isEmpty()) { onComplete(); return }
        val token = requests.current
        val primaryId = settings.primaryDeviceId
        val statuses = ConcurrentHashMap<String, FolderStatus>()
        val completions = ConcurrentHashMap<String, CompletionInfo>()
        val needs = ConcurrentHashMap<String, RemoteNeed>()
        val remaining = AtomicInteger(ids.size * 2)
        fun valid() = accepts(token) && this.api === api && primaryId == settings.primaryDeviceId && ids == activeFolderIds()
        fun done() {
            if (remaining.decrementAndGet() == 0) handler.post {
                if (!accepts(token)) return@post
                if (!valid()) {
                    retry()
                    return@post
                }
                freshFolderStatus.clear()
                freshFolderStatus.putAll(statuses)
                freshRemoteCompletion.clear()
                freshRemoteCompletion.putAll(completions)
                freshRemoteNeed.clear()
                freshRemoteNeed.putAll(needs)
                onComplete()
            }
        }
        ids.forEach { id ->
            api.getFreshFolderStatus(id, { status ->
                if (!valid()) { done(); return@getFreshFolderStatus }
                conflictExecutor.execute {
                    if (valid()) {
                        runCatching {
                            api.refreshEsdeConflictFiles(id, status)
                        }.onFailure { status.error = "Conflict check failed: ${it.message}" }
                        statuses[id] = status
                    }
                    done()
                }
            }, {
                done()
            })
            api.getFreshFolderCompletion(id, primaryId,
                { completion ->
                    if (!valid()) {
                        done()
                        return@getFreshFolderCompletion
                    }
                    completions[id] = completion
                    val rawRemotePending = completion.completion < 100 || completion.needBytes > 0.0 ||
                        completion.needItems > 0
                    if (!rawRemotePending) {
                        needs[id] = RemoteNeed()
                        done()
                    } else {
                        api.getFreshRemoteNeed(id, primaryId,
                            { need ->
                                if (valid()) needs[id] = need
                                done()
                            },
                            { done() })
                    }
                },
                {
                    done()
                })
        }
    }

    private fun evaluateGate(): EsdeSyncState {
        val currentService = service
        val api = api
        if (currentService == null || api == null) return EsdeSyncState.STARTING
        val primary = api.getRemoteDeviceStatus(settings.primaryDeviceId)
        val foldersById = api.folders.associateBy { it.id }
        folderHealth = activeFolderIds().map { id ->
            val folder = foldersById[id]
            if (folder == null) return@map EsdeFolderHealth(
                id, false, "unknown", "Folder is not configured", 0, 0, 0, 0, 0, 0.0, 0,
                remoteState = "unknown",
                label = id,
            )
            val statusPair = api.getFolderStatus(id)
            val status = freshFolderStatus[id] ?: statusPair.key
            val cache = statusPair.value
            val remote = freshRemoteCompletion[id]
            val remoteNeed = freshRemoteNeed[id]
            val remoteItems = remoteNeed?.allItems().orEmpty()
            val listedBlocking = remoteItems.count(EsdeRemoteNeedPolicy::isBlocking).toLong()
            val listedIgnored = remoteItems.size.toLong() - listedBlocking
            val declaredRemoteNeed = remote?.needItems?.toLong() ?: remoteItems.size.toLong()
            val unlistedItems = (declaredRemoteNeed - remoteItems.size).coerceAtLeast(0L)
            val conflictFiles = cache.discoveredConflictFiles?.toList().orEmpty()
            EsdeFolderHealth(
                id = id,
                paused = folder.paused || cache.paused,
                state = status.state ?: "unknown",
                error = if (folder.getDevice(settings.primaryDeviceId) == null) {
                    "Folder is not shared with the selected Primary Sync Device"
                } else {
                    listOf(status.error, status.invalid, status.watchError).firstOrNull { !it.isNullOrBlank() } ?: ""
                },
                needFiles = status.needFiles,
                needBytes = status.needBytes,
                needTotalItems = status.needTotalItems,
                pullErrors = status.pullErrors,
                remoteCompletion = remote?.completion?.toInt() ?: api.getRemoteDeviceCompletion(settings.primaryDeviceId),
                remoteNeedBytes = remote?.needBytes ?: api.getRemoteDeviceNeedBytes(settings.primaryDeviceId),
                conflicts = conflictFiles.size,
                remoteNeedItems = remote?.needItems?.toLong() ?: 0,
                remoteState = remote?.remoteState ?: "unknown",
                label = folderDisplayName(folder),
                conflictFiles = conflictFiles,
                remoteNeedKnown = remoteNeed != null,
                remoteBlockingItems = listedBlocking + unlistedItems,
                remoteIgnoredItems = listedIgnored,
            )
        }
        // A failed request must not be replaced by an older aggregate cache to unlock play.
        val missingFreshData = activeFolderIds().any { !freshFolderStatus.containsKey(it) || !freshRemoteCompletion.containsKey(it) }
        val evaluated = EsdeSyncStateEvaluator.evaluate(
            EsdeGateInput(
                configured = settings.isSafeLaunchConfigured(),
                serviceActive = currentService.currentState == SyncthingService.State.ACTIVE,
                primaryConnected = primary.connected,
                primaryPaused = primary.paused,
                folders = folderHealth,
            )
        )
        val next = if (missingFreshData && evaluated == EsdeSyncState.READY_TO_PLAY) EsdeSyncState.STARTING else evaluated
        statusDetail = when (next) {
            EsdeSyncState.WAITING_FOR_PRIMARY -> "Primary Sync Device is not reachable."
            EsdeSyncState.SYNCING -> "Synchronizing game data…"
            EsdeSyncState.ERROR -> "A selected folder has an error or sync conflict."
            EsdeSyncState.STARTING -> "Starting Syncthing…"
            else -> statusDetail
        }
        return next
    }

    private fun launchEsde(offline: Boolean) {
        if (state in setOf(EsdeSyncState.IMPORTING_METADATA, EsdeSyncState.EXPORTING_METADATA, EsdeSyncState.LAUNCHING)) return
        if (!journalRepository.state.value.loaded) {
            statusDetail = "Reading saved synchronization state. Please wait."
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(settings.applicationPackage)
        if (launchIntent == null) {
            state = EsdeSyncState.ERROR
            statusDetail = "The selected ES-DE application is not installed."
            return
        }
        invalidateRequests()
        val token = requests.current
        fun reserveAndLaunch() {
            val coordinator = service?.esdeSyncCoordinator ?: run {
                state = EsdeSyncState.ERROR; statusDetail = "Metadata bridge is unavailable"; return
            }
            coordinator.prepareLaunch(offline) { success, message ->
                if (!accepts(token)) {
                    return@prepareLaunch
                }
                if (!success) { state = EsdeSyncState.ERROR; statusDetail = message }
                else updateJournal({ it.begin(settings.activeSessionId, activeFolderIds()) }) {
                    completeEsdeLaunch(launchIntent, offline)
                }
            }
        }
        state = EsdeSyncState.LAUNCHING
        statusDetail = "Verifying launch readiness…"
        if (offline) reserveAndLaunch() else refreshFreshGateData {
            if (evaluateGate() == EsdeSyncState.READY_TO_PLAY && !settings.bootstrapPendingImport) reserveAndLaunch()
            else { preSyncStarted = false; beginPreSync() }
        }
    }

    private fun completeEsdeLaunch(launchIntent: Intent, offline: Boolean) {
        settings.offlineOverrideUsed = offline
        settings.esdeWasLaunched = true
        settings.launchTimestamp = System.currentTimeMillis()
        postSyncStarted = false
        if (offline) {
            settings.pendingLocalChanges = true
            state = EsdeSyncState.OFFLINE_PLAYING
        } else {
            state = EsdeSyncState.ESDE_RUNNING
        }
        runCatching { startActivity(launchIntent) }.onFailure {
            settings.esdeWasLaunched = false
            state = EsdeSyncState.ERROR
            statusDetail = "Could not start ES-DE: ${it.message}"
        }
    }

    private fun beginPostSync() {
        val token = requests.current
        state = EsdeSyncState.EXPORTING_METADATA
        statusDetail = "Verifying ES-DE is closed before reading final metadata…"
        val coordinator = service?.esdeSyncCoordinator
        if (coordinator == null) {
            state = EsdeSyncState.ERROR
            statusDetail = "Local changes are waiting for synchronization."
            persistPendingChanges(statusDetail)
            EsdeDeferredSyncScheduler.schedule(this)
            return
        }
        coordinator.closeEsdeAfterPlay { closed, message ->
            if (!accepts(token)) return@closeEsdeAfterPlay
            if (!closed) {
                state = EsdeSyncState.ERROR
                statusDetail = message
                persistPendingChanges(message)
                EsdeDeferredSyncScheduler.schedule(this)
            } else {
                statusDetail = message
                updateJournal({ journal ->
                    if (journal.load() == null) journal.begin(settings.activeSessionId, activeFolderIds())
                    journal.markPending()
                }) { exportAndSyncAfterPlay(coordinator) }
            }
        }
    }

    private fun exportAndSyncAfterPlay(coordinator: EsdeSyncCoordinator) {
        val token = requests.current
        EsdeSessionWorkflow(
            perform = { step, completed ->
                if (accepts(token)) when (step) {
                    EsdeSessionStep.CLOSE_ESDE -> coordinator.closeEsdeAfterPlay { ok, message ->
                        if (accepts(token)) completed(if (ok) Result.success(Unit) else Result.failure(IllegalStateException(message)))
                    }
                    EsdeSessionStep.EXPORT_METADATA -> {
                        statusDetail = "Exporting final per-game metadata…"
                        coordinator.exportNow { result ->
                            if (accepts(token)) completed(if (result.successful) Result.success(Unit)
                                else Result.failure(IllegalStateException(result.errors.joinToString("; "))))
                        }
                    }
                    EsdeSessionStep.PUBLISH_SHARED_STATE -> {
                        statusDetail = "Publishing selected Collections and Settings…"
                        coordinator.publishSharedState { result ->
                            if (accepts(token)) valueConflicts = result.collections.decisions + result.settings.decisions
                            if (accepts(token)) completed(if (result.successful) Result.success(Unit)
                                else Result.failure(IllegalStateException(result.errorSummary())))
                        }
                    }
                    EsdeSessionStep.SYNC_FILES -> completed(Result.success(Unit))
                }
            },
            checkpoint = { next, continuation -> updateJournal({ it.advance(next) }, continuation) },
            failed = { message ->
                state = EsdeSyncState.ERROR
                statusDetail = message
                persistPendingChanges(message)
                EsdeDeferredSyncScheduler.schedule(this)
            },
            readyToSync = {
                updateJournal({ it.markReconciling() }) {
                    state = EsdeSyncState.SYNCING_AFTER_PLAY
                    statusDetail = "Synchronizing game data after play…"
                    preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_POST_SYNC, System.currentTimeMillis()).apply()
                    rescanSelected { pollPostSync() }
                }
            },
        ).resume(journalEntry?.nextStep ?: EsdeSessionStep.CLOSE_ESDE)
    }

    private fun rescanSelected(onComplete: () -> Unit) {
        val rest = api ?: run { state = EsdeSyncState.ERROR; statusDetail = "Syncthing is unavailable. Retry."; return }
        val ids = activeFolderIds()
        if (ids.isEmpty()) { state = EsdeSyncState.NOT_CONFIGURED; return }
        val token = requests.current
        var remaining = ids.size
        var failures = 0
        fun done(ok: Boolean) {
            if (!accepts(token)) return
            if (!ok) failures++
            if (--remaining == 0) {
                if (failures > 0) {
                    state = EsdeSyncState.ERROR
                    statusDetail = "$failures folder scan(s) failed. Retry before playing or switching devices."
                } else { progressWatchdog.reset(); onComplete() }
            }
        }
        ids.forEach { rest.rescanFolderVerified(it, { done(true) }, { done(false) }) }
    }

    private fun pollPostSync() {
        refreshFreshGateData { when (val evaluated = evaluateGate()) {
            EsdeSyncState.READY_TO_PLAY -> {
                val token = requests.current
                val coordinator = service?.esdeSyncCoordinator ?: run {
                    state = EsdeSyncState.ERROR
                    statusDetail = "Metadata bridge disconnected; Retry to verify final synchronization."
                    return@refreshFreshGateData
                }
                coordinator.closeEsdeAfterPlay { closed, message ->
                    if (!accepts(token)) return@closeEsdeAfterPlay
                    if (!closed) {
                        state = EsdeSyncState.ERROR
                        statusDetail = message
                        updateJournal({ it.advance(EsdeSessionStep.CLOSE_ESDE) })
                        return@closeEsdeAfterPlay
                    }
                    updateJournal({ it.clear() }) {
                    state = EsdeSyncState.SAFE_TO_SWITCH
                    statusDetail = "ES-DE is closed and all changes are synchronized. Safe to switch device."
                    settings.pendingLocalChanges = false
                    EsdeDeferredSyncScheduler.cancel(this)
                    preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_SUCCESSFUL_SYNC, System.currentTimeMillis()).apply()
                    restoreForceState()
                    }
                }
            }
            EsdeSyncState.ERROR -> {
                state = EsdeSyncState.ERROR
                statusDetail = "Pending changes need attention before synchronization can finish."
                persistPendingChanges(statusDetail)
            }
            EsdeSyncState.WAITING_FOR_PRIMARY -> {
                state = EsdeSyncState.OFFLINE_CHANGES_PENDING
                statusDetail = "Offline changes are saved locally. The Primary Sync Device is not reachable yet."
                persistPendingChanges(statusDetail)
                EsdeDeferredSyncScheduler.schedule(this)
            }
            else -> {
                state = EsdeSyncState.SYNCING_AFTER_PLAY
                if (progressWatchdog.stalled(evaluated to folderHealth, android.os.SystemClock.elapsedRealtime()))
                    statusDetail = "No visible progress for two minutes. Still checking pending changes; review network and folders."
                handler.postDelayed(::pollPostSync, pollPolicy.nextDelay(evaluated to folderHealth))
            }
        } }
    }

    private fun beginPendingReconciliation() {
        preSyncStarted = true
        postSyncStarted = true
        val api = api
        if (api == null) {
            state = EsdeSyncState.RECONNECTING
            statusDetail = "Starting Syncthing to reconnect pending offline changes…"
            handler.postDelayed({ if (!isFinishing) beginPendingReconciliation() }, POLL_MS)
            return
        }
        beginPostSync()
    }

    private fun restoreForceState() {
        preferences.edit().putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, settings.previousForceState).apply()
        service?.evaluateRunConditions()
    }

    private fun activeFolderIds(): Set<String> = buildSet {
        addAll(settings.requiredFolderIds())
        journalEntry?.folderIds?.let(::addAll)
    }

    private fun persistPendingChanges(message: String = "") {
        val sessionId = settings.activeSessionId.ifBlank { "pending-${System.currentTimeMillis()}" }
        val folders = activeFolderIds()
        updateJournal({ journal ->
            if (journal.load() == null) journal.begin(sessionId, folders)
            journal.markPending(message)
        })
        settings.pendingLocalChanges = true
    }

    private fun finishSession() {
        invalidateRequests()
        restoreForceState()
        settings.clearSession()
        // The journal was durably cleared before SAFE_TO_SWITCH was exposed.
        EsdeDeferredSyncScheduler.cancel(this)
        handler.removeCallbacksAndMessages(null)
        preSyncStarted = false
        postSyncStarted = false
        legacyConfigurationChecked = false
        bootstrapDiscoveryAttempts = 0
        folderHealth = emptyList()
        state = EsdeSafeLaunchCompletionPolicy.afterDone(state)
        statusDetail = "Session complete. ES-DE is closed and this device is idle. Safe to switch device."
    }

    private fun startAgain() {
        invalidateRequests()
        if (settings.activeSessionId.isNotBlank()) {
            restoreForceState()
            settings.clearSession()
        }
        startActivity(Intent(this, EsdeSafeLaunchActivity::class.java))
        finish()
    }

    private fun requestPowerOff() {
        showPowerOffConfirmation = false
        val allowed = EsdePowerOffPolicy.canRequest(
            state = state,
            esdeWasLaunched = settings.esdeWasLaunched,
            pendingLocalChanges = settings.pendingLocalChanges,
            hasOfflineJournal = journalEntry != null,
            activeSessionId = settings.activeSessionId,
        )
        if (!allowed) {
            powerOffFeedback = "Power off is available only after ES-DE is closed and all pending changes are synchronized."
            return
        }

        val controller = EsdeDevicePowerController(this)
        if (controller.openSystemShutdownDialog()) {
            powerOffFeedback = "Android's power-off confirmation was opened."
            return
        }

        powerOffFeedback = "Requesting privileged power off…"
        conflictExecutor.execute {
            val result = controller.requestRootPowerOff()
            handler.post {
                if (isFinishing) return@post
                powerOffFeedback = when (result) {
                    EsdePowerOffResult.ROOT_REQUESTED -> "Power off was accepted by the device."
                    EsdePowerOffResult.UNSUPPORTED ->
                        "This Android firmware does not allow third-party apps to power off the device. Root or vendor system permission is required."
                }
            }
        }
    }

    private fun retry() {
        invalidateRequests()
        if (!journalRepository.state.value.loaded) {
            updateJournal({}) { continueRetry() }
            return
        }
        val token = requests.current
        val currentApi = api
        val foldersById = currentApi?.folders?.associateBy { it.id }.orEmpty()
        val cachedConflicts = folderHealth.filter { it.conflictFiles.isNotEmpty() }
        if (currentApi == null || cachedConflicts.isEmpty()) {
            continueRetry()
            return
        }

        state = EsdeSyncState.RESCANNING
        statusDetail = "Rechecking reported conflict files…"
        conflictExecutor.execute {
            val resolver = EsdeConflictResolver(File(filesDir, "esde-sync/backups"))
            val refreshed = cachedConflicts.map { health ->
                val root = foldersById[health.id]?.path?.takeIf(String::isNotBlank)?.let(::File)
                val existing = if (root?.isDirectory == true) {
                    runCatching { resolver.existingConflicts(root, health.conflictFiles) }
                        .getOrElse { health.conflictFiles }
                } else {
                    health.conflictFiles
                }
                RefreshedConflicts(health.id, existing, health.conflictFiles.size - existing.size)
            }
            handler.post {
                if (!accepts(token)) return@post
                refreshed.forEach { result ->
                    currentApi.setDiscoveredConflictFiles(result.folderId, result.existing.toTypedArray())
                }
                val removed = refreshed.sumOf(RefreshedConflicts::removed)
                if (removed > 0) {
                    conflictFolder = null
                    conflictFeedback = if (removed == 1) {
                        "1 stale conflict entry was cleared because its file no longer exists."
                    } else {
                        "$removed stale conflict entries were cleared because their files no longer exist."
                    }
                }
                continueRetry()
            }
        }
    }

    private fun continueRetry() {
        if (!settings.esdeWasLaunched && journalEntry != null) {
            beginPendingReconciliation()
            return
        }
        val retryPostSync = postSyncStarted && settings.launchTimestamp > 0
        preSyncStarted = false
        legacyConfigurationChecked = false
        bootstrapDiscoveryAttempts = 0
        if (retryPostSync) {
            postSyncStarted = true
            beginPostSync()
        } else {
            postSyncStarted = false
            settings.esdeWasLaunched = false
            beginPreSync()
        }
    }

    private fun resolveConflict(pending: PendingConflictResolution) {
        val folder = api?.folders?.firstOrNull { it.id == pending.folderId }
        val path = folder?.path
        if (path.isNullOrBlank()) {
            conflictFeedback = "The selected folder path is unavailable."
            pendingConflictResolution = null
            return
        }
        val count = pending.relativePaths.size
        conflictFeedback = if (count == 1) "Resolving ${pending.relativePaths.single()}…"
            else "Resolving $count conflicts…"
        conflictExecutor.execute {
            val result = runCatching {
                val resolver = EsdeConflictResolver(File(filesDir, "esde-sync/backups"))
                if (count == 1) {
                    resolver.resolve(File(path), pending.relativePaths.single(), pending.resolution)
                    1
                } else {
                    resolver.resolveAll(File(path), pending.relativePaths, pending.resolution)
                }
            }
            handler.post {
                if (isFinishing) return@post
                pendingConflictResolution = null
                result.onSuccess { resolvedCount ->
                    val resolvedPaths = pending.relativePaths.toSet()
                    val remaining = folderHealth.firstOrNull { it.id == pending.folderId }
                        ?.conflictFiles.orEmpty().filterNot { it in resolvedPaths }
                    api?.setDiscoveredConflictFiles(pending.folderId, remaining.toTypedArray())
                    conflictFolder = null
                    conflictFeedback = "$resolvedCount conflict(s) resolved. All versions were backed up privately."
                    retry()
                }.onFailure { error ->
                    val message = error.message ?: "unknown error"
                    if (message.startsWith("Conflict copy no longer exists:")) {
                        conflictFeedback = "The conflict file disappeared while resolving it. Refreshing the conflict list…"
                        retry()
                    } else {
                        conflictFeedback = "Conflict could not be resolved: $message"
                    }
                }
            }
        }
    }

    private fun openSyncthing() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openNetworkSettings() {
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun initializeFromThisDevice() {
        val coordinator = service?.esdeSyncCoordinator
        if (coordinator == null) {
            state = EsdeSyncState.ERROR
            statusDetail = "Metadata bridge is not available."
            return
        }
        state = EsdeSyncState.EXPORTING_METADATA
        statusDetail = "Creating the initial synchronized metadata sidecars…"
        coordinator.initializeFromThisDevice { result ->
            when {
                result.blockedByExistingSidecars -> {
                    statusDetail = "Existing synchronized metadata was found. It will be imported after synchronization."
                    retry()
                }
                result.export.gamesRead > 0 -> {
                    statusDetail = "Initial metadata source created: ${result.export.sidecarsWritten} sidecar(s)."
                    retry()
                }
                else -> {
                    state = EsdeSyncState.NOT_CONFIGURED
                    statusDetail = "No games were found in the selected gamelist root. Check the directory and gamelist.xml files."
                }
            }
        }
    }

    private fun setupRequirementsMessage(missing: Set<EsdeSetupRequirement>): String {
        if (missing == setOf(EsdeSetupRequirement.INITIAL_METADATA_SOURCE)) {
            return "No synchronized metadata source exists yet. Use the local Android gamelists only if this device is authoritative. For a NAS or desktop source, create the sidecars there first."
        }
        val labels = missing.mapNotNull {
            when (it) {
                EsdeSetupRequirement.ENABLE_SYNC -> "enable ES-DE Gaming Sync"
                EsdeSetupRequirement.ESDE_DIRECTORY -> "ES-DE application data directory"
                EsdeSetupRequirement.GAMELIST_DIRECTORY -> "gamelist root directory"
                EsdeSetupRequirement.ESDE_APPLICATION -> "ES-DE application"
                EsdeSetupRequirement.PRIMARY_DEVICE -> "Primary Gaming Sync Device"
                EsdeSetupRequirement.GAMING_FOLDERS -> "at least one Gaming Sync Folder"
                EsdeSetupRequirement.ROM_FOLDER -> "ROM / gamelist Syncthing folder"
                EsdeSetupRequirement.SHARED_STATE_FOLDER -> "ES-DE Settings & Collections sync folder"
                EsdeSetupRequirement.INITIAL_METADATA_SOURCE -> null
            }
        }
        return "Setup is incomplete. Missing: ${labels.joinToString()}."
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_START_DESTINATION, "Gaming"))
    }

    private fun openEsdeAppInfo() {
        if (settings.applicationPackage.isNotBlank()) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${settings.applicationPackage}")))
    }

    private fun decideSharedValue(conflict: EsdeValueConflict, useShared: Boolean) {
        decidingValue = null
        val token = requests.current
        service?.esdeSyncCoordinator?.resolveSharedConflict(conflict, useShared) { ok, message ->
            if (!accepts(token)) return@resolveSharedConflict
            statusDetail = message
            if (ok) { valueConflicts = valueConflicts - conflict; retry() }
        }
    }

    override fun onDestroy() {
        transferHandler.removeCallbacksAndMessages(null)
        invalidateRequests()
        conflictExecutor.shutdownNow()
        if (isFinishing && settings.activeSessionId.isNotBlank() && journalRepository.state.value.loaded && journalEntry == null &&
            (state == EsdeSyncState.SAFE_TO_SWITCH || state == EsdeSyncState.IDLE)
        ) {
            restoreForceState()
            settings.clearSession()
        }
        super.onDestroy()
    }

    @Composable
    private fun SafeLaunchScreen() {
        val journalSnapshot by journalRepository.state.collectAsState()
        val hasPendingChanges = !journalSnapshot.loaded || journalSnapshot.entry != null || settings.pendingLocalChanges
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SYNCTHING ES-DE SAFE SYNC",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(stateLabel(state), color = stateColor(state), style = MaterialTheme.typography.titleLarge)
                    Text(statusDetail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    if (state in setOf(EsdeSyncState.READY_TO_PLAY, EsdeSyncState.SAFE_TO_SWITCH, EsdeSyncState.IDLE)) LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = stateColor(state),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    else if (state in TRANSFER_STATES || state in setOf(EsdeSyncState.IMPORTING_METADATA,
                        EsdeSyncState.EXPORTING_METADATA, EsdeSyncState.LAUNCHING))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp), color = stateColor(state))
                    Text(progressLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (state in TRANSFER_STATES) Text(transferLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            InstructionCard()
            folderHealth.forEach { health -> FolderCard(health) { conflictFolder = health } }
            valueConflicts.forEach { conflict ->
                OutlinedButton(onClick = { decidingValue = conflict }) { Text("RESOLVE ${conflict.category.uppercase()}: ${conflict.name}") }
            }
            if (conflictFeedback.isNotBlank()) {
                Text(conflictFeedback, color = warningColor(), style = MaterialTheme.typography.bodyMedium)
            }
            if (powerOffFeedback.isNotBlank()) {
                Text(powerOffFeedback, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    EsdeSyncState.READY_TO_PLAY -> Button(
                        onClick = { launchEsde(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                    ) { Text("START ES-DE") }
                    EsdeSyncState.NOT_CONFIGURED -> {
                        if (settings.missingSafeLaunchRequirements() == setOf(EsdeSetupRequirement.INITIAL_METADATA_SOURCE)) {
                            Button(onClick = ::initializeFromThisDevice) { Text("USE LOCAL ANDROID GAMELISTS AS SOURCE") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = ::openSettings) { Text("OPEN SETTINGS") }
                            Button(
                                onClick = { launchEsde(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                            ) { Text("START OFFLINE SESSION") }
                        }
                    }
                    EsdeSyncState.SAFE_TO_SWITCH -> {
                        Button(
                            onClick = ::finishSession,
                            colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                        ) { Text("DONE") }
                        OutlinedButton(onClick = ::startAgain) { Text("START NEW SESSION") }
                    }
                    EsdeSyncState.IDLE -> {
                        Button(
                            onClick = ::startAgain,
                            colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                        ) { Text("START NEW SESSION") }
                        Button(
                            onClick = { showPowerOffConfirmation = true },
                            colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                        ) { Text("POWER OFF DEVICE") }
                    }
                    EsdeSyncState.OFFLINE_CHANGES_PENDING -> {
                        Button(
                            onClick = ::beginPendingReconciliation,
                            colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                        ) { Text("SYNC PENDING CHANGES") }
                        OutlinedButton(onClick = ::openNetworkSettings) { Text("OPEN NETWORK SETTINGS") }
                        OutlinedButton(onClick = ::openSyncthing) { Text("OPEN SYNCTHING") }
                    }
                    EsdeSyncState.ERROR -> {
                        OutlinedButton(onClick = ::openEsdeAppInfo) { Text("OPEN ES-DE APP INFO · FORCE STOP") }
                        OutlinedButton(onClick = ::retry) { Text("RETRY") }
                        OutlinedButton(onClick = ::openSyncthing) { Text("OPEN SYNCTHING") }
                        if (!hasPendingChanges) Button(
                                onClick = { launchEsde(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                            ) { Text("START OFFLINE SESSION") }
                    }
                    EsdeSyncState.STARTING, EsdeSyncState.WAITING_FOR_PRIMARY, EsdeSyncState.RESCANNING,
                    EsdeSyncState.SYNCING -> {
                        OutlinedButton(onClick = ::retry) { Text("RETRY") }
                        Button(
                            onClick = { launchEsde(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                        ) { Text("START OFFLINE SESSION") }
                    }
                    else -> Unit
                }
            }
            if (state != EsdeSyncState.READY_TO_PLAY && state != EsdeSyncState.SAFE_TO_SWITCH &&
                state != EsdeSyncState.IDLE
            ) {
                Text(
                    "Local changes will be kept. Fully synchronize this device before continuing on another handheld.",
                    color = warningColor(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "A  SELECT     B  BACK     HOME  RETURN TO SAFE SYNC",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        conflictFolder?.let { ConflictListDialog(it) }
        decidingValue?.let { conflict ->
            AlertDialog(onDismissRequest = { decidingValue = null },
                title = { Text("RESOLVE ${conflict.name}") },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("Local: ${conflict.localValue}")
                    Text("Synchronized: ${conflict.sharedValue}")
                    Text("The replaced value is backed up. If either value changes, refresh before deciding.")
                } },
                confirmButton = { TextButton(onClick = { decideSharedValue(conflict, true) }) { Text("USE SYNCHRONIZED") } },
                dismissButton = { TextButton(onClick = { decideSharedValue(conflict, false) }) { Text("KEEP LOCAL") } })
        }
        pendingConflictResolution?.let { ConflictConfirmationDialog(it) }
        if (showPowerOffConfirmation) PowerOffConfirmationDialog()
    }

    @Composable
    private fun PowerOffConfirmationDialog() {
        AlertDialog(
            onDismissRequest = { showPowerOffConfirmation = false },
            title = { Text("POWER OFF DEVICE") },
            text = {
                Text(
                    "ES-DE is closed and synchronization is complete. Power off this device now? " +
                        "Android may show an additional system or root confirmation.",
                )
            },
            confirmButton = {
                Button(
                    onClick = ::requestPowerOff,
                    colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                ) { Text("POWER OFF") }
            },
            dismissButton = {
                TextButton(onClick = { showPowerOffConfirmation = false }) { Text("CANCEL") }
            },
        )
    }

    @Composable
    private fun InstructionCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT TO DO", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(currentInstruction(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                InstructionStep(1, "Start ES-DE from this screen.")
                InstructionStep(2, "Play, then close the emulator and return to ES-DE.")
                InstructionStep(3, "Press Home to return. When requested, open ES-DE app info, select Force stop, then return and Retry.")
                InstructionStep(4, "Keep SafeSync open until SAFE TO SWITCH DEVICE appears.")
            }
        }
    }

    @Composable
    private fun InstructionStep(number: Int, instruction: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text("$number.", color = SAFE_GREEN, fontWeight = FontWeight.Bold)
            Text(instruction, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun FolderCard(health: EsdeFolderHealth, onViewConflicts: () -> Unit) {
        val rawRemotePending = health.remoteNeedItems.coerceAtLeast(
            if (health.remoteCompletion < 100 || health.remoteNeedBytes > 0.0) 1 else 0,
        )
        val remotePending = if (health.remoteNeedKnown) health.remoteBlockingItems else rawRemotePending
        val healthy = health.state == "idle" && health.needTotalItems == 0L && health.conflicts == 0 &&
            health.error.isBlank() && health.pullErrors == 0L && remotePending == 0L && health.remoteState == "valid"
        Card(
            modifier = Modifier.fillMaxWidth().focusable(),
            border = BorderStroke(1.dp, if (healthy) stateSafeColor() else warningColor()),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(health.label, color = MaterialTheme.colorScheme.onSurface)
                        if (health.label != health.id) Text("Folder ID: ${health.id}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    val remaining = health.needTotalItems + remotePending
                    val label = when {
                        health.conflicts > 0 -> "⚠ ${health.conflicts} conflict(s)"
                        health.error.isNotBlank() || health.pullErrors > 0 -> "⚠ Folder error"
                        healthy -> "✓ Up to date"
                        else -> "$remaining items remaining"
                    }
                    Text(label, color = if (healthy) stateSafeColor() else warningColor())
                }
                if (health.error.isNotBlank()) Text(health.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (health.pullErrors > 0) Text("${health.pullErrors} Syncthing pull error(s)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (health.remoteIgnoredItems > 0) Text(
                    "${health.remoteIgnoredItems} intentionally ignored historical item(s) do not block SafeSync.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (health.conflictFiles.isNotEmpty()) {
                    OutlinedButton(onClick = onViewConflicts) { Text("VIEW CONFLICTS") }
                }
            }
        }
    }

    @Composable
    private fun ConflictListDialog(health: EsdeFolderHealth) {
        AlertDialog(
            onDismissRequest = { conflictFolder = null },
            title = { Text("SYNC CONFLICTS") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(health.label, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose which copy to keep. SafeSync creates private backups before changing any file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    health.conflictFiles.forEach { relativePath ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(relativePath, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                Text(conflictDescription(relativePath), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = {
                                        conflictFolder = null
                                        pendingConflictResolution = PendingConflictResolution(
                                            health.id,
                                            listOf(relativePath),
                                            EsdeConflictResolution.KEEP_CURRENT,
                                        )
                                    },
                                ) { Text(if (isGamelistConflict(relativePath)) "KEEP LOCAL GAMELIST" else "KEEP CURRENT") }
                                if (!isGamelistConflict(relativePath)) {
                                    OutlinedButton(
                                        onClick = {
                                            conflictFolder = null
                                            pendingConflictResolution = PendingConflictResolution(
                                                health.id,
                                                listOf(relativePath),
                                                EsdeConflictResolution.USE_CONFLICT_COPY,
                                            )
                                        },
                                    ) { Text("USE CONFLICT COPY") }
                                } else {
                                    Text(
                                        "gamelist.xml is never merged or replaced. Keep the local file and repair its ignore rule if necessary.",
                                        color = warningColor(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (health.conflictFiles.size > 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("BATCH ACTIONS", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text(
                            "Apply one decision to all ${health.conflictFiles.size} conflicts in this folder. All files are validated and backed up before changes begin.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                conflictFolder = null
                                pendingConflictResolution = PendingConflictResolution(
                                    health.id,
                                    health.conflictFiles,
                                    EsdeConflictResolution.KEEP_CURRENT,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                        ) { Text("KEEP CURRENT FOR ALL") }
                        Button(
                            onClick = {
                                conflictFolder = null
                                pendingConflictResolution = PendingConflictResolution(
                                    health.id,
                                    health.conflictFiles,
                                    EsdeConflictResolution.USE_CONFLICT_COPY,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                        ) { Text("USE CONFLICT COPY FOR ALL") }
                        if (health.conflictFiles.any(::isGamelistConflict)) {
                            Text(
                                "Safety exception: local gamelist.xml files are always kept, including in a batch.",
                                color = warningColor(),
                                fontStyle = FontStyle.Italic,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { conflictFolder = null }) { Text("CLOSE") } },
        )
    }

    @Composable
    private fun ConflictConfirmationDialog(pending: PendingConflictResolution) {
        val useConflict = pending.resolution == EsdeConflictResolution.USE_CONFLICT_COPY
        val batch = pending.relativePaths.size > 1
        AlertDialog(
            onDismissRequest = { pendingConflictResolution = null },
            title = { Text("CONFIRM CONFLICT RESOLUTION") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (batch) "${pending.relativePaths.size} conflict files"
                        else pending.relativePaths.single(),
                    )
                    Text(
                        if (useConflict && batch) {
                            "Every conflict copy will replace its current file after validation and backup. Local gamelist.xml files are the safety exception and remain unchanged."
                        } else if (useConflict) {
                            "The current file and conflict copy will be backed up. The conflict copy will then replace the current file."
                        } else if (batch) {
                            "Every conflict copy will be backed up and removed. All current local files will remain unchanged."
                        } else {
                            "The conflict copy will be backed up and removed. The current local file will remain unchanged."
                        },
                    )
                    Text("This action cannot be undone from Syncthing, but its private backup remains available.", fontStyle = FontStyle.Italic)
                }
            },
            confirmButton = {
                Button(
                    onClick = { resolveConflict(pending) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (useConflict) DANGER_RED else SAFE_GREEN,
                        contentColor = if (useConflict) Color.White else Color(0xFF10210E),
                    ),
                ) { Text(when {
                    useConflict && batch -> "USE FOR ALL"
                    useConflict -> "USE CONFLICT COPY"
                    batch -> "KEEP ALL CURRENT"
                    else -> "KEEP CURRENT"
                }) }
            },
            dismissButton = { TextButton(onClick = { pendingConflictResolution = null }) { Text("CANCEL") } },
        )
    }

    private fun isGamelistConflict(path: String): Boolean =
        path.substringAfterLast('/').substringAfterLast('\\').startsWith("gamelist.sync-conflict-", ignoreCase = true)

    private fun conflictDescription(path: String): String {
        val match = CONFLICT_MARKER.find(path) ?: return "Syncthing conflict copy"
        return "Created ${match.groupValues[1]} ${match.groupValues[2]} · device ${match.groupValues[3]}"
    }

    private fun folderDisplayName(folder: Folder): String {
        val name = folder.label.takeIf { it.isNotBlank() }
            ?: folder.path?.let { java.io.File(it).name }?.takeIf { it.isNotBlank() }
            ?: folder.id
        return folder.group.takeIf { it.isNotBlank() }?.let { "$it / $name" } ?: name
    }

    private fun currentInstruction(): String = when (state) {
        EsdeSyncState.READY_TO_PLAY -> "Synchronization is complete. Select START ES-DE to begin playing."
        EsdeSyncState.ESDE_RUNNING, EsdeSyncState.OFFLINE_PLAYING ->
            "After playing, close the emulator, return to ES-DE and press Home. Do not switch devices yet."
        EsdeSyncState.OFFLINE_CHANGES_PENDING ->
            "Your changes are safely stored on this device but have not reached the Primary Sync Device. Reconnect and select SYNC PENDING CHANGES."
        EsdeSyncState.RECONNECTING, EsdeSyncState.RECONCILING_OFFLINE_CHANGES ->
            "Keep this screen open while SafeSync verifies and reconciles the pending offline session."
        EsdeSyncState.EXPORTING_METADATA, EsdeSyncState.SYNCING_AFTER_PLAY ->
            "Keep this screen open while SafeSync publishes and synchronizes your changes."
        EsdeSyncState.SAFE_TO_SWITCH ->
            "ES-DE is closed and all changes are synchronized. Start a new session, close SafeSync, or switch devices."
        EsdeSyncState.IDLE ->
            "SafeSync is idle. ES-DE remains closed until you deliberately start a new synchronized session."
        EsdeSyncState.ERROR -> "Resolve the message below or retry. Do not continue on another handheld."
        EsdeSyncState.NOT_CONFIGURED -> "Complete First Setup before starting ES-DE with synchronized game data."
        else -> "Wait while SafeSync checks the primary device and prepares the latest game data."
    }

    private fun progressLabel(): String = when (state) {
        EsdeSyncState.READY_TO_PLAY -> "READY · START ES-DE"
        EsdeSyncState.ESDE_RUNNING, EsdeSyncState.OFFLINE_PLAYING -> "PLAYING · RETURN WITH HOME WHEN FINISHED"
        EsdeSyncState.OFFLINE_CHANGES_PENDING -> "SAVED LOCALLY · NOT SAFE TO SWITCH DEVICE"
        EsdeSyncState.RECONNECTING -> "RECONNECTING · PENDING CHANGES"
        EsdeSyncState.RECONCILING_OFFLINE_CHANGES -> "VERIFYING · PENDING CHANGES"
        EsdeSyncState.SAFE_TO_SWITCH -> "COMPLETE · SAFE TO SWITCH DEVICE"
        EsdeSyncState.IDLE -> "IDLE · SAFE TO SWITCH DEVICE"
        else -> stateLabel(state) + folderHealth.takeIf { it.isNotEmpty() }?.let {
            " · ${it.sumOf { folder -> folder.needTotalItems }} local items pending"
        }.orEmpty()
    }

    private fun stateLabel(value: EsdeSyncState): String = when (value) {
        EsdeSyncState.READY_TO_PLAY -> "SAFE TO PLAY"
        EsdeSyncState.LAUNCHING -> "VERIFYING LAUNCH"
        EsdeSyncState.SAFE_TO_SWITCH -> "SAFE TO SWITCH DEVICE"
        EsdeSyncState.IDLE -> "IDLE"
        EsdeSyncState.OFFLINE_PLAYING -> "OFFLINE SESSION"
        EsdeSyncState.OFFLINE_CHANGES_PENDING -> "OFFLINE CHANGES PENDING"
        EsdeSyncState.RECONNECTING -> "RECONNECTING"
        EsdeSyncState.RECONCILING_OFFLINE_CHANGES -> "RECONCILING OFFLINE CHANGES"
        EsdeSyncState.WAITING_FOR_PRIMARY -> "PRIMARY DEVICE UNAVAILABLE"
        EsdeSyncState.ERROR -> "ACTION REQUIRED"
        EsdeSyncState.NOT_CONFIGURED -> "SETUP REQUIRED"
        EsdeSyncState.SYNCING_AFTER_PLAY, EsdeSyncState.EXPORTING_METADATA -> "SYNCHRONIZING GAME DATA"
        else -> "SYNCHRONIZING…"
    }

    @Composable
    private fun stateColor(value: EsdeSyncState): Color = when (value) {
        EsdeSyncState.READY_TO_PLAY, EsdeSyncState.SAFE_TO_SWITCH, EsdeSyncState.IDLE -> stateSafeColor()
        EsdeSyncState.ERROR -> MaterialTheme.colorScheme.error
        else -> warningColor()
    }

    @Composable
    private fun stateSafeColor(): Color = if (isSystemInDarkTheme()) SAFE_GREEN else Color(0xFF397437)

    @Composable
    private fun warningColor(): Color = if (isSystemInDarkTheme()) Color(0xFFD8A657) else Color(0xFF795300)

    companion object {
        private val TRANSFER_STATES = setOf(EsdeSyncState.STARTING, EsdeSyncState.WAITING_FOR_PRIMARY,
            EsdeSyncState.RESCANNING, EsdeSyncState.SYNCING, EsdeSyncState.SYNCING_AFTER_PLAY,
            EsdeSyncState.RECONNECTING, EsdeSyncState.RECONCILING_OFFLINE_CHANGES)
        private fun formatRate(bytes: Double): String = when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB/s", bytes / (1024 * 1024))
            bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB/s", bytes / 1024)
            else -> "${bytes.toLong()} B/s"
        }
        private val SAFE_GREEN = Color(0xFF74BF6C)
        private val DANGER_RED = Color(0xFF9C001E)
        private val CONFLICT_MARKER = Regex("\\.sync-conflict-(\\d{8})-(\\d{6})-([A-Za-z0-9]+)")
        private const val RETURN_FLUSH_MS = 1000L
        private const val SETTINGS_RETURN_DELAY_MS = 700L
        private const val POLL_MS = 1500L
        private const val BOOTSTRAP_DISCOVERY_ATTEMPTS = 4
    }

    private data class PendingConflictResolution(
        val folderId: String,
        val relativePaths: List<String>,
        val resolution: EsdeConflictResolution,
    )

    private data class RefreshedConflicts(
        val folderId: String,
        val existing: List<String>,
        val removed: Int,
    )

}
