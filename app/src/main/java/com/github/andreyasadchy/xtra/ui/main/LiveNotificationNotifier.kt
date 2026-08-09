package com.github.andreyasadchy.xtra.ui.main

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class LiveNotificationNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    suspend fun deliverPending(repository: NotificationsRepository): Int {
        if (!canPostNotifications()) {
            return 0
        }
        val events = repository.getPendingNotificationEvents()
        if (events.isEmpty()) {
            return 0
        }
        ensureLiveNotificationChannel()
        var delivered = 0
        var firstError: Throwable? = null
        events.forEach { event ->
            try {
                notificationManager.notify(event.channelId.hashCode(), buildNotification(event))
                repository.markNotificationDelivered(event.eventId)
                delivered += 1
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e
                }
            }
        }
        firstError?.let { throw it }
        return delivered
    }

    fun cancelLiveNotifications() {
        notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.activeNotifications
                .filter { it.notification.channelId == liveChannelId }
                .forEach { notificationManager.cancel(it.tag, it.id) }
        }
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationManager.getNotificationChannel(liveChannelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureLiveNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(liveChannelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    liveChannelId,
                    ContextCompat.getString(context, R.string.notification_live_channel_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    private fun buildNotification(event: NotificationEvent) = NotificationCompat.Builder(context, liveChannelId).apply {
        val channelName = event.channelName?.takeIf { it.isNotBlank() }
        val channelLogin = event.channelLogin?.takeIf { it.isNotBlank() }
        val displayName = if (channelName != null && channelLogin != null && !channelLogin.equals(channelName, true)) {
            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                "0" -> "$channelName($channelLogin)"
                "1" -> channelName
                else -> channelLogin
            }
        } else {
            channelName ?: channelLogin ?: event.channelId
        }
        setContentTitle(context.getString(R.string.live_notification, displayName))
        setContentText(event.title)
        setSmallIcon(R.drawable.notification_icon)
        setWhen(event.startedAt)
        setAutoCancel(true)
        setContentIntent(
            PendingIntent.getActivity(
                context,
                event.channelId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = MainActivity.INTENT_LIVE_NOTIFICATION
                    putExtra(MainActivity.KEY_VIDEO, event.toStream())
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
    }.build()

    private val liveChannelId: String
        get() = context.getString(R.string.notification_live_channel_id)

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0
    }
}
