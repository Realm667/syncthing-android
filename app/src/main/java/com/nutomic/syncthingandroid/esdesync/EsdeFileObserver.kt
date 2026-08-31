package com.nutomic.syncthingandroid.esdesync

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class EsdeFileObserver(
    private val gamelistsDirectory: File,
    private val onGamelistChanged: (File) -> Unit,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ESDESync-ObserverDebounce")
    }
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    val isRunning: Boolean get() = observers.isNotEmpty()

    fun start() {
        if (!gamelistsDirectory.isDirectory) return
        observeDirectory(gamelistsDirectory, true)
        refreshSystems()
    }

    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        scheduler.shutdownNow()
    }

    private fun refreshSystems() {
        gamelistsDirectory.listFiles { file -> file.isDirectory }?.forEach { observeDirectory(it, false) }
    }

    private fun observeDirectory(directory: File, root: Boolean) {
        if (observers.containsKey(directory.absolutePath)) return
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(directory.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (root) {
                    if (event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0) refreshSystems()
                    return
                }
                if (path == EsdeMetadataBridge.GAMELIST || event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                    debounce(File(directory, EsdeMetadataBridge.GAMELIST))
                }
            }
        }
        observers[directory.absolutePath] = observer
        observer.startWatching()
    }

    private fun debounce(gamelist: File) {
        val key = gamelist.absolutePath
        pending.remove(key)?.cancel(false)
        pending[key] = scheduler.schedule({
            pending.remove(key)
            if (gamelist.isFile) onGamelistChanged(gamelist)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    companion object { const val DEBOUNCE_MS = 900L }
}
