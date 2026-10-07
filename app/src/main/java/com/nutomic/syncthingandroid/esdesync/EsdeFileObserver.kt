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
    @Volatile private var stopped = false

    val isRunning: Boolean get() = observers.isNotEmpty()

    @Synchronized fun start() {
        if (stopped || !gamelistsDirectory.isDirectory) return
        observeDirectory(gamelistsDirectory, true)
        refreshSystems()
    }

    @Synchronized fun stop() {
        stopped = true
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        scheduler.shutdownNow()
    }

    @Synchronized private fun refreshSystems() {
        if (stopped) return
        val directories = gamelistsDirectory.listFiles { file -> file.isDirectory &&
            file.canonicalFile == File(gamelistsDirectory.canonicalFile, file.name) }.orEmpty()
        val active = directories.mapTo(mutableSetOf(gamelistsDirectory.absolutePath)) { it.absolutePath }
        observers.keys.filter { it !in active }.forEach { observers.remove(it)?.stopWatching() }
        directories.forEach { observeDirectory(it, false) }
    }

    private fun observeDirectory(directory: File, root: Boolean) {
        if (observers.containsKey(directory.absolutePath)) return
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(directory.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (stopped) return
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

    @Synchronized private fun debounce(gamelist: File) {
        if (stopped) return
        val key = gamelist.absolutePath
        pending.remove(key)?.cancel(false)
        pending[key] = scheduler.schedule({
            pending.remove(key)
            if (!stopped && gamelist.isFile) onGamelistChanged(gamelist)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    companion object { const val DEBOUNCE_MS = 900L }
}
