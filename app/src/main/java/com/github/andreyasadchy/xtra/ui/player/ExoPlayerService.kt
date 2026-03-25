package com.github.andreyasadchy.xtra.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.MediaButtonReceiver
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.floor

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ExoPlayerService : BasePlaybackService() {

    var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var artworkUri: String? = null
    private var cachedBitmap: Bitmap? = null
    private var bitmapLoadJob: Job? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null

    private var playingAds = false
    private var proxyMediaPlaylist = false
    private var stopProxy = false
    private var hidden = false
    private var backupQualities: List<String>? = null
    private var updateQualities = false
    private var created = false

    private fun create(restorePauseState: Boolean) {
        if (!created) {
            created = true
            val playerListener = object : Player.Listener {
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

                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty) {
                        if (!loaded) {
                            loaded = true
                            serviceListener?.loaded()
                            toggleSubtitles(prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                        }
                        if (qualities?.find { it.name == AUTO_QUALITY } != null && quality?.name != AUDIO_ONLY_QUALITY && !hidden) {
                            changeQuality(quality)
                        }
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updateMetadata()
                    updateNotification()
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && qualities?.find { it.name == AUTO_QUALITY } != null) {
                        updateQualities = quality?.name != AUDIO_ONLY_QUALITY
                    }
                    if (qualities.isNullOrEmpty() || updateQualities) {
                        val playlist = (player?.currentManifest as? HlsManifest)?.multivariantPlaylist
                        val list = playlist?.variants?.mapNotNull { variant ->
                            val name = variant.format.label?.takeIf { it.isNotBlank() }
                                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
                            if (name != null) {
                                VideoQuality(name, variant.format.codecs, variant.url.toString())
                            } else null
                        }
                        if (!list.isNullOrEmpty()) {
                            qualities = list.asSequence()
                                .sortedByDescending {
                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .sortedByDescending {
                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .toMutableList().apply {
                                    add(0, VideoQuality(AUTO_QUALITY))
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(1, VideoQuality(SOURCE_QUALITY, source.codecs, source.url))
                                    }
                                    val audio = find { it.name?.startsWith("audio", true) == true }?.also {
                                        remove(it)
                                    }
                                    add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.url))
                                    if (type == STREAM) {
                                        add(VideoQuality(CHAT_ONLY_QUALITY))
                                    }
                                }
                            setDefaultQuality()
                            serviceListener?.changePlayerMode()
                            if (quality?.name == AUDIO_ONLY_QUALITY) {
                                changeQuality(quality)
                            }
                        }
                        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                            updateQualities = false
                        }
                    }
                    if (type == STREAM) {
                        val hideAds = prefs().getBoolean(C.PLAYER_HIDE_ADS, false)
                        val useProxy = prefs().getBoolean(C.PROXY_MEDIA_PLAYLIST, true)
                                && !prefs().getString(C.PROXY_HOST, null).isNullOrBlank()
                                && prefs().getString(C.PROXY_PORT, null)?.toIntOrNull() != null
                        if (hideAds || useProxy) {
                            val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist
                            val ads = playlist?.segments?.lastOrNull()?.let { segment ->
                                val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
                                listOf("Amazon", "Adform", "DCM").any { segment.title.contains(it) } ||
                                        playlist.interstitials.find {
                                            val startTime = it.startDateUnixUs
                                            val endTime = it.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                                                ?: it.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                                ?: it.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                            endTime != null
                                                    && (it.id.startsWith("stitched-ad-")
                                                    || it.clientDefinedAttributes.find { it.name == "CLASS" }?.textValue == "twitch-stitched-ad"
                                                    || it.clientDefinedAttributes.find { it.name.startsWith("X-TV-TWITCH-AD-") } != null)
                                                    && segmentStartTime in startTime..endTime
                                        } != null
                            } == true
                            val oldValue = playingAds
                            playingAds = ads
                            if (ads) {
                                if (proxyMediaPlaylist) {
                                    if (!stopProxy) {
                                        proxyMediaPlaylist = false
                                        stopProxy = true
                                    }
                                } else {
                                    if (!oldValue) {
                                        val playlist = quality?.url
                                        if (!stopProxy && !playlist.isNullOrBlank() && useProxy) {
                                            proxyMediaPlaylist = true
                                            lifecycleScope.launch {
                                                for (i in 0 until 10) {
                                                    delay(10000)
                                                    if (!checkPlaylist(prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), playlist)) {
                                                        break
                                                    }
                                                }
                                                proxyMediaPlaylist = false
                                            }
                                        } else {
                                            if (hideAds) {
                                                hidden = true
                                                player?.let { player ->
                                                    if (quality?.name != AUDIO_ONLY_QUALITY) {
                                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                        }.build()
                                                    }
                                                    player.volume = 0f
                                                }
                                                serviceListener?.toast(R.string.waiting_ads, Toast.LENGTH_LONG)
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (hideAds && hidden) {
                                    hidden = false
                                    player?.let { player ->
                                        if (quality?.name != AUDIO_ONLY_QUALITY) {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                            }.build()
                                        }
                                        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    updatePlaybackState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    updatePlaybackState()
                    when (type) {
                        STREAM -> {
                            val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                            val isNetworkAvailable = networkCapabilities != null
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            if (isNetworkAvailable) {
                                when {
                                    responseCode == 404 -> {
                                        serviceListener?.toast(R.string.stream_ended, Toast.LENGTH_LONG)
                                    }
                                    useCustomProxy && responseCode >= 400 -> {
                                        useCustomProxy = false
                                        serviceListener?.toast(R.string.proxy_error, Toast.LENGTH_LONG)
                                        lifecycleScope.launch {
                                            delay(1500L)
                                            restartPlayer()
                                        }
                                    }
                                    else -> {
                                        serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                        lifecycleScope.launch {
                                            delay(1500L)
                                            restartPlayer()
                                        }
                                    }
                                }
                            }
                        }
                        VIDEO -> {
                            val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                            val isNetworkAvailable = networkCapabilities != null
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            if (isNetworkAvailable) {
                                when {
                                    !skipAccessToken && responseCode != 0 -> {
                                        skipAccessToken = true
                                        videoAnimatedPreviewURL?.let { preview ->
                                            val urls = TwitchApiHelper.getVideoUrlsFromPreview(preview, videoType, backupQualities)
                                            val list = urls.map {
                                                VideoQuality(it.key, null, it.value)
                                            }
                                            qualities = list
                                                .sortedByDescending {
                                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                                }
                                                .sortedByDescending {
                                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                                }
                                                .toMutableList().apply {
                                                    find { it.name.equals("source", true) }?.let { source ->
                                                        remove(source)
                                                        add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.url))
                                                    }
                                                    val audio = find { it.name?.startsWith("audio", true) == true }?.also {
                                                        remove(it)
                                                    }
                                                    add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.url))
                                                }
                                            quality = qualities?.firstOrNull()
                                            serviceListener?.changePlayerMode()
                                            val url = quality?.url
                                            if (url != null) {
                                                player?.let { player ->
                                                    val playbackPosition = player.currentPosition
                                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    }.build()
                                                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                                                    player.setMediaSource(
                                                        HlsMediaSource.Factory(
                                                            DefaultDataSource.Factory(
                                                                this@ExoPlayerService,
                                                                when {
                                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                                        HttpEngineDataSource.Factory(httpEngine!!.get(), cronetExecutor, null, null) { false }
                                                                    }
                                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                                        CronetDataSource.Factory(cronetEngine!!.get(), cronetExecutor, null, null) { false }
                                                                    }
                                                                    else -> {
                                                                        OkHttpDataSource.Factory(okHttpClient, null) { false }
                                                                    }
                                                                }
                                                            )
                                                        ).apply {
                                                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                                        }.createMediaSource(
                                                            MediaItem.fromUri(url)
                                                        )
                                                    )
                                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                                    player.prepare()
                                                    player.playWhenReady = true
                                                    player.seekTo(playbackPosition)
                                                }
                                            }
                                        }
                                    }
                                    responseCode == 403 -> {
                                        serviceListener?.toast(R.string.video_subscribers_only, Toast.LENGTH_LONG)
                                    }
                                    else -> {
                                        serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                        lifecycleScope.launch {
                                            delay(1500L)
                                            player?.prepare()
                                        }
                                    }
                                }
                            }
                        }
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
            val sessionCallback = object : MediaSession.Callback() {
                override fun onPrepare() {
                    player?.prepare()
                }

                override fun onPlay() {
                    Util.handlePlayPauseButtonAction(player)
                }

                override fun onPause() {
                    player?.pause()
                }

                override fun onSkipToNext() {
                    player?.seekForward()
                }

                override fun onSkipToPrevious() {
                    player?.seekBack()
                }

                override fun onFastForward() {
                    player?.seekForward()
                }

                override fun onRewind() {
                    player?.seekBack()
                }

                override fun onStop() {
                    player?.stop()
                }

                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos)
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    player?.setPlaybackSpeed(speed)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> player?.seekBack()
                        INTENT_FAST_FORWARD -> player?.seekForward()
                    }
                }
            }
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
            player.addListener(playerListener)
            val session = MediaSession(this, "ExoPlayerService")
            this.session = session
            session.setCallback(sessionCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    session.setMediaButtonBroadcastReceiver(ComponentName(this, MediaButtonReceiver::class.java))
                } catch (e: IllegalArgumentException) {
                    // https://github.com/androidx/media/issues/1730
                }
            } else {
                @Suppress("DEPRECATION")
                session.setMediaButtonReceiver(
                    PendingIntent.getBroadcast(this, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java), PendingIntent.FLAG_MUTABLE)
                )
            }
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
            start(restorePauseState)
        }
    }

    private fun start(restorePauseState: Boolean) {
        lifecycleScope.launch {
            restorePlaybackState()
            when (type) {
                STREAM -> {
                    started = true
                    serviceListener?.started()
                    if (qualities.isNullOrEmpty()) {
                        useCustomProxy = prefs().getBoolean(C.PLAYER_STREAM_PROXY, false)
                    }
                    loadStream(restorePauseState)
                }
                VIDEO -> {
                    started = true
                    serviceListener?.started()
                    loadVideo(restorePauseState)
                }
                CLIP -> {
                    started = true
                    serviceListener?.started()
                    loadClip(restorePauseState)
                }
                OFFLINE_VIDEO -> {
                    offlineVideoId?.let { id ->
                        val video = offlineRepository.getVideoById(id)
                        if (video != null) {
                            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                                video.lastWatchPosition
                            } else {
                                null
                            } ?: savedPosition ?: 0
                            chatUrl = video.chatUrl
                            started = true
                            serviceListener?.started()
                            if (qualities.isNullOrEmpty()) {
                                qualities = listOf(
                                    VideoQuality(SOURCE_QUALITY, null, video.url),
                                    VideoQuality(AUDIO_ONLY_QUALITY),
                                )
                                setDefaultQuality()
                            }
                            serviceListener?.changePlayerMode()
                            val url = quality?.url ?: qualities?.firstOrNull()?.url
                            if (url != null) {
                                player?.let { player ->
                                    if (quality?.name == AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    }
                                    player.setMediaItem(MediaItem.fromUri(url))
                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = true
                                    player.seekTo(playbackPosition)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadStream(restorePauseState: Boolean = false, restart: Boolean = false) {
        channelLogin?.let { channelLogin ->
            if (restart || qualities.isNullOrEmpty()) {
                val proxyUrl = prefs().getString(C.PLAYER_PROXY_URL, "")
                if (useCustomProxy && !proxyUrl.isNullOrBlank()) {
                    playlistUrl = proxyUrl.replace("\$channel", channelLogin)
                } else {
                    useCustomProxy = false
                    val url = try {
                        playerRepository.loadStreamPlaylistUrl(
                            networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                            channelLogin = channelLogin,
                            randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                            xDeviceId = prefs().getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                            playerType = prefs().getString(C.TOKEN_PLAYERTYPE, "site"),
                            supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                            proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                            proxyHost = prefs().getString(C.PROXY_HOST, null),
                            proxyPort = prefs().getString(C.PROXY_PORT, null)?.toIntOrNull(),
                            proxyUser = prefs().getString(C.PROXY_USER, null),
                            proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                            enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                        )
                    } catch (e: Exception) {
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refreshStream"
                        }
                        null
                    }
                    playlistUrl = url
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    proxyMediaPlaylist = false
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                    val proxyHost = prefs().getString(C.PROXY_HOST, null)
                    val proxyPort = prefs().getString(C.PROXY_PORT, null)?.toIntOrNull()
                    val proxyUser = prefs().getString(C.PROXY_USER, null)
                    val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
                    val multivariantPlaylistProxyClient = if (prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) && !proxyHost.isNullOrBlank() && proxyPort != null) {
                        okHttpClient.newBuilder().apply {
                            proxySelector(
                                object : ProxySelector() {
                                    override fun select(u: URI): List<Proxy> {
                                        return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                        } else {
                                            listOf(Proxy.NO_PROXY)
                                        }
                                    }

                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                }
                            )
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header(
                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                    ).build()
                                }
                            }
                        }.build()
                    } else null
                    val mediaPlaylistProxyClient = if (prefs().getBoolean(C.PROXY_MEDIA_PLAYLIST, true) && !proxyHost.isNullOrBlank() && proxyPort != null) {
                        okHttpClient.newBuilder().apply {
                            proxySelector(
                                object : ProxySelector() {
                                    override fun select(u: URI): List<Proxy> {
                                        return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                        } else {
                                            listOf(Proxy.NO_PROXY)
                                        }
                                    }

                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                }
                            )
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header(
                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                    ).build()
                                }
                            }
                        }.build()
                    } else null
                    player.setMediaSource(
                        HlsMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        HttpEngineDataSource.Factory(httpEngine!!.get(), cronetExecutor, multivariantPlaylistProxyClient, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        CronetDataSource.Factory(cronetEngine!!.get(), cronetExecutor, multivariantPlaylistProxyClient, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                    }
                                    else -> {
                                        OkHttpDataSource.Factory(multivariantPlaylistProxyClient ?: okHttpClient, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                    }
                                }.apply {
                                    prefs().getString(C.PLAYER_STREAM_HEADERS, null)?.let {
                                        val headers = try {
                                            val json = JSONObject(it)
                                            hashMapOf<String, String>().apply {
                                                json.keys().forEach { key ->
                                                    put(key, json.optString(key))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
                                        if (headers != null) {
                                            setDefaultRequestProperties(headers)
                                        }
                                    }
                                }
                            )
                        ).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                            setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                        }.createMediaSource(
                            MediaItem.Builder().apply {
                                setUri(url.toUri())
                                setMimeType(MimeTypes.APPLICATION_M3U8)
                                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                    prefs().getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                    prefs().getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                    prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                }.build())
                            }.build()
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(1f)
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                }
            }
        }
    }

    private suspend fun loadVideo(restorePauseState: Boolean = false) {
        videoId?.let { videoId ->
            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                videoId.toLongOrNull()?.let { playerRepository.getVideoPosition(it)?.position }
            } else {
                null
            } ?: savedPosition ?: 0
            if (qualities.isNullOrEmpty()) {
                val result = try {
                    playerRepository.loadVideoPlaylistUrl(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = videoId,
                        playerType = prefs().getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshVideo"
                    }
                    null
                }
                if (result != null) {
                    playlistUrl = result.first
                    backupQualities = result.second
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                    player.setMediaSource(
                        HlsMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        HttpEngineDataSource.Factory(httpEngine!!.get(), cronetExecutor, null, null) { false }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        CronetDataSource.Factory(cronetEngine!!.get(), cronetExecutor, null, null) { false }
                                    }
                                    else -> {
                                        OkHttpDataSource.Factory(okHttpClient, null) { false }
                                    }
                                }
                            )
                        ).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                        }.createMediaSource(
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(playbackPosition)
                }
            }
        }
    }

    private suspend fun loadClip(restorePauseState: Boolean = false) {
        clipId?.let { clipId ->
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
            if (qualities.isNullOrEmpty()) {
                val list = try {
                    playerRepository.loadClipQualities(
                        networkLibrary = networkLibrary,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService),
                        clipId = clipId,
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                    )
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshClip"
                    }
                    null
                }
                if (list != null) {
                    val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                    val filtered = list.filterNot {
                        it.codecs?.substringBefore('.').let { codec ->
                            (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                        }
                    }
                    qualities = filtered
                        .sortedByDescending {
                            it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .sortedByDescending {
                            it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .toMutableList().apply {
                            add(VideoQuality(AUDIO_ONLY_QUALITY))
                        }
                    setDefaultQuality()
                }
            }
            serviceListener?.changePlayerMode()
            val url = quality?.url ?: qualities?.firstOrNull()?.url
            if (url != null) {
                player?.let { player ->
                    if (quality?.name == AUDIO_ONLY_QUALITY) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        }.build()
                    }
                    player.setMediaSource(
                        ProgressiveMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        HttpEngineDataSource.Factory(httpEngine!!.get(), cronetExecutor, null, null) { false }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        CronetDataSource.Factory(cronetEngine!!.get(), cronetExecutor, null, null) { false }
                                    }
                                    else -> {
                                        OkHttpDataSource.Factory(okHttpClient, null) { false }
                                    }
                                }
                            )
                        ).createMediaSource(
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(savedPosition ?: 0)
                }
            }
        }
    }

    override fun retry(item: String) {
        when (item) {
            "refreshStream" -> {
                lifecycleScope.launch {
                    loadStream()
                }
            }
            "refreshVideo" -> {
                lifecycleScope.launch {
                    loadVideo()
                }
            }
            "refreshClip" -> {
                lifecycleScope.launch {
                    loadClip()
                }
            }
        }
    }

    fun changeQuality(selectedQuality: VideoQuality?) {
        previousQuality = quality
        quality = selectedQuality
        quality?.let { quality ->
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.name) {
                        AUTO_QUALITY -> {
                            if (restorePlaylist) {
                                restorePlaylist = false
                                playlistUrl?.let { uri ->
                                    if (mediaItem.localConfiguration?.uri != uri.toUri()) {
                                        val position = player.currentPosition
                                        player.setMediaItem(mediaItem.buildUpon().setUri(uri).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                            } else {
                                player.prepare()
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                        }
                        AUDIO_ONLY_QUALITY -> {
                            proxyMediaPlaylist = false
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            quality.url?.let {
                                val position = player.currentPosition
                                if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                    restorePlaylist = true
                                }
                                player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            proxyMediaPlaylist = false
                            player.stop()
                        }
                        else -> {
                            if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                if (restorePlaylist) {
                                    restorePlaylist = false
                                    playlistUrl?.let { uri ->
                                        val position = player.currentPosition
                                        player.setMediaItem(mediaItem.buildUpon().setUri(uri).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                } else {
                                    player.prepare()
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    if (!player.currentTracks.isEmpty) {
                                        player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let {
                                            val selectedQuality = quality.name?.split("p")
                                            val targetResolution = selectedQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            val targetFps = selectedQuality?.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                            if (it.mediaTrackGroup.length > 0) {
                                                if (targetResolution != null) {
                                                    val formats = mutableListOf<Triple<Int, Int, Float>>()
                                                    for (i in 0 until it.mediaTrackGroup.length) {
                                                        val format = it.mediaTrackGroup.getFormat(i)
                                                        formats.add(Triple(i, format.height, format.frameRate))
                                                    }
                                                    val list = formats.sortedWith(
                                                        compareByDescending<Triple<Int, Int, Float>> { it.third }.thenByDescending { it.second }
                                                    )
                                                    list.find {
                                                        (targetResolution == it.second && targetFps >= floor(it.third)) || targetResolution > it.second || it == list.last()
                                                    }?.first?.let { index ->
                                                        setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, index))
                                                    }
                                                } else {
                                                    setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                                                }
                                            }
                                        }
                                    }
                                }.build()
                            } else {
                                player.currentMediaItem?.let {
                                    if (it.localConfiguration?.uri?.toString() != quality.url) {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(quality.url).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                            }
                        }
                    }
                    val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    if ((!cellular && prefs().getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") || (cellular && prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                        prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                    }
                }
            }
        }
    }

    fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }

    suspend fun checkPlaylist(networkLibrary: String?, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val playlist = when {
                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    response.second.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                networkLibrary == "Cronet" && cronetEngine != null -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                        cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                        val response = request.future.get().responseBody as ByteArray
                        response.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        val response = suspendCancellableCoroutine { continuation ->
                            cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                        }
                        response.second.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
                else -> {
                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
            }
            playlist.segments.lastOrNull()?.let { segment ->
                segment.title?.let { it.contains("Amazon") || it.contains("Adform") || it.contains("DCM") } == true ||
                        segment.programDateTime?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { segmentStartTime ->
                            playlist.dateRanges.find { dateRange ->
                                (dateRange.id.startsWith("stitched-ad-") || dateRange.rangeClass == "twitch-stitched-ad" || dateRange.ad) &&
                                        dateRange.endDate?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { endTime ->
                                            segmentStartTime < endTime
                                        } == true ||
                                        dateRange.startDate.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { startTime ->
                                            (dateRange.duration ?: dateRange.plannedDuration)?.let { (it * 1000f).toLong() }?.let { duration ->
                                                segmentStartTime < (startTime + duration)
                                            } == true
                                        } == true
                            } != null
                        } == true
            } == true
        } catch (e: Exception) {
            false
        }
    }

    fun restartPlayer() {
        if (quality?.name != CHAT_ONLY_QUALITY) {
            lifecycleScope.launch {
                loadStream(restart = true)
            }
        }
    }

    fun startAudioOnly() {
        player?.let { player ->
            proxyMediaPlaylist = false
            if (quality?.name != AUDIO_ONLY_QUALITY) {
                restoreQuality = true
                previousQuality = quality
                quality = qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                quality?.let { quality ->
                    player.currentMediaItem?.let { mediaItem ->
                        if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                        }
                        if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                            quality.url?.let { url ->
                                val position = player.currentPosition
                                if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                    restorePlaylist = true
                                }
                                player.setMediaItem(mediaItem.buildUpon().setUri(url).build())
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop(isInPIPMode: Boolean) {
        player?.let { player ->
            proxyMediaPlaylist = false
            val isInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if ((!isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                || (!isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                || (isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                || (isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                if (player.playWhenReady && quality?.name != AUDIO_ONLY_QUALITY) {
                    restoreQuality = true
                    previousQuality = quality
                    quality = qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    quality?.let { quality ->
                        player.currentMediaItem?.let { mediaItem ->
                            if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                            }
                            if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                quality.url?.let { url ->
                                    val position = player.currentPosition
                                    if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                        restorePlaylist = true
                                    }
                                    player.setMediaItem(mediaItem.buildUpon().setUri(url).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                            }
                        }
                    }
                }
            } else {
                player.pause()
            }
        }
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
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@ExoPlayerService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@ExoPlayerService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                }.build()
            )
        }
    }

    private fun updateMetadata() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                        cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                        val response = request.future.get()
                                        if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                            response.responseBody as ByteArray
                                        } else null
                                    } else {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            response.second
                                        } else null
                                    }
                                }
                                else -> {
                                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    setMetadata(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
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
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                        cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                        val response = request.future.get()
                                        if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                            response.responseBody as ByteArray
                                        } else null
                                    } else {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            response.second
                                        } else null
                                    }
                                }
                                else -> {
                                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    sendNotification(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
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
                        this@ExoPlayerService,
                        REQUEST_CODE_RESUME,
                        Intent(this@ExoPlayerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@ExoPlayerService, R.string.rewind),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_REWIND,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (Util.shouldShowPlayButton(player)) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@ExoPlayerService, R.string.resume),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@ExoPlayerService, R.string.pause),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@ExoPlayerService, R.string.forward),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
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
            if (!player.currentTracks.isEmpty) {
                if (prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    when (type) {
                        VIDEO -> {
                            videoId?.toLongOrNull()?.let {
                                runBlocking {
                                    playerRepository.saveVideoPosition(VideoPosition(it, player.currentPosition))
                                }
                            }
                        }
                        OFFLINE_VIDEO -> {
                            offlineVideoId?.let {
                                runBlocking {
                                    offlineRepository.updateVideoPosition(it, player.currentPosition)
                                }
                            }
                        }
                    }
                }
                runBlocking {
                    playerRepository.deletePlaybackStates()
                }
            }
        }
    }

    private fun updateSavedPosition() {
        player?.let { player ->
            if (!player.currentTracks.isEmpty) {
                val currentPosition = player.currentPosition
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    if (prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                        when (type) {
                            VIDEO -> {
                                videoId?.toLongOrNull()?.let {
                                    runBlocking {
                                        playerRepository.saveVideoPosition(VideoPosition(it, currentPosition))
                                    }
                                }
                            }
                            OFFLINE_VIDEO -> {
                                offlineVideoId?.let {
                                    runBlocking {
                                        offlineRepository.updateVideoPosition(it, currentPosition)
                                    }
                                }
                            }
                        }
                    }
                    runBlocking {
                        savePlaybackState(currentPosition, !player.playWhenReady)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_REWIND -> player?.seekBack()
            INTENT_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            INTENT_FAST_FORWARD -> player?.seekForward()
            INTENT_START -> create(restorePauseState = true)
            Intent.ACTION_MEDIA_BUTTON -> create(restorePauseState = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@ExoPlayerService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        player?.playWhenReady = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        session?.release()
        bitmapLoadJob?.cancel()
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    class CustomHlsPlaylistParserFactory: HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser()
        }

        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        }
    }

    companion object {
        const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"

        private const val NOTIFICATION_ID = 1001
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.PLAYBACK_NOTIFICATIONS"

        private const val REQUEST_CODE_RESUME = 0
        private const val REQUEST_CODE_REWIND = 1
        private const val REQUEST_CODE_PLAY_PAUSE = 2
        private const val REQUEST_CODE_FAST_FORWARD = 3

        private const val INTENT_REWIND = "com.github.andreyasadchy.xtra.REWIND"
        private const val INTENT_PLAY_PAUSE = "com.github.andreyasadchy.xtra.PLAY_PAUSE"
        private const val INTENT_FAST_FORWARD = "com.github.andreyasadchy.xtra.FAST_FORWARD"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_PLAYBACK_SERVICE"
    }
}