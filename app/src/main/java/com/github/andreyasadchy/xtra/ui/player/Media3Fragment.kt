package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.format.DateUtils
import android.util.Log
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class Media3Fragment : PlayerFragment() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val player: MediaController?
        get() = controllerFuture?.let { if (it.isDone && !it.isCancelled) it.get() else null }
    private var playerListener: Player.Listener? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        controllerFuture = MediaController.Builder(
            requireContext(),
            SessionToken(
                requireContext(),
                ComponentName(requireContext(), PlaybackService::class.java)
            )
        ).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            controller?.setVideoSurfaceView(binding.playerSurface)
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
                        player?.sendCustomCommand(
                            SessionCommand(PlaybackService.GET_QUALITIES, Bundle.EMPTY),
                            Bundle.EMPTY
                        )?.let { result ->
                            result.addListener({
                                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                    val names = result.get().extras.getStringArray(PlaybackService.NAMES)
                                    val codecs = result.get().extras.getStringArray(PlaybackService.CODECS)?.map { codec ->
                                        codec?.substringBefore('.').let {
                                            when (it) {
                                                "av01" -> "AV1"
                                                "hev1" -> "H.265"
                                                "avc1" -> "H.264"
                                                else -> it
                                            }
                                        }
                                    }?.takeUnless { it.all { it == "H.264" || it == "mp4a" } }
                                    val urls = result.get().extras.getStringArray(PlaybackService.URLS)
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
                            }, MoreExecutors.directExecutor())
                        }
                    }
                    if (videoType == STREAM) {
                        val hideAds = prefs.getBoolean(C.PLAYER_HIDE_ADS, false)
                        val useProxy = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true)
                                && !prefs.getString(C.PROXY_HOST, null).isNullOrBlank()
                                && prefs.getString(C.PROXY_PORT, null)?.toIntOrNull() != null
                        if (hideAds || useProxy) {
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.CHECK_ADS, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val playingAds = result.get().extras.getBoolean(PlaybackService.RESULT)
                                        val oldValue = viewModel.playingAds
                                        viewModel.playingAds = playingAds
                                        if (playingAds) {
                                            if (viewModel.usingProxy) {
                                                if (!viewModel.stopProxy) {
                                                    player?.sendCustomCommand(
                                                        SessionCommand(
                                                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                                                PlaybackService.USING_PROXY to false
                                                            )
                                                        ), Bundle.EMPTY
                                                    )
                                                    viewModel.usingProxy = false
                                                    viewModel.stopProxy = true
                                                }
                                            } else {
                                                if (!oldValue) {
                                                    val playlist = viewModel.qualities[viewModel.quality]?.second
                                                    if (!viewModel.stopProxy && !playlist.isNullOrBlank() && useProxy) {
                                                        player?.sendCustomCommand(
                                                            SessionCommand(
                                                                PlaybackService.TOGGLE_PROXY, bundleOf(
                                                                    PlaybackService.USING_PROXY to false
                                                                )
                                                            ), Bundle.EMPTY
                                                        )
                                                        viewModel.usingProxy = true
                                                        viewLifecycleOwner.lifecycleScope.launch {
                                                            for (i in 0 until 10) {
                                                                delay(10000)
                                                                if (!viewModel.checkPlaylist(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), playlist)) {
                                                                    break
                                                                }
                                                            }
                                                            player?.sendCustomCommand(
                                                                SessionCommand(
                                                                    PlaybackService.TOGGLE_PROXY, bundleOf(
                                                                        PlaybackService.USING_PROXY to false
                                                                    )
                                                                ), Bundle.EMPTY
                                                            )
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
                                }, MoreExecutors.directExecutor())
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(tag, "Player error", error)
                    when (videoType) {
                        STREAM -> {
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
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
                                }, MoreExecutors.directExecutor())
                            }
                        }
                        VIDEO -> {
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
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
                                }, MoreExecutors.directExecutor())
                            }
                        }
                    }
                }
            }
            controller?.addListener(listener)
            playerListener = listener
            if (viewModel.restoreQuality) {
                viewModel.restoreQuality = false
                changeQuality(viewModel.previousQuality)
            }
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.SET_SLEEP_TIMER, bundleOf(
                        PlaybackService.DURATION to -1L
                    )
                ), Bundle.EMPTY
            )?.let { result ->
                result.addListener({
                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                        val endTime = result.get().extras.getLong(PlaybackService.RESULT)
                        if (endTime > 0L) {
                            val duration = endTime - System.currentTimeMillis()
                            if (duration > 0L) {
                                (activity as? MainActivity)?.setSleepTimer(duration)
                            } else {
                                minimize()
                                close()
                                (activity as? MainActivity)?.closePlayer()
                            }
                        }
                    }
                }, MoreExecutors.directExecutor())
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
        }, MoreExecutors.directExecutor())
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    override fun startStream(url: String?) {
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_STREAM, bundleOf(
                    PlaybackService.URI to url,
                    PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                    PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                    PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                )
            ), Bundle.EMPTY
        )
    }

    override fun startVideo(url: String?, playbackPosition: Long?) {
        player?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
            }.build()
            binding.playerSurface.visible()
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_VIDEO, bundleOf(
                        PlaybackService.URI to url,
                        PlaybackService.PLAYBACK_POSITION to playbackPosition,
                        PlaybackService.VIDEO_ID to requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull(),
                        PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                        PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                        PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                    )
                ), Bundle.EMPTY
            )
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
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_CLIP, bundleOf(
                        PlaybackService.URI to url,
                        PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                        PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                        PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                    )
                ), Bundle.EMPTY
            )
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
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_OFFLINE_VIDEO, bundleOf(
                        PlaybackService.URI to url,
                        PlaybackService.VIDEO_ID to requireArguments().getInt(KEY_OFFLINE_VIDEO_ID),
                        PlaybackService.PLAYBACK_POSITION to position,
                        PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                        PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                        PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                    )
                ), Bundle.EMPTY
            )
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
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.TOGGLE_DYNAMICS_PROCESSING,
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val state = result.get().extras.getBoolean(PlaybackService.RESULT)
                    if (state) {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                }
            }, MoreExecutors.directExecutor())
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
        player?.sendCustomCommand(
            SessionCommand(
                if (mediaPlaylist) {
                    PlaybackService.GET_MEDIA_PLAYLIST
                } else {
                    PlaybackService.GET_MULTIVARIANT_PLAYLIST
                },
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val tags = result.get().extras.getStringArray(PlaybackService.RESULT)?.joinToString("\n")
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
            }, MoreExecutors.directExecutor())
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
                                player.sendCustomCommand(
                                    SessionCommand(
                                        PlaybackService.TOGGLE_PROXY, bundleOf(
                                            PlaybackService.USING_PROXY to false
                                        )
                                    ), Bundle.EMPTY
                                )
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
                                player.sendCustomCommand(
                                    SessionCommand(
                                        PlaybackService.TOGGLE_PROXY, bundleOf(
                                            PlaybackService.USING_PROXY to false
                                        )
                                    ), Bundle.EMPTY
                                )
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
            if (player.isConnected) {
                savePosition()
                if (viewModel.usingProxy) {
                    player.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                PlaybackService.USING_PROXY to false
                            )
                        ), Bundle.EMPTY
                    )
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
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, bundleOf(
                            PlaybackService.DURATION to ((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        )
                    ), Bundle.EMPTY
                )
            }
        }
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun downloadVideo() {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.GET_DURATION, Bundle.EMPTY),
            Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val totalDuration = result.get().extras.getLong(PlaybackService.RESULT)
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
            }, MoreExecutors.directExecutor())
        }
    }

    override fun close() {
        savePosition()
        player?.pause()
        player?.stop()
        player?.removeMediaItem(0)
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun onStop() {
        super.onStop()
        player?.let { player ->
            if (player.isConnected) {
                savePosition()
                if (viewModel.usingProxy) {
                    player.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                PlaybackService.USING_PROXY to false
                            )
                        ), Bundle.EMPTY
                    )
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
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, bundleOf(
                            PlaybackService.DURATION to ((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        )
                    ), Bundle.EMPTY
                )
            }
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
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
        fun newInstance(item: Stream): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getStreamArguments(item)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition)
            }
        }

        fun newInstance(item: Clip): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }
    }
}