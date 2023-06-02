package com.github.andreyasadchy.xtra.ui.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.offline.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.player.lowlatency.DefaultHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.player.lowlatency.DefaultHlsPlaylistTracker
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.util.LinkedList
import java.util.regex.Pattern
import javax.inject.Inject


@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    private var mediaSession: MediaSession? = null
    private var playerMode = PlayerMode.NORMAL
    private var dynamicsProcessing: DynamicsProcessing? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = prefs()
        val player = ExoPlayer.Builder(this).apply {
            setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                prefs.getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                prefs.getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                prefs.getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull() ?: 2000,
                prefs.getString(C.PLAYER_BUFFER_REBUFFER, "5000")?.toIntOrNull() ?: 5000
            ).build())
            setSeekBackIncrementMs(prefs.getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000)
            setSeekForwardIncrementMs(prefs.getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000)
        }.build().apply {
            if (item != null) {
                (urls.values.elementAtOrNull(qualityUrlIndex) ?: urls.values.elementAtOrNull(previousUrlIndex) ?: urls.values.firstOrNull())?.let { url ->
                    when (item) {
                        is Stream -> {
                            (item as Stream).let { item ->
                                HlsMediaSource.Factory(DefaultDataSource.Factory(this@PlaybackService, DefaultHttpDataSource.Factory().apply {
                                    headers?.let {
                                        setDefaultRequestProperties(it)
                                    }
                                })).apply {
                                    setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                                    setPlaylistTrackerFactory(DefaultHlsPlaylistTracker.FACTORY)
                                    setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                    if (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false)) {
                                        setAllowChunklessPreparation(false)
                                    }
                                }.createMediaSource(MediaItem.Builder().apply {
                                    setUri(url)
                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                        prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "5000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                    }.build())
                                    setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.title)
                                        .setArtist(item.channelName)
                                        .setArtworkUri(item.channelLogo?.toUri())
                                        .build())
                                }.build()).let { setMediaSource(it) }
                                volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                setPlaybackSpeed(1f)
                                prepare()
                                playWhenReady = true
                                setVideoQuality()
                            }
                        }
                        is Video -> {
                            (item as Video).let { item ->
                                HlsMediaSource.Factory(DefaultDataSource.Factory(this@PlaybackService, DefaultHttpDataSource.Factory())).apply {
                                    setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                                    if (usingPlaylist && (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false))) {
                                        setAllowChunklessPreparation(false)
                                    }
                                }.createMediaSource(MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.title)
                                        .setArtist(item.channelName)
                                        .setArtworkUri(item.channelLogo?.toUri())
                                        .build())
                                    .build()
                                ).let { setMediaSource(it) }
                                volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                prepare()
                                playWhenReady = true
                                seekTo(playbackPosition)
                                setVideoQuality()
                            }
                        }
                        is Clip -> {
                            (item as Clip).let { item ->
                                setMediaItem(MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.title)
                                        .setArtist(item.channelName)
                                        .setArtworkUri(item.channelLogo?.toUri())
                                        .build())
                                    .build())
                                volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                prepare()
                                playWhenReady = true
                                seekTo(playbackPosition)
                                setVideoQuality()
                            }
                        }
                        is OfflineVideo -> {
                            (item as OfflineVideo).let { item ->
                                setMediaItem(MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.name)
                                        .setArtist(item.channelName)
                                        .build())
                                    .build())
                                volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                prepare()
                                playWhenReady = true
                                seekTo(playbackPosition)
                                setVideoQuality()
                            }
                        }
                    }
                }
            }
            reinitializeDynamicsProcessing(audioSessionId)
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty && usingPlaylist && (qualityIndex != -1 && qualityIndex != audioIndex)) {
                        updateVideoQuality(qualityUrlIndex)
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    val manifest = mediaSession?.player?.currentManifest as? HlsManifest
                    if (urls.isEmpty() && manifest is HlsManifest) {
                        manifest.multivariantPlaylist.let {
                            val tags = it.tags
                            val map = mutableMapOf<String, String>()
                            val audioOnly = getString(R.string.audio_only)
                            val pattern = Pattern.compile("NAME=\"(.+?)\"")
                            var trackIndex = 0
                            tags.forEach { tag ->
                                if (tag.startsWith("#EXT-X-MEDIA")) {
                                    val matcher = pattern.matcher(tag)
                                    if (matcher.find()) {
                                        val quality = matcher.group(1)!!
                                        val url = it.variants[trackIndex++].url.toString()
                                        map[if (!quality.startsWith("audio", true)) quality else audioOnly] = url
                                    }
                                }
                            }
                            urls = map.apply {
                                if (containsKey(audioOnly)) {
                                    remove(audioOnly)?.let { url ->
                                        put(audioOnly, url) //move audio option to bottom
                                    }
                                } else {
                                    put(audioOnly, "")
                                }
                            }
                            qualities = LinkedList(map.keys).apply {
                                if (usingAutoQuality) {
                                    addFirst(getString(R.string.auto))
                                }
                                if (usingChatOnlyQuality) {
                                    add(getString(R.string.chat_only))
                                }
                            }
                            setQualityIndex()
                        }
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    reinitializeDynamicsProcessing(audioSessionId)
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, REQUEST_CODE_RESUME,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.KEY_CODE, MainActivity.INTENT_OPEN_PLAYER)
                }, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val connectionResult = super.onConnect(session, controller)
                    val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                        .add(SessionCommand(START_STREAM, Bundle.EMPTY))
                        .add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(START_CLIP, Bundle.EMPTY))
                        .add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(CHANGE_QUALITY, Bundle.EMPTY))
                        .add(SessionCommand(START_AUDIO_ONLY, Bundle.EMPTY))
                        .add(SessionCommand(SWITCH_AUDIO_MODE, Bundle.EMPTY))
                        .add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                        .add(SessionCommand(MOVE_BACKGROUND, Bundle.EMPTY))
                        .add(SessionCommand(MOVE_FOREGROUND, Bundle.EMPTY))
                        .add(SessionCommand(CLEAR, Bundle.EMPTY))
                        .add(SessionCommand(GET_URLS, Bundle.EMPTY))
                        .add(SessionCommand(GET_LAST_TAG, Bundle.EMPTY))
                        .add(SessionCommand(GET_QUALITIES, Bundle.EMPTY))
                        .add(SessionCommand(GET_QUALITY_TEXT, Bundle.EMPTY))
                        .add(SessionCommand(GET_MEDIA_PLAYLIST, Bundle.EMPTY))
                        .add(SessionCommand(GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY))
                        .add(SessionCommand(GET_VIDEO_DOWNLOAD_INFO, Bundle.EMPTY))
                        .add(SessionCommand(GET_ERROR_CODE, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    return when (customCommand.customAction) {
                        START_STREAM -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                customCommand.customExtras.getParcelable(ITEM, Stream::class.java)
                            } else {
                                @Suppress("DEPRECATION") customCommand.customExtras.getParcelable(ITEM)
                            }?.let { item ->
                                usingPlaylist = true
                                usingAutoQuality = true
                                usingChatOnlyQuality = true
                                val uri = customCommand.customExtras.getString(URI)?.toUri()
                                val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    customCommand.customExtras.getSerializable(HEADERS, HashMap::class.java) as? HashMap<String, String>
                                } else {
                                    @Suppress("DEPRECATION") customCommand.customExtras.getSerializable(HEADERS) as? HashMap<String, String>
                                }
                                Companion.item = item
                                Companion.headers = headers
                                HlsMediaSource.Factory(DefaultDataSource.Factory(this@PlaybackService, DefaultHttpDataSource.Factory().apply {
                                    if (headers != null) {
                                        setDefaultRequestProperties(headers)
                                    }
                                })).apply {
                                    setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                                    setPlaylistTrackerFactory(DefaultHlsPlaylistTracker.FACTORY)
                                    setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                    if (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false)) {
                                        setAllowChunklessPreparation(false)
                                    }
                                }.createMediaSource(MediaItem.Builder().apply {
                                    setUri(uri)
                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                        prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "5000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                    }.build())
                                    setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.title)
                                        .setArtist(item.channelName)
                                        .setArtworkUri(item.channelLogo?.toUri())
                                        .build())
                                }.build()).let { (session.player as? ExoPlayer)?.setMediaSource(it) }
                                session.player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(1f)
                                session.player.prepare()
                                session.player.playWhenReady = true
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        START_VIDEO -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                customCommand.customExtras.getParcelable(ITEM, Video::class.java)
                            } else {
                                @Suppress("DEPRECATION") customCommand.customExtras.getParcelable(ITEM)
                            }?.let { item ->
                                val usingPlaylist = customCommand.customExtras.getBoolean(USING_PLAYLIST)
                                if (usingPlaylist) {
                                    customCommand.customExtras.getString(URI)?.toUri()
                                } else {
                                    item.animatedPreviewURL?.let { preview ->
                                        val map = TwitchApiHelper.getVideoUrlMapFromPreview(preview, item.type)
                                        urls = map.apply {
                                            if (containsKey("audio_only")) {
                                                remove("audio_only")?.let { url ->
                                                    put(getString(R.string.audio_only), url) //move audio option to bottom
                                                }
                                            } else {
                                                put(getString(R.string.audio_only), "")
                                            }
                                        }
                                        qualities = LinkedList(urls.keys).apply {
                                            addFirst(getString(R.string.auto))
                                        }
                                        qualityIndex = 1
                                        urls.values.first().toUri()
                                    }
                                }?.let { url ->
                                    usingAutoQuality = true
                                    Companion.usingPlaylist = usingPlaylist
                                    Companion.item = item
                                    HlsMediaSource.Factory(DefaultDataSource.Factory(this@PlaybackService, DefaultHttpDataSource.Factory())).apply {
                                        setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                                        if (usingPlaylist && (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false))) {
                                            setAllowChunklessPreparation(false)
                                        }
                                    }.createMediaSource(MediaItem.Builder()
                                        .setUri(url)
                                        .setMediaMetadata(MediaMetadata.Builder()
                                            .setTitle(item.title)
                                            .setArtist(item.channelName)
                                            .setArtworkUri(item.channelLogo?.toUri())
                                            .build())
                                        .build()
                                    ).let { (session.player as? ExoPlayer)?.setMediaSource(it) }
                                    session.player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                    session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    session.player.prepare()
                                    session.player.playWhenReady = true
                                    session.player.seekTo(customCommand.customExtras.getLong(PLAYBACK_POSITION))
                                }
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        START_CLIP -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                customCommand.customExtras.getParcelable(ITEM, Clip::class.java)
                            } else {
                                @Suppress("DEPRECATION") customCommand.customExtras.getParcelable(ITEM)
                            }?.let { item ->
                                customCommand.customExtras.getStringArray(URLS_KEYS)?.let { keys ->
                                    customCommand.customExtras.getStringArray(URLS_VALUES)?.let { values ->
                                        val map = keys.zip(values).toMap(mutableMapOf())
                                        Companion.item = item
                                        urls = map.apply {
                                            put(getString(R.string.audio_only), "")
                                        }
                                        qualities = LinkedList(urls.keys)
                                        setQualityIndex()
                                        (map.values.elementAtOrNull(qualityUrlIndex) ?: map.values.firstOrNull())?.let { url ->
                                            session.player.setMediaItem(MediaItem.Builder()
                                                .setUri(url)
                                                .setMediaMetadata(MediaMetadata.Builder()
                                                    .setTitle(item.title)
                                                    .setArtist(item.channelName)
                                                    .setArtworkUri(item.channelLogo?.toUri())
                                                    .build())
                                                .build())
                                            session.player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                            session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                            session.player.prepare()
                                            session.player.playWhenReady = true
                                        }
                                    }
                                }
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        START_OFFLINE_VIDEO -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                customCommand.customExtras.getParcelable(ITEM, OfflineVideo::class.java)
                            } else {
                                @Suppress("DEPRECATION") customCommand.customExtras.getParcelable(ITEM)
                            }?.let { item ->
                                Companion.item = item
                                urls = mapOf(
                                    getString(R.string.source) to item.url,
                                    getString(R.string.audio_only) to ""
                                )
                                qualities = LinkedList(urls.keys)
                                session.player.setMediaItem(MediaItem.Builder()
                                    .setUri(item.url)
                                    .setMediaMetadata(MediaMetadata.Builder()
                                        .setTitle(item.name)
                                        .setArtist(item.channelName)
                                        .build())
                                    .build())
                                session.player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) item.lastWatchPosition ?: 0 else 0)
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        CHANGE_QUALITY -> {
                            val index = customCommand.customExtras.getInt(INDEX)
                            changeQuality(index)
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to playerMode)))
                        }
                        START_AUDIO_ONLY -> {
                            startAudioOnly()
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to playerMode)))
                        }
                        SWITCH_AUDIO_MODE -> {
                            if (playerMode != PlayerMode.AUDIO_ONLY) {
                                changeQuality(audioIndex)
                            } else {
                                changeQuality(previousIndex)
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to playerMode)))
                        }
                        TOGGLE_DYNAMICS_PROCESSING -> {
                            toggleDynamicsProcessing()
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to dynamicsProcessing?.enabled)))
                        }
                        MOVE_BACKGROUND -> {
                            if (prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0") == "2") {
                                savePosition()
                                playbackPosition = session.player.currentPosition
                                session.player.stop()
                            } else {
                                if (playerMode == PlayerMode.NORMAL) {
                                    if (session.player.isPlaying) {
                                        startAudioOnly()
                                    } else {
                                        savePosition()
                                        playbackPosition = session.player.currentPosition
                                        session.player.stop()
                                    }
                                }
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to playerMode)))
                        }
                        MOVE_FOREGROUND -> {
                            if (playerMode == PlayerMode.NORMAL) {
                                session.player.prepare()
                            } else if (playerMode == PlayerMode.AUDIO_ONLY) {
                                if (qualityIndex != audioIndex) {
                                    setVideoQuality()
                                }
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to playerMode)))
                        }
                        CLEAR -> {
                            clear()
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        GET_URLS -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                            URLS_KEYS to urls.keys.toTypedArray(),
                            URLS_VALUES to urls.values.toTypedArray()
                        )))
                        GET_LAST_TAG -> {
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.lastOrNull()
                            )))
                        }
                        GET_QUALITIES -> {
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to qualities.toTypedArray(),
                                INDEX to qualityIndex,
                            )))
                        }
                        GET_QUALITY_TEXT -> {
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(RESULT to qualities.getOrNull(qualityIndex))))
                        }
                        GET_MEDIA_PLAYLIST -> {
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.dropLastWhile { it == "ads=true" }?.joinToString("\n")
                            )))
                        }
                        GET_MULTIVARIANT_PLAYLIST -> {
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.joinToString("\n")
                            )))
                        }
                        GET_VIDEO_DOWNLOAD_INFO -> {
                            val info = (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.let { playlist ->
                                val segments = playlist.segments
                                val size = segments.size
                                val relativeTimes = ArrayList<Long>(size)
                                val durations = ArrayList<Long>(size)
                                for (i in 0 until size) {
                                    val segment = segments[i]
                                    relativeTimes.add(segment.relativeStartTimeUs / 1000L)
                                    durations.add(segment.durationUs / 1000L)
                                }
                                VideoDownloadInfo(
                                    video = Video(),
                                    qualities = urls,
                                    relativeStartTimes = relativeTimes,
                                    durations = durations,
                                    totalDuration = playlist.durationUs / 1000L,
                                    targetDuration = playlist.targetDurationUs / 1000L,
                                    currentPosition = session.player.currentPosition
                                )
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to info
                            )))
                        }
                        GET_ERROR_CODE -> {
                            val responseCode = (session.player as? ExoPlayer)?.playerError?.let { playerError ->
                                if (playerError.type == ExoPlaybackException.TYPE_SOURCE) {
                                    (playerError.sourceException as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                                } else null
                            }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(
                                RESULT to responseCode,
                            )))
                        }
                        else -> super.onCustomCommand(session, controller, customCommand, args)
                    }
                }
            })
            .build()
    }

    private fun changeQuality(index: Int) {
        previousIndex = qualityIndex
        qualityIndex = index
        setVideoQuality()
    }

    private fun setVideoQuality() {
        mediaSession?.player?.let { player ->
            val mode = playerMode
            if (mode != PlayerMode.NORMAL) {
                playerMode = PlayerMode.NORMAL
                if (mode == PlayerMode.AUDIO_ONLY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    }.build()
                    if (player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } == null) {
                        val position = player.currentPosition
                        mediaItem?.let { player.setMediaItem(it) }
                        player.prepare()
                        player.seekTo(position)
                    }
                } else if (mode == PlayerMode.DISABLED) {
                    player.prepare()
                }
            }
            when {
                qualityIndex > audioIndex -> {
                    player.stop()
                    playerMode = PlayerMode.DISABLED
                }
                qualityIndex == audioIndex -> {
                    val urlIndex = if (!urls.entries.elementAtOrNull(audioUrlIndex)?.value.isNullOrBlank()) {
                        audioUrlIndex
                    } else {
                        if (!urls.entries.elementAtOrNull(previousUrlIndex)?.value.isNullOrBlank()) {
                            previousUrlIndex
                        } else {
                            0
                        }
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    }.build()
                    playerMode = PlayerMode.AUDIO_ONLY
                    updateVideoQuality(urlIndex)
                }
                else -> {
                    updateVideoQuality(qualityUrlIndex)
                    if (prefs().getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") {
                        if (qualityIndex == -1) {
                            "Auto"
                        } else {
                            if (qualityIndex < audioIndex) qualities.getOrNull(qualityIndex) else null
                        }?.let { prefs().edit { putString(C.PLAYER_QUALITY, it) } }
                    }
                }
            }
        }
    }

    private fun startAudioOnly() {
        mediaSession?.player?.let { player ->
            val urlIndex = if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false) && !urls.entries.elementAtOrNull(audioUrlIndex)?.value.isNullOrBlank()) {
                audioUrlIndex
            } else {
                if (!urls.entries.elementAtOrNull(qualityUrlIndex)?.value.isNullOrBlank()) {
                    qualityUrlIndex
                } else {
                    0
                }
            }
            if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
            }
            playerMode = PlayerMode.AUDIO_ONLY
            updateVideoQuality(urlIndex)
        }
    }

    private fun updateVideoQuality(urlIndex: Int) {
        mediaSession?.player?.let { player ->
            if (!usingPlaylist || urlIndex == audioUrlIndex) {
                urls.values.elementAtOrNull(urlIndex)?.let { url ->
                    val position = player.currentPosition
                    player.currentMediaItem?.let {
                        mediaItem = it
                        player.setMediaItem(it.buildUpon().setUri(url).build())
                    }
                    player.prepare()
                    player.seekTo(position)
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    if (urlIndex == -1) {
                        clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    } else {
                        if (!player.currentTracks.isEmpty) {
                            player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let {
                                if (it.length - 1 >= urlIndex) {
                                    setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, urlIndex))
                                }
                            }
                        }
                    }
                }.build()
            }
        }
    }

    private fun setQualityIndex() {
        val defaultQuality = prefs().getString(C.PLAYER_DEFAULTQUALITY, "saved")
        val savedQuality = prefs().getString(C.PLAYER_QUALITY, "720p60")
        val index = when (defaultQuality) {
            "Source" -> {
                if (usingAutoQuality) {
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
                    default[0].filter(Char::isDigit).toIntOrNull()?.let { defaultRes ->
                        val defaultFps = if (default.size >= 2) default[1].filter(Char::isDigit).toIntOrNull() ?: 0 else 0
                        qualities.indexOf(qualities.find { qualityString ->
                            qualityString.split("p").let { quality ->
                                quality[0].filter(Char::isDigit).toIntOrNull()?.let { qualityRes ->
                                    val qualityFps = if (quality.size >= 2) quality[1].filter(Char::isDigit).toIntOrNull() ?: 0 else 0
                                    (defaultRes == qualityRes && defaultFps >= qualityFps) || defaultRes > qualityRes
                                } ?: false
                            }
                        }).let { if (it != -1) it else null }
                    }
                }
            }
        }
        qualityIndex = index ?: 0
    }

    private fun toggleDynamicsProcessing() {
        (mediaSession?.player as? ExoPlayer)?.let { player ->
            val enable = !prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)
            prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enable) }
            if (enable) {
                if (dynamicsProcessing == null) {
                    reinitializeDynamicsProcessing(player.audioSessionId)
                } else {
                    dynamicsProcessing?.enabled = true
                }
            } else {
                dynamicsProcessing?.enabled = false
            }
        }
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        dynamicsProcessing?.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, null).apply {
                for (channelIdx in 0 until channelCount) {
                    for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                        setMbcBandByChannelIndex(channelIdx, bandIdx,
                            getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                attackTime = 0f
                                releaseTime = 0.25f
                                ratio = 1.6f
                                threshold = -50f
                                kneeWidth = 40f
                                preGain = 0f
                                postGain = 10f
                            })
                    }
                }
                enabled = true
            }
        }
    }

    private fun clear() {
        savePosition()
        item = null
        mediaItem = null
        headers = null
        playbackPosition = 0
        playerMode = PlayerMode.NORMAL
        usingPlaylist = false
        usingAutoQuality = false
        usingChatOnlyQuality = false
        urls = emptyMap()
        qualities = emptyList()
        qualityIndex = 0
        previousIndex = 0
        mediaSession?.player?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
            }.build()
            player.removeMediaItem(0)
        }
    }

    private fun savePosition() {
        item?.let { item ->
            mediaSession?.player?.currentPosition?.let { position ->
                if (prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    when (item) {
                        is Video -> {
                            item.id?.toLongOrNull()?.let { id ->
                                playerRepository.saveVideoPosition(VideoPosition(id, position))
                            }
                        }
                        is OfflineVideo -> offlineRepository.updateVideoPosition(item.id, position)
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        mediaSession?.player?.pause()
        mediaSession?.player?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private var item: Parcelable? = null
        private var mediaItem: MediaItem? = null
        private var headers: HashMap<String, String>? = null
        private var playbackPosition: Long = 0

        private var usingPlaylist = false
        private var usingAutoQuality = false
        private var usingChatOnlyQuality = false

        private var urls: Map<String, String> = emptyMap()
        private var qualities: List<String> = emptyList()
        private var qualityIndex = 0
        private val qualityUrlIndex: Int
            get() = if (usingAutoQuality) qualityIndex - 1 else qualityIndex
        private var previousIndex = 0
        private val previousUrlIndex: Int
            get() = if (usingAutoQuality) previousIndex - 1 else previousIndex
        private val audioIndex: Int
            get() = if (usingChatOnlyQuality) qualities.lastIndex - 1 else qualities.lastIndex
        private val audioUrlIndex: Int
            get() = urls.size - 1

        const val START_STREAM = "startStream"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"

        const val CHANGE_QUALITY = "changeQuality"
        const val START_AUDIO_ONLY = "startAudioOnly"
        const val SWITCH_AUDIO_MODE = "switchAudioMode"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val MOVE_BACKGROUND = "moveBackground"
        const val MOVE_FOREGROUND = "moveForeground"
        const val CLEAR = "clear"

        const val GET_URLS = "getUrls"
        const val GET_LAST_TAG = "getLastTag"
        const val GET_QUALITIES = "getQualities"
        const val GET_QUALITY_TEXT = "getQualityText"
        const val GET_MEDIA_PLAYLIST = "getMediaPlaylist"
        const val GET_MULTIVARIANT_PLAYLIST = "getMultivariantPlaylist"
        const val GET_VIDEO_DOWNLOAD_INFO = "getVideoDownloadInfo"
        const val GET_ERROR_CODE = "getErrorCode"

        const val RESULT = "result"
        const val ITEM = "item"
        const val INDEX = "index"
        const val URI = "uri"
        const val URLS_KEYS = "urlsKeys"
        const val URLS_VALUES = "urlsValues"
        const val HEADERS = "headers"
        const val USING_PLAYLIST = "usingPlaylist"
        const val PLAYBACK_POSITION = "playbackPosition"

        const val REQUEST_CODE_RESUME = 2
    }
}