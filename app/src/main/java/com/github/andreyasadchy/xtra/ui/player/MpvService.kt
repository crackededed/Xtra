package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import dagger.hilt.android.AndroidEntryPoint
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.runBlocking
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MpvService : Service() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    private var playerListener: MPVLib.EventObserver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var applicationHandler: Handler? = null
    private var bitmapLoader: BitmapLoader? = null
    private var metadataBitmapCallback: FutureCallback<Bitmap>? = null
    private var notificationBitmapCallback: FutureCallback<Bitmap>? = null
    var title: String? = null
    var channelName: String? = null
    var channelLogo: String? = null
    var seekPosition: Long? = null

    private var background = false
    var videoId: Long? = null
    var offlineVideoId: Int? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        val videoDriver = prefs().getString(C.MPV_VO, "gpu-next") ?: "gpu-next"
        MPVLib.create(this)
        MPVLib.setOptionString("vo", videoDriver)
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("hwdec",
            if (videoDriver == "mediacodec_embed") {
                "mediacodec"
            } else {
                prefs().getString(C.MPV_HWDEC, "auto") ?: "auto"
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
            MPVLib.setOptionString("demuxer-max-back-bytes", "64MiB")
        } else {
            MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
            MPVLib.setOptionString("demuxer-max-back-bytes", "32MiB")
        }
        MPVLib.setOptionString("load-auto-profiles", "no")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("idle", "yes")
        MPVLib.init()
        val listener = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}

            override fun eventProperty(property: String, value: Boolean) {
                when (property) {
                    "pause" -> {
                        if (MPVLib.getPropertyBoolean("idle-active") == false) {
                            updatePlaybackState()
                            updateNotification()
                            if (!value) {
                                if (savePositionTimer == null && (videoId != null || offlineVideoId != null)) {
                                    savePositionTimer = Timer().apply {
                                        scheduleAtFixedRate(30000, 30000) {
                                            Handler(Looper.getMainLooper()).post {
                                                updateSavedPosition()
                                            }
                                        }
                                    }
                                }
                            } else {
                                savePositionTimer?.cancel()
                                savePositionTimer = null
                                updateSavedPosition()
                            }
                        }
                    }
                }
            }

            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {}
            override fun eventProperty(property: String, value: MPVNode) {}

            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        updateMetadata()
                        updatePlaybackState()
                        updateNotification()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                        updatePlaybackState()
                        updateNotification()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        seekPosition?.let {
                            MPVLib.setPropertyDouble("time-pos", (it / 1000.0))
                            seekPosition = null
                        }
                    }
                }
            }
        }
        MPVLib.addObserver(listener)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        playerListener = listener
        val powerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mpv:WakeLock")
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wakeLock?.acquire()
        wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Mpv:WifiLock")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mpv:WifiLock")
        }
        wifiLock?.acquire()
        val session = MediaSession(this, "MpvService")
        this.session = session
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    MPVLib.setPropertyBoolean("pause", false)
                    if (MPVLib.getPropertyBoolean("eof-reached") == true) {
                        MPVLib.command("seek", "0", "absolute")
                    }
                }

                override fun onPause() = MPVLib.setPropertyBoolean("pause", true)

                override fun onSkipToNext() {
                    val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                    MPVLib.command("seek", (fastForwardMs / 1000.0).toString(), "relative+exact")
                }

                override fun onSkipToPrevious() {
                    val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                    MPVLib.command("seek", (-(rewindMs / 1000.0)).toString(), "relative+exact")
                }

                override fun onFastForward() {
                    val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                    MPVLib.command("seek", (fastForwardMs / 1000.0).toString(), "relative+exact")
                }

                override fun onRewind() {
                    val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                    MPVLib.command("seek", (-(rewindMs / 1000.0)).toString(), "relative+exact")
                }

                override fun onStop() = MPVLib.setPropertyBoolean("pause", true)
                override fun onSeekTo(pos: Long) = MPVLib.command("seek", (pos / 1000.0).toString(), "absolute")
                override fun onSetPlaybackSpeed(speed: Float) = MPVLib.setPropertyFloat("speed", speed)

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> {
                            val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                            MPVLib.command("seek", (-(rewindMs / 1000.0)).toString(), "relative+exact")
                        }
                        INTENT_FAST_FORWARD -> {
                            val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                            MPVLib.command("seek", (fastForwardMs / 1000.0).toString(), "relative+exact")
                        }
                    }
                }
            }
        )
        session.isActive = true
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = getString(R.string.notification_playback_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    ContextCompat.getString(this, R.string.notification_playback_channel_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        setShowBadge(false)
                    }
                }
            )
        }
        applicationHandler = Handler(mainLooper)
    }

    private fun updatePlaybackState() {
        val paused = MPVLib.getPropertyBoolean("pause") == true
        session?.setPlaybackState(
            PlaybackState.Builder().apply {
                setState(
                    if (paused) {
                        PlaybackState.STATE_PAUSED
                    } else {
                        PlaybackState.STATE_PLAYING
                    },
                    MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: -1,
                    if (!paused) {
                        MPVLib.getPropertyFloat("speed") ?: 1f
                    } else {
                        0f
                    }
                )
                setActions(
                    (PlaybackState.ACTION_STOP
                            or PlaybackState.ACTION_PAUSE
                            or PlaybackState.ACTION_PLAY
                            or PlaybackState.ACTION_REWIND
                            or PlaybackState.ACTION_FAST_FORWARD
                            or PlaybackState.ACTION_SET_RATING
                            or PlaybackState.ACTION_PLAY_PAUSE
                            or PlaybackState.ACTION_SEEK_TO).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            (it or PlaybackState.ACTION_PREPARE).let {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    it or PlaybackState.ACTION_SET_PLAYBACK_SPEED
                                } else {
                                    it
                                }
                            }
                        } else {
                            it
                        }
                    }
                )
                addCustomAction(INTENT_REWIND, ContextCompat.getString(this@MpvService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@MpvService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
            }.build()
        )
    }

    private fun updateMetadata() {
        val bitmap = channelLogo?.let { channelLogo ->
            val loader = bitmapLoader ?: CacheBitmapLoader(DataSourceBitmapLoader(this)).also { bitmapLoader = it }
            loader.loadBitmap(channelLogo.toUri()).let { bitmapFuture ->
                metadataBitmapCallback = null
                if (bitmapFuture.isDone) {
                    try {
                        bitmapFuture.get()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    val callback = object : FutureCallback<Bitmap> {
                        override fun onSuccess(result: Bitmap?) {
                            if (this == metadataBitmapCallback) {
                                setMetadata(result)
                            }
                        }

                        override fun onFailure(t: Throwable) {}
                    }
                    metadataBitmapCallback = callback
                    applicationHandler?.let { Futures.addCallback(bitmapFuture, callback, it::post) }
                    null
                }
            }
        }
        setMetadata(bitmap)
    }

    private fun setMetadata(bitmap: Bitmap?) {
        session?.setMetadata(
            MediaMetadata.Builder().apply {
                putText(MediaMetadata.METADATA_KEY_TITLE, title)
                putText(MediaMetadata.METADATA_KEY_ARTIST, channelName)
                if (bitmap != null) {
                    putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                }
                putLong(
                    MediaMetadata.METADATA_KEY_DURATION,
                    MPVLib.getPropertyDouble("duration/full")?.times(1000)?.toLong() ?: -1
                )
            }.build()
        )
    }

    private fun updateNotification() {
        val bitmap = channelLogo?.let { channelLogo ->
            val loader = bitmapLoader ?: CacheBitmapLoader(DataSourceBitmapLoader(this)).also { bitmapLoader = it }
            loader.loadBitmap(channelLogo.toUri()).let { bitmapFuture ->
                notificationBitmapCallback = null
                if (bitmapFuture.isDone) {
                    try {
                        bitmapFuture.get()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    val callback = object : FutureCallback<Bitmap> {
                        override fun onSuccess(result: Bitmap?) {
                            if (this == notificationBitmapCallback) {
                                sendNotification(result)
                            }
                        }

                        override fun onFailure(t: Throwable) {}
                    }
                    notificationBitmapCallback = callback
                    applicationHandler?.let { Futures.addCallback(bitmapFuture, callback, it::post) }
                    null
                }
            }
        }
        sendNotification(bitmap)
    }

    private fun sendNotification(bitmap: Bitmap?) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, getString(R.string.notification_playback_channel_id))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            val paused = MPVLib.getPropertyBoolean("pause") == true
            setContentTitle(title)
            setContentText(channelName)
            setSmallIcon(R.drawable.notification_icon)
            if (bitmap != null) {
                setLargeIcon(bitmap)
            }
            setGroup(GROUP_KEY)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            setOngoing(false)
            setOnlyAlertOnce(true)
            if (!paused && MPVLib.getPropertyFloat("speed") == 1f) {
                setWhen(System.currentTimeMillis() - (MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0))
                setShowWhen(true)
                setUsesChronometer(true)
            }
            setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            setContentIntent(
                PendingIntent.getActivity(
                    this@MpvService,
                    REQUEST_CODE_RESUME,
                    Intent(this@MpvService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_PLAYER
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@MpvService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@MpvService, R.string.rewind),
                        PendingIntent.getService(
                            this@MpvService,
                            REQUEST_CODE_REWIND,
                            Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (paused) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MpvService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@MpvService, R.string.resume),
                            PendingIntent.getService(
                                this@MpvService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MpvService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@MpvService, R.string.pause),
                            PendingIntent.getService(
                                this@MpvService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@MpvService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@MpvService, R.string.forward),
                        PendingIntent.getService(
                            this@MpvService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            } else @Suppress("DEPRECATION") {
                addAction(
                    Notification.Action.Builder(
                        androidx.media3.session.R.drawable.media3_icon_rewind,
                        ContextCompat.getString(this@MpvService, R.string.rewind),
                        PendingIntent.getService(
                            this@MpvService,
                            REQUEST_CODE_REWIND,
                            Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (paused) {
                    addAction(
                        Notification.Action.Builder(
                            androidx.media3.session.R.drawable.media3_icon_play,
                            ContextCompat.getString(this@MpvService, R.string.resume),
                            PendingIntent.getService(
                                this@MpvService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            androidx.media3.session.R.drawable.media3_icon_pause,
                            ContextCompat.getString(this@MpvService, R.string.pause),
                            PendingIntent.getService(
                                this@MpvService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        androidx.media3.session.R.drawable.media3_icon_fast_forward,
                        ContextCompat.getString(this@MpvService, R.string.forward),
                        PendingIntent.getService(
                            this@MpvService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@MpvService, ExoPlayerService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            }
        }.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun setSleepTimer(duration: Long): Long {
        background = duration != -1L
        val endTime = sleepTimerEndTime
        sleepTimer?.cancel()
        sleepTimerEndTime = 0L
        if (duration > 0L) {
            sleepTimer = Timer().apply {
                schedule(duration) {
                    Handler(Looper.getMainLooper()).post {
                        savePosition()
                        stopSelf()
                    }
                }
            }
            sleepTimerEndTime = System.currentTimeMillis() + duration
        }
        return endTime
    }

    private fun savePosition() {
        if (MPVLib.getPropertyBoolean("idle-active") == false && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            videoId?.let {
                runBlocking {
                    val currentPosition = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
                    playerRepository.saveVideoPosition(VideoPosition(it, currentPosition))
                }
            } ?:
            offlineVideoId?.let {
                runBlocking {
                    val currentPosition = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
                    offlineRepository.updateVideoPosition(it, currentPosition)
                }
            }
        }
    }

    private fun updateSavedPosition() {
        if (MPVLib.getPropertyBoolean("idle-active") == false && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            val currentPosition = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
            val savedPosition = lastSavedPosition
            if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                lastSavedPosition = currentPosition
                videoId?.let {
                    runBlocking {
                        playerRepository.saveVideoPosition(VideoPosition(it, currentPosition))
                    }
                } ?:
                offlineVideoId?.let {
                    runBlocking {
                        offlineRepository.updateVideoPosition(it, currentPosition)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            INTENT_REWIND -> {
                val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                MPVLib.command("seek", (-(rewindMs / 1000.0)).toString(), "relative+exact")
            }
            INTENT_PLAY_PAUSE -> {
                val paused = MPVLib.getPropertyBoolean("pause") == true
                MPVLib.setPropertyBoolean("pause", !paused)
                if (MPVLib.getPropertyBoolean("eof-reached") == true) {
                    MPVLib.command("seek", "0", "absolute")
                }
            }
            INTENT_FAST_FORWARD -> {
                val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                MPVLib.command("seek", (fastForwardMs / 1000.0).toString(), "relative+exact")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@MpvService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        stopSelf()
    }

    override fun onDestroy() {
        playerListener?.let {
            MPVLib.removeObserver(it)
        }
        playerListener = null
        wakeLock?.release()
        wifiLock?.release()
        MPVLib.destroy()
        session?.release()
        metadataBitmapCallback = null
        notificationBitmapCallback = null
        applicationHandler?.removeCallbacksAndMessages(null)
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.PLAYBACK_NOTIFICATIONS"

        private const val REQUEST_CODE_RESUME = 0
        private const val REQUEST_CODE_REWIND = 1
        private const val REQUEST_CODE_PLAY_PAUSE = 2
        private const val REQUEST_CODE_FAST_FORWARD = 3

        private const val INTENT_REWIND = "com.github.andreyasadchy.xtra.REWIND"
        private const val INTENT_PLAY_PAUSE = "com.github.andreyasadchy.xtra.PLAY_PAUSE"
        private const val INTENT_FAST_FORWARD = "com.github.andreyasadchy.xtra.FAST_FORWARD"
    }
}