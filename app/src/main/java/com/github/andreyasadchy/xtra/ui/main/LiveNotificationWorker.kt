package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.NotificationUsersRepository
import com.github.andreyasadchy.xtra.repository.ShownNotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject

@HiltWorker
class LiveNotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    @Inject
    lateinit var shownNotifications: ShownNotificationsRepository

    @Inject
    lateinit var notificationUsersRepository: NotificationUsersRepository

    @Inject
    lateinit var graphQLRepository: GraphQLRepository

    @Inject
    lateinit var helixRepository: HelixRepository

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val streams = shownNotifications.getNewStreams(
            notificationUsersRepository = notificationUsersRepository,
            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
            helixRepository = helixRepository
        )
        if (streams.isNotEmpty()) {
            val channelId = context.getString(R.string.notification_live_channel_id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (notificationManager.getNotificationChannel(channelId) == null) {
                    notificationManager.createNotificationChannel(
                        NotificationChannel(
                            channelId,
                            ContextCompat.getString(context, R.string.notification_live_channel_title),
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                }
            }
            streams.forEach {
                val notification = NotificationCompat.Builder(context, channelId).apply {
                    setGroup(GROUP_KEY)
                    setContentTitle(ContextCompat.getString(context, R.string.live_notification).format(
                        if (it.channelLogin != null && !it.channelLogin.equals(it.channelName, true)) {
                            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                "0" -> "${it.channelName}(${it.channelLogin})"
                                "1" -> it.channelName
                                else -> it.channelLogin
                            }
                        } else {
                            it.channelName
                        }
                    ))
                    setContentText(it.title)
                    setSmallIcon(R.drawable.notification_icon)
                    setAutoCancel(true)
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            it.channelId.hashCode(),
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                action = MainActivity.INTENT_LIVE_NOTIFICATION
                                putExtra(MainActivity.KEY_VIDEO, it)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }.build()
                notificationManager.notify(it.channelId.hashCode(), notification)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val notification = NotificationCompat.Builder(context, channelId).apply {
                    setGroup(GROUP_KEY)
                    setSmallIcon(R.drawable.notification_icon)
                    setGroupSummary(true)
                }.build()
                notificationManager.notify(0, notification)
            }
        }
        return Result.success()
    }

    companion object {
        const val GROUP_KEY = "com.github.andreyasadchy.xtra.LIVE_NOTIFICATIONS"
    }
}
