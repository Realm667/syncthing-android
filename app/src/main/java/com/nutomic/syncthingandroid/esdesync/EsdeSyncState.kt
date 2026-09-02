package com.nutomic.syncthingandroid.esdesync

import com.nutomic.syncthingandroid.model.RemoteNeedItem

enum class EsdeSyncState {
    NOT_CONFIGURED,
    STARTING,
    WAITING_FOR_PRIMARY,
    RESCANNING,
    SYNCING,
    IMPORTING_METADATA,
    READY_TO_PLAY,
    OFFLINE_PLAYING,
    OFFLINE_CHANGES_PENDING,
    RECONNECTING,
    RECONCILING_OFFLINE_CHANGES,
    ESDE_RUNNING,
    EXPORTING_METADATA,
    SYNCING_AFTER_PLAY,
    SAFE_TO_SWITCH,
    IDLE,
    ERROR,
}

object EsdeSafeLaunchCompletionPolicy {
    fun afterDone(current: EsdeSyncState): EsdeSyncState =
        if (current == EsdeSyncState.SAFE_TO_SWITCH) EsdeSyncState.IDLE else current
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
    val label: String = id,
    val conflictFiles: List<String> = emptyList(),
    val remoteNeedKnown: Boolean = false,
    val remoteBlockingItems: Long = 0,
    val remoteIgnoredItems: Long = 0,
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
        if (input.folders.any { folder ->
                val rawRemotePending = folder.remoteCompletion < 100 || folder.remoteNeedBytes > 0.0 ||
                    folder.remoteNeedItems > 0
                val blockingRemotePending = if (folder.remoteNeedKnown) {
                    folder.remoteBlockingItems > 0
                } else {
                    rawRemotePending
                }
                folder.state != "idle" || folder.needFiles > 0 || folder.needBytes > 0 ||
                    folder.needTotalItems > 0 || blockingRemotePending || folder.remoteState != "valid"
            }) return EsdeSyncState.SYNCING
        return EsdeSyncState.READY_TO_PLAY
    }
}

object EsdeRemoteNeedPolicy {
    fun isBlocking(item: RemoteNeedItem): Boolean {
        val path = item.name.replace('\\', '/').trimStart('/')
        if (path.isBlank()) return true
        if (path.substringAfterLast('/').equals("gamelist.xml", ignoreCase = true)) return false
        if (item.type.contains("DIRECTORY", ignoreCase = true)) return false
        return true
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
    ROM_FOLDER,
    SHARED_STATE_FOLDER,
    INITIAL_METADATA_SOURCE,
}

data class EsdeSetupInput(
    val enabled: Boolean,
    val esdeDirectorySelected: Boolean,
    val gamelistDirectorySelected: Boolean,
    val applicationSelected: Boolean,
    val primaryDeviceSelected: Boolean,
    val gamingFoldersSelected: Boolean,
    val romFolderSelected: Boolean,
    val sharedStateFolderReady: Boolean,
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
        if (!input.romFolderSelected) add(EsdeSetupRequirement.ROM_FOLDER)
        if (!input.sharedStateFolderReady) add(EsdeSetupRequirement.SHARED_STATE_FOLDER)
        if (!input.metadataSourceReady) add(EsdeSetupRequirement.INITIAL_METADATA_SOURCE)
    }
}

object EsdeFirstSetupPolicy {
    fun shouldOpenAutomatically(
        offered: Boolean,
        complete: Boolean,
        deferred: Boolean,
    ): Boolean = offered && !complete && !deferred

    fun canChooseSyncTargets(apiReady: Boolean): Boolean = apiReady

    fun canFinish(
        coreComplete: Boolean,
        apiReady: Boolean,
        coordinatorReady: Boolean,
        role: String,
        sourceInitialized: Boolean,
    ): Boolean = coreComplete && apiReady && coordinatorReady &&
        (role != EsdeSyncSettings.ROLE_SOURCE || sourceInitialized)
}

enum class EsdeIgnoreRuleState { ACTIVE, MISSING, CONFLICTING_INCLUDE }

object EsdeRomIgnoreRules {
    fun evaluate(lines: Collection<String>): EsdeIgnoreRuleState {
        val normalized = lines.mapNotNull(::normalizeEffective)
        if (normalized.any { (included, pattern) -> included && pattern in GAMELIST_PATTERNS }) {
            return EsdeIgnoreRuleState.CONFLICTING_INCLUDE
        }
        val first = normalized.firstOrNull() ?: return EsdeIgnoreRuleState.MISSING
        return if (!first.first && first.second in GAMELIST_PATTERNS) {
            EsdeIgnoreRuleState.ACTIVE
        } else EsdeIgnoreRuleState.MISSING
    }

    fun placeIgnoreRuleFirst(lines: Collection<String>): List<String> = buildList {
        add(IGNORE_RULE)
        addAll(lines.filterNot { raw ->
            val (included, pattern) = normalize(raw)
            (!included && pattern in GAMELIST_PATTERNS) || (included && pattern in EsdeSharedStateIgnoreRules.INCLUDE_PATTERNS)
        })
    }

    private fun normalize(raw: String): Pair<Boolean, String> {
        var line = raw.trim()
        while (line.startsWith("(?i)") || line.startsWith("(?d)")) line = line.drop(4)
        val included = line.startsWith('!')
        if (included) line = line.drop(1)
        return included to line
    }

    private fun normalizeEffective(raw: String): Pair<Boolean, String>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        return normalize(trimmed)
    }

    private val GAMELIST_PATTERNS = setOf("gamelist.xml", "**/gamelist.xml")
    const val IGNORE_RULE = "gamelist.xml"
}

object EsdeSharedStateIgnoreRules {
    internal val INCLUDE_PATTERNS = listOf(
        "/.esde-sync-global",
        "/.esde-sync-global/**",
    )
    internal val REQUIRED_RULES = INCLUDE_PATTERNS.map { "!$it" }

    fun evaluate(lines: Collection<String>): EsdeIgnoreRuleState {
        val normalized = lines.mapNotNull(::normalizeEffective)
        val valid = normalized.take(INCLUDE_PATTERNS.size) == INCLUDE_PATTERNS.map { true to it }
        return if (valid) EsdeIgnoreRuleState.ACTIVE else EsdeIgnoreRuleState.MISSING
    }

    fun placeRulesFirst(lines: Collection<String>): List<String> = buildList {
        addAll(REQUIRED_RULES)
        addAll(lines.filterNot { raw ->
            val (included, pattern) = normalize(raw)
            included && pattern in INCLUDE_PATTERNS
        })
    }

    private fun normalize(raw: String): Pair<Boolean, String> {
        var line = raw.trim()
        while (line.startsWith("(?i)") || line.startsWith("(?d)")) line = line.drop(4)
        val included = line.startsWith('!')
        if (included) line = line.drop(1)
        return included to line
    }

    private fun normalizeEffective(raw: String): Pair<Boolean, String>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        return normalize(trimmed)
    }
}
