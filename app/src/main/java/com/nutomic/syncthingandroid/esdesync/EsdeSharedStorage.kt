package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object EsdeHashes {
    fun file(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal class EsdeSharedSnapshotStore(private val root: File, private val gson: Gson = Gson()) {
    fun load(namespace: String, key: String): EsdeSharedSnapshot? {
        val file = target(namespace, key)
        if (!file.isFile || file.length() > 16 * 1024) return null
        return runCatching { file.reader().use { gson.fromJson(it, EsdeSharedSnapshot::class.java) } }.getOrNull()
    }

    fun save(namespace: String, key: String, snapshot: EsdeSharedSnapshot) {
        AtomicFileWriter.write(target(namespace, key)) { output ->
            output.writer().apply { gson.toJson(snapshot, this); flush() }
        }
    }

    private fun target(namespace: String, key: String): File {
        require(namespace.matches(SAFE))
        val encoded = EsdeHashes.text(key)
        return File(File(root, namespace), "$encoded.json")
    }

    companion object { private val SAFE = Regex("^[A-Za-z0-9._-]+$") }
}

internal class EsdePrivateFileBackup(private val root: File) {
    fun create(category: String, source: File) {
        require(category.matches(Regex("^[A-Za-z0-9._-]+$")))
        require(source.isFile)
        val directory = File(root, category)
        require(directory.exists() || directory.mkdirs()) { "Cannot create backup directory" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "$stamp-$safeName")
        AtomicFileWriter.write(target) { output -> source.inputStream().use { it.copyTo(output) } }
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_BACKUPS)?.forEach { it.delete() }
    }

    companion object { private const val MAX_BACKUPS = 10 }
}
