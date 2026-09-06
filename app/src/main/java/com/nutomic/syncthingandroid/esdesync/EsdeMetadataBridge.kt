package com.nutomic.syncthingandroid.esdesync

import java.io.File

class EsdeMetadataBridge(
    private val parser: EsdeGamelistParser,
    private val sidecars: EsdeSidecarStore,
    private val snapshots: EsdeSnapshotStore,
    private val backups: EsdeBackupManager,
    private val onInspected: (File, EsdeSystemDiagnostics) -> Unit = { _, _ -> },
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
        if (previous != current) snapshots.save(systemDirectory.name, current)
        onInspected(systemDirectory, EsdeSystemDiagnostics.from(current.keys, sidecars.scan(systemDirectory)))
        return EsdeExportResult(current.size, writes)
    }

    fun importSystem(systemDirectory: File): EsdeImportResult {
        val gamelist = File(systemDirectory, GAMELIST)
        if (!gamelist.isFile) return EsdeImportResult()
        val scan = sidecars.scan(systemDirectory)
        if (scan.states.isEmpty()) {
            onInspected(systemDirectory, EsdeSystemDiagnostics.from(emptySet(), scan))
            return EsdeImportResult(invalid = scan.invalid)
        }
        val snapshot = parser.applyWithSnapshot(gamelist, scan.states) {
            backups.backupOnce(systemDirectory.name, gamelist)
        }
        val applied = snapshot.result
        snapshots.save(systemDirectory.name, snapshot.metadata)
        onInspected(systemDirectory, EsdeSystemDiagnostics.from(snapshot.metadata.keys, scan))
        return EsdeImportResult(applied.matched, applied.unmatched, scan.invalid, applied.changed)
    }

    companion object { const val GAMELIST = "gamelist.xml" }
}
