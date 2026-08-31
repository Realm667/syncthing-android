package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

object EsdeLegacyGamelistConfigurator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ESDESync-LegacyConfig")
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensure(
        context: Context,
        settings: EsdeSyncSettings,
        callback: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                ensureRequiredEsdeSettingsBlocking(
                    appFilesDirectory = appContext.filesDir,
                    esdeDirectory = settings.esdeDirectory,
                    legacyLocationRequired = settings.usesLegacyGamelistLocation(),
                )
            }
            mainHandler.post {
                callback(result.isSuccess, result.getOrElse { it.message ?: "Unknown ES-DE settings error" })
            }
        }
    }

}

internal fun ensureRequiredEsdeSettingsBlocking(
    appFilesDirectory: File,
    esdeDirectory: String,
    legacyLocationRequired: Boolean,
): String {
    require(esdeDirectory.isNotBlank()) { "Select the ES-DE application data directory first" }
    val root = File(esdeDirectory).canonicalFile
    val settingsFile = File(root, "settings/es_settings.xml").canonicalFile
    val prefix = root.path.trimEnd(File.separatorChar) + File.separator
    require(settingsFile.path.startsWith(prefix)) { "ES-DE settings file escaped its configured root" }
    require(settingsFile.isFile) { "Missing ES-DE settings file: ${settingsFile.path}" }
    val backup = File(appFilesDirectory, "esde-sync/backups/settings/es_settings.xml")
    if (!backup.isFile) {
        AtomicFileWriter.write(backup) { output -> settingsFile.inputStream().use { it.copyTo(output) } }
    }
    val changed = EsdeSettingsEditor().ensureSafeSyncRequirements(settingsFile, legacyLocationRequired)
    return when {
        changed == 0 -> "Required ES-DE settings are active"
        legacyLocationRequired -> "Enabled ROM gamelists and immediate gamelist saving"
        else -> "Enabled immediate gamelist saving"
    }
}
