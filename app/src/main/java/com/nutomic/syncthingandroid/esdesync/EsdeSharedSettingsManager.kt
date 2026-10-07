package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal class EsdeSharedSettingsManager(
    sharedStateSyncRoot: File,
    private val esdeRoot: File,
    private val snapshots: EsdeSharedSnapshotStore,
    private val backups: EsdePrivateFileBackup,
    private val gson: Gson = Gson(),
) {
    private val sharedFile = File(
        File(File(sharedStateSyncRoot, EsdeGlobalLayout.DIRECTORY), EsdeGlobalLayout.SETTINGS_DIRECTORY),
        EsdeGlobalLayout.SETTINGS_FILE,
    )
    private val settingsFile = File(File(esdeRoot, "settings"), "es_settings.xml")
    private val editor = EsdeSettingsEditor()

    init {
        requireInside(sharedStateSyncRoot, sharedFile)
        requireInside(esdeRoot, settingsFile)
    }

    fun publish(selected: Set<String>, allowInitialize: Boolean = false): EsdeSharedOperationResult {
        if (selected.isEmpty()) return EsdeSharedOperationResult()
        val shareable = EsdeSharedSettingsCatalog.shareableSelection(selected)
        val reservedSkipped = selected.size - shareable.size
        if (shareable.isEmpty()) return EsdeSharedOperationResult(selected.size, skipped = reservedSkipped)
        sharedConflictFiles().takeIf { it.isNotEmpty() }?.let {
            return EsdeSharedOperationResult(conflicts = it)
        }
        if (!sharedFile.isFile && !allowInitialize) {
            return EsdeSharedOperationResult(
                errors = listOf("No shared settings profile exists; automatic publishing cannot create one from device defaults"),
            )
        }
        val errors = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val local = editor.read(settingsFile, shareable)
        val existing = if (sharedFile.isFile) runCatching { readProfile() }
            .getOrElse { return EsdeSharedOperationResult(errors = listOf(it.message ?: "Invalid shared settings profile")) }
            else EsdeSharedSettingsProfile()
        val output = existing.settings.filterKeys(EsdeSharedSettingsCatalog::isShareable).toMutableMap()
        var applied = 0
        var skipped = 0
        val completed = mutableMapOf<String, Pair<String, String>>()
        val decisions = mutableListOf<EsdeValueConflict>()

        shareable.sorted().forEach { name ->
            try {
                val spec = EsdeSharedSettingsCatalog.requireAllowed(name)
                val xml = local[name] ?: throw IllegalArgumentException("local value is missing")
                require(xml.type == spec.type) { "$name has unexpected XML type ${xml.type}" }
                val typed = typedXmlValue(spec, xml.value)
                val normalized = spec.normalize(typed)
                val candidate = EsdeSharedSetting(spec.type, typed)
                val localHash = valueHash(spec.type, normalized)
                val old = existing.settings[name]
                val oldHash = old?.let { valueHash(it.type, spec.normalize(it.value)) }
                if (oldHash == localHash) {
                    skipped++
                    completed[name] = localHash to localHash
                    return@forEach
                }
                val snapshot = snapshots.load("settings", name)
                if (snapshot?.withheld == true && localHash == snapshot.localHash) {
                    skipped++
                    return@forEach
                }
                if (oldHash == null && snapshot == null && !allowInitialize) {
                    // A missing shared field is not permission for automatic publishing to promote
                    // this device's local default. Only the explicit source action may add it.
                    skipped++
                    return@forEach
                }
                if (oldHash != null && (snapshot == null || oldHash != snapshot.sharedHash)) {
                    if (snapshot != null && localHash == snapshot.localHash) {
                        skipped++ // Only the synchronized side changed; preserve it for import.
                        return@forEach
                    }
                    conflicts += name
                    decisions += EsdeValueConflict("settings", name, normalized,
                        spec.normalize(old!!.value), localHash, oldHash)
                    return@forEach
                }
                output[name] = candidate
                completed[name] = localHash to localHash
                applied++
            } catch (error: Exception) {
                errors += "$name (${error.message ?: "invalid"})"
            }
        }
        if (applied > 0) writeProfile(EsdeSharedSettingsProfile(settings = output.toSortedMap()))
        completed.forEach { (name, hashes) -> snapshots.save("settings", name, EsdeSharedSnapshot(hashes.first, hashes.second)) }
        return EsdeSharedOperationResult(selected.size, applied, skipped + reservedSkipped, conflicts, errors, decisions = decisions)
    }

    fun importSelected(selected: Set<String>): EsdeSharedOperationResult {
        if (selected.isEmpty()) return EsdeSharedOperationResult()
        val shareable = EsdeSharedSettingsCatalog.shareableSelection(selected)
        val reservedSkipped = selected.size - shareable.size
        if (shareable.isEmpty()) return EsdeSharedOperationResult(selected.size, skipped = reservedSkipped)
        sharedConflictFiles().takeIf { it.isNotEmpty() }?.let {
            return EsdeSharedOperationResult(conflicts = it)
        }
        if (!sharedFile.isFile) return EsdeSharedOperationResult(skipped = selected.size)
        val profile = runCatching { readProfile() }
            .getOrElse { return EsdeSharedOperationResult(errors = listOf(it.message ?: "Invalid shared settings profile")) }
        val local = editor.read(settingsFile, shareable + "Theme")
        val changes = linkedMapOf<String, EsdeSettingsEditor.XmlSetting>()
        val completed = mutableMapOf<String, EsdeSharedSnapshot>()
        val conflicts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val decisions = mutableListOf<EsdeValueConflict>()
        var skipped = reservedSkipped

        shareable.sorted().forEach { name ->
            val shared = profile.settings[name]
            if (shared == null) {
                skipped++ // Missing shared fields intentionally preserve local XML.
                return@forEach
            }
            try {
                val spec = EsdeSharedSettingsCatalog.requireAllowed(name)
                require(shared.type == spec.type) { "$name has wrong profile type" }
                val sharedValue = spec.normalize(shared.value)
                val sharedHash = valueHash(spec.type, sharedValue)
                val current = local[name]
                val localHash = current?.let { valueHash(it.type, it.value) }
                if (localHash == sharedHash) {
                    skipped++
                    completed[name] = EsdeSharedSnapshot(sharedHash, sharedHash)
                    return@forEach
                }
                if (name == "Theme" && !themeExists(sharedValue)) {
                    skipped++
                    completed[name] = EsdeSharedSnapshot(localHash.orEmpty(), sharedHash, withheld = true)
                    warnings += "$name was not found locally and was left unchanged"
                    return@forEach
                }
                if (name == "ThemeVariant" && !themeVariantExists(sharedValue, changes, local)) {
                    skipped++
                    completed[name] = EsdeSharedSnapshot(localHash.orEmpty(), sharedHash, withheld = true)
                    warnings += "$name was not found locally and was left unchanged"
                    return@forEach
                }
                val snapshot = snapshots.load("settings", name)
                // With no snapshot this device has never participated in this setting. The validated
                // shared value is authoritative on first import, preventing fresh device defaults
                // from becoming the source. A private backup is created before applying the value.
                if (current != null && snapshot != null && localHash != snapshot.localHash) {
                    if (sharedHash == snapshot.sharedHash) {
                        skipped++ // Only the local side changed. Publish it after play.
                        return@forEach
                    }
                    conflicts += name
                    decisions += EsdeValueConflict("settings", name, current.value, sharedValue, localHash.orEmpty(), sharedHash)
                    return@forEach
                }
                changes[name] = EsdeSettingsEditor.XmlSetting(spec.type, sharedValue)
                completed[name] = EsdeSharedSnapshot(sharedHash, sharedHash)
            } catch (error: Exception) {
                errors += "$name (${error.message ?: "invalid"})"
            }
        }
        if (changes.isNotEmpty()) {
            backups.create("settings", settingsFile)
            editor.apply(settingsFile, changes)
        }
        completed.forEach { (name, snapshot) -> snapshots.save("settings", name, snapshot) }
        return EsdeSharedOperationResult(selected.size, changes.size, skipped, conflicts, errors, warnings, decisions)
    }

    fun resolveConflict(conflict: EsdeValueConflict, useShared: Boolean) {
        require(conflict.category == "settings")
        require(sharedConflictFiles().isEmpty()) { "Resolve Syncthing file conflicts first" }
        val spec = EsdeSharedSettingsCatalog.requireAllowed(conflict.name)
        require(EsdeSharedSettingsCatalog.isShareable(conflict.name))
        val local = editor.read(settingsFile, setOf(conflict.name))[conflict.name] ?: error("Local setting disappeared")
        val profile = readProfile()
        val shared = profile.settings[conflict.name] ?: error("Shared setting disappeared")
        require(valueHash(local.type, local.value) == conflict.localHash &&
            valueHash(shared.type, spec.normalize(shared.value)) == conflict.sharedHash) { "Values changed; refresh the conflict before deciding" }
        val value = if (useShared) spec.normalize(shared.value) else spec.normalize(typedXmlValue(spec, local.value))
        if (useShared) {
            if (conflict.name == "Theme") require(themeExists(value)) { "Install the selected theme first" }
            if (conflict.name == "ThemeVariant") require(themeVariantExists(value, emptyMap(), editor.read(settingsFile, setOf("Theme")))) { "Install the selected theme variant first" }
            backups.create("settings", settingsFile)
            editor.apply(settingsFile, mapOf(conflict.name to EsdeSettingsEditor.XmlSetting(spec.type, value)))
        } else {
            backups.create("shared-settings", sharedFile)
            writeProfile(profile.copy(settings = profile.settings + (conflict.name to EsdeSharedSetting(spec.type, typedXmlValue(spec, value)))))
        }
        val hash = valueHash(spec.type, value)
        snapshots.save("settings", conflict.name, EsdeSharedSnapshot(hash, hash))
    }

    private fun readProfile(): EsdeSharedSettingsProfile {
        require(sharedFile.length() in 1..MAX_PROFILE_BYTES) { "Shared settings profile has invalid size" }
        val root = sharedFile.reader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it) }
        require(root.isJsonObject) { "Shared settings profile must be an object" }
        val objectRoot = root.asJsonObject
        require(objectRoot.keySet() == setOf("schemaVersion", "settings")) { "Unknown shared profile field" }
        require(objectRoot["schemaVersion"].asInt == EsdeSharedSettingsProfile.SCHEMA_VERSION) { "Unsupported shared settings schema" }
        val settingsObject = objectRoot["settings"]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Missing shared settings map")
        val result = linkedMapOf<String, EsdeSharedSetting>()
        settingsObject.entrySet().forEach { (name, element) ->
            val spec = EsdeSharedSettingsCatalog.requireAllowed(name)
            require(element.isJsonObject) { "$name must be an object" }
            val entry = element.asJsonObject
            require(entry.keySet() == setOf("type", "value")) { "$name has unknown fields" }
            val type = entry["type"]?.asString ?: throw IllegalArgumentException("$name has no type")
            require(type == spec.type) { "$name has wrong type" }
            val valueElement = entry["value"] ?: throw IllegalArgumentException("$name has no value")
            val value: Any = when (type) {
                "bool" -> valueElement.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                "int" -> valueElement.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                "float" -> valueElement.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                "string" -> valueElement.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                else -> null
            } ?: throw IllegalArgumentException("$name has an invalid value type")
            spec.normalize(value)
            result[name] = EsdeSharedSetting(type, value)
        }
        return EsdeSharedSettingsProfile(settings = result)
    }

    private fun writeProfile(profile: EsdeSharedSettingsProfile) {
        AtomicFileWriter.write(sharedFile) { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).apply {
                gson.toJson(profile, this)
                append('\n')
                flush()
            }
        }
    }

    private fun typedXmlValue(spec: EsdeSettingSpec, value: String): Any = when (spec.type) {
        "bool" -> value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("${spec.name} is not boolean")
        "int" -> value.toLongOrNull() ?: throw IllegalArgumentException("${spec.name} is not integer")
        "float" -> value.toDoubleOrNull() ?: throw IllegalArgumentException("${spec.name} is not numeric")
        else -> value
    }

    private fun themeExists(theme: String): Boolean = theme.isBlank() || EsdeThemeCatalog(esdeRoot).find(theme) != null

    private fun themeVariantExists(
        variant: String,
        pending: Map<String, EsdeSettingsEditor.XmlSetting>,
        local: Map<String, EsdeSettingsEditor.XmlSetting>,
    ): Boolean {
        if (variant.isBlank()) return true
        val theme = pending["Theme"]?.value ?: local["Theme"]?.value ?: return false
        return EsdeThemeCatalog(esdeRoot).find(theme)?.hasVariant(variant) == true
    }

    private fun valueHash(type: String, value: String): String = EsdeHashes.text("$type\u0000$value")

    private fun sharedConflictFiles(): List<String> = sharedFile.parentFile?.listFiles()
        ?.filter { it.isFile && it.name.contains(".sync-conflict-") }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    private fun requireInside(root: File, child: File) {
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(child.canonicalPath.startsWith(prefix)) { "Shared settings path escaped its configured root" }
    }

    companion object {
        const val MAX_PROFILE_BYTES = 256L * 1024L
    }
}
