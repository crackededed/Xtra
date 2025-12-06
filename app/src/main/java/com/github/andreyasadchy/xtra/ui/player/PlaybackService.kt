package com.github.andreyasadchy.xtra.ui.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.http.HttpEngine
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate


@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    @JvmField
    var httpEngine: Lazy<HttpEngine>? = null

    @Inject
    @JvmField
    var cronetEngine: Lazy<CronetEngine>? = null

    @Inject
    lateinit var cronetExecutor: ExecutorService

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    private var mediaSession: MediaSession? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var background = false
    private var proxyMediaPlaylist = false
    private var videoId: Long? = null
    private var offlineVideoId: Int? = null
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
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
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

                override fun onPlayerError(error: PlaybackException) {
                    if (background) {
                        player.prepare()
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        it.release()
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }
            }
        )
        mediaSession = MediaSession.Builder(
            this,
            object : ForwardingSimpleBasePlayer(player) {
                override fun getState(): State {
                    val state = super.getState()
                    return state
                        .buildUpon()
                        .setAvailableCommands(
                            state.availableCommands.buildUpon()
                                .add(COMMAND_SEEK_TO_NEXT)
                                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                                .build()
                        )
                        .build()
                }

                override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                    return when (seekCommand) {
                        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            player.seekForward()
                            Futures.immediateVoidFuture()
                        }
                        COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            player.seekBack()
                            Futures.immediateVoidFuture()
                        }
                        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                    }
                }
            }
        ).apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService,
                    REQUEST_CODE_RESUME,
                    Intent(this@PlaybackService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_PLAYER
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            setCallback(
                object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                        val connectionResult = super.onConnect(session, controller)
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(START_CLIP, Bundle.EMPTY))
                            add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_PROXY, Bundle.EMPTY))
                            add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(CHECK_ADS, Bundle.EMPTY))
                            add(SessionCommand(GET_QUALITIES, Bundle.EMPTY))
                            add(SessionCommand(GET_DURATION, Bundle.EMPTY))
                            add(SessionCommand(GET_ERROR_CODE, Bundle.EMPTY))
                            add(SessionCommand(GET_MEDIA_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY))
                        }.build()
                        return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
                    }

                    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                        return when (customCommand.customAction) {
                            START_STREAM -> {
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                videoId = null
                                offlineVideoId = null
                                proxyMediaPlaylist = false
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
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                                player.setMediaSource(
                                    HlsMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
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
                                                    try {
                                                        val json = JSONObject(it)
                                                        hashMapOf<String, String>().apply {
                                                            json.keys().forEach { key ->
                                                                put(key, json.optString(key))
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        null
                                                    }
                                                }?.let {
                                                    setDefaultRequestProperties(it)
                                                }
                                            }
                                        )
                                    ).apply {
                                        setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                        setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                    }.createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMimeType(MimeTypes.APPLICATION_M3U8)
                                            setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                                prefs().getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                                prefs().getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                                prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                            }.build())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(1f)
                                session.player.prepare()
                                session.player.playWhenReady = true
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_VIDEO -> {
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getLong(VIDEO_ID).takeIf { it != 0L }
                                val position = if (videoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = newId
                                offlineVideoId = null
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                                player.setMediaSource(
                                    HlsMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
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
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_CLIP -> {
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                videoId = null
                                offlineVideoId = null
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                                player.setMediaSource(
                                    ProgressiveMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
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
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_OFFLINE_VIDEO -> {
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getInt(VIDEO_ID).takeIf { it != 0 }
                                val position = if (offlineVideoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = null
                                offlineVideoId = newId
                                session.player.setMediaItem(
                                    MediaItem.Builder().apply {
                                        setUri(uri)
                                        setMediaMetadata(
                                            MediaMetadata.Builder().apply {
                                                setTitle(title)
                                                setArtist(channelName)
                                                setArtworkUri(channelLogo?.toUri())
                                            }.build()
                                        )
                                    }.build()
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            TOGGLE_DYNAMICS_PROCESSING -> {
                                if (dynamicsProcessing?.enabled == true) {
                                    dynamicsProcessing?.enabled = false
                                } else {
                                    if (dynamicsProcessing == null) {
                                        reinitializeDynamicsProcessing(player.audioSessionId)
                                    } else {
                                        dynamicsProcessing?.enabled = true
                                    }
                                }
                                val enabled = dynamicsProcessing?.enabled
                                prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled == true) }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to enabled
                                )))
                            }
                            TOGGLE_PROXY -> {
                                proxyMediaPlaylist = customCommand.customExtras.getBoolean(USING_PROXY)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_SLEEP_TIMER -> {
                                val duration = customCommand.customExtras.getLong(DURATION)
                                background = duration != -1L
                                val endTime = sleepTimerEndTime
                                sleepTimer?.cancel()
                                sleepTimerEndTime = 0L
                                if (duration > 0L) {
                                    sleepTimer = Timer().apply {
                                        schedule(duration) {
                                            Handler(Looper.getMainLooper()).post {
                                                savePosition()
                                                mediaSession?.player?.clearMediaItems()
                                                pauseAllPlayersAndStopSelf()
                                            }
                                        }
                                    }
                                    sleepTimerEndTime = System.currentTimeMillis() + duration
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to endTime
                                )))
                            }
                            CHECK_ADS -> {
                                val playlist = (session.player.currentManifest as? HlsManifest)?.mediaPlaylist
                                val adSegment = playlist?.segments?.lastOrNull()?.let { segment ->
                                    val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
                                    listOf("Amazon", "Adform", "DCM").any { segment.title.contains(it) } ||
                                            playlist.interstitials.find {
                                                val startTime = it.startDateUnixUs
                                                val endTime = it.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                                                    ?: it.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                                    ?: it.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                                endTime != null && (it.id.startsWith("stitched-ad-") ||
                                                        it.clientDefinedAttributes.find { it.name == "CLASS" }?.textValue == "twitch-stitched-ad" ||
                                                        it.clientDefinedAttributes.find { it.name.startsWith("X-TV-TWITCH-AD-") } != null)
                                                        && (startTime <= segmentStartTime && segmentStartTime < endTime)
                                            } != null
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to adSegment
                                )))
                            }
                            GET_QUALITIES -> {
                                val playlist = (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist
                                val names = playlist?.variants?.mapNotNull { it.format.label }?.toTypedArray()
                                if (!names.isNullOrEmpty()) {
                                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                        NAMES to names,
                                        CODECS to playlist.variants.map { it.format.codecs }.toTypedArray(),
                                        URLS to playlist.variants.map { it.url.toString() }.toTypedArray(),
                                    )))
                                } else {
                                    val variants = playlist?.variants?.mapNotNull { variant ->
                                        playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.let { variant to it }
                                    }
                                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                        NAMES to variants?.map { it.second }?.toTypedArray(),
                                        CODECS to variants?.map { it.first.format.codecs }?.toTypedArray(),
                                        URLS to variants?.map { it.first.url.toString() }?.toTypedArray(),
                                    )))
                                }
                            }
                            GET_DURATION -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000)
                                )))
                            }
                            GET_ERROR_CODE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to (session.player.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
                                )))
                            }
                            GET_MEDIA_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray()
                                )))
                            }
                            GET_MULTIVARIANT_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                    RESULT to (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray()
                                )))
                            }
                            else -> super.onCustomCommand(session, controller, customCommand, args)
                        }
                    }
                }
            )
        }.build()
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
        mediaSession?.player?.let { player ->
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
        mediaSession?.player?.let { player ->
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        mediaSession?.player?.clearMediaItems()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    class CustomHlsPlaylistParserFactory(): HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser()
        }

        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        }
    }

    companion object {
        const val START_STREAM = "startStream"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val TOGGLE_PROXY = "toggleProxy"
        const val SET_SLEEP_TIMER = "setSleepTimer"
        const val CHECK_ADS = "checkAds"
        const val GET_QUALITIES = "getQualities"
        const val GET_DURATION = "getDuration"
        const val GET_ERROR_CODE = "getErrorCode"
        const val GET_MEDIA_PLAYLIST = "getMediaPlaylist"
        const val GET_MULTIVARIANT_PLAYLIST = "getMultivariantPlaylist"

        const val RESULT = "result"
        const val URI = "uri"
        const val VIDEO_ID = "videoId"
        const val PLAYBACK_POSITION = "playbackPosition"
        const val TITLE = "title"
        const val CHANNEL_NAME = "channelName"
        const val CHANNEL_LOGO = "channelLogo"
        const val USING_PROXY = "usingProxy"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val CODECS = "codecs"
        const val URLS = "urls"

        const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"

        const val REQUEST_CODE_RESUME = 2
    }
}