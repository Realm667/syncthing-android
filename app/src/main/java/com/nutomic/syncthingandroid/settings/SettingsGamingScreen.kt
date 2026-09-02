package com.nutomic.syncthingandroid.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.esdesync.EsdeIgnoreRuleManager
import com.nutomic.syncthingandroid.esdesync.EsdeFolderRoleMigration
import com.nutomic.syncthingandroid.esdesync.EsdeIgnoreRuleState
import com.nutomic.syncthingandroid.esdesync.EsdeRomIgnoreRules
import com.nutomic.syncthingandroid.esdesync.EsdeSafeLaunchActivity
import com.nutomic.syncthingandroid.esdesync.EsdeSyncSettings
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import java.text.DateFormat
import java.io.File
import java.util.Date

fun EntryProviderScope<SettingsRoute>.settingsGamingEntry() {
    entry<SettingsRoute.Gaming> { SettingsGamingScreen() }
}

fun EntryProviderScope<SettingsRoute>.settingsGamingDiagnosticsEntry() {
    entry<SettingsRoute.GamingDiagnostics> { SettingsGamingDiagnosticsScreen() }
}

@Composable
fun SettingsGamingScreen() {
    val context = LocalContext.current
    val navigator = LocalSettingsNavigator.current
    val service = LocalSyncthingService.current
    val api = service?.api
    val preferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val settings = remember { EsdeSyncSettings(preferences) }
    val enabled = rememberPreferenceState(EsdeSyncSettings.PREF_ENABLED, false)
    val sharedStateEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_STATE_ENABLED, false)
    var directory by remember { mutableStateOf(settings.esdeDirectory) }
    var gamelistDirectory by remember { mutableStateOf(settings.gamelistDirectory) }
    var applicationPackage by remember { mutableStateOf(settings.applicationPackage) }
    var primaryDevice by remember { mutableStateOf(settings.primaryDeviceId) }
    var selectedFolders by remember { mutableStateOf(settings.selectedFolderIds) }
    var romFolder by remember { mutableStateOf(settings.romFolderId) }
    var sharedStateFolder by remember { mutableStateOf(settings.sharedStateFolderId) }
    var showDevices by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showRomFolder by remember { mutableStateOf(false) }
    var showSharedStateFolder by remember { mutableStateOf(false) }
    var showMigrationConfirmation by remember { mutableStateOf(false) }
    var romProtectionStatus by remember { mutableStateOf("Not selected") }

    val folderSignature = api?.folders?.joinToString("|") { "${it.id}:${it.group}:${it.label}" }.orEmpty()
    LaunchedEffect(api, folderSignature, romFolder) {
        val currentApi = api ?: return@LaunchedEffect
        if (romFolder.isBlank()) {
            EsdeFolderRoleMigration.legacyRomFolderId(currentApi.folders)?.let { migrated ->
                romFolder = migrated
                settings.romFolderId = migrated
            }
        }
        val assigned = romFolder
        if (assigned.isBlank()) {
            romProtectionStatus = "Not selected"
        } else if (currentApi.folders.none { it.id == assigned }) {
            romProtectionStatus = "Unavailable · select an existing Syncthing folder"
        } else {
            currentApi.getFolderIgnoreList(assigned, { response ->
                (context as Activity).runOnUiThread {
                    romProtectionStatus = when (EsdeRomIgnoreRules.evaluate(response.ignore.orEmpty().asList())) {
                        EsdeIgnoreRuleState.ACTIVE -> "Protected · gamelist.xml remains local"
                        EsdeIgnoreRuleState.MISSING -> "Missing · select this folder again to apply protection"
                        EsdeIgnoreRuleState.CONFLICTING_INCLUDE -> "Conflicting include rule · manual review required"
                    }
                }
            }, {
                (context as Activity).runOnUiThread { romProtectionStatus = "Unavailable · ignore list could not be read" }
            })
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.let {
                directory = it
                settings.esdeDirectory = it
                if (!settings.hasExplicitGamelistDirectory) {
                    gamelistDirectory = File(it, "gamelists").path
                    settings.gamelistDirectory = gamelistDirectory
                }
                settings.bootstrapComplete = false
                settings.bootstrapPendingImport = false
            }
        }
    }
    val gamelistDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.let {
                gamelistDirectory = it
                settings.gamelistDirectory = it
                settings.bootstrapComplete = false
                settings.bootstrapPendingImport = false
            }
        }
    }
    val appPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val component: ComponentName? = result.data?.component
        if (result.resultCode == Activity.RESULT_OK && component != null) {
            if (component.packageName == context.packageName) {
                Toast.makeText(context, "Choose ES-DE, not this sync app.", Toast.LENGTH_LONG).show()
            } else {
                applicationPackage = component.packageName
                settings.applicationPackage = component.packageName
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.esde_sync_settings_title),
        description = "Safe synchronization for per-game metadata, saves, Collections and selected ES-DE settings.",
    ) {
        item { GamingSectionHeader("Status & First Setup") }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.esde_sync_enable)) },
                summary = { Text(stringResource(R.string.esde_sync_enable_summary)) },
                state = enabled,
            )
        }
        item {
            Preference(
                title = { Text("ROM / gamelist sync folder") },
                summary = { Text(api?.folders?.firstOrNull { it.id == romFolder }?.let(::fullFolderDisplayName)
                    ?.let { "$it · $romProtectionStatus" }
                    ?: "Not selected · choose the folder containing the per-system gamelist.xml files") },
                onClick = { showRomFolder = true },
                enabled = enabled.value && api != null,
            )
        }
        item {
            Preference(
                title = { Text(if (settings.firstSetupComplete) "First Setup complete" else "Run First Setup") },
                summary = { Text(if (settings.firstSetupComplete) "Review or change the guided setup at any time." else "Configure ES-DE, the primary device, Gaming Sync Folders, content and Safe Launch in the recommended order.") },
                onClick = { navigator.navigateTo(SettingsRoute.GamingFirstSetup) },
            )
        }
        item {
            val missing = settings.missingSafeLaunchRequirements()
            Preference(
                title = { Text(if (missing.isEmpty()) "Core configuration ready" else "${missing.size} setup requirement(s) remaining") },
                summary = { Text(if (missing.isEmpty()) "Safe Launch will perform live connection, folder and conflict checks." else "Open First Setup to complete: ${missing.joinToString { it.name.lowercase().replace('_', ' ') }}") },
            )
        }
        item { GamingSectionHeader("ES-DE & Metadata") }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_application)) },
                summary = { Text(applicationPackage.ifBlank { "Not selected · choose the installed ES-DE launcher" }) },
                onClick = {
                    val target = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    appPicker.launch(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, target))
                },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_directory)) },
                summary = { Text(directory.ifBlank { "Not selected · contains settings, themes and collections" }) },
                onClick = {
                    directoryPicker.launch(FolderPickerActivity.createIntent(context, directory.takeIf { it.isNotBlank() }, null))
                },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_gamelist_directory)) },
                summary = { Column {
                    Text(gamelistDirectory.ifBlank { "Not selected · choose the ROM root for per-system gamelist.xml files" })
                    Text("gamelist.xml remains local; only bounded per-game .esde.json sidecars are synchronized.")
                } },
                onClick = {
                    gamelistDirectoryPicker.launch(
                        FolderPickerActivity.createIntent(
                            context,
                            gamelistDirectory.takeIf { it.isNotBlank() },
                            null,
                        )
                    )
                },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text("Automatically managed ES-DE requirements") },
                summary = { Text("SafeSync enables ROM gamelists when needed and enforces SaveGamelistsMode = always before every Safe Launch. These technical values are not synchronized as user preferences.") },
            )
        }
        item {
            Preference(
                title = { Text("Synchronized per-game metadata") },
                summary = { Text("Favorite · completed · play count · play time · last played · alternate emulator · players · rating") },
                enabled = enabled.value,
            )
        }
        item { GamingSectionHeader("Syncthing Topology") }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_primary_device)) },
                summary = {
                    val display = api?.getDevices(false)?.firstOrNull { it.deviceID == primaryDevice }?.displayName
                    Text(display ?: primaryDevice.ifBlank { "Not selected · choose the authoritative NAS or desktop" })
                },
                onClick = { showDevices = true },
                enabled = enabled.value && api != null,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_folders)) },
                summary = {
                    Column {
                        Text(if (selectedFolders.isEmpty()) stringResource(R.string.esde_sync_not_selected) else "${selectedFolders.size} selected")
                        Text("Include every ROM metadata, save and emulator folder that must be current before play.")
                    }
                },
                onClick = { showFolders = true },
                enabled = enabled.value && api != null,
            )
        }
        item { Preference(title = { Text("Automatic gamelist.xml protection") }, summary = { Text("SafeSync applies the basename rule only to the explicitly assigned ROM / gamelist folder. Its display name may be changed without losing the assignment.") }) }
        item { GamingSectionHeader("Synchronized Content") }
        item { SwitchPreference(title = { Text("Synchronize ES-DE Settings & Collections") }, summary = { Text("Optional and technically separate from ROMs and saves. Turn this off on devices that must keep all ES-DE settings and Collections local.") }, state = sharedStateEnabled, enabled = enabled.value) }
        if (sharedStateEnabled.value) item {
            Preference(
                title = { Text("ES-DE Settings & Collections sync folder") },
                summary = { Text(api?.folders?.firstOrNull { it.id == sharedStateFolder }?.let(::fullFolderDisplayName)
                    ?: "Not selected · choose the dedicated shared-state Syncthing folder") },
                onClick = { showSharedStateFolder = true },
                enabled = enabled.value && api != null,
            )
        }
        if (sharedStateEnabled.value && sharedStateFolder.isNotBlank()) item {
            Preference(
                title = { Text("Migrate legacy shared state") },
                summary = { Text("Validates and copies recoverable .esde-sync-global data from the old ROM-root location without deleting it or overwriting conflicts.") },
                onClick = {
                    showMigrationConfirmation = true
                },
            )
        }
        item { Preference(title = { Text("Collections & ES-DE setting categories") }, summary = { Text("Choose categories, review what each contains, and publish or import explicitly. Category switches select what is synchronized; they never toggle the ES-DE feature itself.") }, onClick = { navigator.navigateTo(SettingsRoute.GamingSharedState) }, enabled = enabled.value && sharedStateEnabled.value && sharedStateFolder.isNotBlank()) }
        item { GamingSectionHeader("Safe Launch & Android") }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_open_safe_launch)) },
                summary = { Text(stringResource(R.string.esde_sync_open_safe_launch_summary)) },
                onClick = { context.startActivity(Intent(context, EsdeSafeLaunchActivity::class.java)) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_choose_home)) },
                summary = { Text(stringResource(R.string.esde_sync_choose_home_summary)) },
                onClick = {
                    val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                    runCatching { context.startActivity(intent) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_unused_app_settings)) },
                summary = { Text(stringResource(R.string.esde_sync_unused_app_settings_summary)) },
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
        }
        item { Preference(title = { Text("Required post-play procedure") }, summary = { Text("Close the emulator, return to ES-DE, press Home and keep SafeSync open until SAFE TO SWITCH DEVICE is displayed.") }) }
        item { GamingSectionHeader("Advanced & Diagnostics") }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_diagnostics)) },
                onClick = { navigator.navigateTo(SettingsRoute.GamingDiagnostics) },
            )
        }
    }

    if (showDevices) DeviceDialog(
        devices = api?.getDevices(false) ?: emptyList(),
        selected = primaryDevice,
        onSelect = { primaryDevice = it; settings.primaryDeviceId = it; showDevices = false },
        onDismiss = { showDevices = false },
    )
    if (showFolders) FolderDialog(
        folders = api?.folders?.filter { it.id != sharedStateFolder }
            ?.sortedWith(compareBy<Folder> { it.group }.thenBy { it.label }.thenBy { it.id }) ?: emptyList(),
        selected = selectedFolders,
        primaryDevice = primaryDevice,
        onSave = {
            selectedFolders = it - sharedStateFolder
            settings.selectedFolderIds = selectedFolders
            showFolders = false
        },
        onDismiss = { showFolders = false },
    )
    if (showRomFolder) FolderRoleDialog(
        title = "ROM / gamelist sync folder",
        folders = api?.folders.orEmpty(),
        selected = romFolder,
        primaryDevice = primaryDevice,
        onSelect = { id ->
            romFolder = id
            settings.romFolderId = id
            selectedFolders += id
            settings.selectedFolderIds = selectedFolders
            showRomFolder = false
            api?.let { rest -> EsdeIgnoreRuleManager(rest).ensureRom(id) { result ->
                (context as Activity).runOnUiThread {
                    val message = when {
                        result.checked == 0 -> "The assigned ROM folder is unavailable; no ignore list was changed."
                        result.conflicting > 0 -> "ROM folder assigned, but its gamelist.xml rule needs manual review."
                        else -> "ROM folder assigned and gamelist.xml protected; ${result.updated} ignore list updated."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } }
        },
        onDismiss = { showRomFolder = false },
    )
    if (showSharedStateFolder) FolderRoleDialog(
        title = "ES-DE Settings & Collections sync folder",
        folders = api?.folders.orEmpty(),
        selected = sharedStateFolder,
        primaryDevice = primaryDevice,
        onSelect = { id ->
            sharedStateFolder = id
            settings.sharedStateFolderId = id
            selectedFolders -= id
            settings.selectedFolderIds = selectedFolders
            showSharedStateFolder = false
            api?.let { rest -> EsdeIgnoreRuleManager(rest).ensureSharedState(id) { result ->
                (context as Activity).runOnUiThread {
                    Toast.makeText(context, if (result.failed > 0) "Shared-state folder assigned, but its ignore list could not be updated." else "Shared-state folder assigned and isolated from raw ES-DE files.", Toast.LENGTH_LONG).show()
                }
            } }
        },
        onDismiss = { showSharedStateFolder = false },
    )
    if (showMigrationConfirmation) AlertDialog(
        onDismissRequest = { showMigrationConfirmation = false },
        title = { Text("MIGRATE LEGACY SHARED STATE") },
        text = { Text("SafeSync will validate and privately back up legacy Collections and shared settings from the old ROM-root location, then copy only missing files to the selected Settings & Collections folder. Existing destination files and legacy source files will not be overwritten or deleted.") },
        confirmButton = {
            Button(onClick = {
                showMigrationConfirmation = false
                service?.esdeSyncCoordinator?.migrateLegacySharedState { result ->
                    Toast.makeText(context, "Migration: ${result.copied} copied, ${result.skipped} skipped, ${result.conflicts.size} conflicts, ${result.errors.size} errors", Toast.LENGTH_LONG).show()
                }
            }) { Text("VALIDATE & MIGRATE") }
        },
        dismissButton = { TextButton(onClick = { showMigrationConfirmation = false }) { Text("CANCEL") } },
    )
}

@Composable
internal fun DeviceDialog(devices: List<Device>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.esde_sync_primary_device)) },
        text = { Column { devices.forEach { device ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = device.deviceID == selected, onClick = { onSelect(device.deviceID) })
                Text(device.displayName)
            }
        } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun FolderDialog(
    folders: List<Folder>,
    selected: Set<String>,
    primaryDevice: String,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var values by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.esde_sync_folders)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { folders.forEach { folder ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = folder.id in values,
                    onCheckedChange = { checked -> values = if (checked) values + folder.id else values - folder.id },
                )
                Column {
                    val label = folderDisplayName(folder)
                    Text(if (folder.group.isBlank()) label else "${folder.group} / $label")
                    if (primaryDevice.isNotBlank() && folder.getDevice(primaryDevice) == null) {
                        Text("Not shared with the selected Primary Sync Device")
                    }
                }
            }
        } } },
        confirmButton = { Button(onClick = { onSave(values) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun folderDisplayName(folder: Folder): String = folder.label.takeIf { it.isNotBlank() }
    ?: folder.path?.let(::File)?.name?.takeIf { it.isNotBlank() }
    ?: folder.id

private fun fullFolderDisplayName(folder: Folder): String = folder.group.takeIf { it.isNotBlank() }
    ?.let { "$it / ${folderDisplayName(folder)}" } ?: folderDisplayName(folder)

@Composable
internal fun FolderRoleDialog(
    title: String,
    folders: List<Folder>,
    selected: String,
    primaryDevice: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { folders.forEach { folder ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = folder.id == selected, onClick = { onSelect(folder.id) })
                Column {
                    Text(fullFolderDisplayName(folder))
                    if (primaryDevice.isNotBlank() && folder.getDevice(primaryDevice) == null) {
                        Text("Not shared with the selected Primary Sync Device", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun GamingSectionHeader(title: String) {
    Text(
        title.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SettingsGamingDiagnosticsScreen() {
    val service = LocalSyncthingService.current
    val context = LocalContext.current
    val preferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val settings = remember { EsdeSyncSettings(preferences) }
    var diagnostics by remember(service) { mutableStateOf(service?.esdeSyncCoordinator?.diagnostics()) }
    var actionResult by remember { mutableStateOf("") }
    fun timestamp(key: String): String {
        val value = preferences.getLong(key, 0L)
        return if (value == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(value))
    }
    SettingsScaffold(title = stringResource(R.string.esde_sync_diagnostics)) {
        item { Preference(title = { Text("ES-DE application data directory") }, summary = { Text(settings.esdeDirectory.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Gamelist root directory") }, summary = { Text(settings.gamelistDirectory.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Primary peer") }, summary = { Text(settings.primaryDeviceId.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Selected folders") }, summary = { Text(settings.selectedFolderIds.joinToString().ifBlank { "None" }) }) }
        item { Preference(title = { Text("ROM / gamelist folder") }, summary = { Text(settings.romFolderId.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Settings & Collections folder") }, summary = { Text(if (!settings.sharedStateSyncEnabled) "Disabled" else settings.sharedStateFolderId.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Systems found") }, summary = { Text((diagnostics?.systemsFound ?: 0).toString()) }) }
        item { Preference(title = { Text("Sidecars") }, summary = { Text("total ${diagnostics?.sidecarsTotal ?: 0} · matched ${diagnostics?.matched ?: 0} · unmatched ${diagnostics?.unmatched ?: 0} · invalid ${diagnostics?.invalid ?: 0}") }) }
        item { Preference(title = { Text("FileObserver") }, summary = { Text(if (diagnostics?.observerRunning == true) "Running" else "Stopped") }) }
        item { Preference(title = { Text("Pending local changes") }, summary = { Text(if (settings.pendingLocalChanges) "Yes" else "No") }) }
        item { Preference(title = { Text("Last import") }, summary = { Text(timestamp(EsdeSyncSettings.PREF_LAST_IMPORT)) }) }
        item { Preference(title = { Text("Last export") }, summary = { Text(timestamp(EsdeSyncSettings.PREF_LAST_EXPORT)) }) }
        item { Preference(title = { Text("Last pre-sync") }, summary = { Text(timestamp(EsdeSyncSettings.PREF_LAST_PRE_SYNC)) }) }
        item { Preference(title = { Text("Last post-sync") }, summary = { Text(timestamp(EsdeSyncSettings.PREF_LAST_POST_SYNC)) }) }
        item { Preference(title = { Text("Last successful sync") }, summary = { Text(timestamp(EsdeSyncSettings.PREF_LAST_SUCCESSFUL_SYNC)) }) }
        diagnostics?.lastError?.let { error -> item { Preference(title = { Text("Last error") }, summary = { Text(error) }) } }
        item {
            Preference(
                title = { Text("Run diagnostics") },
                onClick = { service?.esdeSyncCoordinator?.runDiagnostics { diagnostics = it } },
            )
        }
        item { Preference(title = { Text("Force metadata import") }, summary = { Text("Closes ES-DE when possible, then reports matched, changed, unmatched and invalid sidecars.") }, onClick = {
            service?.esdeSyncCoordinator?.importNow { result ->
                actionResult = "Import: ${result.matched} matched · ${result.changedGames} changed · ${result.unmatched} unmatched · ${result.invalid} invalid"
            }
        }) }
        item { Preference(title = { Text("Force metadata export") }, summary = { Text("Reads local gamelist.xml files and writes only changed per-game sidecars.") }, onClick = {
            service?.esdeSyncCoordinator?.exportNow { result -> actionResult = "Export: ${result.gamesRead} games read · ${result.sidecarsWritten} sidecars written" }
        }) }
        item { Preference(title = { Text("Create metadata backup") }, onClick = { service?.esdeSyncCoordinator?.createBackup { ok -> actionResult = if (ok) "Metadata backup created" else "Metadata backup failed" } }) }
        if (actionResult.isNotBlank()) item { Preference(title = { Text("Last manual action") }, summary = { Text(actionResult) }) }
    }
}
