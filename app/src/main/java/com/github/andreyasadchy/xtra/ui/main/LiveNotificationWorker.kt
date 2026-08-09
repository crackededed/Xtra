package com.github.andreyasadchy.xtra.ui.main

import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import androidx.core.content.edit

class LiveNotificationWorker(
    private val context: android.content.Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val notifier = LiveNotificationNotifier(context)

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val prefs = context.prefs()
        val xtraApp = context.applicationContext as XtraApp
        val repository = xtraApp.xtraModule.notificationsRepository
        prefs.edit {
            putLong(C.LIVE_NOTIFICATION_LAST_RUN, startedAt)
        }
        if (!prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !notifier.canPostNotifications()) {
            repository.clearPendingNotificationEvents()
            notifier.cancelLiveNotifications()
            recordSuccess(startedAt, 0, "notifications_disabled")
            return Result.success()
        }

        val baselineOnly = inputData.getBoolean(INPUT_BASELINE_ONLY, false)

        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
        val useLocalFollows = (prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0) != 0

        if (!useLocalFollows && shouldSyncNotificationUsers()) {
            prefs.edit { putLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, System.currentTimeMillis()) }
            try {
                if (xtraApp.xtraModule.notificationsRepository.syncNotificationUsers(networkLibrary, gqlHeaders)) {
                    prefs.edit { putLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, System.currentTimeMillis()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notification preference sync failed; retaining the previous channel IDs", e)
            }
        }

        try {
            xtraApp.xtraModule.notificationsRepository.getNewStreams(
                networkLibrary = networkLibrary,
                gqlHeaders = gqlHeaders,
                helixHeaders = helixHeaders,
                includeFollowedStreams = false,
                preferHelix = true,
                enqueueNotificationEvents = !baselineOnly,
            )
            if (!prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !notifier.canPostNotifications()) {
                repository.clearPendingNotificationEvents()
                notifier.cancelLiveNotifications()
                recordSuccess(startedAt, 0, "notifications_disabled")
                return Result.success()
            }
            val delivered = notifier.deliverPending(xtraApp.xtraModule.notificationsRepository)
            recordSuccess(startedAt, delivered, "helix")
            Log.d(TAG, "Live notification reconciliation completed in ${SystemClock.elapsedRealtime() - startElapsed}ms; delivered=$delivered")
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(startedAt, e)
            return Result.retry()
        }
    }

    private fun shouldSyncNotificationUsers(): Boolean {
        val prefs = context.prefs()
        val now = System.currentTimeMillis()
        val lastSuccess = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, 0L)
        val lastAttempt = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, 0L)
        return (lastSuccess == 0L || now - lastSuccess >= FOLLOW_SYNC_INTERVAL_MS) &&
            (lastAttempt == 0L || now - lastAttempt >= FOLLOW_SYNC_RETRY_INTERVAL_MS)
    }

    private fun recordSuccess(startedAt: Long, delivered: Int, api: String) {
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SUCCESS, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_API, api)
            putInt(C.LIVE_NOTIFICATION_LAST_EVENT_COUNT, delivered)
            remove(C.LIVE_NOTIFICATION_LAST_ERROR)
        }
        Log.d(TAG, "Live notification worker succeeded; elapsed=${System.currentTimeMillis() - startedAt}ms; delivered=$delivered")
    }

    private fun recordFailure(startedAt: Long, error: Exception) {
        val message = "${error::class.simpleName}: ${error.message.orEmpty()}".take(500)
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_ERROR, message)
        }
        Log.w(TAG, "Live notification worker failed after ${System.currentTimeMillis() - startedAt}ms", error)
    }

    companion object {
        private const val TAG = "LiveNotificationWorker"
        private const val FOLLOW_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val FOLLOW_SYNC_RETRY_INTERVAL_MS = 30 * 60 * 1000L
        const val INPUT_BASELINE_ONLY = "baseline_only"
    }
}
