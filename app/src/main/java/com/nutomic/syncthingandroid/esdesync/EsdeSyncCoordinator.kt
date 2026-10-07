package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.nutomic.syncthingandroid.service.RestApi
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class EsdeSyncCoordinator(
    context: Context,
    private val restApi: RestApi,
    private val preferences: SharedPreferences,
) {
    private val appContext = context.applicationContext
    private val settings = EsdeSyncSettings(preferences)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ESDESync-Coordinator")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diagnosticsCache = EsdeDiagnosticsCache()
    private val bridge = EsdeMetadataBridge(
        EsdeGamelistParser(),
        EsdeSidecarStore(Gson()),
        EsdeSnapshotStore(File(appContext.filesDir, "esde-sync/snapshots")),
        EsdeBackupManager(File(appContext.filesDir, "esde-sync/backups")),
        diagnosticsCache::put,
    )
    @Volatile private var observer: EsdeFileObserver? = null
    @Volatile private var stopped = false
    private val remoteRevision = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var diagnostics = EsdeDiagnostics()
    private val remoteImports = EsdeCoalescingQueue<File>(
        schedule = { work -> executor.schedule({ work() }, 900, TimeUnit.MILLISECONDS) },
        action = { system ->
            if (settings.enabled && !stopped && isInsideGamelists(system)) {
                // Only Safe Launch (or an explicit import) may apply received metadata.
                // Importing here could race the final local export after ES-DE was stopped.
                diagnosticsCache.invalidate(system)
            }
        },
    )
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
        remoteImports.close()
        observer?.stop()
        observer = null
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        executor.shutdownNow()
    }

    fun onRemoteSidecarChanged(fullPath: String) {
        if (stopped || !settings.enabled) return
        val normalized = fullPath.replace('\\', '/')
        val global = settings.sharedStateSyncEnabled && normalized.contains("/.esde-sync-global/")
        val game = normalized.contains("/.esde-sync/") && fullPath.endsWith(EsdeSidecarStore.SIDECAR_SUFFIX)
        if (!global && !game) return
        synchronized(remoteRevision) {
            remoteRevision.incrementAndGet()
            if (game || settings.bootstrapComplete) settings.bootstrapPendingImport = true
        }
        if (!game) return
        // Lexical grouping only. Canonical root checks and all file access stay on the worker.
        val system = generateSequence(File(fullPath).parentFile) { it.parentFile }
            .firstOrNull { it.name == EsdeSidecarStore.SIDECAR_DIRECTORY }?.parentFile ?: return
        remoteImports.submit(system.absoluteFile)
    }

    fun currentRemoteRevision(): Long = remoteRevision.get()

    fun importNow(finalizeBootstrap: Boolean = false, expectedRevision: Long? = null, callback: (EsdeImportResult) -> Unit = {}) {
        executor.execute {
            val revision = expectedRevision ?: remoteRevision.get()
            val result = runCatching {
                requireEsdeStopped()
                importAllInternal()
            }
                .onFailure { recordError("Import failed", it) }
                .getOrElse { EsdeImportResult(invalid = 1, errors = listOf(it.message ?: "Metadata import failed")) }
            if (result.invalid == 0 && result.errors.isEmpty()) {
                synchronized(remoteRevision) { settings.bootstrapPendingImport = remoteRevision.get() != revision }
            }
            if (finalizeBootstrap && result.invalid == 0 && result.errors.isEmpty()) {
                settings.bootstrapComplete = true
                startObserver()
            }
            mainHandler.post { callback(result) }
        }
    }

    fun exportNow(full: Boolean = false, callback: (EsdeExportResult) -> Unit = {}) {
        executor.execute {
            val result = runCatching { requireEsdeStopped(); exportAllInternal(full) }
                .onFailure { recordError("Export failed", it) }
                .getOrElse { EsdeExportResult(errors = listOf(it.message ?: "Metadata export failed")) }
            mainHandler.post { callback(result) }
        }
    }

    /** Shares the bridge queue: no import can overlap the transition into ES-DE. */
    fun prepareLaunch(offline: Boolean, callback: (Boolean, String) -> Unit) {
        executor.execute {
            val result = runCatching {
                check(!stopped && settings.enabled) { "Metadata bridge is unavailable" }
                check(offline || !settings.bootstrapPendingImport) { "New synchronized metadata arrived. Retry the start synchronization." }
                settings.esdeWasLaunched = true
            }
            mainHandler.post { callback(result.isSuccess, result.exceptionOrNull()?.message.orEmpty()) }
        }
    }

    fun closeEsdeAfterPlay(callback: (Boolean, String) -> Unit = { _, _ -> }) {
        executor.execute {
            val result = runCatching {
                validateFolderConfiguration()
                check(EsdeProcessController.isConfirmedStopped(appContext, settings.applicationPackage)) {
                    "Android cannot confirm ES-DE is closed. Open ES-DE app info, select Force stop, then return and Retry."
                }
                settings.esdeWasLaunched = false
                "ES-DE was closed. Reading final metadata…"
            }
            result.exceptionOrNull()?.let { recordError("Could not close ES-DE after play", it) }
            mainHandler.post {
                callback(result.isSuccess, result.getOrElse { it.message ?: "ES-DE could not be closed." })
            }
        }
    }

    fun discoverSharedCollections(callback: (Set<String>) -> Unit) {
        executor.execute {
            val names = if (settings.sharedStateSyncEnabled) {
                runCatching { collectionsManager().discover() }.getOrDefault(emptySet())
            } else emptySet()
            mainHandler.post { callback(names) }
        }
    }

    fun resolveSharedConflict(conflict: EsdeValueConflict, useShared: Boolean, callback: (Boolean, String) -> Unit) {
        executor.execute {
            val result = runCatching {
                requireEsdeStopped()
                if (conflict.category == "settings") settingsManager().resolveConflict(conflict, useShared)
                else collectionsManager().resolveConflict(conflict, useShared)
            }
            mainHandler.post { callback(result.isSuccess, result.exceptionOrNull()?.message ?: "Conflict resolved; verifying synchronization") }
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
                val collections = if (settings.sharedStateSyncEnabled && settings.sharedCollectionsEnabled) {
                    collectionsManager().importSelected(settings.sharedCollectionNames, esdeSettingsFile())
                } else EsdeSharedOperationResult()
                val sharedSettings = if (settings.sharedStateSyncEnabled && settings.sharedSettingsEnabled) {
                    settingsManager().importSelected(settings.sharedSettingNames)
                } else EsdeSharedOperationResult()
                if (settings.sharedStateSyncEnabled && settings.sharedCollectionsEnabled) recordSharedResult(
                    EsdeSyncSettings.PREF_LAST_COLLECTION_IMPORT, collections,
                )
                if (settings.sharedStateSyncEnabled && settings.sharedSettingsEnabled) recordSharedResult(
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
            val closed = runCatching { requireEsdeStopped() }
            if (closed.isFailure) {
                mainHandler.post { callback(EsdeGlobalImportResult(settings = EsdeSharedOperationResult(
                    errors = listOf(closed.exceptionOrNull()?.message ?: "ES-DE must be closed")))) }
                return@execute
            }
            val collections = if (settings.sharedStateSyncEnabled && settings.sharedCollectionsEnabled) {
                runCatching { collectionsManager().publish(settings.sharedCollectionNames) }
                    .getOrElse { EsdeSharedOperationResult(errors = listOf(it.message ?: "Publish failed")) }
            } else EsdeSharedOperationResult()
            val sharedSettings = if (settings.sharedStateSyncEnabled && settings.sharedSettingsEnabled) {
                // Safe Launch must never seed a missing profile from a newly installed device's defaults.
                runCatching { settingsManager().publish(settings.sharedSettingNames, allowInitialize = false) }
                    .getOrElse { EsdeSharedOperationResult(errors = listOf(it.message ?: "Publish failed")) }
            } else EsdeSharedOperationResult()
            if (settings.sharedStateSyncEnabled && settings.sharedCollectionsEnabled) recordSharedResult(EsdeSyncSettings.PREF_LAST_COLLECTION_PUBLISH, collections)
            if (settings.sharedStateSyncEnabled && settings.sharedSettingsEnabled) recordSharedResult(EsdeSyncSettings.PREF_LAST_SETTINGS_PUBLISH, sharedSettings)
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
            val result = runCatching { requireEsdeStopped(); exportAllInternal(full = true) }
                .onFailure { recordError("Initial export failed", it) }
                .getOrElse { EsdeExportResult(errors = listOf(it.message ?: "Initial metadata export failed")) }
            if (result.gamesRead > 0 && result.successful) {
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

    fun migrateLegacySharedState(callback: (EsdeSharedStateMigrationResult) -> Unit = {}) {
        executor.execute {
            val result = runCatching {
                EsdeSharedStateMigration(
                    EsdePrivateFileBackup(File(appContext.filesDir, "esde-sync/backups/shared-migration")),
                ).migrate(gamelistsDirectory(), sharedStateSyncRoot())
            }.getOrElse { error ->
                EsdeSharedStateMigrationResult(errors = listOf(error.message ?: "Migration failed"))
            }
            mainHandler.post { callback(result) }
        }
    }

    fun ensureLegacyGamelistLocation(callback: (Boolean, String) -> Unit = { _, _ -> }) {
        executor.execute {
            val result = runCatching {
                validateFolderConfiguration()
                requireEsdeStopped()
                ensureRequiredEsdeSettingsBlocking(appContext.filesDir, settings.esdeDirectory,
                    settings.usesLegacyGamelistLocation())
            }
            result.exceptionOrNull()?.let { recordError("Could not configure ES-DE ROM gamelists", it) }
            mainHandler.post {
                callback(result.isSuccess, result.getOrElse { it.message ?: "Unknown ES-DE settings error" })
            }
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
            val next = runCatching { importSystemInternal(system) }.getOrElse {
                EsdeImportResult(invalid = 1, errors = listOf("${system.name}: ${it.message}"))
            }
            result = EsdeImportResult(
                result.matched + next.matched,
                result.unmatched + next.unmatched,
                result.invalid + next.invalid,
                result.changedGames + next.changedGames,
                result.errors + next.errors,
            )
        }
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_IMPORT, System.currentTimeMillis()).apply()
        refreshDiagnostics(full = false)
        return result
    }

    private fun importSystemInternal(system: File): EsdeImportResult {
        diagnosticsCache.invalidate(system)
        val result = bridge.importSystem(system)
        if (result.changedGames > 0) settings.pendingLocalChanges = true
        return result
    }

    private fun exportAllInternal(full: Boolean): EsdeExportResult {
        var result = EsdeExportResult()
        systemDirectories().forEach { system ->
            val next = runCatching { bridge.exportSystem(system, full) }.getOrElse {
                EsdeExportResult(errors = listOf("${system.name}: ${it.message}"))
            }
            if (next.sidecarsWritten > 0) diagnosticsCache.invalidate(system)
            result = EsdeExportResult(result.gamesRead + next.gamesRead, result.sidecarsWritten + next.sidecarsWritten, result.errors + next.errors)
        }
        if (result.sidecarsWritten > 0) settings.pendingLocalChanges = true
        preferences.edit().putLong(EsdeSyncSettings.PREF_LAST_EXPORT, System.currentTimeMillis()).apply()
        refreshDiagnostics(full = false)
        return result
    }

    @Synchronized private fun startObserver() {
        if (observer != null || stopped || !settings.enabled || !settings.bootstrapComplete) return
        val gamelists = gamelistsDirectory()
        observer = EsdeFileObserver(gamelists) { gamelist ->
            if (!stopped) runCatching { executor.execute {
                if (stopped || !settings.enabled || !isInsideGamelists(gamelist)) return@execute
                runCatching {
                    gamelist.parentFile?.let {
                        bridge.exportSystem(it).also { result ->
                            if (result.sidecarsWritten > 0) diagnosticsCache.invalidate(it)
                            check(result.successful) { result.errors.joinToString("; ") }
                        }
                    } ?: EsdeExportResult()
                }
                    .onSuccess { if (it.sidecarsWritten > 0) settings.pendingLocalChanges = true }
                    .onFailure { recordError("Observed export failed", it) }
                // Refresh invalidated diagnostics at finalization or on explicit request,
                // not by scanning every sidecar after each observed game change.
            } }.onFailure { if (!stopped) recordError("Could not queue metadata export", it) }
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

    private fun refreshDiagnostics(full: Boolean = true) {
        val systems = systemDirectories()
        val parser = EsdeGamelistParser()
        val store = EsdeSidecarStore()
        val counts = diagnosticsCache.refresh(systems, full) { system ->
            val local: Set<String> = runCatching {
                parser.parse(File(system, EsdeMetadataBridge.GAMELIST)).keys.toSet()
            }.getOrDefault(emptySet())
            EsdeSystemDiagnostics.from(local, store.scan(system))
        }
        diagnostics = diagnostics.copy(
            systemsFound = counts.systemsFound,
            sidecarsTotal = counts.sidecarsTotal,
            matched = counts.matched,
            unmatched = counts.unmatched,
            invalid = counts.invalid,
            pendingLocalChanges = settings.pendingLocalChanges,
            observerRunning = observer?.isRunning == true,
        )
    }

    private fun recordError(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        diagnostics = diagnostics.copy(lastError = "$message: ${error.message}")
    }

    private fun collectionsManager(): EsdeSharedCollectionsManager = EsdeSharedCollectionsManager(
        sharedStateSyncRoot(),
        File(settings.esdeDirectory),
        EsdeSharedSnapshotStore(File(appContext.filesDir, "esde-sync/shared-snapshots")),
        EsdePrivateFileBackup(File(appContext.filesDir, "esde-sync/backups/shared")),
    )

    private fun settingsManager(): EsdeSharedSettingsManager = EsdeSharedSettingsManager(
        sharedStateSyncRoot(),
        File(settings.esdeDirectory),
        EsdeSharedSnapshotStore(File(appContext.filesDir, "esde-sync/shared-snapshots")),
        EsdePrivateFileBackup(File(appContext.filesDir, "esde-sync/backups/shared")),
    )

    private fun esdeSettingsFile(): File = File(File(settings.esdeDirectory, "settings"), "es_settings.xml")

    private fun sharedStateSyncRoot(): File {
        check(settings.sharedStateSyncEnabled) { "ES-DE Settings & Collections synchronization is disabled" }
        val id = settings.sharedStateFolderId
        check(id.isNotBlank()) { "No ES-DE Settings & Collections sync folder is selected" }
        val folder = restApi.folders.firstOrNull { it.id == id }
            ?: error("The selected ES-DE Settings & Collections sync folder is unavailable")
        val path = folder.path?.takeIf { it.isNotBlank() }
            ?: error("The selected ES-DE Settings & Collections sync folder has no local path")
        return File(path)
    }

    private fun requireEsdeStopped() {
        validateFolderConfiguration()
        check(!settings.esdeWasLaunched) { "ES-DE is running; shared state can only be applied before Safe Launch" }
        check(EsdeProcessController.isConfirmedStopped(appContext, settings.applicationPackage)) {
            "Android cannot confirm ES-DE is closed. Open ES-DE app info, select Force stop, then return and Retry."
        }
        require(esdeSettingsFile().isFile) { "Missing ES-DE settings/es_settings.xml" }
    }

    private fun validateFolderConfiguration() {
        val folders = restApi.folders.associateBy { it.id }
        settings.requiredFolderIds().forEach { id ->
            val folder = folders[id] ?: error("Selected sync folder is unavailable: $id")
            require(folder.getDevice(settings.primaryDeviceId) != null) { "${folder.label}: not shared with the primary device" }
        }
        val rom = folders[settings.romFolderId] ?: error("Select the ROM / gamelist sync folder")
        val shared = if (settings.sharedStateSyncEnabled) sharedStateSyncRoot() else null
        require(!rom.path.isNullOrBlank()) { "ROM folder has no local path" }
        EsdeFolderConfiguration.validate(File(rom.path), gamelistsDirectory(), File(settings.esdeDirectory), shared)
    }

    private fun sharedAction(
        timestampKey: String,
        callback: (EsdeSharedOperationResult) -> Unit,
        action: () -> EsdeSharedOperationResult,
    ) {
        executor.execute {
            val result = runCatching { requireEsdeStopped(); action() }.getOrElse { error ->
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
            .putString(EsdeSyncSettings.PREF_LAST_SHARED_WARNINGS, result.warnings.joinToString())
            .apply()
    }

    companion object { private const val TAG = "ESDESync" }
}
