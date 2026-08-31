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
        executor.execute {
            val result = runCatching {
                if (!settings.usesLegacyGamelistLocation()) {
                    return@runCatching "Central ES-DE gamelist location is selected"
                }
                val settingsFile = esdeSettingsFile()
                val backup = File(appContext.filesDir, "esde-sync/backups/settings/es_settings.xml")
                if (!backup.isFile) {
                    AtomicFileWriter.write(backup) { output -> settingsFile.inputStream().use { it.copyTo(output) } }
                }
                val changed = EsdeSettingsEditor().enableLegacyGamelistLocation(settingsFile)
                if (changed) "Enabled LegacyGamelistFileLocation in es_settings.xml"
                else "LegacyGamelistFileLocation is already enabled"
            }
            result.onFailure { recordError("Could not configure ES-DE ROM gamelists", it) }
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

    private fun esdeSettingsFile(): File {
        val root = File(settings.esdeDirectory).canonicalFile
        val file = File(root, "settings/es_settings.xml").canonicalFile
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        require(file.path.startsWith(prefix)) { "ES-DE settings file escaped its configured root" }
        return file
    }

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

    companion object { private const val TAG = "ESDESync" }
}
