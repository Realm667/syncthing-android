package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.esdesync.EsdeInstallLaunch
import com.nutomic.syncthingandroid.esdesync.EsdeOnlineRelease
import com.nutomic.syncthingandroid.esdesync.EsdeOnlineUpdateManager
import com.nutomic.syncthingandroid.esdesync.EsdeUpdateCheckResult
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preference


private const val TAG = "SettingsAboutScreen"

fun EntryProviderScope<SettingsRoute>.settingsAboutEntry() {
    entry<SettingsRoute.About> {
        SettingsAboutScreen()
    }
}


@Composable
fun SettingsAboutScreen() {
    val context = LocalContext.current
    val navigator = LocalSettingsNavigator.current
    val uriHandler = LocalUriHandler.current
    val stService = LocalSyncthingService.current
    val stServiceTick = LocalServiceUpdateTick.current
    val updateManager = remember(context) { EsdeOnlineUpdateManager(context.cacheDir) }
    val updateScope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("Tap to check GitHub Releases for an update.") }
    var availableRelease by remember { mutableStateOf<EsdeOnlineRelease?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val loading = stringResource(R.string.state_loading)
    val unknown = stringResource(R.string.state_unknown)

    val state by produceState(initialValue = AboutState(
            appVersion = loading,
            coreVersion = loading,
            dbSize = loading,
            fileLimit = loading,
        ), stService, stServiceTick) {
        value = withContext(Dispatchers.IO) {
            AboutState(
                appVersion = getAppVersion(context) ?: unknown,
                coreVersion = stService?.api?.version ?: unknown,
                dbSize = getDatabaseSize(context) ?: unknown,
                fileLimit = getOpenFileLimit() ?: unknown,
            )
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.category_about),
    ) {
        item {
            Preference(
                title = { Text(stringResource(R.string.app_version_title)) },
                summary = { Text(state.appVersion) },
            )
        }
        item {
            Preference(
                title = { Text("Online update") },
                summary = { Text(updateStatus) },
                enabled = !updateBusy,
                onClick = {
                    val cachedApk = downloadedApk
                    val release = availableRelease
                    when {
                        cachedApk != null -> {
                            runCatching { updateManager.launchInstaller(context, cachedApk) }
                                .onSuccess { launch ->
                                    updateStatus = if (launch == EsdeInstallLaunch.STARTED) {
                                        "Android installer opened. Confirm the update to keep all app data."
                                    } else {
                                        "Allow installs from SafeSync, then tap Online update again."
                                    }
                                }
                                .onFailure { updateStatus = "Could not open installer: ${it.message}" }
                        }
                        release != null -> showUpdateDialog = true
                        else -> updateScope.launch {
                            val current = getAppVersion(context)?.removePrefix("v")
                            if (current == null) {
                                updateStatus = "Installed app version could not be read."
                                return@launch
                            }
                            updateBusy = true
                            updateStatus = "Checking GitHub Releases…"
                            runCatching { withContext(Dispatchers.IO) { updateManager.check(current) } }
                                .onSuccess { result ->
                                    when (result) {
                                        EsdeUpdateCheckResult.Current ->
                                            updateStatus = "v$current is current. Tap to check again."
                                        is EsdeUpdateCheckResult.Available -> {
                                            availableRelease = result.release
                                            updateStatus = "v${result.release.version} is available. Tap to update."
                                            showUpdateDialog = true
                                        }
                                    }
                                }
                                .onFailure { updateStatus = "Update check failed: ${it.message}" }
                            updateBusy = false
                        }
                    }
                },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.syncthing_version_title)) },
                summary = { Text(state.coreVersion) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.syncthing_database_size)) },
                summary = { Text(state.dbSize) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.os_open_file_limit)) },
                summary = { Text(state.fileLimit) },
            )
        }
        item {
            val stForumUri = stringResource(R.string.syncthing_forum_url)
            Preference(
                title = { Text(stringResource(R.string.syncthing_forum_title)) },
                summary = { Text(stringResource(R.string.syncthing_forum_summary)) },
                onClick = { uriHandler.openUri(stForumUri) },
            )
        }
        item {
            val stPrivacyPolicyUri = stringResource(R.string.privacy_policy_url)
            Preference(
                title = { Text(stringResource(R.string.privacy_title)) },
                summary = { Text(stringResource(R.string.privacy_summary)) },
                onClick = { uriHandler.openUri(stPrivacyPolicyUri) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.open_source_licenses_title)) },
                summary = { Text(stringResource(R.string.open_source_licenses_summary)) },
                onClick = { navigator.navigateTo(SettingsRoute.Licenses) },
            )
        }
    }

    val release = availableRelease
    if (showUpdateDialog && release != null) {
        AlertDialog(
            onDismissRequest = { if (!updateBusy) showUpdateDialog = false },
            title = { Text("UPDATE AVAILABLE") },
            text = {
                Text(
                    "Syncthing ES-DE Safe Sync v${release.version} is available. " +
                        "SafeSync will download the universal APK from the official Realm667 GitHub release, " +
                        "verify its SHA-256 checksum and open Android's installer. Your app data is retained.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !updateBusy,
                    onClick = {
                        updateBusy = true
                        updateStatus = "Downloading and verifying v${release.version}…"
                        showUpdateDialog = false
                        updateScope.launch {
                            runCatching { withContext(Dispatchers.IO) { updateManager.downloadVerified(release) } }
                                .onSuccess { apk ->
                                    downloadedApk = apk
                                    runCatching { updateManager.launchInstaller(context, apk) }
                                        .onSuccess { launch ->
                                            updateStatus = if (launch == EsdeInstallLaunch.STARTED) {
                                                "v${release.version} verified. Confirm the update in Android's installer."
                                            } else {
                                                "v${release.version} verified. Allow installs from SafeSync, then tap Online update again."
                                            }
                                        }
                                        .onFailure { updateStatus = "Could not open installer: ${it.message}" }
                                }
                                .onFailure { updateStatus = "Update download failed: ${it.message}" }
                            updateBusy = false
                        }
                    },
                ) { Text("DOWNLOAD & INSTALL") }
            },
            dismissButton = {
                TextButton(enabled = !updateBusy, onClick = { showUpdateDialog = false }) { Text("LATER") }
            },
        )
    }
}

data class AboutState(
    val appVersion: String = "",
    val coreVersion: String = "",
    val dbSize: String = "",
    val fileLimit: String = ""
)

private fun getAppVersion(context: Context): String? {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${packageInfo.versionName}"
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Failed to get app version name")
        null
    }
}

private fun getOpenFileLimit(): String? {
    val shellCommand = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        "/system/bin/ulimit -n"
    else
        "ulimit -n"

    val result = Util.runShellCommandGetOutput(shellCommand)
    return if (result.isNullOrBlank()) null else result.trim()
}

private fun getDatabaseSize(context: Context): String? {
    val dbPath = Constants.getIndexDbFolder(context).absolutePath
    val result = Util.runShellCommandGetOutput("/system/bin/du -sh $dbPath")

    if (result.isNullOrBlank()) {
        return null
    }

    // Split by whitespace and grab the first part (the size)
    val resultParts = result.trim().split(Regex("\\s+"))
    return resultParts.firstOrNull()
}
