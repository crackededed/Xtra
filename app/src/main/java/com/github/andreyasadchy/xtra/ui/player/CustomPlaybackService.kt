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
import android.media.MediaMetadata
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
class CustomPlaybackService : Service() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var applicationHandler: Handler? = null
    private var bitmapLoader: BitmapLoader? = null
    private var metadataBitmapCallback: FutureCallback<Bitmap>? = null
    private var notificationBitmapCallback: FutureCallback<Bitmap>? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var background = false
    var proxyMediaPlaylist = false
    var videoId: Long? = null
    var offlineVideoId: Int? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).apply {
            setLoadControl(
                DefaultLoadControl.Builder().apply {
                    setBufferDurationsMs(
                        prefs().getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                        prefs().getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                        prefs().getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull() ?: 2000,
                        prefs().getString(C.PLAYER_BUFFER_REBUFFER, "2000")?.toIntOrNull() ?: 2000
                    )
                }.build()
            )
            setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
            setHandleAudioBecomingNoisy(prefs().getBoolean(C.PLAYER_HANDLE_AUDIO_BECOMING_NOISY, true))
            setSeekBackIncrementMs(prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000)
            setSeekForwardIncrementMs(prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000)
        }.build()
        this.player = player
        player.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        it.release()
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updatePlaybackState()
                    updateMetadata()
                }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    updateMetadata()
                    updateNotification()
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updateMetadata()
                    updateNotification()
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    updatePlaybackState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    updatePlaybackState()
                    if (background) {
                        player.prepare()
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlaybackState()
                    if (isPlaying) {
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

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    updatePlaybackState()
                }
            }
        )
        val session = MediaSession(this, "CustomPlaybackService")
        this.session = session
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPrepare() = player.prepare()

                override fun onPlay() {
                    Util.handlePlayPauseButtonAction(player)
                }

                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.seekForward()
                override fun onSkipToPrevious() = player.seekBack()
                override fun onFastForward() = player.seekForward()
                override fun onRewind() = player.seekBack()
                override fun onStop() = player.stop()
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
                override fun onSetPlaybackSpeed(speed: Float) = player.setPlaybackSpeed(speed)

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> player.seekBack()
                        INTENT_FAST_FORWARD -> player.seekForward()
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
        player?.let { player ->
            val isLive = player.isCurrentMediaItemLive
            session?.setPlaybackState(
                PlaybackState.Builder().apply {
                    setState(
                        when (player.playbackState) {
                            Player.STATE_IDLE -> PlaybackState.STATE_NONE
                            Player.STATE_BUFFERING -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_BUFFERING
                                }
                            }
                            Player.STATE_READY -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_PLAYING
                                }
                            }
                            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                            else -> PlaybackState.STATE_NONE
                        },
                        if (!isLive) {
                            player.currentPosition
                        } else {
                            -1
                        },
                        if (player.isPlaying && !isLive) {
                            player.playbackParameters.speed
                        } else {
                            0f
                        }
                    )
                    setBufferedPosition(
                        if (!isLive) {
                            player.bufferedPosition
                        } else {
                            -1
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
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@CustomPlaybackService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@CustomPlaybackService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                }.build()
            )
        }
    }

    private fun updateMetadata() {
        val bitmap = player?.currentMediaItem?.mediaMetadata?.let { metadata ->
            val loader = bitmapLoader ?: CacheBitmapLoader(DataSourceBitmapLoader(this)).also { bitmapLoader = it }
            loader.loadBitmapFromMetadata(metadata)?.let { bitmapFuture ->
                metadataBitmapCallback = null
                if (bitmapFuture.isDone) {
                    bitmapFuture.get()
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
                    putText(MediaMetadata.METADATA_KEY_TITLE, player.currentMediaItem?.mediaMetadata?.title)
                    putText(MediaMetadata.METADATA_KEY_ARTIST, player.currentMediaItem?.mediaMetadata?.artist)
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                    }
                    putLong(
                        MediaMetadata.METADATA_KEY_DURATION,
                        if (!player.isCurrentMediaItemLive) {
                            player.duration
                        } else {
                            -1
                        }
                    )
                }.build()
            )
        }
    }

    private fun updateNotification() {
        val bitmap = player?.currentMediaItem?.mediaMetadata?.let { metadata ->
            val loader = bitmapLoader ?: CacheBitmapLoader(DataSourceBitmapLoader(this)).also { bitmapLoader = it }
            loader.loadBitmapFromMetadata(metadata)?.let { bitmapFuture ->
                notificationBitmapCallback = null
                if (bitmapFuture.isDone) {
                    bitmapFuture.get()
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
                setContentTitle(player.currentMediaItem?.mediaMetadata?.title)
                setContentText(player.currentMediaItem?.mediaMetadata?.artist)
                setSmallIcon(R.drawable.notification_icon)
                if (bitmap != null) {
                    setLargeIcon(bitmap)
                }
                setGroup(GROUP_KEY)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(false)
                setOnlyAlertOnce(true)
                if (player.isPlaying && player.playbackParameters.speed == 1f) {
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
                        this@CustomPlaybackService,
                        REQUEST_CODE_RESUME,
                        Intent(this@CustomPlaybackService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@CustomPlaybackService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@CustomPlaybackService, R.string.rewind),
                        PendingIntent.getService(
                            this@CustomPlaybackService,
                            REQUEST_CODE_REWIND,
                            Intent(this@CustomPlaybackService, CustomPlaybackService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (Util.shouldShowPlayButton(player)) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@CustomPlaybackService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@CustomPlaybackService, R.string.resume),
                            PendingIntent.getService(
                                this@CustomPlaybackService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@CustomPlaybackService, CustomPlaybackService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@CustomPlaybackService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@CustomPlaybackService, R.string.pause),
                            PendingIntent.getService(
                                this@CustomPlaybackService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@CustomPlaybackService, CustomPlaybackService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@CustomPlaybackService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@CustomPlaybackService, R.string.forward),
                        PendingIntent.getService(
                            this@CustomPlaybackService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@CustomPlaybackService, CustomPlaybackService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
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
                        player?.clearMediaItems()
                        player?.playWhenReady = false
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
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                videoId?.let {
                    runBlocking {
                        playerRepository.saveVideoPosition(VideoPosition(it, player.currentPosition))
                    }
                } ?:
                offlineVideoId?.let {
                    runBlocking {
                        offlineRepository.updateVideoPosition(it, player.currentPosition)
                    }
                }
            }
        }
    }

    private fun updateSavedPosition() {
        player?.let { player ->
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                val currentPosition = player.currentPosition
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
            INTENT_REWIND -> player?.seekBack()
            INTENT_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            INTENT_FAST_FORWARD -> player?.seekForward()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@CustomPlaybackService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        player?.clearMediaItems()
        player?.playWhenReady = false
        stopSelf()
    }

    override fun onDestroy() {
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