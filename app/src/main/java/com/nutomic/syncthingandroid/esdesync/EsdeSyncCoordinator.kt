package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.nutomic.syncthingandroid.service.RestApi
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EsdeSyncCoordinator(
    context: Context,
    private val restApi: RestApi,
    private val preferences: SharedPreferences,
) {
    private val appContext = context.applicationContext
    private val settings = EsdeSyncSettings(preferences)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ESDESync-Coordinator")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = EsdeMetadataBridge(
        EsdeGamelistParser(),
        EsdeSidecarStore(Gson()),
        EsdeSnapshotStore(File(appContext.filesDir, "esde-sync/snapshots")),
        EsdeBackupManager(File(appContext.filesDir, "esde-sync/backups")),
    )
    @Volatile private var observer: EsdeFileObserver? = null
    @Volatile private var stopped = false
    @Volatile private var diagnostics = EsdeDiagnostics()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            EsdeSyncSettings.PREF_ENABLED -> if (settings.enabled) start() else stopObserver()
            EsdeSyncSettings.PREF_ESDE_DIRECTORY -> {
                stopObserver()
                if (settings.enabled) start()
            }
            EsdeSyncSettings.PREF_GAMELIST_DIRECTORY -> {
                stopObserver()
                if (settings.enabled) start()
            }
            EsdeSyncSettings.PREF_BOOTSTRAP_COMPLETE -> {
                if (settings.enabled && settings.bootstrapComplete) startObserver() else stopObserver()
            }
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun start() {
        if (!settings.enabled || stopped) return
        executor.execute {
            try {
                val systems = systemDirectories()
                val sidecarsExist = systems.any { File(it, EsdeSidecarStore.SIDECAR_DIRECTORY).walkTopDown()
                    .any { file -> file.isFile && file.name.endsWith(EsdeSidecarStore.SIDECAR_SUFFIX) } }
                when (EsdeBootstrapEvaluator.evaluate(settings.bootstrapComplete, sidecarsExist)) {
                    // Safe Launch performs the import only after its full-sync gate passes.
                    EsdeBootstrapAction.IMPORT_EXISTING -> settings.bootstrapPendingImport = true
                    EsdeBootstrapAction.START_OBSERVING -> startObserver()
                    EsdeBootstrapAction.REQUIRE_SOURCE_CONFIRMATION -> settings.bootstrapPendingImport = false
                }
                refreshDiagnostics()
            } catch (error: Exception) {
                recordError("Initialization failed", error)
            }
        }
    }

    fun stop() {
        stopped = true
        observer?.stop()
        observer = null
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        executor.shutdownNow()
    }

    fun onRemoteSidecarChanged(fullPath: String) {
        if (!settings.enabled || !fullPath.replace('\\', '/').contains("/.esde-sync/") ||
            !fullPath.endsWith(EsdeSidecarStore.SIDECAR_SUFFIX)) return
        executor.execute {
            if (!settings.bootstrapComplete) {
                settings.bootstrapPendingImport = true
                return@execute
            }
            val file = File(fullPath)
            val system = generateSequence(file.parentFile) { it.parentFile }
                .firstOrNull { it.name == EsdeSidecarStore.SIDECAR_DIRECTORY }?.parentFile
            if (system != null && isInsideGamelists(system)) importSystemInternal(system)
        }
    }

    fun importNow(finalizeBootstrap: Boolean = false, callback: (EsdeImportResult) -> Unit = {}) {
        executor.execute {
            val result = runCatching { importAllInternal() }
                .onFailure { recordError("Import failed", it) }
                .getOrDefault(EsdeImportResult(invalid = 1))
            if (finalizeBootstrap && result.invalid == 0) {
                settings.bootstrapPendingImport = false
                settings.bootstrapComplete = true
                startObserver()
            }
            mainHandler.post { callback(result) }
        }
    }

    fun exportNow(full: Boolean = false, callback: (EsdeExportResult) -> Unit = {}) {
        executor.execute {
            val result = runCatching { exportAllInternal(full) }
                .onFailure { recordError("Export failed", it) }
                .getOrDefault(EsdeExportResult())
            mainHandler.post { callback(result) }
        }
    }

    fun discoverSharedCollections(callback: (Set<String>) -> Unit) {
        executor.execute {
            val names = runCatching { collectionsManager().discover() }.getOrDefault(emptySet())
            mainHandler.post { callback(names) }
        }
    }

    fun publishSharedCollections(callback: (EsdeSharedOperationResult) -> Unit = {}) = sharedAction(
        timestampKey = EsdeSyncSettings.PREF_LAST_COLLECTION_PUBLISH,
        callback = callback,
    ) { collectionsManager().publish(settings.sharedCollectionNames) }

    fun importSharedCollections(callback: (EsdeSharedOperationResult) -> Unit = {}) = sharedAction(
        timestampKey = EsdeSyncSettings.PREF_LAST_COLLECTION_IMPORT,
        callback = callback,
    ) {
        requireEsdeStopped()
        collectionsManager().importSelected(settings.sharedCollectionNames, esdeSettingsFile())
    }

    fun publishSharedSettings(callback: (EsdeSharedOperationResult) -> Unit = {}) = sharedAction(
        timestampKey = EsdeSyncSettings.PREF_LAST_SETTINGS_PUBLISH,
        callback = callback,
    ) {
        // A manual click is the explicit action that may establish the very first shared profile.
        settingsManager().publish(settings.sharedSettingNames, allowInitialize = true)
    }

    fun importSharedSettings(callback: (EsdeSharedOperationResult) -> Unit = {}) = sharedAction(
        timestampKey = EsdeSyncSettings.PREF_LAST_SETTINGS_IMPORT,
        callback = callback,
    ) {
        requireEsdeStopped()
        settingsManager().importSelected(settings.sharedSettingNames)
    }

    fun importSharedStateBeforeLaunch(callback: (EsdeGlobalImportResult) -> Unit = {}) {
        executor.execute {
            val result = runCatching {
                requireEsdeStopped()
                val collections = if (settings.sharedCollectionsEnabled) {
                    collectionsManager().importSelected(settings.sharedCollectionNames, esdeSettingsFile())
                } else EsdeSharedOperationResult()
                val sharedSettings = if (settings.sharedSettingsEnabled) {
                    settingsManager().importSelected(settings.sharedSettingNames)
                } else EsdeSharedOperationResult()
                if (settings.sharedCollectionsEnabled) recordSharedResult(
                    EsdeSyncSettings.PREF_LAST_COLLECTION_IMPORT, collections,
                )
                if (settings.sharedSettingsEnabled) recordSharedResult(
                    EsdeSyncSettings.PREF_LAST_SETTINGS_IMPORT, sharedSettings,
                )
                EsdeGlobalImportResult(collections, sharedSettings)
            }.getOrElse { error ->
                recordError("Shared state import failed", error)
                EsdeGlobalImportResult(settings = EsdeSharedOperationResult(errors = listOf(error.message ?: "Import failed")))
            }
            mainHandler.post { callback(result) }
        }
    }

    fun publishSharedState(callback: (EsdeGlobalImportResult) -> Unit = {}) {
        executor.execute {
            val collections = if (settings.sharedCollectionsEnabled) {
                runCatching { collectionsManager().publish(settings.sharedCollectionNames) }
                    .getOrElse { EsdeSharedOperationResult(errors = listOf(it.message ?: "Publish failed")) }
            } else EsdeSharedOperationResult()
            val sharedSettings = if (settings.sharedSettingsEnabled) {
                // Safe Launch must never seed a missing profile from a newly installed device's defaults.
                runCatching { settingsManager().publish(settings.sharedSettingNames, allowInitialize = false) }
                    .getOrElse { EsdeSharedOperationResult(errors = listOf(it.message ?: "Publish failed")) }
            } else EsdeSharedOperationResult()
            if (settings.sharedCollectionsEnabled) recordSharedResult(EsdeSyncSettings.PREF_LAST_COLLECTION_PUBLISH, collections)
            if (settings.sharedSettingsEnabled) recordSharedResult(EsdeSyncSettings.PREF_LAST_SETTINGS_PUBLISH, sharedSettings)
            mainHandler.post { callback(EsdeGlobalImportResult(collections, sharedSettings)) }
        }
    }

    fun initializeFromThisDevice(callback: (EsdeInitializationResult) -> Unit = {}) {
        executor.execute {
            if (sidecarsExist()) {
                settings.bootstrapPendingImport = true
                mainHandler.post { callback(EsdeInitializationResult(blockedByExistingSidecars = true)) }
                return@execute
            }
            val result = runCatching { exportAllInternal(full = true) }
                .onFailure { recordError("Initial export failed", it) }
                .getOrDefault(EsdeExportResult())
            if (result.gamesRead > 0) {
                settings.bootstrapPendingImport = false
                settings.bootstrapComplete = true
                startObserver()
            }
            mainHandler.post { callback(EsdeInitializationResult(result)) }
        }
    }

    fun createBackup(callback: (Boolean) -> Unit = {}) {
        executor.execute {
            val ok = runCatching {
                val manager = EsdeBackupManager(File(appContext.filesDir, "esde-sync/backups"))
                systemDirectories().forEach {
                    val gamelist = File(it, EsdeMetadataBridge.GAMELIST)
                    if (gamelist.isFile) manager.backup(it.name, gamelist)
                }
            }.isSuccess
            mainHandler.post { callback(ok) }
        }
    }

    fun ensureLegacyGamelistLocation(callback: (Boolean, String) -> Unit = { _, _ -> }) {
        EsdeLegacyGamelistConfigurator.ensure(appContext, settings) { success, message ->
            if (!success) recordError("Could not configure ES-DE ROM gamelists", IllegalStateException(message))
            callback(success, message)
        }
    }

    fun diagnostics(): EsdeDiagnostics = diagnostics

    fun runDiagnostics(callback: (EsdeDiagnostics) -> Unit = {}) {
        executor.execute {
            runCatching { refreshDiagnostics() }
                .onFailure { recordError("Diagnostics failed", it) }
            mainHandler.post { callback(diagnostics) }
        }
    }

    private fun importAllInternal(): EsdeImportResult {
        var result = EsdeImportResult()
        systemDirectories().forEach { system ->
            val next = importSystemInternal(system)
            result = EsdeImportResult(
                result.matched + next.matched,
                result.unmatched + next.unmatched,
                result.invalid + next.invalid,
                result.changedGames + next.changedGames,
            )
        }
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_IMPORT, System.currentTimeMillis()).apply()
        refreshDiagnostics()
        return result
    }

    private fun importSystemInternal(system: File): EsdeImportResult {
        val result = bridge.importSystem(system)
        if (result.changedGames > 0) settings.pendingLocalChanges = true
        return result
    }

    private fun exportAllInternal(full: Boolean): EsdeExportResult {
        var result = EsdeExportResult()
        systemDirectories().forEach { system ->
            val next = bridge.exportSystem(system, full)
            result = EsdeExportResult(result.gamesRead + next.gamesRead, result.sidecarsWritten + next.sidecarsWritten)
        }
        if (result.sidecarsWritten > 0) settings.pendingLocalChanges = true
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_EXPORT, System.currentTimeMillis()).apply()
        refreshDiagnostics()
        return result
    }

    @Synchronized private fun startObserver() {
        if (observer != null || stopped || !settings.enabled || !settings.bootstrapComplete) return
        val gamelists = gamelistsDirectory()
        observer = EsdeFileObserver(gamelists) { gamelist ->
            executor.execute {
                runCatching {
                    gamelist.parentFile?.let { bridge.exportSystem(it) } ?: EsdeExportResult()
                }
                    .onSuccess { if (it.sidecarsWritten > 0) settings.pendingLocalChanges = true }
                    .onFailure { recordError("Observed export failed", it) }
                refreshDiagnostics()
            }
        }.also { it.start() }
    }

    @Synchronized private fun stopObserver() {
        observer?.stop()
        observer = null
        diagnostics = diagnostics.copy(observerRunning = false)
    }

    private fun systemDirectories(): List<File> = EsdeGamelistLocator(gamelistsDirectory()).systemDirectories()

    private fun sidecarsExist(): Boolean = systemDirectories().any {
        File(it, EsdeSidecarStore.SIDECAR_DIRECTORY).walkTopDown()
            .any { file -> file.isFile && file.name.endsWith(EsdeSidecarStore.SIDECAR_SUFFIX) }
    }

    private fun gamelistsDirectory(): File = File(settings.gamelistDirectory)

    private fun isInsideGamelists(file: File): Boolean = runCatching {
        EsdeGamelistLocator(gamelistsDirectory()).contains(file)
    }.getOrDefault(false)

    private fun refreshDiagnostics() {
        val systems = systemDirectories()
        var total = 0
        var invalid = 0
        var matched = 0
        var unmatched = 0
        val parser = EsdeGamelistParser()
        val store = EsdeSidecarStore()
        systems.forEach { system ->
            val local: Set<String> = runCatching {
                parser.parse(File(system, EsdeMetadataBridge.GAMELIST)).keys.toSet()
            }.getOrDefault(emptySet())
            val scan = store.scan(system)
            total += scan.total
            invalid += scan.invalid
            matched += scan.states.keys.count { it in local }
            unmatched += scan.states.keys.count { it !in local }
        }
        diagnostics = diagnostics.copy(
            systemsFound = systems.size,
            sidecarsTotal = total,
            matched = matched,
            unmatched = unmatched,
            invalid = invalid,
            pendingLocalChanges = settings.pendingLocalChanges,
            observerRunning = observer?.isRunning == true,
        )
    }

    private fun recordError(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        diagnostics = diagnostics.copy(lastError = "$message: ${error.message}")
    }

    private fun collectionsManager(): EsdeSharedCollectionsManager = EsdeSharedCollectionsManager(
        gamelistsDirectory(),
        File(settings.esdeDirectory),
        EsdeSharedSnapshotStore(File(appContext.filesDir, "esde-sync/shared-snapshots")),
        EsdePrivateFileBackup(File(appContext.filesDir, "esde-sync/backups/shared")),
    )

    private fun settingsManager(): EsdeSharedSettingsManager = EsdeSharedSettingsManager(
        gamelistsDirectory(),
        File(settings.esdeDirectory),
        EsdeSharedSnapshotStore(File(appContext.filesDir, "esde-sync/shared-snapshots")),
        EsdePrivateFileBackup(File(appContext.filesDir, "esde-sync/backups/shared")),
    )

    private fun esdeSettingsFile(): File = File(File(settings.esdeDirectory, "settings"), "es_settings.xml")

    private fun requireEsdeStopped() {
        check(!settings.esdeWasLaunched) { "ES-DE is running; shared state can only be applied before Safe Launch" }
        require(esdeSettingsFile().isFile) { "Missing ES-DE settings/es_settings.xml" }
    }

    private fun sharedAction(
        timestampKey: String,
        callback: (EsdeSharedOperationResult) -> Unit,
        action: () -> EsdeSharedOperationResult,
    ) {
        executor.execute {
            val result = runCatching(action).getOrElse { error ->
                recordError("Shared ES-DE operation failed", error)
                EsdeSharedOperationResult(errors = listOf(error.message ?: "Operation failed"))
            }
            recordSharedResult(timestampKey, result)
            mainHandler.post { callback(result) }
        }
    }

    private fun recordSharedResult(timestampKey: String, result: EsdeSharedOperationResult) {
        preferences.edit()
            .putLong(timestampKey, System.currentTimeMillis())
            .putString(EsdeSyncSettings.PREF_LAST_SHARED_STATUS, result.summary("Shared state"))
            .putInt(EsdeSyncSettings.PREF_LAST_SHARED_APPLIED, result.applied)
            .putInt(EsdeSyncSettings.PREF_LAST_SHARED_SKIPPED, result.skipped)
            .putString(EsdeSyncSettings.PREF_LAST_SHARED_CONFLICTS, result.conflicts.joinToString())
            .putString(EsdeSyncSettings.PREF_LAST_SHARED_ERRORS, result.errors.joinToString())
            .apply()
    }

    companion object { private const val TAG = "ESDESync" }
}
