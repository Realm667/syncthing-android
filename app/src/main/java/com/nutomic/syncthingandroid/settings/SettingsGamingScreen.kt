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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.esdesync.EsdeIgnoreRuleManager
import com.nutomic.syncthingandroid.esdesync.EsdeLegacyGamelistConfigurator
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
    var directory by remember { mutableStateOf(settings.esdeDirectory) }
    var gamelistDirectory by remember { mutableStateOf(settings.gamelistDirectory) }
    var applicationPackage by remember { mutableStateOf(settings.applicationPackage) }
    var primaryDevice by remember { mutableStateOf(settings.primaryDeviceId) }
    var selectedFolders by remember { mutableStateOf(settings.selectedFolderIds) }
    var showDevices by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.let {
                directory = it
                settings.esdeDirectory = it
                if (!settings.hasExplicitGamelistDirectory) {
                    gamelistDirectory = File(it, "gamelists").path
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
        description = stringResource(R.string.esde_sync_settings_summary),
    ) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.esde_sync_enable)) },
                summary = { Text(stringResource(R.string.esde_sync_enable_summary)) },
                state = enabled,
            )
        }
        item {
            Preference(
                title = { Text("Metadata fields") },
                summary = { Text("favorite · completed · playcount · playtime · lastplayed · altemulator") },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_directory)) },
                summary = { Text(directory.ifBlank { stringResource(R.string.esde_sync_not_selected) }) },
                onClick = {
                    directoryPicker.launch(FolderPickerActivity.createIntent(context, directory.takeIf { it.isNotBlank() }, null))
                },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_gamelist_directory)) },
                summary = {
                    Column {
                        Text(gamelistDirectory.ifBlank { stringResource(R.string.esde_sync_not_selected) })
                        Text(stringResource(R.string.esde_sync_gamelist_directory_summary))
                    }
                },
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
            val legacyRequired = settings.usesLegacyGamelistLocation()
            val legacySummary = when {
                !enabled.value -> "Enable ES-DE Gaming Sync first"
                directory.isBlank() || gamelistDirectory.isBlank() -> "Select both directories first"
                !legacyRequired -> "Not required for the central ES-DE/gamelists layout"
                else -> stringResource(R.string.esde_sync_enable_legacy_location_summary)
            }
            Preference(
                title = { Text(stringResource(R.string.esde_sync_enable_legacy_location)) },
                summary = { Text(legacySummary) },
                onClick = {
                    EsdeLegacyGamelistConfigurator.ensure(context, settings) { _, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = enabled.value && directory.isNotBlank() && gamelistDirectory.isNotBlank(),
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_application)) },
                summary = { Text(applicationPackage.ifBlank { stringResource(R.string.esde_sync_not_selected) }) },
                onClick = {
                    val target = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    appPicker.launch(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, target))
                },
                enabled = enabled.value,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_primary_device)) },
                summary = {
                    val display = api?.getDevices(false)?.firstOrNull { it.deviceID == primaryDevice }?.displayName
                    Text(display ?: primaryDevice.ifBlank { stringResource(R.string.esde_sync_not_selected) })
                },
                onClick = { showDevices = true },
                enabled = enabled.value && api != null,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_folders)) },
                summary = { Text(if (selectedFolders.isEmpty()) stringResource(R.string.esde_sync_not_selected) else "${selectedFolders.size} selected") },
                onClick = { showFolders = true },
                enabled = enabled.value && api != null,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_ignore_check)) },
                summary = { Text("Append gamelist.xml without replacing existing ignore patterns") },
                onClick = {
                    if (api != null) {
                        EsdeIgnoreRuleManager(api).ensure(selectedFolders) { result ->
                            (context as Activity).runOnUiThread {
                                val warning = if (result.conflicting > 0) "; ${result.conflicting} conflicting include rule(s) require manual review" else ""
                                Toast.makeText(context, "Checked ${result.checked}; updated ${result.updated}$warning", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = enabled.value && api != null && selectedFolders.isNotEmpty(),
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_initialize)) },
                summary = { Text("Use this device only when no synchronized sidecars exist") },
                onClick = {
                    service?.esdeSyncCoordinator?.initializeFromThisDevice { result ->
                        val message = if (result.blockedByExistingSidecars) {
                            "Existing sidecars detected. Fully sync and import them; initial export was blocked."
                        } else {
                            "Created ${result.export.sidecarsWritten} sidecars"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = enabled.value && service?.esdeSyncCoordinator != null,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_import_now)) },
                summary = { Text(stringResource(R.string.esde_sync_import_summary)) },
                onClick = {
                    service?.esdeSyncCoordinator?.importNow { result ->
                        val valid = result.matched + result.unmatched
                        val message = when {
                            result.invalid > 0 -> "Import finished: ${result.changedGames} changed, ${result.invalid} invalid sidecar(s)"
                            valid == 0 -> "No .esde.json sidecars found. On the first device, use ‘Use this device as initial metadata source’."
                            else -> "Import finished: ${result.matched} matched, ${result.changedGames} changed, ${result.unmatched} unmatched"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = enabled.value && service?.esdeSyncCoordinator != null,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.esde_sync_export_now)) },
                summary = { Text(stringResource(R.string.esde_sync_export_summary)) },
                onClick = {
                    service?.esdeSyncCoordinator?.exportNow { result ->
                        Toast.makeText(
                            context,
                            "Export finished: ${result.gamesRead} games read, ${result.sidecarsWritten} sidecar(s) written",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                enabled = enabled.value && service?.esdeSyncCoordinator != null && settings.bootstrapComplete,
            )
        }
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
        folders = api?.folders?.filter { primaryDevice.isBlank() || it.getDevice(primaryDevice) != null } ?: emptyList(),
        selected = selectedFolders,
        onSave = { selectedFolders = it; settings.selectedFolderIds = it; showFolders = false },
        onDismiss = { showFolders = false },
    )
}

@Composable
private fun DeviceDialog(devices: List<Device>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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
private fun FolderDialog(folders: List<Folder>, selected: Set<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var values by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.esde_sync_folders)) },
        text = { Column { folders.forEach { folder ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = folder.id in values,
                    onCheckedChange = { checked -> values = if (checked) values + folder.id else values - folder.id },
                )
                Text(folder.toString())
            }
        } } },
        confirmButton = { Button(onClick = { onSave(values) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsGamingDiagnosticsScreen() {
    val service = LocalSyncthingService.current
    val context = LocalContext.current
    val preferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val settings = remember { EsdeSyncSettings(preferences) }
    var diagnostics by remember(service) { mutableStateOf(service?.esdeSyncCoordinator?.diagnostics()) }
    fun timestamp(key: String): String {
        val value = preferences.getLong(key, 0L)
        return if (value == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(value))
    }
    SettingsScaffold(title = stringResource(R.string.esde_sync_diagnostics)) {
        item { Preference(title = { Text("ES-DE application data directory") }, summary = { Text(settings.esdeDirectory.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Gamelist root directory") }, summary = { Text(settings.gamelistDirectory.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Primary peer") }, summary = { Text(settings.primaryDeviceId.ifBlank { "Not selected" }) }) }
        item { Preference(title = { Text("Selected folders") }, summary = { Text(settings.selectedFolderIds.joinToString().ifBlank { "None" }) }) }
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
        item { Preference(title = { Text("Force metadata import") }, onClick = { service?.esdeSyncCoordinator?.importNow() }) }
        item { Preference(title = { Text("Force metadata export") }, onClick = { service?.esdeSyncCoordinator?.exportNow() }) }
        item { Preference(title = { Text("Create metadata backup") }, onClick = { service?.esdeSyncCoordinator?.createBackup() }) }
    }
}
