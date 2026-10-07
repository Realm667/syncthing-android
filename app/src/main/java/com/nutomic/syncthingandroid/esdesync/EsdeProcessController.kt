package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.content.pm.ApplicationInfo

internal object EsdeProcessController {
    fun isConfirmedStopped(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        // A process missing from runningAppProcesses is not evidence of termination.
        // Android 14+ cannot stop another app with killBackgroundProcesses. The system
        // stopped flag provides a positive confirmation after the user force-stops ES-DE.
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.flags and ApplicationInfo.FLAG_STOPPED != 0
        }.getOrDefault(false)
    }
}
