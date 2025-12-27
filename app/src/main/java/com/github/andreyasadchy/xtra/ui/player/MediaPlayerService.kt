package com.github.andreyasadchy.xtra.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.DynamicsProcessing
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
import androidx.core.content.edit
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
import kotlinx.coroutines.runBlocking
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MediaPlayerService : Service() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    var player: MediaPlayer? = null
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
    var preparedListener: MediaPlayer.OnPreparedListener? = null
    var seekCompleteListener: MediaPlayer.OnSeekCompleteListener? = null
    var completionListener: MediaPlayer.OnCompletionListener? = null
    var infoListener: MediaPlayer.OnInfoListener? = null
    var errorListener: MediaPlayer.OnErrorListener? = null
    var pauseListener: (() -> Unit)? = null
    var speedListener: ((Float) -> Unit)? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var background = false
    var videoId: Long? = null
    var offlineVideoId: Int? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        val player = MediaPlayer().apply {
            setWakeMode(this@MediaPlayerService, PowerManager.PARTIAL_WAKE_LOCK)
        }
        this.player = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        player.setOnPreparedListener { player ->
            seekPosition?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    player?.seekTo(it, MediaPlayer.SEEK_CLOSEST)
                } else {
                    player?.seekTo(it.toInt())
                }
                seekPosition = null
            }
            player.start()
            updateMetadata()
            updatePlayingState()
            preparedListener?.onPrepared(player)
        }
        player.setOnSeekCompleteListener { player ->
            updatePlaybackState()
            updateNotification()
            seekCompleteListener?.onSeekComplete(player)
        }
        player.setOnCompletionListener { player ->
            updatePlaybackState()
            updateNotification()
            completionListener?.onCompletion(player)
        }
        player.setOnInfoListener { mp, what, extra ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START, MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    updatePlaybackState()
                    updateNotification()
                }
            }
            infoListener?.onInfo(mp, what, extra)
            return@setOnInfoListener true
        }
        player.setOnErrorListener { mp, what, extra ->
            updatePlaybackState(true)
            updateNotification()
            errorListener?.onError(mp, what, extra)
            return@setOnErrorListener true
        }
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MediaPlayer:WifiLock")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MediaPlayer:WifiLock")
        }
        wifiLock?.acquire()
        val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
        val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
        val session = MediaSession(this, "MediaPlayerService")
        this.session = session
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPrepare() = player.prepareAsync()

                override fun onPlay() {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.start()
                    }
                    updatePlayingState()
                    pauseListener?.invoke()
                }

                override fun onPause() {
                    player.pause()
                    updatePlayingState()
                    pauseListener?.invoke()
                }

                override fun onSkipToNext() {
                    val position = player.currentPosition + fastForwardMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }

                override fun onSkipToPrevious() {
                    val position = player.currentPosition - rewindMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }

                override fun onFastForward() {
                    val position = player.currentPosition + fastForwardMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }

                override fun onRewind() {
                    val position = player.currentPosition - rewindMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }

                override fun onStop() {
                    player.stop()
                    updatePlayingState()
                    pauseListener?.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(pos.toInt())
                    }
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val params = PlaybackParams()
                        params.speed = speed
                        player.playbackParams = params
                        speedListener?.invoke(speed)
                    }
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> {
                            val position = player.currentPosition - rewindMs
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                            } else {
                                player.seekTo(position.toInt())
                            }
                        }
                        INTENT_FAST_FORWARD -> {
                            val position = player.currentPosition + fastForwardMs
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                            } else {
                                player.seekTo(position.toInt())
                            }
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

    private fun updatePlaybackState(error: Boolean = false) {
        player?.let { player ->
            val isLive = !error && player.duration == -1
            session?.setPlaybackState(
                PlaybackState.Builder().apply {
                    setState(
                        if (!player.isPlaying) {
                            PlaybackState.STATE_PAUSED
                        } else {
                            PlaybackState.STATE_PLAYING
                        },
                        if (!isLive) {
                            player.currentPosition.toLong()
                        } else {
                            -1
                        },
                        if (player.isPlaying && !isLive) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                player.playbackParams.speed
                            } else {
                                1f
                            }
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
                                or PlaybackState.ACTION_PLAY_PAUSE).let {
                            if (!isLive) {
                                it or PlaybackState.ACTION_SEEK_TO
                            } else {
                                it
                            }.let {
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
                        }
                    )
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@MediaPlayerService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@MediaPlayerService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                }.build()
            )
        }
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
        player?.let { player ->
            session?.setMetadata(
                MediaMetadata.Builder().apply {
                    putText(MediaMetadata.METADATA_KEY_TITLE, title)
                    putText(MediaMetadata.METADATA_KEY_ARTIST, channelName)
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                    }
                    putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration.toLong())
                }.build()
            )
        }
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
        player?.let { player ->
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, getString(R.string.notification_playback_channel_id))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
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
                if (player.isPlaying &&
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        player.playbackParams.speed
                    } else {
                        1f
                    } == 1f
                ) {
                    setWhen(System.currentTimeMillis() - player.currentPosition)
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
                        this@MediaPlayerService,
                        REQUEST_CODE_RESUME,
                        Intent(this@MediaPlayerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_rewind),
                            ContextCompat.getString(this@MediaPlayerService, R.string.rewind),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_REWIND,
                                Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_REWIND
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                    if (!player.isPlaying) {
                        addAction(
                            Notification.Action.Builder(
                                Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_play),
                                ContextCompat.getString(this@MediaPlayerService, R.string.resume),
                                PendingIntent.getService(
                                    this@MediaPlayerService,
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
                                        action = INTENT_PLAY_PAUSE
                                    },
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            ).build()
                        )
                    } else {
                        addAction(
                            Notification.Action.Builder(
                                Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_pause),
                                ContextCompat.getString(this@MediaPlayerService, R.string.pause),
                                PendingIntent.getService(
                                    this@MediaPlayerService,
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
                                        action = INTENT_PLAY_PAUSE
                                    },
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            ).build()
                        )
                    }
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                            ContextCompat.getString(this@MediaPlayerService, R.string.forward),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_FAST_FORWARD,
                                Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
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
                            ContextCompat.getString(this@MediaPlayerService, R.string.rewind),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_REWIND,
                                Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_REWIND
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                    if (!player.isPlaying) {
                        addAction(
                            Notification.Action.Builder(
                                androidx.media3.session.R.drawable.media3_icon_play,
                                ContextCompat.getString(this@MediaPlayerService, R.string.resume),
                                PendingIntent.getService(
                                    this@MediaPlayerService,
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
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
                                ContextCompat.getString(this@MediaPlayerService, R.string.pause),
                                PendingIntent.getService(
                                    this@MediaPlayerService,
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
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
                            ContextCompat.getString(this@MediaPlayerService, R.string.forward),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_FAST_FORWARD,
                                Intent(this@MediaPlayerService, ExoPlayerService::class.java).apply {
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
                        player?.pause()
                        updatePlayingState()
                        pauseListener?.invoke()
                        stopSelf()
                    }
                }
            }
            sleepTimerEndTime = System.currentTimeMillis() + duration
        }
        return endTime
    }

    fun toggleDynamicsProcessing(): Boolean {
        if (dynamicsProcessing?.enabled == true) {
            dynamicsProcessing?.enabled = false
        } else {
            if (dynamicsProcessing == null) {
                player?.audioSessionId?.let { reinitializeDynamicsProcessing(it) }
            } else {
                dynamicsProcessing?.enabled = true
            }
        }
        val enabled = dynamicsProcessing?.enabled == true
        prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
        return enabled
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, null).apply {
                for (channelIdx in 0 until channelCount) {
                    for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                        setMbcBandByChannelIndex(
                            channelIdx,
                            bandIdx,
                            getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                attackTime = 0f
                                releaseTime = 0.25f
                                ratio = 1.6f
                                threshold = -50f
                                kneeWidth = 40f
                                preGain = 0f
                                postGain = 10f
                            }
                        )
                    }
                }
                enabled = true
            }
        }
    }

    private fun savePosition() {
        player?.let { player ->
            if (player.duration != -1 && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                videoId?.let {
                    runBlocking {
                        playerRepository.saveVideoPosition(VideoPosition(it, player.currentPosition.toLong()))
                    }
                } ?:
                offlineVideoId?.let {
                    runBlocking {
                        offlineRepository.updateVideoPosition(it, player.currentPosition.toLong())
                    }
                }
            }
        }
    }

    fun updatePlayingState() {
        updatePlaybackState()
        updateNotification()
        player?.let { player ->
            if (player.isPlaying) {
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

    private fun updateSavedPosition() {
        player?.let { player ->
            if (player.duration != -1 && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                val currentPosition = player.currentPosition.toLong()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            INTENT_REWIND -> {
                player?.let { player ->
                    val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                    val position = player.currentPosition - rewindMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }
            }
            INTENT_PLAY_PAUSE -> {
                player?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.start()
                    }
                    updatePlayingState()
                    pauseListener?.invoke()
                }
            }
            INTENT_FAST_FORWARD -> {
                player?.let { player ->
                    val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                    val position = player.currentPosition + fastForwardMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@MediaPlayerService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        player?.pause()
        updatePlayingState()
        pauseListener?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        wifiLock?.release()
        player?.release()
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