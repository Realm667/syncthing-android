package com.nutomic.syncthingandroid.esdesync

import com.google.gson.annotations.SerializedName

data class EsdeSharedOperationResult(
    val processed: Int = 0,
    val applied: Int = 0,
    val skipped: Int = 0,
    val conflicts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val successful: Boolean get() = conflicts.isEmpty() && errors.isEmpty()
    fun summary(subject: String): String = buildString {
        append("$subject: $applied applied, $skipped skipped")
        if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflict(s): ${conflicts.joinToString()}")
        if (errors.isNotEmpty()) append(", ${errors.size} error(s): ${errors.joinToString()}")
        if (warnings.isNotEmpty()) append(", ${warnings.size} warning(s): ${warnings.joinToString()}")
    }
}

data class EsdeGlobalImportResult(
    val collections: EsdeSharedOperationResult = EsdeSharedOperationResult(),
    val settings: EsdeSharedOperationResult = EsdeSharedOperationResult(),
) {
    val successful: Boolean get() = collections.successful && settings.successful
    fun errorSummary(): String = listOfNotNull(
        collections.takeUnless { it.successful }?.summary("Shared Collections"),
        settings.takeUnless { it.successful }?.summary("Shared ES-DE Settings"),
    ).joinToString("; ")
}

data class EsdeSharedSetting(
    @SerializedName("type") val type: String,
    @SerializedName("value") val value: Any,
)

data class EsdeSharedSettingsProfile(
    @SerializedName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("settings") val settings: Map<String, EsdeSharedSetting> = emptyMap(),
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

internal data class EsdeSharedSnapshot(
    val localHash: String,
    val sharedHash: String,
)

object EsdeGlobalLayout {
    const val DIRECTORY = ".esde-sync-global"
    const val COLLECTIONS_DIRECTORY = "collections"
    const val SETTINGS_DIRECTORY = "settings"
    const val SETTINGS_FILE = "shared-settings.json"
}
