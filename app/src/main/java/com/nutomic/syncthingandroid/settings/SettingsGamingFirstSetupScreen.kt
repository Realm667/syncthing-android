package com.nutomic.syncthingandroid.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.esdesync.EsdeIgnoreRuleManager
import com.nutomic.syncthingandroid.esdesync.EsdeFirstSetupPolicy
import com.nutomic.syncthingandroid.esdesync.EsdeSafeLaunchActivity
import com.nutomic.syncthingandroid.esdesync.EsdeSharedSettingsCatalog
import com.nutomic.syncthingandroid.esdesync.EsdeSyncSettings
import com.nutomic.syncthingandroid.service.SyncthingService
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import java.io.File

fun EntryProviderScope<SettingsRoute>.settingsGamingFirstSetupEntry() {
    entry<SettingsRoute.GamingFirstSetup> { SettingsGamingFirstSetupScreen() }
}

@Composable
fun SettingsGamingFirstSetupScreen() {
    val context = LocalContext.current
    val navigator = LocalSettingsNavigator.current
    val service = LocalSyncthingService.current
    val serviceUpdateTick = LocalServiceUpdateTick.current
    val api = service?.api
    val preferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val settings = remember { EsdeSyncSettings(preferences) }
    val collectionsEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_COLLECTIONS_ENABLED, false)
    val sharedSettingsEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_SETTINGS_ENABLED, false)
    var step by rememberSaveable {
        mutableStateOf(settings.firstSetupStep.coerceIn(0, SETUP_STEPS.lastIndex))
    }
    var directory by remember { mutableStateOf(settings.esdeDirectory) }
    var gamelistDirectory by remember { mutableStateOf(settings.gamelistDirectory) }
    var applicationPackage by remember { mutableStateOf(settings.applicationPackage) }
    var primaryDevice by remember { mutableStateOf(settings.primaryDeviceId) }
    var selectedFolders by remember { mutableStateOf(settings.selectedFolderIds) }
    var role by remember { mutableStateOf(settings.firstSetupRole) }
    var sourceInitialized by remember { mutableStateOf(settings.bootstrapComplete) }
    var feedback by remember { mutableStateOf("") }
    var showDevices by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings.firstSetupOffered = true
        settings.enabled = true
        settings.acquireFirstSetupServiceLease()
        val serviceIntent = Intent(context, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
    LaunchedEffect(service, serviceUpdateTick) {
        service?.evaluateRunConditions()
    }

    fun selectStep(value: Int) {
        step = value.coerceIn(0, SETUP_STEPS.lastIndex)
        settings.firstSetupStep = step
    }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.takeIf { result.resultCode == Activity.RESULT_OK }?.let {
            directory = it
            settings.esdeDirectory = it
            if (!settings.hasExplicitGamelistDirectory) {
                gamelistDirectory = File(it, "gamelists").path
                settings.gamelistDirectory = gamelistDirectory
            }
            settings.bootstrapComplete = false
            settings.bootstrapPendingImport = false
            sourceInitialized = false
        }
    }
    val gamelistPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.takeIf { result.resultCode == Activity.RESULT_OK }?.let {
            gamelistDirectory = it
            settings.gamelistDirectory = it
            settings.bootstrapComplete = false
            settings.bootstrapPendingImport = false
            sourceInitialized = false
        }
    }
    val appPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val component: ComponentName? = result.data?.component
        if (result.resultCode == Activity.RESULT_OK && component != null && component.packageName != context.packageName) {
            applicationPackage = component.packageName
            settings.applicationPackage = component.packageName
        }
    }

    fun validateAndApply() {
        settings.enabled = true
        feedback = "Checking required ES-DE settings and ignore rules…"
        service?.esdeSyncCoordinator?.ensureLegacyGamelistLocation { success, message ->
            if (!success) {
                feedback = "ES-DE configuration failed: $message"
                return@ensureLegacyGamelistLocation
            }
            if (api == null || selectedFolders.isEmpty()) {
                feedback = "$message. Syncthing is not ready to check folder protection yet."
                return@ensureLegacyGamelistLocation
            }
            EsdeIgnoreRuleManager(api).ensure(selectedFolders) { result ->
                (context as Activity).runOnUiThread {
                    feedback = if (result.checked == 0) {
                        "$message. No selected Master / Roms folder was found; no ignore list was changed."
                    } else {
                        "$message. Protected Master / Roms; updated ${result.updated}." +
                            if (result.conflicting > 0) " Its ignore list needs manual review." else ""
                    }
                }
            }
        } ?: run { feedback = "Syncthing is still starting. Retry in a moment." }
    }

    fun finishSetup() {
        settings.enabled = true
        feedback = "Applying final safety checks…"
        val coordinator = service?.esdeSyncCoordinator ?: run {
            feedback = "Syncthing is still starting. Retry in a moment."
            return
        }
        val currentApi = api ?: run {
            feedback = "Syncthing is still starting. Retry in a moment."
            return
        }
        coordinator.ensureLegacyGamelistLocation { success, message ->
            if (!success) {
                selectStep(4)
                feedback = "ES-DE configuration failed: $message"
                return@ensureLegacyGamelistLocation
            }
            EsdeIgnoreRuleManager(currentApi).ensure(selectedFolders) { result ->
                (context as Activity).runOnUiThread {
                    if (result.conflicting > 0) {
                        selectStep(4)
                        feedback = "${result.conflicting} folder(s) contain a conflicting gamelist.xml include rule. Review their ignore lists before finishing."
                    } else {
                        settings.firstSetupComplete = true
                        settings.firstSetupStep = 0
                        settings.releaseFirstSetupServiceLease()
                        service.evaluateRunConditions()
                        context.startActivity(Intent(context, EsdeSafeLaunchActivity::class.java))
                        navigator.navigateUp()
                    }
                }
            }
        }
    }

    val coreComplete = directory.isNotBlank() && gamelistDirectory.isNotBlank() && applicationPackage.isNotBlank() &&
        primaryDevice.isNotBlank() && selectedFolders.isNotEmpty()
    val syncTargetsReady = EsdeFirstSetupPolicy.canChooseSyncTargets(api != null)
    val canFinish = EsdeFirstSetupPolicy.canFinish(
        coreComplete = coreComplete,
        apiReady = api != null,
        coordinatorReady = service?.esdeSyncCoordinator != null,
        role = role,
        sourceInitialized = sourceInitialized,
    )

    SettingsScaffold(
        title = "ES-DE Gaming Sync · First Setup",
        description = "Step ${step + 1} of ${SETUP_STEPS.size} · ${SETUP_STEPS[step]}",
    ) {
        item {
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / SETUP_STEPS.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        when (step) {
            0 -> {
                item { SetupHeading("Choose this device's role") }
                item {
                    Preference(
                        title = { Text("New handheld · receive existing data") },
                        summary = { Text("Recommended when the NAS or another device already contains the authoritative sidecars and shared settings. Fresh defaults will never be published first.") },
                        icon = { RadioButton(selected = role == EsdeSyncSettings.ROLE_RECEIVER, onClick = null) },
                        onClick = { role = EsdeSyncSettings.ROLE_RECEIVER; settings.firstSetupRole = role },
                    )
                }
                item {
                    Preference(
                        title = { Text("This device is the initial metadata source") },
                        summary = { Text("Use only when no synchronized sidecars exist anywhere and this device's gamelist.xml files are authoritative.") },
                        icon = { RadioButton(selected = role == EsdeSyncSettings.ROLE_SOURCE, onClick = null) },
                        onClick = { role = EsdeSyncSettings.ROLE_SOURCE; settings.firstSetupRole = role },
                    )
                }
                item { Preference(title = { Text("Data safety") }, summary = { Text("gamelist.xml always remains local. New receiving devices import before they are allowed to publish settings or metadata.") }) }
            }
            1 -> {
                item { SetupHeading("ES-DE and gamelist locations") }
                item { Preference(title = { Text("ES-DE application") }, summary = { Text(applicationPackage.ifBlank { "Not selected" }) }, onClick = {
                    val target = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    appPicker.launch(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, target))
                }) }
                item { Preference(title = { Text("ES-DE data directory") }, summary = { Text(directory.ifBlank { "Not selected · contains settings, themes and collections" }) }, onClick = {
                    directoryPicker.launch(FolderPickerActivity.createIntent(context, directory.takeIf(String::isNotBlank), null))
                }) }
                item { Preference(title = { Text("Gamelist root") }, summary = { Text(gamelistDirectory.ifBlank { "Not selected · choose the ROM root when gamelist.xml is stored per system" }) }, onClick = {
                    gamelistPicker.launch(FolderPickerActivity.createIntent(context, gamelistDirectory.takeIf(String::isNotBlank), null))
                }) }
                item { Preference(title = { Text("Managed automatically") }, summary = { Text("SafeSync enables ROM gamelists when required and enforces SaveGamelistsMode = always so metadata is written before synchronization.") }) }
            }
            2 -> {
                item { SetupHeading("Primary device and synchronized folders") }
                if (!syncTargetsReady) item { Preference(title = { Text("Starting Syncthing…") }, summary = { Text("SafeSync keeps Syncthing active during First Setup. The device and folder choices unlock automatically as soon as its local API is ready.") }) }
                item { Preference(title = { Text("Primary Gaming Sync Device") }, summary = { Text(api?.getDevices(false)?.firstOrNull { it.deviceID == primaryDevice }?.displayName ?: primaryDevice.ifBlank { "Not selected · choose the authoritative NAS or desktop" }) }, enabled = syncTargetsReady, onClick = { showDevices = true }) }
                item { Preference(title = { Text("Gaming Sync Folders") }, summary = { Text(if (selectedFolders.isEmpty()) "Not selected" else "${selectedFolders.size} selected · every save, settings, ROM metadata and collection folder must be included") }, enabled = syncTargetsReady, onClick = { showFolders = true }) }
                item { Preference(title = { Text("Folder requirement") }, summary = { Text("Every selected folder must be shared with the primary device. Friendly labels and local path names are shown instead of technical IDs.") }) }
            }
            3 -> {
                item { SetupHeading("Choose synchronized content") }
                item { Preference(title = { Text("Per-game metadata") }, summary = { Text("Always synchronizes favorite, completed, play count, play time, last played, alternate emulator, players and rating through per-game .esde.json sidecars.") }) }
                item { SwitchPreference(title = { Text("Shared Collections") }, summary = { Text("Synchronizes only selected validated .xcc collections. Missing shared collections never delete local data.") }, state = collectionsEnabled) }
                if (collectionsEnabled.value) item { Preference(title = { Text("Choose Collections") }, summary = { Text("Select the individual .xcc collections after they have been discovered locally or received from the primary device.") }, onClick = { navigator.navigateTo(SettingsRoute.GamingSharedState) }) }
                item { SwitchPreference(title = { Text("Shared ES-DE Settings") }, summary = { Text("The category switches choose what is synchronized; they do not change an ES-DE feature's value.") }, state = sharedSettingsEnabled) }
                EsdeSharedSettingsCatalog.categories.forEach { category -> item {
                    val state = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_SETTING_CATEGORY_PREFIX + category.id, false)
                    SwitchPreference(
                        title = { Text(category.title) },
                        summary = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(category.summary)
                                if (category.id == "theme") {
                                    Text(
                                        "Note: Theme files must be installed locally. Missing themes are skipped with a warning and never block Safe Launch.",
                                        fontStyle = FontStyle.Italic,
                                    )
                                }
                            }
                        },
                        state = state,
                        enabled = sharedSettingsEnabled.value,
                    )
                } }
            }
            4 -> {
                item { SetupHeading("Automatic safety checks") }
                item { Preference(title = { Text("Apply and validate protection") }, summary = { Text("Configures immediate gamelist saving and the ROM gamelist location. The first-effective gamelist.xml ignore rule is applied only to Master / Roms; other selected folders are not modified.") }, enabled = coreComplete, onClick = ::validateAndApply) }
                if (feedback.isNotBlank()) item { Preference(title = { Text("Result") }, summary = { Text(feedback) }) }
                if (role == EsdeSyncSettings.ROLE_SOURCE) item {
                    Preference(
                        title = { Text("Use this device as initial metadata source") },
                        summary = { Text("Creates the first sidecars only if none exist. Do not use this on a new receiving handheld.") },
                        enabled = coreComplete && service?.esdeSyncCoordinator != null,
                        onClick = sourceClick@{
                            val coordinator = service?.esdeSyncCoordinator ?: return@sourceClick
                            coordinator.initializeFromThisDevice { result ->
                                if (result.blockedByExistingSidecars) {
                                    feedback = "Existing sidecars found; source initialization was safely blocked."
                                } else if (result.export.gamesRead == 0) {
                                    feedback = "No games were found. Check the gamelist root before using this device as the initial source."
                                } else {
                                    coordinator.publishSharedSettings { settingsResult ->
                                        coordinator.publishSharedCollections { collectionsResult ->
                                            feedback = "Created ${result.export.sidecarsWritten} initial sidecar(s). " +
                                                settingsResult.summary("Settings") + "; " + collectionsResult.summary("Collections")
                                            sourceInitialized = settings.bootstrapComplete
                                        }
                                    }
                                }
                            }
                        },
                    )
                } else item { Preference(title = { Text("Receiver protection") }, summary = { Text("Safe Launch waits for the primary device and imports synchronized state before automatic publishing is enabled. Never initialize this new device from its defaults.") }) }
            }
            5 -> {
                item { SetupHeading("Safe Launch and finish") }
                item { Preference(title = { Text("Choose SafeSync as Home app") }, summary = { Text("Android may show it as Syncthing ES-DE Safe Sync. This lets Home return to the post-play synchronization screen.") }, onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }) }
                item { Preference(title = { Text("Android background protection") }, summary = { Text("Set battery use to Unrestricted and disable Pause app activity if unused so final synchronization can finish.") }, onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) }
                item { Preference(title = { Text("After every play session") }, summary = { Text("Close the emulator, return to ES-DE, press Home, keep SafeSync open and wait for SAFE TO SWITCH DEVICE before changing handhelds or powering off.") }) }
                item { Preference(title = { Text(if (canFinish) "Ready to finish" else "Setup incomplete") }, summary = { Text(when {
                    !coreComplete -> "Select ES-DE, both directories, a primary device and at least one Gaming Sync Folder."
                    role == EsdeSyncSettings.ROLE_SOURCE && !sourceInitialized -> "Create the initial metadata source on the Safety step before finishing."
                    !syncTargetsReady -> "Syncthing is still starting. The finish button unlocks automatically."
                    else -> "The core selections are complete. Safe Launch performs the final live synchronization checks."
                }) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (step == 0) {
                        settings.releaseFirstSetupServiceLease()
                        service?.evaluateRunConditions()
                        navigator.navigateUp()
                    } else selectStep(step - 1)
                }) { Text(if (step == 0) "SET UP LATER" else "BACK") }
                if (step < SETUP_STEPS.lastIndex) Button(onClick = { settings.enabled = true; selectStep(step + 1) }) { Text("NEXT") }
                else Button(
                    enabled = canFinish,
                    onClick = ::finishSetup,
                ) { Text("FINISH & OPEN SAFE LAUNCH") }
            }
        }
    }

    if (showDevices) DeviceDialog(
        devices = api?.getDevices(false).orEmpty(), selected = primaryDevice,
        onSelect = { primaryDevice = it; settings.primaryDeviceId = it; showDevices = false }, onDismiss = { showDevices = false },
    )
    if (showFolders) FolderDialog(
        folders = api?.folders.orEmpty(), selected = selectedFolders, primaryDevice = primaryDevice,
        onSave = {
            selectedFolders = it
            settings.selectedFolderIds = it
            showFolders = false
            if (api != null && it.isNotEmpty()) EsdeIgnoreRuleManager(api).ensure(it) { }
        }, onDismiss = { showFolders = false },
    )
}

@Composable
private fun SetupHeading(text: String) {
    Text(text.uppercase(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), color = androidx.compose.ui.graphics.Color(0xFF9C001E))
}

private val SETUP_STEPS = listOf("Device role", "ES-DE", "Syncthing", "Content", "Safety", "Safe Launch")
