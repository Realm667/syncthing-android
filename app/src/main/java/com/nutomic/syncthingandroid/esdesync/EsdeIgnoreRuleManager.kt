package com.nutomic.syncthingandroid.esdesync

import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.service.RestApi
import java.util.concurrent.atomic.AtomicInteger

class EsdeIgnoreRuleManager(private val restApi: RestApi) {
    data class Result(val checked: Int, val updated: Int, val failed: Int, val conflicting: Int)

    fun ensure(folderIds: Collection<String>, callback: (Result) -> Unit) {
        if (folderIds.isEmpty()) {
            callback(Result(0, 0, 0, 0))
            return
        }
        val remaining = AtomicInteger(folderIds.size)
        var updated = 0
        var failed = 0
        var conflicting = 0
        fun done() {
            if (remaining.decrementAndGet() == 0) callback(Result(folderIds.size, updated, failed, conflicting))
        }
        folderIds.forEach { folderId ->
            restApi.getFolderIgnoreList(folderId, { response: FolderIgnoreList ->
                try {
                    val existing = response.ignore?.toMutableList() ?: mutableListOf()
                    when (EsdeIgnoreRules.evaluate(existing)) {
                        EsdeIgnoreRuleState.ACTIVE -> Unit
                        EsdeIgnoreRuleState.CONFLICTING_INCLUDE -> synchronized(this) { conflicting++ }
                        EsdeIgnoreRuleState.MISSING -> {
                            val corrected = EsdeIgnoreRules.placeIgnoreRuleFirst(existing)
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

    companion object {
        const val IGNORE_RULE = "gamelist.xml"

        fun hasSuitableRule(lines: Collection<String>): Boolean =
            EsdeIgnoreRules.evaluate(lines) == EsdeIgnoreRuleState.ACTIVE
    }
}
