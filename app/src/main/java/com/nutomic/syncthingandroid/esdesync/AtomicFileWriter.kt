package com.nutomic.syncthingandroid.esdesync

import java.io.File
import java.io.FileOutputStream

internal object AtomicFileWriter {
    fun write(target: File, block: (FileOutputStream) -> Unit) {
        target.parentFile?.let { require(it.exists() || it.mkdirs()) { "Cannot create ${it.path}" } }
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        val backup = File(target.parentFile, ".${target.name}.previous")
        try {
            FileOutputStream(temporary).use { output ->
                block(output)
                output.flush()
                output.fd.sync()
            }
            // Android/Linux rename replaces an existing sibling atomically.
            if (temporary.renameTo(target)) return
            // Conservative fallback for filesystems (notably Windows tests) that
            // refuse replacement: keep the old file recoverable until commit.
            if (target.exists()) {
                if (backup.exists()) backup.delete()
                check(target.renameTo(backup)) { "Cannot stage previous ${target.path}" }
            }
            if (!temporary.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot atomically replace ${target.path}")
            }
            if (backup.exists()) backup.delete()
        } finally {
            if (temporary.exists()) temporary.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
        }
    }
}
