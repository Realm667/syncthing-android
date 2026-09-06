package com.nutomic.syncthingandroid.esdesync

import java.io.File

data class EsdeSystemDiagnostics(val total: Int, val invalid: Int, val matched: Int, val unmatched: Int) {
    companion object {
        fun from(local: Set<String>, scan: EsdeSidecarStore.ScanResult) = EsdeSystemDiagnostics(
            scan.total, scan.invalid, scan.states.keys.count { it in local }, scan.states.keys.count { it !in local },
        )
    }
}

/** Owned by the coordinator's serial executor; never retains DOMs or game metadata. */
class EsdeDiagnosticsCache {
    private val systems = mutableMapOf<File, EsdeSystemDiagnostics>()
    fun put(system: File, value: EsdeSystemDiagnostics) { systems[system.absoluteFile] = value }
    fun invalidate(system: File) { systems.remove(system.absoluteFile) }

    fun refresh(
        directories: List<File>,
        full: Boolean,
        inspect: (File) -> EsdeSystemDiagnostics,
    ): EsdeDiagnostics {
        val active = directories.map { it.absoluteFile }.toSet()
        systems.keys.retainAll(active)
        active.forEach { if (full || it !in systems) systems[it] = inspect(it) }
        return EsdeDiagnostics(
            systemsFound = active.size,
            sidecarsTotal = systems.values.sumOf { it.total },
            invalid = systems.values.sumOf { it.invalid },
            matched = systems.values.sumOf { it.matched },
            unmatched = systems.values.sumOf { it.unmatched },
        )
    }
}
