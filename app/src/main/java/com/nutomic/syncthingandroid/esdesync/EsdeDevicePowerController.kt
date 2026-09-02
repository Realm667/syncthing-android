package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.content.Intent
import java.io.IOException

internal enum class EsdePowerOffResult {
    ROOT_REQUESTED,
    UNSUPPORTED,
}

internal object EsdePowerOffPolicy {
    fun canRequest(
        state: EsdeSyncState,
        esdeWasLaunched: Boolean,
        pendingLocalChanges: Boolean,
        hasOfflineJournal: Boolean,
        activeSessionId: String,
    ): Boolean = state == EsdeSyncState.IDLE &&
        !esdeWasLaunched &&
        !pendingLocalChanges &&
        !hasOfflineJournal &&
        activeSessionId.isBlank()
}

/**
 * Requests an Android power-off without pretending that ordinary third-party applications own
 * the protected shutdown permission. Vendor firmware may expose the system shutdown dialog;
 * rooted handhelds can use the fixed, non-user-controlled `reboot -p` command as a fallback.
 */
internal class EsdeDevicePowerController(private val context: Context) {
    fun openSystemShutdownDialog(): Boolean = REQUEST_SHUTDOWN_ACTIONS.any { action ->
        runCatching {
            val intent = Intent(action)
                .putExtra(EXTRA_KEY_CONFIRM, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(intent.resolveActivity(context.packageManager) != null) { "No system shutdown activity" }
            context.startActivity(intent)
        }.isSuccess
    }

    fun requestRootPowerOff(): EsdePowerOffResult {
        val process = try {
            ProcessBuilder("su", "-c", "reboot -p")
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return EsdePowerOffResult.UNSUPPORTED
        } catch (_: SecurityException) {
            return EsdePowerOffResult.UNSUPPORTED
        }

        val deadline = System.currentTimeMillis() + ROOT_RESPONSE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            if (exitCode != null) {
                return if (exitCode == 0) EsdePowerOffResult.ROOT_REQUESTED
                else EsdePowerOffResult.UNSUPPORTED
            }
            try {
                Thread.sleep(ROOT_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroy()
                return EsdePowerOffResult.UNSUPPORTED
            }
        }
        process.destroy()
        return EsdePowerOffResult.UNSUPPORTED
    }

    companion object {
        private val REQUEST_SHUTDOWN_ACTIONS = listOf(
            "com.android.internal.intent.action.REQUEST_SHUTDOWN",
            "android.intent.action.ACTION_REQUEST_SHUTDOWN",
        )
        private const val EXTRA_KEY_CONFIRM = "android.intent.extra.KEY_CONFIRM"
        private const val ROOT_RESPONSE_TIMEOUT_MS = 8_000L
        private const val ROOT_POLL_INTERVAL_MS = 100L
    }
}
