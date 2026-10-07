package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EsdeSidecarStore(private val gson: Gson = Gson()) {
    data class ScanResult(
        val states: LinkedHashMap<String, EsdeMetadata>,
        val total: Int,
        val invalid: Int,
    )

    fun write(systemDirectory: File, gamePath: String, metadata: EsdeMetadata): Boolean {
        val normalized = EsdePathPolicy.normalizeGamePath(gamePath)
        val target = EsdePathPolicy.sidecarFile(systemDirectory, normalized)
        val state = EsdeGameState(
            game = normalized,
            favorite = metadata.favorite,
            completed = metadata.completed,
            playcount = metadata.playcount,
            playtime = metadata.playtime,
            lastplayed = metadata.lastplayed,
            altemulator = metadata.altemulator,
            players = metadata.players,
            rating = metadata.rating,
            updatedAt = utcNow(),
        )
        val json = gson.toJson(state) + "\n"
        EsdeMetadataValidation.validate(state)
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES) { "Sidecar exceeds size limit" }
        val old = if (target.isFile && target.length() <= MAX_JSON_BYTES) {
            runCatching { read(target) }.getOrNull()
        } else null
        if (old?.metadata() == metadata && old.game == normalized) return false
        AtomicFileWriter.write(target) { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).apply { write(json); flush() }
        }
        return true
    }

    fun read(file: File): EsdeGameState {
        if (!file.isFile || file.length() > MAX_JSON_BYTES) throw JsonParseException("Invalid sidecar size")
        val text = file.reader(StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(MAX_JSON_BYTES.toInt() + 1)
            var size = 0
            while (size < buffer.size) {
                val n = reader.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            if (size > MAX_JSON_BYTES) throw JsonParseException("Sidecar grew beyond size limit")
            String(buffer, 0, size)
        }
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) throw JsonParseException("Invalid sidecar size")
        val json = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw JsonParseException("Sidecar must be an object")
        for (name in listOf("schemaVersion", "playcount", "playtime")) json[name]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || !it.asString.matches(Regex("[0-9]+")) || it.asString.toLongOrNull() == null)
                throw JsonParseException("$name must be a non-negative integer")
        }
        for (name in listOf("favorite", "completed")) json[name]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw JsonParseException("$name must be a boolean")
        }
        for (name in listOf("game", "lastplayed", "altemulator", "players", "updatedAt")) json[name]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw JsonParseException("$name must be a string")
        }
        json["rating"]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) throw JsonParseException("rating must be numeric")
        }
        val state = gson.fromJson(json, EsdeGameState::class.java)
            ?: throw JsonParseException("Empty sidecar")
        if (state.schemaVersion != EsdeGameState.SCHEMA_VERSION) {
            throw JsonParseException("Unsupported schemaVersion ${state.schemaVersion}")
        }
        try { EsdeMetadataValidation.validate(state) }
        catch (error: IllegalArgumentException) { throw JsonParseException(error.message, error) }
        val normalized = try {
            EsdePathPolicy.normalizeGamePath(state.game)
        } catch (error: IllegalArgumentException) {
            throw JsonParseException(error.message, error)
        }
        if (state.game != normalized) throw JsonParseException("Sidecar game path is not canonical")
        if (state.players != null && !EsdeMetadataValidation.isValidPlayers(state.players)) {
            throw JsonParseException("Invalid players value")
        }
        if (state.rating != null && state.rating !in 0.0..1.0) throw JsonParseException("Invalid rating value")
        val systemDirectory = findSystemDirectory(file)
            ?: throw JsonParseException("Sidecar is outside .esde-sync")
        if (EsdePathPolicy.sidecarFile(systemDirectory, normalized).canonicalFile != file.canonicalFile) {
            throw JsonParseException("Sidecar filename does not match game path")
        }
        return state
    }

    fun scan(systemDirectory: File): ScanResult {
        val root = File(systemDirectory, SIDECAR_DIRECTORY)
        if (!root.isDirectory) return ScanResult(linkedMapOf(), 0, 0)
        require(root.canonicalFile.parentFile == systemDirectory.canonicalFile) { "Sidecar directory escaped its system root" }
        val files = root.walkTopDown().onEnter { directory ->
            directory.parentFile?.let { directory.canonicalFile == File(it.canonicalFile, directory.name) } == true
        }.onFail { _, error -> throw error }.filter { it.isFile && it.name.endsWith(SIDECAR_SUFFIX) }
        val states = LinkedHashMap<String, EsdeMetadata>()
        var total = 0
        var invalid = 0
        files.forEach { file ->
            require(total < 500_000) { "Sidecar scan limit exceeded" }
            total++
            try {
                val state = read(file)
                states[state.game] = state.metadata()
            } catch (_: Exception) {
                invalid++
            }
        }
        return ScanResult(states, total, invalid)
    }

    private fun findSystemDirectory(file: File): File? {
        var current = file.parentFile
        while (current != null) {
            if (current.name == SIDECAR_DIRECTORY) return current.parentFile
            current = current.parentFile
        }
        return null
    }

    companion object {
        const val SIDECAR_DIRECTORY = ".esde-sync"
        const val SIDECAR_SUFFIX = ".esde.json"
        const val MAX_JSON_BYTES = 64L * 1024L

        private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
