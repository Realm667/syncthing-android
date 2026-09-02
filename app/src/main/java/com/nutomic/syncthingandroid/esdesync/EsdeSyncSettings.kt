package com.nutomic.syncthingandroid.esdesync

import android.content.SharedPreferences
import com.nutomic.syncthingandroid.service.Constants
import java.io.File
import java.util.UUID

class EsdeSyncSettings(private val preferences: SharedPreferences) {
    init {
        // Preserve the user's previous category choices when upgrading from the
        // version where shared state was implicitly coupled to the ROM folder.
        if (!preferences.contains(PREF_SHARED_STATE_ENABLED)) {
            val previouslyEnabled = preferences.getBoolean(PREF_SHARED_COLLECTIONS_ENABLED, false) ||
                preferences.getBoolean(PREF_SHARED_SETTINGS_ENABLED, false)
            preferences.edit().putBoolean(PREF_SHARED_STATE_ENABLED, previouslyEnabled).apply()
        }
    }
    var enabled: Boolean
        get() = preferences.getBoolean(PREF_ENABLED, false)
        set(value) { preferences.edit().putBoolean(PREF_ENABLED, value).apply() }

    var firstSetupOffered: Boolean
        get() = preferences.getBoolean(PREF_FIRST_SETUP_OFFERED, false)
        set(value) { preferences.edit().putBoolean(PREF_FIRST_SETUP_OFFERED, value).apply() }

    var firstSetupComplete: Boolean
        get() = preferences.getBoolean(PREF_FIRST_SETUP_COMPLETE, false)
        set(value) { preferences.edit().putBoolean(PREF_FIRST_SETUP_COMPLETE, value).apply() }

    var firstSetupRole: String
        get() = preferences.getString(PREF_FIRST_SETUP_ROLE, ROLE_RECEIVER) ?: ROLE_RECEIVER
        set(value) { preferences.edit().putString(PREF_FIRST_SETUP_ROLE, value).apply() }

    var firstSetupStep: Int
        get() = preferences.getInt(PREF_FIRST_SETUP_STEP, 0)
        set(value) { preferences.edit().putInt(PREF_FIRST_SETUP_STEP, value).apply() }

    fun acquireFirstSetupServiceLease() {
        val editor = preferences.edit()
        if (!preferences.contains(PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE)) {
            editor.putInt(
                PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE,
                preferences.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP,
                    Constants.BTNSTATE_NO_FORCE_START_STOP,
                ),
            )
        }
        editor.putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_START).apply()
    }

    fun releaseFirstSetupServiceLease() {
        if (!preferences.contains(PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE)) return
        preferences.edit()
            .putInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                preferences.getInt(
                    PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE,
                    Constants.BTNSTATE_NO_FORCE_START_STOP,
                ),
            )
            .remove(PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE)
            .apply()
    }

    var esdeDirectory: String
        get() = preferences.getString(PREF_ESDE_DIRECTORY, "") ?: ""
        set(value) { preferences.edit().putString(PREF_ESDE_DIRECTORY, value).apply() }

    var gamelistDirectory: String
        get() = preferences.getString(PREF_GAMELIST_DIRECTORY, null)
            ?: esdeDirectory.takeIf { it.isNotBlank() }?.let { File(it, "gamelists").path }.orEmpty()
        set(value) { preferences.edit().putString(PREF_GAMELIST_DIRECTORY, value).apply() }

    val hasExplicitGamelistDirectory: Boolean
        get() = preferences.contains(PREF_GAMELIST_DIRECTORY)

    fun usesLegacyGamelistLocation(): Boolean {
        if (esdeDirectory.isBlank() || gamelistDirectory.isBlank()) return false
        return runCatching {
            File(gamelistDirectory).canonicalFile != File(esdeDirectory, "gamelists").canonicalFile
        }.getOrDefault(true)
    }

    var applicationPackage: String
        get() = preferences.getString(PREF_APPLICATION_PACKAGE, "") ?: ""
        set(value) { preferences.edit().putString(PREF_APPLICATION_PACKAGE, value).apply() }

    var primaryDeviceId: String
        get() = preferences.getString(PREF_PRIMARY_DEVICE, "") ?: ""
        set(value) { preferences.edit().putString(PREF_PRIMARY_DEVICE, value).apply() }

    var selectedFolderIds: Set<String>
        get() = preferences.getStringSet(PREF_GAMING_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) { preferences.edit().putStringSet(PREF_GAMING_FOLDERS, value.toSet()).apply() }

    var romFolderId: String
        get() = preferences.getString(PREF_ROM_FOLDER, "") ?: ""
        set(value) { preferences.edit().putString(PREF_ROM_FOLDER, value).apply() }

    var sharedStateSyncEnabled: Boolean
        get() = preferences.getBoolean(PREF_SHARED_STATE_ENABLED, false)
        set(value) { preferences.edit().putBoolean(PREF_SHARED_STATE_ENABLED, value).apply() }

    var sharedStateFolderId: String
        get() = preferences.getString(PREF_SHARED_STATE_FOLDER, "") ?: ""
        set(value) { preferences.edit().putString(PREF_SHARED_STATE_FOLDER, value).apply() }

    fun requiredFolderIds(): Set<String> = buildSet {
        addAll(selectedFolderIds - sharedStateFolderId)
        if (romFolderId.isNotBlank()) add(romFolderId)
        if (sharedStateSyncEnabled && sharedStateFolderId.isNotBlank()) add(sharedStateFolderId)
    }

    var sharedCollectionsEnabled: Boolean
        get() = preferences.getBoolean(PREF_SHARED_COLLECTIONS_ENABLED, false)
        set(value) { preferences.edit().putBoolean(PREF_SHARED_COLLECTIONS_ENABLED, value).apply() }

    var sharedCollectionNames: Set<String>
        get() = selectedByPrefix(PREF_SHARED_COLLECTION_PREFIX)
        set(value) { replaceSelectedByPrefix(PREF_SHARED_COLLECTION_PREFIX, value) }

    var sharedSettingsEnabled: Boolean
        get() = preferences.getBoolean(PREF_SHARED_SETTINGS_ENABLED, false)
        set(value) { preferences.edit().putBoolean(PREF_SHARED_SETTINGS_ENABLED, value).apply() }

    var sharedSettingCategories: Set<String>
        get() = selectedByPrefix(PREF_SHARED_SETTING_CATEGORY_PREFIX)
        set(value) { replaceSelectedByPrefix(PREF_SHARED_SETTING_CATEGORY_PREFIX, value) }

    var sharedSettingNames: Set<String>
        get() {
            val categorySelectionExists = preferences.all.keys.any {
                it.startsWith(PREF_SHARED_SETTING_CATEGORY_PREFIX)
            }
            return if (categorySelectionExists) {
                EsdeSharedSettingsCatalog.namesForCategories(sharedSettingCategories)
            } else {
                selectedByPrefix(PREF_SHARED_SETTING_PREFIX)
            }
        }
        set(value) {
            removeByPrefix(PREF_SHARED_SETTING_CATEGORY_PREFIX)
            replaceSelectedByPrefix(PREF_SHARED_SETTING_PREFIX, value)
        }

    /** Migrates the v2.1.4.5 per-setting UI to the category-only selection model. */
    fun migrateSharedSettingSelectionToCategories() {
        if (preferences.all.keys.any { it.startsWith(PREF_SHARED_SETTING_CATEGORY_PREFIX) }) return
        val legacyNames = selectedByPrefix(PREF_SHARED_SETTING_PREFIX)
        if (legacyNames.isEmpty()) return
        val categories = EsdeSharedSettingsCatalog.categoriesForSettingNames(legacyNames)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(PREF_SHARED_SETTING_PREFIX) }.forEach(editor::remove)
        categories.forEach { editor.putBoolean(PREF_SHARED_SETTING_CATEGORY_PREFIX + it, true) }
        editor.apply()
    }

    private fun selectedByPrefix(prefix: String): Set<String> = preferences.all.entries
        .filter { (key, value) -> key.startsWith(prefix) && value == true }
        .mapTo(mutableSetOf()) { it.key.removePrefix(prefix) }

    private fun replaceSelectedByPrefix(prefix: String, values: Set<String>) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        values.forEach { editor.putBoolean(prefix + it, true) }
        editor.apply()
    }

    private fun removeByPrefix(prefix: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    var bootstrapComplete: Boolean
        get() = preferences.getBoolean(PREF_BOOTSTRAP_COMPLETE, false)
        set(value) { preferences.edit().putBoolean(PREF_BOOTSTRAP_COMPLETE, value).apply() }

    var bootstrapPendingImport: Boolean
        get() = preferences.getBoolean(PREF_BOOTSTRAP_PENDING_IMPORT, false)
        set(value) { preferences.edit().putBoolean(PREF_BOOTSTRAP_PENDING_IMPORT, value).apply() }

    var pendingLocalChanges: Boolean
        get() = preferences.getBoolean(PREF_PENDING_LOCAL_CHANGES, false)
        set(value) { preferences.edit().putBoolean(PREF_PENDING_LOCAL_CHANGES, value).apply() }

    var offlineOverrideUsed: Boolean
        get() = preferences.getBoolean(PREF_OFFLINE_OVERRIDE, false)
        set(value) { preferences.edit().putBoolean(PREF_OFFLINE_OVERRIDE, value).apply() }

    var activeSessionId: String
        get() = preferences.getString(PREF_SESSION_ID, "") ?: ""
        set(value) { preferences.edit().putString(PREF_SESSION_ID, value).apply() }

    var esdeWasLaunched: Boolean
        get() = preferences.getBoolean(PREF_ESDE_WAS_LAUNCHED, false)
        set(value) { preferences.edit().putBoolean(PREF_ESDE_WAS_LAUNCHED, value).apply() }

    var launchTimestamp: Long
        get() = preferences.getLong(PREF_LAUNCH_TIMESTAMP, 0L)
        set(value) { preferences.edit().putLong(PREF_LAUNCH_TIMESTAMP, value).apply() }

    var previousForceState: Int
        get() = preferences.getInt(PREF_PREVIOUS_FORCE_STATE, 0)
        set(value) { preferences.edit().putInt(PREF_PREVIOUS_FORCE_STATE, value).apply() }

    fun beginSession(previousForceState: Int): String {
        val id = UUID.randomUUID().toString()
        preferences.edit()
            .putString(PREF_SESSION_ID, id)
            .putInt(PREF_PREVIOUS_FORCE_STATE, previousForceState)
            .putBoolean(PREF_OFFLINE_OVERRIDE, false)
            .putBoolean(PREF_ESDE_WAS_LAUNCHED, false)
            .putLong(PREF_LAUNCH_TIMESTAMP, 0L)
            .apply()
        return id
    }

    fun clearSession() {
        preferences.edit()
            .remove(PREF_SESSION_ID)
            .remove(PREF_PREVIOUS_FORCE_STATE)
            .remove(PREF_OFFLINE_OVERRIDE)
            .remove(PREF_ESDE_WAS_LAUNCHED)
            .remove(PREF_LAUNCH_TIMESTAMP)
            .apply()
    }

    fun isConfigured(): Boolean = enabled && esdeDirectory.isNotBlank() && gamelistDirectory.isNotBlank() &&
        applicationPackage.isNotBlank() && primaryDeviceId.isNotBlank() &&
        selectedFolderIds.isNotEmpty() && bootstrapComplete

    fun missingSafeLaunchRequirements(): Set<EsdeSetupRequirement> = EsdeSetupEvaluator.missing(
        EsdeSetupInput(
            enabled = enabled,
            esdeDirectorySelected = esdeDirectory.isNotBlank(),
            gamelistDirectorySelected = gamelistDirectory.isNotBlank(),
            applicationSelected = applicationPackage.isNotBlank(),
            primaryDeviceSelected = primaryDeviceId.isNotBlank(),
            gamingFoldersSelected = selectedFolderIds.isNotEmpty(),
            romFolderSelected = romFolderId.isNotBlank(),
            sharedStateFolderReady = !sharedStateSyncEnabled || sharedStateFolderId.isNotBlank(),
            metadataSourceReady = bootstrapComplete || bootstrapPendingImport,
        )
    )

    fun hasCoreSafeLaunchConfiguration(): Boolean =
        missingSafeLaunchRequirements().all { it == EsdeSetupRequirement.INITIAL_METADATA_SOURCE }

    fun isSafeLaunchConfigured(): Boolean = missingSafeLaunchRequirements().isEmpty()

    companion object {
        const val PREF_ENABLED = "esdeSync.enabled"
        const val PREF_FIRST_SETUP_OFFERED = "esdeSync.firstSetup.offered"
        const val PREF_FIRST_SETUP_COMPLETE = "esdeSync.firstSetup.complete"
        const val PREF_FIRST_SETUP_ROLE = "esdeSync.firstSetup.role"
        const val PREF_FIRST_SETUP_STEP = "esdeSync.firstSetup.step"
        private const val PREF_FIRST_SETUP_PREVIOUS_FORCE_STATE = "esdeSync.firstSetup.previousForceState"
        const val PREF_ESDE_DIRECTORY = "esdeSync.directory"
        const val PREF_GAMELIST_DIRECTORY = "esdeSync.gamelistDirectory"
        const val PREF_APPLICATION_PACKAGE = "esdeSync.applicationPackage"
        const val PREF_PRIMARY_DEVICE = "esdeSync.primaryDevice"
        const val PREF_GAMING_FOLDERS = "esdeSync.gamingFolders"
        const val PREF_ROM_FOLDER = "esdeSync.romFolder"
        const val PREF_SHARED_STATE_ENABLED = "esdeSync.sharedState.enabled"
        const val PREF_SHARED_STATE_FOLDER = "esdeSync.sharedState.folder"
        const val PREF_SHARED_COLLECTIONS_ENABLED = "esdeSync.sharedCollections.enabled"
        const val PREF_SHARED_COLLECTION_PREFIX = "esdeSync.sharedCollections.selected."
        const val PREF_SHARED_SETTINGS_ENABLED = "esdeSync.sharedSettings.enabled"
        const val PREF_SHARED_SETTING_PREFIX = "esdeSync.sharedSettings.selected."
        const val PREF_SHARED_SETTING_CATEGORY_PREFIX = "esdeSync.sharedSettings.category."
        const val PREF_BOOTSTRAP_COMPLETE = "esdeSync.bootstrapComplete"
        const val PREF_BOOTSTRAP_PENDING_IMPORT = "esdeSync.bootstrapPendingImport"
        const val PREF_PENDING_LOCAL_CHANGES = "esdeSync.pendingLocalChanges"
        const val PREF_OFFLINE_OVERRIDE = "esdeSync.offlineOverride"
        const val PREF_SESSION_ID = "esdeSync.sessionId"
        const val PREF_ESDE_WAS_LAUNCHED = "esdeSync.esdeWasLaunched"
        const val PREF_LAUNCH_TIMESTAMP = "esdeSync.launchTimestamp"
        const val PREF_PREVIOUS_FORCE_STATE = "esdeSync.previousForceState"
        const val PREF_LAST_IMPORT = "esdeSync.lastImport"
        const val PREF_LAST_EXPORT = "esdeSync.lastExport"
        const val PREF_LAST_PRE_SYNC = "esdeSync.lastPreSync"
        const val PREF_LAST_POST_SYNC = "esdeSync.lastPostSync"
        const val PREF_LAST_SUCCESSFUL_SYNC = "esdeSync.lastSuccessfulSync"
        const val PREF_LAST_COLLECTION_PUBLISH = "esdeSync.sharedCollections.lastPublish"
        const val PREF_LAST_COLLECTION_IMPORT = "esdeSync.sharedCollections.lastImport"
        const val PREF_LAST_SETTINGS_PUBLISH = "esdeSync.sharedSettings.lastPublish"
        const val PREF_LAST_SETTINGS_IMPORT = "esdeSync.sharedSettings.lastImport"
        const val PREF_LAST_SHARED_STATUS = "esdeSync.shared.lastStatus"
        const val PREF_LAST_SHARED_APPLIED = "esdeSync.shared.lastApplied"
        const val PREF_LAST_SHARED_SKIPPED = "esdeSync.shared.lastSkipped"
        const val PREF_LAST_SHARED_CONFLICTS = "esdeSync.shared.lastConflicts"
        const val PREF_LAST_SHARED_ERRORS = "esdeSync.shared.lastErrors"
        const val PREF_LAST_SHARED_WARNINGS = "esdeSync.shared.lastWarnings"
        const val ROLE_RECEIVER = "receiver"
        const val ROLE_SOURCE = "source"
    }
}
