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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import java.util.concurrent.ConcurrentHashMap
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
    private var bootstrapDiscoveryAttempts = 0
    private var pollStartedAt = 0L
    private val freshFolderStatus = ConcurrentHashMap<String, FolderStatus>()
    private val freshRemoteCompletion = ConcurrentHashMap<String, CompletionInfo>()

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
        if (settings.usesLegacyGamelistLocation() && !legacyConfigurationChecked) {
            val coordinator = service?.esdeSyncCoordinator
            if (coordinator == null) {
                state = EsdeSyncState.ERROR
                statusDetail = "Metadata bridge is not available."
                return
            }
            state = EsdeSyncState.STARTING
            statusDetail = "Checking ES-DE ROM gamelist configuration…"
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
                statusDetail = "Applying synchronized ES-DE metadata…"
                service?.esdeSyncCoordinator?.importNow(finalizeBootstrap = !settings.bootstrapComplete) {
                    state = EsdeSyncState.READY_TO_PLAY
                    statusDetail = "Everything is synchronized."
                } ?: run {
                    state = EsdeSyncState.ERROR
                    statusDetail = "Metadata bridge is not available."
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
            api.getFreshFolderStatus(id, { status -> freshFolderStatus[id] = status; done() }, { done() })
            api.getFreshFolderCompletion(id, settings.primaryDeviceId,
                { completion -> freshRemoteCompletion[id] = completion; done() }, { done() })
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
            )
            val statusPair = api.getFolderStatus(id)
            val status = freshFolderStatus[id] ?: statusPair.key
            val cache = statusPair.value
            val remote = freshRemoteCompletion[id]
            EsdeFolderHealth(
                id = id,
                paused = folder.paused || cache.paused,
                state = status.state ?: "unknown",
                error = listOf(status.error, status.invalid, status.watchError).firstOrNull { !it.isNullOrBlank() } ?: "",
                needFiles = status.needFiles,
                needBytes = status.needBytes,
                needTotalItems = status.needTotalItems,
                pullErrors = status.pullErrors,
                remoteCompletion = remote?.completion?.toInt() ?: api.getRemoteDeviceCompletion(settings.primaryDeviceId),
                remoteNeedBytes = remote?.needBytes ?: api.getRemoteDeviceNeedBytes(settings.primaryDeviceId),
                conflicts = cache.discoveredConflictFiles?.size ?: 0,
                remoteNeedItems = remote?.needItems?.toLong() ?: 0,
                remoteState = remote?.remoteState ?: "unknown",
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
            state = EsdeSyncState.SYNCING_AFTER_PLAY
            statusDetail = "Synchronizing game data after play…"
            api?.let { rest -> settings.selectedFolderIds.forEach { rest.rescanFolder(it) } }
            preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_POST_SYNC, System.currentTimeMillis()).apply()
            pollStartedAt = System.currentTimeMillis()
            handler.postDelayed(::pollPostSync, INITIAL_SCAN_DELAY_MS)
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
            return "Choose the initial metadata source. On the first device, select ‘Use this device as initial metadata source’."
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
        if (isFinishing && settings.activeSessionId.isNotBlank()) {
            restoreForceState()
            settings.clearSession()
        }
        super.onDestroy()
    }

    @Composable
    private fun SafeLaunchScreen() {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF160B0E)).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("SYNCTHING ES-DE SAFE SYNC", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFF3EEE9), fontWeight = FontWeight.Bold)
            Text(stateLabel(state), color = stateColor(state), style = MaterialTheme.typography.titleLarge)
            Text(statusDetail, color = Color(0xFFCAC5C0), style = MaterialTheme.typography.bodyLarge)
            folderHealth.forEach { health -> FolderCard(health) }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    EsdeSyncState.READY_TO_PLAY -> Button(onClick = { launchEsde(false) }) { Text("START ES-DE") }
                    EsdeSyncState.NOT_CONFIGURED -> {
                        if (settings.missingSafeLaunchRequirements() == setOf(EsdeSetupRequirement.INITIAL_METADATA_SOURCE)) {
                            Button(onClick = ::initializeFromThisDevice) { Text("USE THIS DEVICE AS INITIAL SOURCE") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = ::openSettings) { Text("OPEN SETTINGS") }
                            OutlinedButton(onClick = { launchEsde(true) }) { Text("START WITHOUT SYNC") }
                        }
                    }
                    EsdeSyncState.SAFE_TO_SWITCH -> {
                        Button(onClick = ::finishSession) { Text("DONE") }
                        OutlinedButton(onClick = ::startAgain) { Text("START ES-DE AGAIN") }
                    }
                    else -> {
                        OutlinedButton(onClick = ::retry) { Text("RETRY") }
                        OutlinedButton(onClick = { launchEsde(true) }) { Text("START WITHOUT SYNC") }
                    }
                }
            }
            if (state != EsdeSyncState.READY_TO_PLAY && state != EsdeSyncState.SAFE_TO_SWITCH) {
                Text(
                    "Local changes will be kept. Fully synchronize this device before continuing on another handheld.",
                    color = Color(0xFFFFC46B),
                )
            }
        }
    }

    @Composable
    private fun FolderCard(health: EsdeFolderHealth) {
        val healthy = health.state == "idle" && health.needTotalItems == 0L && health.conflicts == 0
        Card(
            modifier = Modifier.fillMaxWidth().focusable(),
            border = BorderStroke(1.dp, if (healthy) Color(0xFF4CAF78) else Color(0xFFFFC46B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1118)),
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(health.id, color = Color.White)
                Text(if (healthy) "✓ Up to date" else "${health.needTotalItems} items remaining", color = if (healthy) Color(0xFF72D69A) else Color(0xFFFFC46B))
            }
        }
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
        EsdeSyncState.READY_TO_PLAY, EsdeSyncState.SAFE_TO_SWITCH -> Color(0xFF72D69A)
        EsdeSyncState.ERROR -> Color(0xFFFF6B6B)
        else -> Color(0xFFFFC46B)
    }

    companion object {
        private const val POLL_MS = 1_500L
        private const val INITIAL_SCAN_DELAY_MS = 1_500L
        private const val RETURN_FLUSH_MS = 1_000L
        private const val SETTINGS_RETURN_DELAY_MS = 250L
        private const val SYNC_TIMEOUT_MS = 90_000L
        private const val BOOTSTRAP_DISCOVERY_ATTEMPTS = 2
    }
}
