package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateUtils
import android.util.Log
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlaybackService.CustomHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.floor

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ExoPlayerFragment : PlayerFragment() {

    private var playbackService: ExoPlayerService? = null
    private var serviceConnection: ServiceConnection? = null
    private val player: ExoPlayer?
        get() = playbackService?.player
    private var playerListener: Player.Listener? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as ExoPlayerService.ServiceBinder
                    playbackService = binder.getService()
                    player?.setVideoSurfaceView(binding.playerSurface)
                    val listener = object : Player.Listener {

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            binding.bufferingIndicator.isVisible = playbackState == Player.STATE_BUFFERING
                            val showPlayButton = Util.shouldShowPlayButton(player)
                            if (showPlayButton) {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                                binding.playerControls.playPause.visible()
                            } else {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                                if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                    binding.playerControls.playPause.gone()
                                }
                            }
                            setPipActions(!showPlayButton)
                            updateProgress()
                            controllerAutoHide = !showPlayButton
                            if (videoType != STREAM && useController) {
                                showController()
                            }
                        }

                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                            binding.bufferingIndicator.isVisible = player?.playbackState == Player.STATE_BUFFERING
                            val showPlayButton = Util.shouldShowPlayButton(player)
                            if (showPlayButton) {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                                binding.playerControls.playPause.visible()
                            } else {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                                if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                    binding.playerControls.playPause.gone()
                                }
                            }
                            setPipActions(!showPlayButton)
                            updateProgress()
                            controllerAutoHide = !showPlayButton
                            if (videoType != STREAM && useController) {
                                showController()
                            }
                        }

                        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                            if (Util.shouldShowPlayButton(player)) {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                                binding.playerControls.playPause.visible()
                            } else {
                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                                if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                    binding.playerControls.playPause.gone()
                                }
                            }
                            val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                            binding.playerControls.progressBar.setDuration(duration)
                            binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                            updateProgress()
                        }

                        override fun onCues(cueGroup: CueGroup) {
                            binding.subtitleView.setCues(cueGroup.cues)
                        }

                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                            binding.playerControls.progressBar.setDuration(duration)
                            binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                            updateProgress()
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                                chatFragment?.updatePosition(newPosition.positionMs)
                            }
                        }

                        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                            chatFragment?.updateSpeed(playbackParameters.speed)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updateProgress()
                            if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                                requireView().keepScreenOn = isPlaying
                            }
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            if (!tracks.isEmpty && !viewModel.loaded.value) {
                                viewModel.loaded.value = true
                                toggleSubtitles(prefs.getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                            }
                            setSubtitlesButton()
                            if (!tracks.isEmpty) {
                                if (viewModel.qualities.containsKey(AUTO_QUALITY)
                                    && viewModel.quality != AUDIO_ONLY_QUALITY
                                    && !viewModel.hidden) {
                                    changeQuality(viewModel.quality)
                                }
                                chatFragment?.startReplayChatLoad()
                            }
                        }

                        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                            val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                            binding.playerControls.progressBar.setDuration(duration)
                            binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                            updateProgress()
                            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                viewModel.updateQualities = viewModel.quality != AUDIO_ONLY_QUALITY
                            }
                            if (viewModel.qualities.isEmpty() || viewModel.updateQualities) {
                                val playlist = (player?.currentManifest as? HlsManifest)?.multivariantPlaylist
                                val names: Array<String>?
                                val codecStrings: Array<String?>?
                                val urls: Array<String>?
                                val labels = playlist?.variants?.mapNotNull { it.format.label }?.toTypedArray()
                                if (!labels.isNullOrEmpty()) {
                                    names = labels
                                    codecStrings = playlist.variants.map { it.format.codecs }.toTypedArray()
                                    urls = playlist.variants.map { it.url.toString() }.toTypedArray()
                                } else {
                                    val variants = playlist?.variants?.mapNotNull { variant ->
                                        playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.let { variant to it }
                                    }
                                    names = variants?.map { it.second }?.toTypedArray()
                                    codecStrings = variants?.map { it.first.format.codecs }?.toTypedArray()
                                    urls = variants?.map { it.first.url.toString() }?.toTypedArray()
                                }
                                val codecs = codecStrings?.map { codec ->
                                    codec?.substringBefore('.').let {
                                        when (it) {
                                            "av01" -> "AV1"
                                            "hev1" -> "H.265"
                                            "avc1" -> "H.264"
                                            else -> it
                                        }
                                    }
                                }?.takeUnless { it.all { it == "H.264" || it == "mp4a" } }
                                if (!names.isNullOrEmpty() && !urls.isNullOrEmpty()) {
                                    val map = mutableMapOf<String, Pair<String, String?>>()
                                    map[AUTO_QUALITY] = Pair(requireContext().getString(R.string.auto), null)
                                    names.forEachIndexed { index, quality ->
                                        urls.getOrNull(index)?.let { url ->
                                            when {
                                                quality.equals("source", true) -> {
                                                    val quality = requireContext().getString(R.string.source)
                                                    map["source"] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                                }
                                                quality.startsWith("audio", true) -> {
                                                    map[AUDIO_ONLY_QUALITY] = Pair(requireContext().getString(R.string.audio_only), url)
                                                }
                                                else -> {
                                                    map[quality] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                                }
                                            }
                                        }
                                    }
                                    if (!map.containsKey(AUDIO_ONLY_QUALITY)) {
                                        map[AUDIO_ONLY_QUALITY] = Pair(requireContext().getString(R.string.audio_only), null)
                                    }
                                    if (videoType == STREAM) {
                                        map[CHAT_ONLY_QUALITY] = Pair(requireContext().getString(R.string.chat_only), null)
                                    }
                                    viewModel.qualities = map.toList()
                                        .sortedByDescending {
                                            it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                        }
                                        .sortedByDescending {
                                            it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                        }
                                        .sortedByDescending {
                                            it.first == "source"
                                        }
                                        .sortedByDescending {
                                            it.first == "auto"
                                        }
                                        .toMap()
                                    setDefaultQuality()
                                    changePlayerMode()
                                    if (viewModel.quality == AUDIO_ONLY_QUALITY) {
                                        changeQuality(viewModel.quality)
                                    }
                                }
                                if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                                    viewModel.updateQualities = false
                                }
                            }
                            if (videoType == STREAM) {
                                val hideAds = prefs.getBoolean(C.PLAYER_HIDE_ADS, false)
                                val useProxy = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true)
                                        && !prefs.getString(C.PROXY_HOST, null).isNullOrBlank()
                                        && prefs.getString(C.PROXY_PORT, null)?.toIntOrNull() != null
                                if (hideAds || useProxy) {
                                    val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist
                                    val playingAds = playlist?.segments?.lastOrNull()?.let { segment ->
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
                                    } == true
                                    val oldValue = viewModel.playingAds
                                    viewModel.playingAds = playingAds
                                    if (playingAds) {
                                        if (viewModel.usingProxy) {
                                            if (!viewModel.stopProxy) {
                                                playbackService?.proxyMediaPlaylist = false
                                                viewModel.usingProxy = false
                                                viewModel.stopProxy = true
                                            }
                                        } else {
                                            if (!oldValue) {
                                                val playlist = viewModel.qualities[viewModel.quality]?.second
                                                if (!viewModel.stopProxy && !playlist.isNullOrBlank() && useProxy) {
                                                    playbackService?.proxyMediaPlaylist = false
                                                    viewModel.usingProxy = true
                                                    viewLifecycleOwner.lifecycleScope.launch {
                                                        for (i in 0 until 10) {
                                                            delay(10000)
                                                            if (!viewModel.checkPlaylist(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), playlist)) {
                                                                break
                                                            }
                                                        }
                                                        playbackService?.proxyMediaPlaylist = false
                                                        viewModel.usingProxy = false
                                                    }
                                                } else {
                                                    if (hideAds) {
                                                        viewModel.hidden = true
                                                        player?.let { player ->
                                                            if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                                                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                                }.build()
                                                                binding.playerSurface.gone()
                                                            }
                                                            player.volume = 0f
                                                        }
                                                        requireContext().toast(R.string.waiting_ads)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        if (hideAds && viewModel.hidden) {
                                            viewModel.hidden = false
                                            player?.let { player ->
                                                if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    }.build()
                                                    binding.playerSurface.visible()
                                                }
                                                player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(tag, "Player error", error)
                            when (videoType) {
                                STREAM -> {
                                    val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                                    if (requireContext().isNetworkAvailable) {
                                        when {
                                            responseCode == 404 -> {
                                                requireContext().toast(R.string.stream_ended)
                                            }
                                            viewModel.useCustomProxy && responseCode >= 400 -> {
                                                requireContext().toast(R.string.proxy_error)
                                                viewModel.useCustomProxy = false
                                                viewLifecycleOwner.lifecycleScope.launch {
                                                    delay(1500L)
                                                    try {
                                                        restartPlayer()
                                                    } catch (e: Exception) {
                                                    }
                                                }
                                            }
                                            else -> {
                                                requireContext().shortToast(R.string.player_error)
                                                viewLifecycleOwner.lifecycleScope.launch {
                                                    delay(1500L)
                                                    try {
                                                        restartPlayer()
                                                    } catch (e: Exception) {
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                VIDEO -> {
                                    val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                                    if (requireContext().isNetworkAvailable) {
                                        val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                                        when {
                                            skipAccessToken == 1 && viewModel.shouldRetry && responseCode != 0 -> {
                                                viewModel.shouldRetry = false
                                                playVideo(false, player?.currentPosition)
                                            }
                                            skipAccessToken == 2 && viewModel.shouldRetry && responseCode != 0 -> {
                                                viewModel.shouldRetry = false
                                                playVideo(true, player?.currentPosition)
                                            }
                                            responseCode == 403 -> {
                                                requireContext().toast(R.string.video_subscribers_only)
                                            }
                                            else -> {
                                                requireContext().shortToast(R.string.player_error)
                                                viewLifecycleOwner.lifecycleScope.launch {
                                                    delay(1500L)
                                                    try {
                                                        player?.prepare()
                                                    } catch (e: Exception) {
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    player?.addListener(listener)
                    playerListener = listener
                    if (viewModel.restoreQuality) {
                        viewModel.restoreQuality = false
                        changeQuality(viewModel.previousQuality)
                    }
                    val endTime = playbackService?.setSleepTimer(-1)
                    if (endTime != null && endTime > 0L) {
                        val duration = endTime - System.currentTimeMillis()
                        if (duration > 0L) {
                            (activity as? MainActivity)?.setSleepTimer(duration)
                        } else {
                            minimize()
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                    }
                    if (viewModel.resume) {
                        viewModel.resume = false
                        player?.playWhenReady = true
                        player?.prepare()
                    }
                    player?.let { player ->
                        if (viewModel.loaded.value && player.currentMediaItem == null) {
                            viewModel.started = false
                        }
                        if (viewModel.started && player.currentMediaItem != null) {
                            chatFragment?.startReplayChatLoad()
                        }
                        if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                            requireView().keepScreenOn = player.isPlaying
                        }
                        if (player.isPlaying) {
                            updateProgress()
                        }
                        if (Util.shouldShowPlayButton(player)) {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                            binding.playerControls.playPause.visible()
                        } else {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                            if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                binding.playerControls.playPause.gone()
                            }
                        }
                    }
                    if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                        startPlayer()
                    }
                    player?.let { player ->
                        setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService = null
            }
        }
        serviceConnection = connection
        val intent = Intent(requireContext(), ExoPlayerService::class.java)
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    override fun startStream(url: String?) {
        player?.let { player ->
            playbackService?.videoId = null
            playbackService?.offlineVideoId = null
            playbackService?.proxyMediaPlaylist = false
            player.setMediaSource(
                HlsMediaSource.Factory(
                    DefaultDataSource.Factory(
                        requireContext(),
                        viewModel.getDataSourceFactory(
                            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                            proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                            proxyMediaPlaylist = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true),
                            proxyHost = prefs.getString(C.PROXY_HOST, null),
                            proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                            proxyUser = prefs.getString(C.PROXY_USER, null),
                            proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                            useProxy = { playbackService?.proxyMediaPlaylist == true }
                        ).apply {
                            prefs.getString(C.PLAYER_STREAM_HEADERS, null)?.let {
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
                        setUri(url?.toUri())
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                        setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                            prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                            prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                            prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                        }.build())
                        setMediaMetadata(
                            MediaMetadata.Builder().apply {
                                setTitle(requireArguments().getString(KEY_TITLE))
                                setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                            }.build()
                        )
                    }.build()
                )
            )
            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
            player.setPlaybackSpeed(1f)
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun startVideo(url: String?, playbackPosition: Long?) {
        player?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
            }.build()
            binding.playerSurface.visible()
            val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
            val position = if (playbackService?.videoId == newId && player.currentMediaItem != null) {
                player.currentPosition
            } else {
                playbackPosition ?: 0
            }
            playbackService?.videoId = newId
            playbackService?.offlineVideoId = null
            player.setMediaSource(
                HlsMediaSource.Factory(
                    DefaultDataSource.Factory(
                        requireContext(),
                        viewModel.getDataSourceFactory(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                    )
                ).apply {
                    setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                }.createMediaSource(
                    MediaItem.Builder().apply {
                        setUri(url?.toUri())
                        setMediaMetadata(
                            MediaMetadata.Builder().apply {
                                setTitle(requireArguments().getString(KEY_TITLE))
                                setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                            }.build()
                        )
                    }.build()
                )
            )
            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
            player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
            player.prepare()
            player.playWhenReady = true
            player.seekTo(position)
        }
    }

    override fun startClip(url: String?) {
        player?.let { player ->
            val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
            if (quality?.key == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                binding.playerSurface.gone()
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                binding.playerSurface.visible()
            }
            playbackService?.videoId = null
            playbackService?.offlineVideoId = null
            player.setMediaSource(
                ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(
                        requireContext(),
                        viewModel.getDataSourceFactory(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                    )
                ).createMediaSource(
                    MediaItem.Builder().apply {
                        setUri(url?.toUri())
                        setMediaMetadata(
                            MediaMetadata.Builder().apply {
                                setTitle(requireArguments().getString(KEY_TITLE))
                                setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                            }.build()
                        )
                    }.build()
                )
            )
            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
            player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun startOfflineVideo(url: String?, position: Long) {
        player?.let { player ->
            val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
            if (quality?.key == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                binding.playerSurface.gone()
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                binding.playerSurface.visible()
            }
            val newId = requireArguments().getInt(KEY_OFFLINE_VIDEO_ID).takeIf { it != 0 }
            val position = if (playbackService?.offlineVideoId == newId && player.currentMediaItem != null) {
                player.currentPosition
            } else {
                position
            }
            playbackService?.videoId = null
            playbackService?.offlineVideoId = newId
            player.setMediaItem(
                MediaItem.Builder().apply {
                    setUri(url)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(requireArguments().getString(KEY_TITLE))
                            setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                            setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                        }.build()
                    )
                }.build()
            )
            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
            player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
            player.prepare()
            player.playWhenReady = true
            player.seekTo(position)
        }
    }

    override fun getCurrentPosition() = player?.currentPosition

    override fun getCurrentSpeed() = player?.playbackParameters?.speed

    override fun getCurrentVolume() = player?.volume

    override fun playPause() {
        Util.handlePlayPauseButtonAction(player)
    }

    override fun rewind() {
        player?.seekBack()
    }

    override fun fastForward() {
        player?.seekForward()
    }

    override fun seek(position: Long) {
        player?.seekTo(position)
    }

    override fun seekToLivePosition() {
        player?.seekToDefaultPosition()
    }

    override fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        player?.volume = volume
    }

    override fun updateProgress() {
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = player?.currentPosition ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                progressBar.setPosition(currentPosition)
                progressBar.setBufferedPosition(player?.bufferedPosition ?: 0)
                root.removeCallbacks(updateProgressAction)
                player?.let { player ->
                    if (player.isPlaying) {
                        val speed = player.playbackParameters.speed
                        val delay = if (speed > 0f) {
                            (progressBar.preferredUpdateDelay / speed).toLong().coerceIn(200L..1000L)
                        } else {
                            1000
                        }
                        root.postDelayed(updateProgressAction, delay)
                    }
                }
            }
        }
    }

    override fun toggleAudioCompressor() {
        val enabled = playbackService?.toggleDynamicsProcessing()
        if (enabled == true) {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
        } else {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
        }
    }

    override fun setSubtitlesButton() {
        with(binding.playerControls) {
            val textTracks = player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            if (textTracks != null && prefs.getBoolean(C.PLAYER_SUBTITLES, false)) {
                subtitles.visible()
                if (textTracks.isSelected) {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on)
                    subtitles.setOnClickListener {
                        toggleSubtitles(false)
                        prefs.edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                    }
                } else {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_off)
                    subtitles.setOnClickListener {
                        toggleSubtitles(true)
                        prefs.edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                    }
                }
            } else {
                subtitles.gone()
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(textTracks)
        }
    }

    override fun toggleSubtitles(enabled: Boolean) {
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

    override fun showPlaylistTags(mediaPlaylist: Boolean) {
        val tags = if (mediaPlaylist) {
            (player?.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray()
        } else {
            (player?.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray()
        }?.joinToString("\n")
        if (!tags.isNullOrBlank()) {
            requireContext().getAlertDialogBuilder().apply {
                setView(NestedScrollView(context).apply {
                    addView(HorizontalScrollView(context).apply {
                        addView(TextView(context).apply {
                            text = tags
                            textSize = 12F
                            setTextIsSelectable(true)
                        })
                    })
                })
                setNegativeButton(R.string.copy_clip) { _, _ ->
                    val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("label", tags))
                }
                setPositiveButton(android.R.string.ok, null)
            }.show()
        }
    }

    override fun changeQuality(selectedQuality: String?) {
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = selectedQuality
        viewModel.qualities.entries.find { it.key == selectedQuality }?.let { quality ->
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.key) {
                        AUTO_QUALITY -> {
                            viewModel.playlistUrl?.let { uri ->
                                if (mediaItem.localConfiguration?.uri != uri) {
                                    val position = player.currentPosition
                                    player.setMediaItem(mediaItem.buildUpon().setUri(uri).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                                viewModel.playlistUrl = null
                            } ?: player.prepare()
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                            binding.playerSurface.visible()
                        }
                        AUDIO_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                playbackService?.proxyMediaPlaylist = false
                                viewModel.usingProxy = false
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            binding.playerSurface.gone()
                            quality.value.second?.let {
                                val position = player.currentPosition
                                if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                    viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                }
                                player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                playbackService?.proxyMediaPlaylist = false
                                viewModel.usingProxy = false
                            }
                            player.stop()
                        }
                        else -> {
                            if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                viewModel.playlistUrl?.let { uri ->
                                    player.currentMediaItem?.let {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(uri).build())
                                        player.prepare()
                                        player.seekTo(position)
                                        viewModel.playlistUrl = null
                                    }
                                } ?: player.prepare()
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    binding.playerSurface.visible()
                                    if (!player.currentTracks.isEmpty) {
                                        player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let {
                                            val selectedQuality = quality.key.split("p")
                                            val targetResolution = selectedQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            val targetFps = selectedQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
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
                                    if (it.localConfiguration?.uri?.toString() != quality.value.second) {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(quality.value.second).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                                binding.playerSurface.visible()
                            }
                        }
                    }
                    if (prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") {
                        prefs.edit { putString(C.PLAYER_QUALITY, quality.key) }
                    }
                }
            }
        }
    }

    override fun startAudioOnly() {
        player?.let { player ->
            if (playbackService != null) {
                savePosition()
                if (viewModel.usingProxy) {
                    playbackService?.proxyMediaPlaylist = false
                    viewModel.usingProxy = false
                }
                if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = AUDIO_ONLY_QUALITY
                    viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                        player.currentMediaItem?.let { mediaItem ->
                            if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                                binding.playerSurface.gone()
                            }
                            if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                quality.value.second?.let {
                                    val position = player.currentPosition
                                    if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                        viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                    }
                                    player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                            }
                        }
                    }
                }
                playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            }
        }
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun downloadVideo() {
        val totalDuration = (player?.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000)
        val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
        DownloadDialog.newInstance(
            id = requireArguments().getString(KEY_VIDEO_ID),
            title = requireArguments().getString(KEY_TITLE),
            uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
            duration = requireArguments().getString(KEY_DURATION),
            videoType = requireArguments().getString(KEY_VIDEO_TYPE),
            animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
            channelId = requireArguments().getString(KEY_CHANNEL_ID),
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
            channelName = requireArguments().getString(KEY_CHANNEL_NAME),
            channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
            thumbnail = requireArguments().getString(KEY_THUMBNAIL),
            gameId = requireArguments().getString(KEY_GAME_ID),
            gameSlug = requireArguments().getString(KEY_GAME_SLUG),
            gameName = requireArguments().getString(KEY_GAME_NAME),
            totalDuration = totalDuration,
            currentPosition = getCurrentPosition(),
            qualityKeys = qualities.keys.toTypedArray(),
            qualityNames = qualities.map { it.value.first }.toTypedArray(),
            qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
        ).show(childFragmentManager, null)
    }

    override fun close() {
        savePosition()
        player?.pause()
        player?.stop()
        player?.removeMediaItem(0)
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun onStop() {
        super.onStop()
        player?.let { player ->
            if (playbackService != null) {
                savePosition()
                if (viewModel.usingProxy) {
                    playbackService?.proxyMediaPlaylist = false
                    viewModel.usingProxy = false
                }
                val isInteractive = (requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                val isInPIPMode = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                    else -> false
                }
                if ((!isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                    || (!isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                    || (isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                    || (isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                    if (player.playWhenReady && viewModel.quality != AUDIO_ONLY_QUALITY) {
                        viewModel.restoreQuality = true
                        viewModel.previousQuality = viewModel.quality
                        viewModel.quality = AUDIO_ONLY_QUALITY
                        viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                            player.currentMediaItem?.let { mediaItem ->
                                if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                    }.build()
                                    binding.playerSurface.gone()
                                }
                                if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                    quality.value.second?.let {
                                        val position = player.currentPosition
                                        if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                            viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                        }
                                        player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    viewModel.resume = player.playWhenReady
                    player.pause()
                }
                playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            }
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (videoType == STREAM) {
                restartPlayer()
            } else {
                player?.prepare()
            }
        }
    }

    override fun onNetworkLost() {
        if (videoType != STREAM && isResumed) {
            player?.stop()
        }
    }

    companion object {
        fun newInstance(item: Stream): ExoPlayerFragment {
            return ExoPlayerFragment().apply {
                arguments = getStreamArguments(item)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): ExoPlayerFragment {
            return ExoPlayerFragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition)
            }
        }

        fun newInstance(item: Clip): ExoPlayerFragment {
            return ExoPlayerFragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): ExoPlayerFragment {
            return ExoPlayerFragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }
    }
}