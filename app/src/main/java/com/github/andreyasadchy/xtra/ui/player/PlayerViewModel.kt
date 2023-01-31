package com.github.andreyasadchy.xtra.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.common.BaseAndroidViewModel
import com.github.andreyasadchy.xtra.ui.common.OnQualityChangeListener
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerViewModel
import com.github.andreyasadchy.xtra.ui.player.video.VideoPlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.Player.EVENT_PLAYBACK_STATE_CHANGED
import com.google.android.exoplayer2.Player.EVENT_PLAY_WHEN_READY_CHANGED
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.concurrent.schedule


abstract class PlayerViewModel(context: Application) : BaseAndroidViewModel(context), Player.Listener, OnQualityChangeListener {

    protected val tag: String = javaClass.simpleName
    protected val prefs = context.prefs()

    var player: ExoPlayer? = null
    protected var mediaSourceFactory: MediaSource.Factory? = null
    protected lateinit var mediaItem: MediaItem //TODO maybe redo these viewmodels to custom players

    private val _playerUpdated = MutableLiveData<Boolean>()
    val playerUpdated: LiveData<Boolean>
        get() = _playerUpdated
    protected val _playerMode = MutableLiveData<PlayerMode>().apply { value = PlayerMode.NORMAL }
    val playerMode: LiveData<PlayerMode>
        get() = _playerMode
    open var qualities: List<String>? = null
        protected set
    var qualityIndex = 0
        protected set
    var previousQuality = 0
    protected var playbackPosition: Long = 0

    protected var binder: AudioPlayerService.AudioBinder? = null

    protected var isResumed = true
    var pauseHandled = false

    lateinit var mediaSession: MediaSessionCompat
    lateinit var mediaSessionConnector: MediaSessionConnector

    private val _showPauseButton = MutableLiveData<Boolean>()
    val showPauseButton: LiveData<Boolean>
        get() = _showPauseButton
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean>
        get() = _isPlaying
    private val _subtitlesAvailable = MutableLiveData<Boolean>()
    val subtitlesAvailable: LiveData<Boolean>
        get() = _subtitlesAvailable

    private var timer: Timer? = null
    private val _sleepTimer = MutableLiveData<Boolean>()
    val sleepTimer: LiveData<Boolean>
        get() = _sleepTimer
    private var timerEndTime = 0L
    val timerTimeLeft
        get() = timerEndTime - System.currentTimeMillis()

    fun setTimer(duration: Long) {
        timer?.let {
            it.cancel()
            timerEndTime = 0L
            timer = null
        }
        if (duration > 0L) {
            timer = Timer().apply {
                timerEndTime = System.currentTimeMillis() + duration
                schedule(duration) {
                    stopBackgroundAudio()
                    _sleepTimer.postValue(true)
                }
            }
        }
    }

    open fun onResume() {
        initializePlayer()
        play()
    }

    open fun onPause() {
        releasePlayer()
    }

    open fun restartPlayer() {
        player?.currentPosition?.let { playbackPosition = it }
        player?.stop()
        initializePlayer()
        play()
        player?.seekTo(playbackPosition)
    }

    protected fun initializePlayer() {
        if (player == null) {
            val context = getApplication<Application>()
            player = ExoPlayer.Builder(context).apply {
                if (prefs.getBoolean(C.PLAYER_FORCE_FIRST_DECODER, false)) {
                    setRenderersFactory(DefaultRenderersFactory(context).setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder).firstOrNull()?.let {
                            listOf(it)
                        } ?: emptyList()
                    })
                }
                mediaSourceFactory?.let { setMediaSourceFactory(it) }
                setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                    prefs.getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                    prefs.getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                    prefs.getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull() ?: 2000,
                    prefs.getString(C.PLAYER_BUFFER_REBUFFER, "5000")?.toIntOrNull() ?: 5000
                ).build())
                setSeekBackIncrementMs(prefs.getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000)
                setSeekForwardIncrementMs(prefs.getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000)
            }.build().apply {
                addListener(this@PlayerViewModel)
                volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                if (this@PlayerViewModel !is StreamPlayerViewModel) {
                    setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
                }
                playWhenReady = true
            }
            _playerUpdated.postValue(true)
        }
    }

    protected fun play() {
        if (this::mediaItem.isInitialized) {
            player?.let {
                it.setMediaItem(mediaItem)
                it.prepare()
                mediaSessionConnector.setPlayer(it)
                mediaSession.isActive = true
            }
        }
    }

    protected fun releasePlayer() {
        player?.release()
        player = null
        _playerUpdated.postValue(true)
        mediaSessionConnector.setPlayer(null)
        mediaSession.isActive = false
    }

    protected fun startBackgroundAudio(playlistUrl: String, channelName: String?, title: String?, imageUrl: String?, usePlayPause: Boolean, type: Int, videoId: Number?, showNotification: Boolean) {
        val context = XtraApp.INSTANCE //TODO
        val intent = Intent(context, AudioPlayerService::class.java).apply {
            putExtra(AudioPlayerService.KEY_PLAYLIST_URL, playlistUrl)
            putExtra(AudioPlayerService.KEY_CHANNEL_NAME, channelName)
            putExtra(AudioPlayerService.KEY_TITLE, title)
            putExtra(AudioPlayerService.KEY_IMAGE_URL, imageUrl)
            putExtra(AudioPlayerService.KEY_USE_PLAY_PAUSE, usePlayPause)
            putExtra(AudioPlayerService.KEY_CURRENT_POSITION, player?.currentPosition)
            putExtra(AudioPlayerService.KEY_TYPE, type)
            putExtra(AudioPlayerService.KEY_VIDEO_ID, videoId)
        }
        releasePlayer()
        val connection = object : ServiceConnection {

            override fun onServiceDisconnected(name: ComponentName) {
            }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as AudioPlayerService.AudioBinder
                player = service.player
                _playerUpdated.postValue(true)
                if (showNotification) {
                    showAudioNotification()
                }
            }
        }
        AudioPlayerService.connection = connection
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    protected fun stopBackgroundAudio() {
        AudioPlayerService.connection?.let {
//            val context = getApplication<Application>()
            XtraApp.INSTANCE.unbindService(it) //TODO
        }
    }

    protected fun showAudioNotification() {
        binder?.showNotification()
    }

    protected fun hideAudioNotification() {
        if (AudioPlayerService.connection != null) {
            binder?.hideNotification()
        } else {
            qualityIndex = previousQuality
            releasePlayer()
            initializePlayer()
            play()
            player?.seekTo(AudioPlayerService.position)
        }
    }

    protected fun setQualityIndex() {
        val defaultQuality = prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved")
        val savedQuality = prefs.getString(C.PLAYER_QUALITY, "720p60")
        val index = qualities?.let { qualities ->
            when (defaultQuality) {
                "Source" -> {
                    if (this is StreamPlayerViewModel || this is VideoPlayerViewModel) {
                        if (qualities.size >= 2) 1 else null
                    } else null
                }
                "saved" -> {
                    if (savedQuality != "Auto") {
                        qualities.indexOf(savedQuality).let { if (it != -1) it else null }
                    } else null
                }
                else -> {
                    defaultQuality?.split("p")?.let { default ->
                        default[0].toIntOrNull()?.let { res ->
                            val fps = if (default.size >= 2) default[1].toIntOrNull() ?: 0 else 0
                            val map = mutableMapOf<Int, Int>()
                            qualities.forEach { quality ->
                                quality.split("p").let {
                                    it[0].toIntOrNull()?.let { res ->
                                        map[res] = if (it.size >= 2) it[1].toIntOrNull() ?: 0 else 0
                                    }
                                }
                            }
                            map.filter { (res == it.key && fps >= it.value) || res > it.key }.entries.firstOrNull()?.let { entry ->
                                qualities.indexOf("${entry.key}p" + if (entry.value != 0) "${entry.value}" else "").let { if (it != -1) it else null }
                            }
                        }
                    }
                }
            }
        }
        qualityIndex = index ?: 0
    }

    //Player.Listener

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED)) {
            _showPauseButton.postValue(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.postValue(isPlaying)
    }

    override fun onTracksChanged(tracks: Tracks) {
        _subtitlesAvailable.postValue(tracks.groups.find { it.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT } != null)
    }

    override fun onPlayerError(error: PlaybackException) {
        val playerError = player?.playerError
        Log.e(tag, "Player error", playerError)
        val context = getApplication<Application>()
        context.shortToast(R.string.player_error)
        viewModelScope.launch {
            delay(1500L)
            try {
                restartPlayer()
            } catch (e: Exception) {
//                            Crashlytics.log(Log.ERROR, tag, "onPlayerError: Retry error. ${e.message}")
//                            Crashlytics.logException(e)
            }
        }
    }

    override fun onCleared() {
        releasePlayer()
        timer?.cancel()
    }

    fun subtitlesEnabled(): Boolean {
        return player?.currentTracks?.groups?.find { it.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT }?.isSelected == true
    }

    fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(com.google.android.exoplayer2.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }
}