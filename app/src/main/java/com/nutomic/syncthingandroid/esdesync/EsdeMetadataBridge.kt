package com.nutomic.syncthingandroid.esdesync

import java.io.File

class EsdeMetadataBridge(
    private val parser: EsdeGamelistParser,
    private val sidecars: EsdeSidecarStore,
    private val snapshots: EsdeSnapshotStore,
    private val backups: EsdeBackupManager,
) {
    fun exportSystem(systemDirectory: File, full: Boolean = false): EsdeExportResult {
        val gamelist = File(systemDirectory, GAMELIST)
        if (!gamelist.isFile) return EsdeExportResult()
        val current = parser.parse(gamelist)
        val previous = snapshots.load(systemDirectory.name)
        var writes = 0
        current.forEach { (path, metadata) ->
            if ((full || previous[path] != metadata) && sidecars.write(systemDirectory, path, metadata)) writes++
        }
        snapshots.save(systemDirectory.name, current)
        return EsdeExportResult(current.size, writes)
    }

    fun importSystem(systemDirectory: File): EsdeImportResult {
        val gamelist = File(systemDirectory, GAMELIST)
        if (!gamelist.isFile) return EsdeImportResult()
        val scan = sidecars.scan(systemDirectory)
        if (scan.states.isEmpty()) return EsdeImportResult(invalid = scan.invalid)
        val local = parser.parse(gamelist)
        val matchedValues = scan.states.filterKeys { it in local }
        if (matchedValues.any { (path, value) -> value != local[path] }) {
            backups.backupOnce(systemDirectory.name, gamelist)
        }
        val applied = parser.apply(gamelist, scan.states)
        snapshots.save(systemDirectory.name, parser.parse(gamelist))
        return EsdeImportResult(applied.matched, applied.unmatched, scan.invalid, applied.changed)
    }

    companion object { const val GAMELIST = "gamelist.xml" }
}
