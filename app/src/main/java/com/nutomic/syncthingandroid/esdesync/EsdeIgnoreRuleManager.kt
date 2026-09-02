package com.nutomic.syncthingandroid.esdesync

import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.service.RestApi
import java.util.concurrent.atomic.AtomicInteger

class EsdeIgnoreRuleManager(private val restApi: RestApi) {
    data class Result(val checked: Int, val updated: Int, val failed: Int, val conflicting: Int)

    fun ensureRom(folderId: String, callback: (Result) -> Unit) = ensure(
        targetIds = setOf(folderId).filter { id -> id.isNotBlank() && restApi.folders.any { it.id == id } }.toSet(),
        evaluate = EsdeRomIgnoreRules::evaluate,
        correct = EsdeRomIgnoreRules::placeIgnoreRuleFirst,
        callback = callback,
    )

    fun ensureSharedState(folderId: String, callback: (Result) -> Unit) = ensure(
        targetIds = setOf(folderId).filter { id -> id.isNotBlank() && restApi.folders.any { it.id == id } }.toSet(),
        evaluate = EsdeSharedStateIgnoreRules::evaluate,
        correct = EsdeSharedStateIgnoreRules::placeRulesFirst,
        callback = callback,
    )

    private fun ensure(
        targetIds: Set<String>,
        evaluate: (Collection<String>) -> EsdeIgnoreRuleState,
        correct: (Collection<String>) -> List<String>,
        callback: (Result) -> Unit,
    ) {
        if (targetIds.isEmpty()) {
            callback(Result(0, 0, 0, 0))
            return
        }
        val remaining = AtomicInteger(targetIds.size)
        var updated = 0
        var failed = 0
        var conflicting = 0
        fun done() {
            if (remaining.decrementAndGet() == 0) callback(Result(targetIds.size, updated, failed, conflicting))
        }
        targetIds.forEach { folderId ->
            restApi.getFolderIgnoreList(folderId, { response: FolderIgnoreList ->
                try {
                    val existing = response.ignore?.toMutableList() ?: mutableListOf()
                    when (evaluate(existing)) {
                        EsdeIgnoreRuleState.ACTIVE -> Unit
                        EsdeIgnoreRuleState.CONFLICTING_INCLUDE -> synchronized(this) { conflicting++ }
                        EsdeIgnoreRuleState.MISSING -> {
                            val corrected = correct(existing)
                            restApi.postFolderIgnoreList(folderId, corrected.toTypedArray())
                            synchronized(this) { updated++ }
                        }
                    }
                } catch (_: Exception) {
                    synchronized(this) { failed++ }
                } finally {
                    done()
                }
            }, {
                synchronized(this) { failed++ }
                done()
            })
        }
    }

}
