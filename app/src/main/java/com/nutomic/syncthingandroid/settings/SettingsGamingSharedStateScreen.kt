package com.nutomic.syncthingandroid.settings

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.navigation3.runtime.EntryProviderScope
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.esdesync.EsdeGlobalLayout
import com.nutomic.syncthingandroid.esdesync.EsdeSharedSettingsCatalog
import com.nutomic.syncthingandroid.esdesync.EsdeSyncSettings
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import java.text.DateFormat
import java.util.Date

fun EntryProviderScope<SettingsRoute>.settingsGamingSharedStateEntry() {
    entry<SettingsRoute.GamingSharedState> { SettingsGamingSharedStateScreen() }
}

@Composable
fun SettingsGamingSharedStateScreen() {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val preferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val settings = remember {
        EsdeSyncSettings(preferences).also { it.migrateSharedSettingSelectionToCategories() }
    }
    val collectionsEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_COLLECTIONS_ENABLED, false)
    val sharedSettingsEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_SETTINGS_ENABLED, false)
    val sharedStateEnabled = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_STATE_ENABLED, false)
    var collections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feedback by remember { mutableStateOf(preferences.getString(EsdeSyncSettings.PREF_LAST_SHARED_STATUS, "Never") ?: "Never") }

    LaunchedEffect(service, settings.sharedStateFolderId, settings.esdeDirectory, sharedStateEnabled.value) {
        if (sharedStateEnabled.value) service?.esdeSyncCoordinator?.discoverSharedCollections { collections = it }
        else collections = emptySet()
    }

    fun show(message: String) {
        feedback = message
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    fun timestamp(key: String): String {
        val value = preferences.getLong(key, 0L)
        return if (value == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(value))
    }

    SettingsScaffold(
        title = "Shared Collections & ES-DE Settings",
        description = "Optional shared state: <Settings & Collections sync folder>/${EsdeGlobalLayout.DIRECTORY}/. ROMs, saves and gamelist.xml are never included.",
    ) {
        item {
            Preference(
                title = { Text("What is synchronized here") },
                summary = { Text("This page controls only the separate ES-DE Settings & Collections folder. Turning it off does not affect ROMs, saves or per-game metadata.") },
            )
        }
        item { SharedSectionHeading("Collections") }
        item {
            SwitchPreference(
                title = { Text("Shared Collections") },
                summary = { Text("Opt-in synchronization of validated .xcc filter definitions. Missing shared files never delete local collections.") },
                state = collectionsEnabled,
                enabled = sharedStateEnabled.value,
            )
        }
        item {
            Preference(
                title = { Text("RetroAchievements note") },
                summary = { Text("Achievements filters depend on the local ES-DE/RetroAchievements setup. No account or credential is shared.") },
            )
        }
        if (collections.isEmpty()) {
            item { Preference(title = { Text("Collections") }, summary = { Text("No valid local or shared .xcc definitions found") }) }
        } else collections.forEach { name ->
            item {
                val state = rememberPreferenceState(EsdeSyncSettings.PREF_SHARED_COLLECTION_PREFIX + name, false)
                SwitchPreference(title = { Text(name) }, state = state, enabled = sharedStateEnabled.value && collectionsEnabled.value)
            }
        }
        item {
            Preference(
                title = { Text("Publish shared collections") },
                summary = { Text("Last published: ${timestamp(EsdeSyncSettings.PREF_LAST_COLLECTION_PUBLISH)}") },
                enabled = sharedStateEnabled.value && collectionsEnabled.value && settings.sharedCollectionNames.isNotEmpty() && service?.esdeSyncCoordinator != null,
                onClick = { service?.esdeSyncCoordinator?.publishSharedCollections { show(it.summary("Collections publish")) } },
            )
        }
        item { SharedSectionHeading("ES-DE Settings Categories") }
        item {
            Preference(
                title = { Text("Import shared collections now") },
                summary = { Text("Last imported: ${timestamp(EsdeSyncSettings.PREF_LAST_COLLECTION_IMPORT)}") },
                enabled = sharedStateEnabled.value && collectionsEnabled.value && settings.sharedCollectionNames.isNotEmpty() && service?.esdeSyncCoordinator != null,
                onClick = { service?.esdeSyncCoordinator?.importSharedCollections { show(it.summary("Collections import")) } },
            )
        }
        item {
            SwitchPreference(
                title = { Text("Shared ES-DE Settings") },
                summary = { Text("Enables category-based synchronization of explicitly allowlisted values. Secrets and device-specific paths are never included.") },
                state = sharedSettingsEnabled,
                enabled = sharedStateEnabled.value,
            )
        }
        item {
            Preference(
                title = { Text("What these switches mean") },
                summary = {
                    Text(
                        "Category switches only choose which settings are synchronized; they do not turn ES-DE features on or off. " +
                            "Set the actual values in ES-DE. Publishing shares this device's current values, while importing applies the shared values.",
                    )
                },
            )
        }
        item {
            Preference(
                title = { Text("Adding a new device") },
                summary = {
                    Text(
                        "Wait for Syncthing to reach 100%, enable the same categories, then import or use Safe Launch. " +
                            "On first import the existing shared profile wins over fresh device defaults. Automatic publishing cannot create a missing profile.",
                    )
                },
            )
        }
        EsdeSharedSettingsCatalog.categories.forEach { category ->
            item {
                val state = rememberPreferenceState(
                    EsdeSyncSettings.PREF_SHARED_SETTING_CATEGORY_PREFIX + category.id,
                    false,
                )
                SwitchPreference(
                    title = { Text(category.title) },
                    summary = { Text(category.summary) },
                    state = state,
                    enabled = sharedStateEnabled.value && sharedSettingsEnabled.value,
                )
            }
        }
        item { SharedSectionHeading("Transfer & Conflict Safety") }
        item {
            Preference(
                title = { Text("Publish this device's ES-DE settings") },
                summary = {
                    Text(
                        "Explicit source action: shares this device's current values and may create the first profile. " +
                            "Do not use this first on a newly installed receiving device. Last published: ${timestamp(EsdeSyncSettings.PREF_LAST_SETTINGS_PUBLISH)}",
                    )
                },
                enabled = sharedStateEnabled.value && sharedSettingsEnabled.value && settings.sharedSettingNames.isNotEmpty() && service?.esdeSyncCoordinator != null,
                onClick = { service?.esdeSyncCoordinator?.publishSharedSettings { show(it.summary("Settings publish")) } },
            )
        }
        item {
            Preference(
                title = { Text("Import shared ES-DE settings now") },
                summary = { Text("Recommended first action on a new device. Last imported: ${timestamp(EsdeSyncSettings.PREF_LAST_SETTINGS_IMPORT)}") },
                enabled = sharedStateEnabled.value && sharedSettingsEnabled.value && settings.sharedSettingNames.isNotEmpty() && service?.esdeSyncCoordinator != null,
                onClick = { service?.esdeSyncCoordinator?.importSharedSettings { show(it.summary("Settings import")) } },
            )
        }
        item { Preference(title = { Text("Last shared-state result") }, summary = { Text(feedback) }) }
        item { Preference(title = { Text("Applied values") }, summary = { Text(preferences.getInt(EsdeSyncSettings.PREF_LAST_SHARED_APPLIED, 0).toString()) }) }
        item { Preference(title = { Text("Skipped values") }, summary = { Text(preferences.getInt(EsdeSyncSettings.PREF_LAST_SHARED_SKIPPED, 0).toString()) }) }
        item { Preference(title = { Text("Conflicts") }, summary = { Text(preferences.getString(EsdeSyncSettings.PREF_LAST_SHARED_CONFLICTS, "")?.ifBlank { "None" } ?: "None") }) }
        item { Preference(title = { Text("Validation errors") }, summary = { Text(preferences.getString(EsdeSyncSettings.PREF_LAST_SHARED_ERRORS, "")?.ifBlank { "None" } ?: "None") }) }
        item { Preference(title = { Text("Warnings") }, summary = { Text(preferences.getString(EsdeSyncSettings.PREF_LAST_SHARED_WARNINGS, "")?.ifBlank { "None" } ?: "None") }) }
        item {
            Preference(
                title = { Text("Conflict behavior") },
                summary = { Text("First-time mismatches and concurrent local/remote edits are reported and never overwritten. Shared omissions never reset or delete local state.") },
            )
        }
    }
}

@Composable
private fun SharedSectionHeading(title: String) {
    Text(
        title.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
