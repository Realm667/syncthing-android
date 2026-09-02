package com.nutomic.syncthingandroid.esdesync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService

object EsdeDeferredSyncScheduler {
    private const val JOB_ID = 0x45534445
    private const val NOTIFICATION_ID = 0x4553
    private const val CHANNEL_ID = "04_esde_pending_sync"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, EsdeDeferredSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    internal fun notifyReady(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ES-DE pending synchronization", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(context, EsdeSafeLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, JOB_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("SafeSync changes are waiting")
                .setContentText("A network is available. Tap to verify and finish synchronization.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Offline ES-DE changes remain stored locally. Tap to connect to the primary device, resolve conflicts and finish synchronization.",
                ))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class EsdeDeferredSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val journal = EsdeOfflineJournal(java.io.File(filesDir, "esde-sync/offline-journal.json"))
        if (journal.load() == null) return false
        ContextCompat.startForegroundService(this, Intent(this, SyncthingService::class.java))
        EsdeDeferredSyncScheduler.notifyReady(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
