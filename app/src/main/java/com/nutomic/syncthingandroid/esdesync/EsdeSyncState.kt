package com.nutomic.syncthingandroid.esdesync

enum class EsdeSyncState {
    NOT_CONFIGURED,
    STARTING,
    WAITING_FOR_PRIMARY,
    RESCANNING,
    SYNCING,
    IMPORTING_METADATA,
    READY_TO_PLAY,
    OFFLINE_OVERRIDE,
    ESDE_RUNNING,
    EXPORTING_METADATA,
    SYNCING_AFTER_PLAY,
    SAFE_TO_SWITCH,
    ERROR,
}

data class EsdeFolderHealth(
    val id: String,
    val paused: Boolean,
    val state: String,
    val error: String,
    val needFiles: Long,
    val needBytes: Long,
    val needTotalItems: Long,
    val pullErrors: Long,
    val remoteCompletion: Int,
    val remoteNeedBytes: Double,
    val conflicts: Int,
    val remoteNeedItems: Long = 0,
    val remoteState: String = "valid",
)

data class EsdeGateInput(
    val configured: Boolean,
    val serviceActive: Boolean,
    val primaryConnected: Boolean,
    val primaryPaused: Boolean,
    val folders: List<EsdeFolderHealth>,
)

object EsdeSyncStateEvaluator {
    fun evaluate(input: EsdeGateInput): EsdeSyncState {
        if (!input.configured) return EsdeSyncState.NOT_CONFIGURED
        if (!input.serviceActive) return EsdeSyncState.STARTING
        if (!input.primaryConnected || input.primaryPaused) return EsdeSyncState.WAITING_FOR_PRIMARY
        if (input.folders.isEmpty()) return EsdeSyncState.NOT_CONFIGURED
        if (input.folders.any { it.error.isNotBlank() || it.pullErrors > 0 || it.conflicts > 0 }) {
            return EsdeSyncState.ERROR
        }
        if (input.folders.any { it.paused }) return EsdeSyncState.ERROR
        if (input.folders.any {
                it.state != "idle" || it.needFiles > 0 || it.needBytes > 0 || it.needTotalItems > 0 ||
                    it.remoteCompletion < 100 || it.remoteNeedBytes > 0.0 || it.remoteNeedItems > 0 ||
                    it.remoteState != "valid"
            }) return EsdeSyncState.SYNCING
        return EsdeSyncState.READY_TO_PLAY
    }
}

enum class EsdeBootstrapAction { IMPORT_EXISTING, REQUIRE_SOURCE_CONFIRMATION, START_OBSERVING }

object EsdeBootstrapEvaluator {
    fun evaluate(bootstrapComplete: Boolean, sidecarsExist: Boolean): EsdeBootstrapAction = when {
        bootstrapComplete -> EsdeBootstrapAction.START_OBSERVING
        sidecarsExist -> EsdeBootstrapAction.IMPORT_EXISTING
        else -> EsdeBootstrapAction.REQUIRE_SOURCE_CONFIRMATION
    }
}

enum class EsdeSetupRequirement {
    ENABLE_SYNC,
    ESDE_DIRECTORY,
    GAMELIST_DIRECTORY,
    ESDE_APPLICATION,
    PRIMARY_DEVICE,
    GAMING_FOLDERS,
    INITIAL_METADATA_SOURCE,
}

data class EsdeSetupInput(
    val enabled: Boolean,
    val esdeDirectorySelected: Boolean,
    val gamelistDirectorySelected: Boolean,
    val applicationSelected: Boolean,
    val primaryDeviceSelected: Boolean,
    val gamingFoldersSelected: Boolean,
    val metadataSourceReady: Boolean,
)

object EsdeSetupEvaluator {
    fun missing(input: EsdeSetupInput): Set<EsdeSetupRequirement> = buildSet {
        if (!input.enabled) add(EsdeSetupRequirement.ENABLE_SYNC)
        if (!input.esdeDirectorySelected) add(EsdeSetupRequirement.ESDE_DIRECTORY)
        if (!input.gamelistDirectorySelected) add(EsdeSetupRequirement.GAMELIST_DIRECTORY)
        if (!input.applicationSelected) add(EsdeSetupRequirement.ESDE_APPLICATION)
        if (!input.primaryDeviceSelected) add(EsdeSetupRequirement.PRIMARY_DEVICE)
        if (!input.gamingFoldersSelected) add(EsdeSetupRequirement.GAMING_FOLDERS)
        if (!input.metadataSourceReady) add(EsdeSetupRequirement.INITIAL_METADATA_SOURCE)
    }
}

enum class EsdeIgnoreRuleState { ACTIVE, MISSING, CONFLICTING_INCLUDE }

object EsdeIgnoreRules {
    fun evaluate(lines: Collection<String>): EsdeIgnoreRuleState {
        val normalized = lines.map(::normalize)
        if (normalized.any { (included, pattern) -> included && pattern in GAMELIST_PATTERNS }) {
            return EsdeIgnoreRuleState.CONFLICTING_INCLUDE
        }
        var includeSeen = false
        normalized.forEach { (included, pattern) ->
            if (included) includeSeen = true
            if (!included && pattern in GAMELIST_PATTERNS) {
                return if (includeSeen) EsdeIgnoreRuleState.MISSING else EsdeIgnoreRuleState.ACTIVE
            }
        }
        return EsdeIgnoreRuleState.MISSING
    }

    fun placeIgnoreRuleFirst(lines: Collection<String>): List<String> = buildList {
        add("gamelist.xml")
        addAll(lines.filterNot { raw ->
            val (included, pattern) = normalize(raw)
            !included && pattern in GAMELIST_PATTERNS
        })
    }

    private fun normalize(raw: String): Pair<Boolean, String> {
        var line = raw.trim()
        while (line.startsWith("(?i)") || line.startsWith("(?d)")) line = line.drop(4)
        val included = line.startsWith('!')
        if (included) line = line.drop(1)
        return included to line
    }

    private val GAMELIST_PATTERNS = setOf("gamelist.xml", "**/gamelist.xml")
}
