package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EsdeSnapshotStore(private val root: File, private val gson: Gson = Gson()) {
    fun load(system: String): LinkedHashMap<String, EsdeMetadata> {
        val file = fileFor(system)
        if (!file.isFile) return linkedMapOf()
        return runCatching {
            val type = object : TypeToken<LinkedHashMap<String, EsdeMetadata>>() {}.type
            file.reader(StandardCharsets.UTF_8).use { gson.fromJson<LinkedHashMap<String, EsdeMetadata>>(it, type) }
                ?: linkedMapOf()
        }.getOrDefault(linkedMapOf())
    }

    fun save(system: String, values: Map<String, EsdeMetadata>) {
        AtomicFileWriter.write(fileFor(system)) { output ->
            output.writer(StandardCharsets.UTF_8).apply { gson.toJson(values, this); flush() }
        }
    }

    private fun fileFor(system: String): File {
        require(Regex("^[A-Za-z0-9._-]+$").matches(system)) { "Invalid ES-DE system name" }
        return File(root, "$system.json")
    }
}

class EsdeBackupManager(private val root: File) {
    fun backupOnce(system: String, gamelist: File) {
        val directory = File(root, system)
        if (!directory.exists()) directory.mkdirs()
        val marker = File(directory, ".automatic-backup-created")
        if (marker.exists()) return
        backup(system, gamelist)
        marker.writeText("created")
    }

    fun backup(system: String, gamelist: File): File {
        require(gamelist.isFile) { "Missing gamelist.xml" }
        val directory = File(root, system)
        require(directory.exists() || directory.mkdirs()) { "Cannot create backup directory" }
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val target = File(directory, "gamelist-${formatter.format(Date())}.xml")
        AtomicFileWriter.write(target) { output -> gamelist.inputStream().use { it.copyTo(output) } }
        directory.listFiles { file -> file.name.startsWith("gamelist-") && file.name.endsWith(".xml") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_BACKUPS)
            ?.forEach { it.delete() }
        return target
    }

    companion object { const val MAX_BACKUPS = 5 }
}
