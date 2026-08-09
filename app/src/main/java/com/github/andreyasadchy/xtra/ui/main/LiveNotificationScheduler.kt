package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.concurrent.TimeUnit

object LiveNotificationScheduler {

    fun enable(context: Context, baselineOnly: Boolean) {
        if (!canPostNotifications(context)) {
            disable(context)
            return
        }
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        val input = Data.Builder()
            .putBoolean(LiveNotificationWorker.INPUT_BASELINE_ONLY, baselineOnly)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateWork(input),
        )
        clearLegacyConnectionNotification(context)
    }

    fun disable(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        LiveNotificationNotifier(context).cancelLiveNotifications()
        clearLegacyConnectionNotification(context)
    }

    fun refresh(context: Context) {
        clearLegacyConnectionNotification(context)
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) && canPostNotifications(context)) {
            val input = Data.Builder()
                .putBoolean(LiveNotificationWorker.INPUT_BASELINE_ONLY, true)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateWork(input),
            )
        } else {
            disable(context)
        }
    }

    fun canPostNotifications(context: Context): Boolean = LiveNotificationNotifier(context).canPostNotifications()

    private fun immediateWork(input: Data) =
        OneTimeWorkRequestBuilder<LiveNotificationWorker>()
            .setInputData(input)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

    private fun clearLegacyConnectionNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(LEGACY_CONNECTION_CHANNEL_ID)
        }
    }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val PERIODIC_WORK_NAME = "live_notifications"
    private const val IMMEDIATE_WORK_NAME = "live_notifications_now"
    private const val LEGACY_CONNECTION_CHANNEL_ID = "xtra_live_connection_channel"
}
