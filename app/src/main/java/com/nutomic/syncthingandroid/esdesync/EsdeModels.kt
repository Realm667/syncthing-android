package com.nutomic.syncthingandroid.esdesync

import com.google.gson.annotations.SerializedName

data class EsdeMetadata(
    @SerializedName("favorite") val favorite: Boolean? = null,
    @SerializedName("completed") val completed: Boolean? = null,
    @SerializedName("playcount") val playcount: Long? = null,
    @SerializedName("playtime") val playtime: Long? = null,
    @SerializedName("lastplayed") val lastplayed: String? = null,
    @SerializedName("altemulator") val altemulator: String? = null,
    @SerializedName("players") val players: String? = null,
    @SerializedName("rating") val rating: Double? = null,
) {
    fun isEmpty(): Boolean = favorite == null && completed == null && playcount == null &&
        playtime == null && lastplayed == null && altemulator == null && players == null && rating == null
}

data class EsdeGameState(
    @SerializedName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("game") val game: String,
    @SerializedName("favorite") val favorite: Boolean? = null,
    @SerializedName("completed") val completed: Boolean? = null,
    @SerializedName("playcount") val playcount: Long? = null,
    @SerializedName("playtime") val playtime: Long? = null,
    @SerializedName("lastplayed") val lastplayed: String? = null,
    @SerializedName("altemulator") val altemulator: String? = null,
    @SerializedName("players") val players: String? = null,
    @SerializedName("rating") val rating: Double? = null,
    @SerializedName("updatedAt") val updatedAt: String? = null,
) {
    fun metadata() = EsdeMetadata(favorite, completed, playcount, playtime, lastplayed, altemulator, players, rating)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

object EsdeMetadataValidation {
    fun isValidPlayers(value: String): Boolean {
        val match = Regex("^(\\d{1,2})(?:-(\\d{1,2}))?$").matchEntire(value) ?: return false
        val first = match.groupValues[1].toInt()
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: first
        return first in 1..99 && last in first..99
    }
}

data class EsdeImportResult(
    val matched: Int = 0,
    val unmatched: Int = 0,
    val invalid: Int = 0,
    val changedGames: Int = 0,
)

data class EsdeExportResult(
    val gamesRead: Int = 0,
    val sidecarsWritten: Int = 0,
)

data class EsdeInitializationResult(
    val export: EsdeExportResult = EsdeExportResult(),
    val blockedByExistingSidecars: Boolean = false,
)

data class EsdeDiagnostics(
    val systemsFound: Int = 0,
    val sidecarsTotal: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val invalid: Int = 0,
    val pendingLocalChanges: Boolean = false,
    val observerRunning: Boolean = false,
    val lastError: String? = null,
)
