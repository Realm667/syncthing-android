package com.nutomic.syncthingandroid.esdesync

import android.app.ActivityManager
import android.content.Context

internal object EsdeProcessController {
    fun stopBackgroundProcess(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return EsdeProcessStopPolicy.stop(
            requestStop = { manager.killBackgroundProcesses(packageName) },
            isRunning = {
                manager.runningAppProcesses.orEmpty().any { process ->
                    process.processName == packageName || process.pkgList?.contains(packageName) == true
                }
            },
            wait = Thread::sleep,
        )
    }
}

internal object EsdeProcessStopPolicy {
    fun stop(
        attempts: Int = 20,
        intervalMs: Long = 250L,
        requestStop: () -> Unit,
        isRunning: () -> Boolean,
        wait: (Long) -> Unit,
    ): Boolean {
        require(attempts > 0)
        repeat(attempts) { attempt ->
            requestStop()
            if (!isRunning()) return true
            if (attempt < attempts - 1) wait(intervalMs)
        }
        return false
    }
}
