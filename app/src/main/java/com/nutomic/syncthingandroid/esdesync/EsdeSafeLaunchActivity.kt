package com.nutomic.syncthingandroid.esdesync

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
    private var state by mutableStateOf(EsdeSyncState.STARTING)
    private var statusDetail by mutableStateOf("")
    private var folderHealth by mutableStateOf<List<EsdeFolderHealth>>(emptyList())
    private var preSyncStarted = false
    private var postSyncStarted = false
    private var legacyConfigurationChecked = false
    private var sharedWarning by mutableStateOf("")
    private var bootstrapDiscoveryAttempts = 0
    private var pollStartedAt = 0L
    private val freshFolderStatus = ConcurrentHashMap<String, FolderStatus>()
    private val freshRemoteCompletion = ConcurrentHashMap<String, CompletionInfo>()
    private val freshRemoteNeed = ConcurrentHashMap<String, RemoteNeed>()
    private val freshRemoteNeedFetchedAt = ConcurrentHashMap<String, Long>()
    private val conflictExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ESDESync-ConflictResolver")
    }
    private var conflictFolder by mutableStateOf<EsdeFolderHealth?>(null)
    private var pendingConflictResolution by mutableStateOf<PendingConflictResolution?>(null)
    private var conflictFeedback by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (settings.activeSessionId.isBlank()) {
            val previous = preferences.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP,
            )
            settings.beginSession(previous)
            preferences.edit().putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_START).apply()
        }
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        setContent { ApplicationTheme { SafeLaunchScreen() } }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        super.onServiceConnected(name, binder)
        if (!preSyncStarted && !settings.esdeWasLaunched) beginPreSync()
    }

    override fun onResume() {
        super.onResume()
        if (settings.esdeWasLaunched && settings.launchTimestamp > 0 && !postSyncStarted) {
            postSyncStarted = true
            handler.postDelayed({ beginPostSync() }, RETURN_FLUSH_MS)
        } else if (!settings.esdeWasLaunched && state == EsdeSyncState.NOT_CONFIGURED && preSyncStarted) {
            handler.postDelayed({ if (!isFinishing) retry() }, SETTINGS_RETURN_DELAY_MS)
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
            coordinator.ensureLegacyGamelistLocation { success, message ->
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
        settings.selectedFolderIds.forEach { api.rescanFolder(it) }
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_PRE_SYNC, System.currentTimeMillis()).apply()
        pollStartedAt = System.currentTimeMillis()
        handler.postDelayed(::pollPreSync, INITIAL_SCAN_DELAY_MS)
    }

    private fun beginPreSyncRetry() {
        preSyncStarted = false
        beginPreSync()
    }

    private fun pollPreSync() {
        refreshFreshGateData {
            val evaluated = evaluateGate()
            state = evaluated
            if (evaluated == EsdeSyncState.READY_TO_PLAY) {
                state = EsdeSyncState.IMPORTING_METADATA
                statusDetail = "Applying Shared Collections and ES-DE settings…"
                val coordinator = service?.esdeSyncCoordinator
                if (coordinator == null) {
                    state = EsdeSyncState.ERROR
                    statusDetail = "Metadata bridge is not available."
                } else coordinator.importSharedStateBeforeLaunch { shared ->
                    if (!shared.successful) {
                        state = EsdeSyncState.ERROR
                        statusDetail = shared.errorSummary()
                    } else {
                        sharedWarning = (shared.collections.warnings + shared.settings.warnings).joinToString("; ")
                        statusDetail = "Applying synchronized per-game metadata…"
                        coordinator.importNow(finalizeBootstrap = !settings.bootstrapComplete) { metadata ->
                            if (metadata.invalid > 0) {
                                state = EsdeSyncState.ERROR
                                statusDetail = "Per-game metadata contains ${metadata.invalid} invalid sidecar(s)."
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
            if (System.currentTimeMillis() - pollStartedAt > SYNC_TIMEOUT_MS) {
                statusDetail = "Synchronization is taking longer than expected. You can retry or start without sync."
                return@refreshFreshGateData
            }
            handler.postDelayed(::pollPreSync, POLL_MS)
        }
    }

    private fun refreshFreshGateData(onComplete: () -> Unit) {
        val api = api ?: run { onComplete(); return }
        val ids = settings.selectedFolderIds
        if (ids.isEmpty()) { onComplete(); return }
        val remaining = AtomicInteger(ids.size * 2)
        fun done() { if (remaining.decrementAndGet() == 0) handler.post(onComplete) }
        ids.forEach { id ->
            api.getFreshFolderStatus(id, { status -> freshFolderStatus[id] = status; done() }, {
                freshFolderStatus.remove(id)
                done()
            })
            api.getFreshFolderCompletion(id, settings.primaryDeviceId,
                { completion ->
                    freshRemoteCompletion[id] = completion
                    val rawRemotePending = completion.completion < 100 || completion.needBytes > 0.0 ||
                        completion.needItems > 0
                    if (!rawRemotePending) {
                        freshRemoteNeed[id] = RemoteNeed()
                        freshRemoteNeedFetchedAt[id] = System.currentTimeMillis()
                        done()
                    } else {
                        val lastFetch = freshRemoteNeedFetchedAt[id] ?: 0L
                        if (freshRemoteNeed.containsKey(id) &&
                            System.currentTimeMillis() - lastFetch < REMOTE_NEED_REFRESH_MS
                        ) {
                            done()
                        } else {
                            api.getFreshRemoteNeed(id, settings.primaryDeviceId,
                                { need ->
                                    freshRemoteNeed[id] = need
                                    freshRemoteNeedFetchedAt[id] = System.currentTimeMillis()
                                    done()
                                },
                                {
                                    freshRemoteNeed.remove(id)
                                    freshRemoteNeedFetchedAt.remove(id)
                                    done()
                                })
                        }
                    }
                },
                {
                    freshRemoteCompletion.remove(id)
                    freshRemoteNeed.remove(id)
                    freshRemoteNeedFetchedAt.remove(id)
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
        folderHealth = settings.selectedFolderIds.map { id ->
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
        val next = EsdeSyncStateEvaluator.evaluate(
            EsdeGateInput(
                configured = settings.isSafeLaunchConfigured(),
                serviceActive = currentService.currentState == SyncthingService.State.ACTIVE,
                primaryConnected = primary.connected,
                primaryPaused = primary.paused,
                folders = folderHealth,
            )
        )
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
        val launchIntent = packageManager.getLaunchIntentForPackage(settings.applicationPackage)
        if (launchIntent == null) {
            state = EsdeSyncState.ERROR
            statusDetail = "The selected ES-DE application is not installed."
            return
        }
        settings.offlineOverrideUsed = offline
        settings.esdeWasLaunched = true
        settings.launchTimestamp = System.currentTimeMillis()
        postSyncStarted = false
        if (offline) {
            settings.pendingLocalChanges = true
            state = EsdeSyncState.OFFLINE_OVERRIDE
        } else {
            state = EsdeSyncState.ESDE_RUNNING
        }
        startActivity(launchIntent)
    }

    private fun beginPostSync() {
        state = EsdeSyncState.EXPORTING_METADATA
        statusDetail = "Reading final ES-DE metadata…"
        val coordinator = service?.esdeSyncCoordinator
        if (coordinator == null) {
            state = EsdeSyncState.ERROR
            statusDetail = "Local changes are waiting for synchronization."
            settings.pendingLocalChanges = true
            return
        }
        coordinator.exportNow { _ ->
            statusDetail = "Publishing selected Shared Collections and ES-DE settings…"
            coordinator.publishSharedState { shared ->
                if (!shared.successful) {
                    state = EsdeSyncState.ERROR
                    settings.pendingLocalChanges = true
                    statusDetail = shared.errorSummary()
                } else {
                    state = EsdeSyncState.SYNCING_AFTER_PLAY
                    statusDetail = "Synchronizing game data after play…"
                    api?.let { rest -> settings.selectedFolderIds.forEach { rest.rescanFolder(it) } }
                    preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_POST_SYNC, System.currentTimeMillis()).apply()
                    pollStartedAt = System.currentTimeMillis()
                    handler.postDelayed(::pollPostSync, INITIAL_SCAN_DELAY_MS)
                }
            }
        }
    }

    private fun pollPostSync() {
        refreshFreshGateData { when (evaluateGate()) {
            EsdeSyncState.READY_TO_PLAY -> {
                state = EsdeSyncState.SAFE_TO_SWITCH
                statusDetail = "Everything is synchronized. Safe to switch device."
                settings.pendingLocalChanges = false
                preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_SUCCESSFUL_SYNC, System.currentTimeMillis()).apply()
                restoreForceState()
            }
            else -> {
                state = EsdeSyncState.SYNCING_AFTER_PLAY
                if (System.currentTimeMillis() - pollStartedAt <= SYNC_TIMEOUT_MS) {
                    handler.postDelayed(::pollPostSync, POLL_MS)
                } else {
                    state = EsdeSyncState.ERROR
                    settings.pendingLocalChanges = true
                    statusDetail = "Local changes are waiting for synchronization."
                }
            }
        } }
    }

    private fun restoreForceState() {
        preferences.edit().putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, settings.previousForceState).apply()
        service?.evaluateRunConditions()
    }

    private fun finishSession() {
        restoreForceState()
        settings.clearSession()
        finish()
    }

    private fun startAgain() {
        restoreForceState()
        settings.clearSession()
        startActivity(Intent(this, EsdeSafeLaunchActivity::class.java))
        finish()
    }

    private fun retry() {
        handler.removeCallbacksAndMessages(null)
        preSyncStarted = false
        postSyncStarted = false
        legacyConfigurationChecked = false
        bootstrapDiscoveryAttempts = 0
        settings.esdeWasLaunched = false
        beginPreSync()
    }

    private fun resolveConflict(pending: PendingConflictResolution) {
        val folder = api?.folders?.firstOrNull { it.id == pending.folderId }
        val path = folder?.path
        if (path.isNullOrBlank()) {
            conflictFeedback = "The selected folder path is unavailable."
            pendingConflictResolution = null
            return
        }
        conflictFeedback = "Resolving ${pending.relativePath}…"
        conflictExecutor.execute {
            val result = runCatching {
                EsdeConflictResolver(File(filesDir, "esde-sync/backups")).resolve(
                    File(path),
                    pending.relativePath,
                    pending.resolution,
                )
            }
            handler.post {
                if (isFinishing) return@post
                pendingConflictResolution = null
                result.onSuccess {
                    api?.getFolderStatus(pending.folderId)?.value?.let { cache ->
                        cache.discoveredConflictFiles = cache.discoveredConflictFiles
                            .filterNot { it == pending.relativePath }
                            .toTypedArray()
                    }
                    conflictFolder = null
                    conflictFeedback = "Conflict resolved. Both versions were backed up privately."
                    retry()
                }.onFailure { error ->
                    conflictFeedback = "Conflict could not be resolved: ${error.message ?: "unknown error"}"
                }
            }
        }
    }

    private fun openSyncthing() {
        startActivity(Intent(this, MainActivity::class.java))
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
                EsdeSetupRequirement.INITIAL_METADATA_SOURCE -> null
            }
        }
        return "Setup is incomplete. Missing: ${labels.joinToString()}."
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_START_DESTINATION, "Gaming"))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        conflictExecutor.shutdownNow()
        if (isFinishing && settings.activeSessionId.isNotBlank()) {
            restoreForceState()
            settings.clearSession()
        }
        super.onDestroy()
    }

    @Composable
    private fun SafeLaunchScreen() {
        Column(
            modifier = Modifier.fillMaxSize().background(ESDE_BACKGROUND).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ESDE_PANEL),
                border = BorderStroke(1.dp, Color(0xFF444444)),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SYNCTHING ES-DE SAFE SYNC",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color(0xFFF2F2F2),
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider(color = Color(0xFF3C3C3C))
                    Text(stateLabel(state), color = stateColor(state), style = MaterialTheme.typography.titleLarge)
                    Text(statusDetail, color = Color(0xFFCACACA), style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { sessionProgress() },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = stateColor(state),
                        trackColor = Color(0xFF454545),
                    )
                    Text(progressLabel(), color = Color(0xFF9C9C9C), style = MaterialTheme.typography.bodySmall)
                }
            }
            InstructionCard()
            folderHealth.forEach { health -> FolderCard(health) { conflictFolder = health } }
            if (conflictFeedback.isNotBlank()) {
                Text(conflictFeedback, color = Color(0xFFD8A657), style = MaterialTheme.typography.bodyMedium)
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
                            ) { Text("START WITHOUT SYNC") }
                        }
                    }
                    EsdeSyncState.SAFE_TO_SWITCH -> {
                        Button(
                            onClick = ::finishSession,
                            colors = ButtonDefaults.buttonColors(containerColor = SAFE_GREEN, contentColor = Color(0xFF10210E)),
                        ) { Text("DONE") }
                        OutlinedButton(onClick = ::startAgain) { Text("START ES-DE AGAIN") }
                    }
                    EsdeSyncState.ERROR -> {
                        OutlinedButton(onClick = ::retry) { Text("RETRY") }
                        OutlinedButton(onClick = ::openSyncthing) { Text("OPEN SYNCTHING") }
                        Button(
                            onClick = { launchEsde(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                        ) { Text("START WITHOUT SYNC") }
                    }
                    EsdeSyncState.STARTING, EsdeSyncState.WAITING_FOR_PRIMARY, EsdeSyncState.RESCANNING,
                    EsdeSyncState.SYNCING, EsdeSyncState.IMPORTING_METADATA -> {
                        OutlinedButton(onClick = ::retry) { Text("RETRY") }
                        Button(
                            onClick = { launchEsde(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = DANGER_RED, contentColor = Color.White),
                        ) { Text("START WITHOUT SYNC") }
                    }
                    else -> Unit
                }
            }
            if (state != EsdeSyncState.READY_TO_PLAY && state != EsdeSyncState.SAFE_TO_SWITCH) {
                Text(
                    "Local changes will be kept. Fully synchronize this device before continuing on another handheld.",
                    color = Color(0xFFD8A657),
                )
            }
            HorizontalDivider(color = Color(0xFF454545))
            Text(
                "A  SELECT     B  BACK     HOME  RETURN TO SAFE SYNC",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color(0xFFB8B8B8),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        conflictFolder?.let { ConflictListDialog(it) }
        pendingConflictResolution?.let { ConflictConfirmationDialog(it) }
    }

    @Composable
    private fun InstructionCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = ESDE_PANEL),
            border = BorderStroke(2.dp, Color(0xFF9C001E)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT TO DO", color = Color.White, fontWeight = FontWeight.Bold)
                Text(currentInstruction(), color = Color(0xFFF0F0F0), style = MaterialTheme.typography.bodyLarge)
                InstructionStep(1, "Start ES-DE from this screen.")
                InstructionStep(2, "Play, then close the emulator and return to ES-DE.")
                InstructionStep(3, "Press Home in ES-DE to return to SafeSync.")
                InstructionStep(4, "Keep SafeSync open until SAFE TO SWITCH DEVICE appears.")
            }
        }
    }

    @Composable
    private fun InstructionStep(number: Int, instruction: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text("$number.", color = SAFE_GREEN, fontWeight = FontWeight.Bold)
            Text(instruction, color = Color(0xFFAFAFAF), style = MaterialTheme.typography.bodyMedium)
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
            border = BorderStroke(1.dp, if (healthy) Color(0xFF74BF6C) else Color(0xFFD8A657)),
            colors = CardDefaults.cardColors(containerColor = ESDE_PANEL),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(health.label, color = Color.White)
                        if (health.label != health.id) Text("Folder ID: ${health.id}", color = Color(0xFF858585), style = MaterialTheme.typography.bodySmall)
                    }
                    val remaining = health.needTotalItems + remotePending
                    val label = when {
                        health.conflicts > 0 -> "⚠ ${health.conflicts} conflict(s)"
                        health.error.isNotBlank() || health.pullErrors > 0 -> "⚠ Folder error"
                        healthy -> "✓ Up to date"
                        else -> "$remaining items remaining"
                    }
                    Text(label, color = if (healthy) SAFE_GREEN else Color(0xFFD8A657))
                }
                if (health.error.isNotBlank()) Text(health.error, color = Color(0xFFD96B68), style = MaterialTheme.typography.bodySmall)
                if (health.pullErrors > 0) Text("${health.pullErrors} Syncthing pull error(s)", color = Color(0xFFD96B68), style = MaterialTheme.typography.bodySmall)
                if (health.remoteIgnoredItems > 0) Text(
                    "${health.remoteIgnoredItems} intentionally ignored historical item(s) do not block SafeSync.",
                    color = Color(0xFF9C9C9C),
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
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242424))) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(relativePath, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                Text(conflictDescription(relativePath), color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = {
                                        conflictFolder = null
                                        pendingConflictResolution = PendingConflictResolution(
                                            health.id,
                                            relativePath,
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
                                                relativePath,
                                                EsdeConflictResolution.USE_CONFLICT_COPY,
                                            )
                                        },
                                    ) { Text("USE CONFLICT COPY") }
                                } else {
                                    Text(
                                        "gamelist.xml is never merged or replaced. Keep the local file and repair its ignore rule if necessary.",
                                        color = Color(0xFFD8A657),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
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
        AlertDialog(
            onDismissRequest = { pendingConflictResolution = null },
            title = { Text("CONFIRM CONFLICT RESOLUTION") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pending.relativePath)
                    Text(
                        if (useConflict) {
                            "The current file and conflict copy will be backed up. The conflict copy will then replace the current file."
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
                ) { Text(if (useConflict) "USE CONFLICT COPY" else "KEEP CURRENT") }
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
        EsdeSyncState.ESDE_RUNNING, EsdeSyncState.OFFLINE_OVERRIDE ->
            "After playing, close the emulator, return to ES-DE and press Home. Do not switch devices yet."
        EsdeSyncState.EXPORTING_METADATA, EsdeSyncState.SYNCING_AFTER_PLAY ->
            "Keep this screen open while SafeSync publishes and synchronizes your changes."
        EsdeSyncState.SAFE_TO_SWITCH -> "All changes are synchronized. You may now switch devices or power this one off."
        EsdeSyncState.ERROR -> "Resolve the message below or retry. Do not continue on another handheld."
        EsdeSyncState.NOT_CONFIGURED -> "Complete First Setup before starting ES-DE with synchronized game data."
        else -> "Wait while SafeSync checks the primary device and prepares the latest game data."
    }

    private fun progressLabel(): String = when (state) {
        EsdeSyncState.READY_TO_PLAY -> "READY · START ES-DE"
        EsdeSyncState.ESDE_RUNNING, EsdeSyncState.OFFLINE_OVERRIDE -> "PLAYING · RETURN WITH HOME WHEN FINISHED"
        EsdeSyncState.SAFE_TO_SWITCH -> "COMPLETE · SAFE TO SWITCH DEVICE"
        else -> "${(sessionProgress() * 100).toInt()}% · ${stateLabel(state)}"
    }

    private fun sessionProgress(): Float {
        val folderCompletion = folderHealth.map { it.remoteCompletion.coerceIn(0, 100) }.average()
            .takeUnless { it.isNaN() }?.div(100.0)?.toFloat() ?: 0f
        return when (state) {
            EsdeSyncState.NOT_CONFIGURED, EsdeSyncState.ERROR -> 0f
            EsdeSyncState.STARTING, EsdeSyncState.WAITING_FOR_PRIMARY -> 0.08f
            EsdeSyncState.RESCANNING -> 0.14f
            EsdeSyncState.SYNCING -> 0.14f + folderCompletion * 0.26f
            EsdeSyncState.IMPORTING_METADATA -> 0.45f
            EsdeSyncState.READY_TO_PLAY -> 0.5f
            EsdeSyncState.OFFLINE_OVERRIDE, EsdeSyncState.ESDE_RUNNING -> 0.55f
            EsdeSyncState.EXPORTING_METADATA -> 0.68f
            EsdeSyncState.SYNCING_AFTER_PLAY -> 0.72f + folderCompletion * 0.27f
            EsdeSyncState.SAFE_TO_SWITCH -> 1f
        }.coerceIn(0f, 1f)
    }

    private fun stateLabel(value: EsdeSyncState): String = when (value) {
        EsdeSyncState.READY_TO_PLAY -> "SAFE TO PLAY"
        EsdeSyncState.SAFE_TO_SWITCH -> "SAFE TO SWITCH DEVICE"
        EsdeSyncState.WAITING_FOR_PRIMARY -> "PRIMARY DEVICE UNAVAILABLE"
        EsdeSyncState.ERROR -> "ACTION REQUIRED"
        EsdeSyncState.NOT_CONFIGURED -> "SETUP REQUIRED"
        EsdeSyncState.SYNCING_AFTER_PLAY, EsdeSyncState.EXPORTING_METADATA -> "SYNCHRONIZING GAME DATA"
        else -> "SYNCHRONIZING…"
    }

    private fun stateColor(value: EsdeSyncState): Color = when (value) {
        EsdeSyncState.READY_TO_PLAY, EsdeSyncState.SAFE_TO_SWITCH -> Color(0xFF74BF6C)
        EsdeSyncState.ERROR -> Color(0xFFD96B68)
        else -> Color(0xFFD8A657)
    }

    companion object {
        private val ESDE_BACKGROUND = Color(0xFF2B2B2B)
        private val ESDE_PANEL = Color(0xFF151515)
        private val SAFE_GREEN = Color(0xFF74BF6C)
        private val DANGER_RED = Color(0xFF9C001E)
        private val CONFLICT_MARKER = Regex("\\.sync-conflict-(\\d{8})-(\\d{6})-([A-Za-z0-9]+)")
        private const val RETURN_FLUSH_MS = 1000L
        private const val SETTINGS_RETURN_DELAY_MS = 700L
        private const val INITIAL_SCAN_DELAY_MS = 1000L
        private const val REMOTE_NEED_REFRESH_MS = 5000L
        private const val POLL_MS = 1500L
        private const val SYNC_TIMEOUT_MS = 120_000L
        private const val BOOTSTRAP_DISCOVERY_ATTEMPTS = 4
    }

    private data class PendingConflictResolution(
        val folderId: String,
        val relativePath: String,
        val resolution: EsdeConflictResolution,
    )

}
