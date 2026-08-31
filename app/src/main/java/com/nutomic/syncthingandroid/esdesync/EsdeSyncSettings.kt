package com.nutomic.syncthingandroid.esdesync

import android.content.SharedPreferences
import java.io.File
import java.util.UUID

class EsdeSyncSettings(private val preferences: SharedPreferences) {
    var enabled: Boolean
        get() = preferences.getBoolean(PREF_ENABLED, false)
        set(value) { preferences.edit().putBoolean(PREF_ENABLED, value).apply() }

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

    fun isSafeLaunchConfigured(): Boolean = enabled && esdeDirectory.isNotBlank() && gamelistDirectory.isNotBlank() &&
        applicationPackage.isNotBlank() && primaryDeviceId.isNotBlank() &&
        selectedFolderIds.isNotEmpty() && (bootstrapComplete || bootstrapPendingImport)

    companion object {
        const val PREF_ENABLED = "esdeSync.enabled"
        const val PREF_ESDE_DIRECTORY = "esdeSync.directory"
        const val PREF_GAMELIST_DIRECTORY = "esdeSync.gamelistDirectory"
        const val PREF_APPLICATION_PACKAGE = "esdeSync.applicationPackage"
        const val PREF_PRIMARY_DEVICE = "esdeSync.primaryDevice"
        const val PREF_GAMING_FOLDERS = "esdeSync.gamingFolders"
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
    }
}
