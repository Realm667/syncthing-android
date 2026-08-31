package com.nutomic.syncthingandroid.esdesync

import android.app.ActivityManager
import android.content.Context

internal object EsdeProcessController {
    fun stopBackgroundProcess(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.killBackgroundProcesses(packageName)
        Thread.sleep(PROCESS_SETTLE_MS)
        return manager.runningAppProcesses.orEmpty().none { process ->
            process.processName == packageName || process.pkgList?.contains(packageName) == true
        }
    }

    private const val PROCESS_SETTLE_MS = 350L
}
