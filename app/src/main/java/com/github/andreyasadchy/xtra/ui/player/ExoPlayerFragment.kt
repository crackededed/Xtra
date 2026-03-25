package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.text.format.DateUtils
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ExoPlayerFragment : PlayerFragment() {

    private var serviceConnection: ServiceConnection? = null
    private val player: ExoPlayer?
        get() = (playbackService as? ExoPlayerService)?.player
    private var playerListener: Player.Listener? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.bufferingIndicator.isVisible = playbackState == Player.STATE_BUFFERING
                val showPlayButton = Util.shouldShowPlayButton(player)
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                setPipActions(!showPlayButton)
                updateProgress()
                controllerAutoHide = !showPlayButton
                if (playbackService?.type != BasePlaybackService.STREAM && useController) {
                    showController()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                binding.bufferingIndicator.isVisible = player?.playbackState == Player.STATE_BUFFERING
                val showPlayButton = Util.shouldShowPlayButton(player)
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                setPipActions(!showPlayButton)
                updateProgress()
                controllerAutoHide = !showPlayButton
                if (playbackService?.type != BasePlaybackService.STREAM && useController) {
                    showController()
                }
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                if (Util.shouldShowPlayButton(player)) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                updateProgress()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize != VideoSize.UNKNOWN && player?.let { it.playbackState != Player.STATE_IDLE } == true) {
                    val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                }
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
                    if (chatFragment?.context != null) { // TODO
                        chatFragment?.updatePosition(newPosition.positionMs)
                    }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                chatFragment?.updateSpeed(playbackParameters.speed)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateProgress()
                if (!requireContext().prefs().getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                    requireView().keepScreenOn = isPlaying
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                setSubtitlesButton()
                if (!tracks.isEmpty) {
                    chatFragment?.startReplayChatLoad()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                updateProgress()
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (parameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
                    binding.playerSurface.visibility = View.GONE
                } else {
                    binding.playerSurface.visibility = View.VISIBLE
                }
            }
        }
        val serviceListener = object : BasePlaybackService.Listener {
            override fun started() {
                if (view != null) {
                    if (!started && (isInitialized || !enableNetworkCheck)) {
                        started = true
                        start()
                    }
                }
            }

            override fun loaded() {
                if (view != null) {
                    with(binding.playerControls) {
                        quality.isEnabled = true
                        quality.setColorFilter(Color.WHITE)
                        download.isEnabled = true
                        download.setColorFilter(Color.WHITE)
                        audioOnly.isEnabled = true
                        audioOnly.setColorFilter(Color.WHITE)
                        setQualityText()
                    }
                }
            }

            override fun changePlayerMode() {
                if (view != null) {
                    this@ExoPlayerFragment.changePlayerMode()
                }
            }

            override fun toast(resId: Int, duration: Int) {
                if (view != null) {
                    Toast.makeText(requireContext(), resId, duration).show()
                }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as ExoPlayerService.ServiceBinder
                    playbackService = binder.getService()
                    playbackService?.serviceListener = serviceListener
                    player?.setVideoSurfaceView(binding.playerSurface)
                    player?.addListener(listener)
                    playerListener = listener
                    val endTime = (playbackService as? ExoPlayerService)?.setSleepTimer(-1)
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
                    player?.let { player ->
                        if (!requireContext().prefs().getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                            requireView().keepScreenOn = player.isPlaying
                        }
                        updateProgress()
                        if (Util.shouldShowPlayButton(player)) {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                            binding.playerControls.playPause.visibility = View.VISIBLE
                        } else {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                            if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                                binding.playerControls.playPause.visibility = View.GONE
                            }
                        }
                    }
                    if (playbackService?.started == true) {
                        if (!started) {
                            if (isInitialized || !enableNetworkCheck) {
                                started = true
                                start()
                            }
                        } else {
                            chatFragment?.startReplayChatLoad()
                            if (playbackService?.restoreQuality == true) {
                                playbackService?.restoreQuality = false
                                changeQuality(playbackService?.previousQuality)
                            }
                        }

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
        val intent = Intent(requireContext(), ExoPlayerService::class.java).apply {
            action = ExoPlayerService.INTENT_START
        }
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        serviceConnection = connection
    }

    override fun getCurrentPosition() = player?.currentPosition

    override fun getCurrentSpeed() = player?.playbackParameters?.speed

    override fun getCurrentVolume() = player?.volume

    override fun getTotalDuration() = (player?.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000)

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

    override fun restartPlayer() {
        (playbackService as? ExoPlayerService)?.restartPlayer()
    }

    override fun toggleAudioCompressor() {
        val enabled = (playbackService as? ExoPlayerService)?.toggleDynamicsProcessing()
        if (enabled == true) {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
        } else {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
        }
    }

    override fun setSubtitlesButton() {
        with(binding.playerControls) {
            val textTracks = player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            if (textTracks != null && requireContext().prefs().getBoolean(C.PLAYER_SUBTITLES, false)) {
                subtitles.visibility = View.VISIBLE
                if (textTracks.isSelected) {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on)
                    subtitles.setOnClickListener {
                        toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                    }
                } else {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_off)
                    subtitles.setOnClickListener {
                        toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                    }
                }
            } else {
                subtitles.visibility = View.GONE
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(textTracks)
        }
    }

    override fun toggleSubtitles(enabled: Boolean) {
        (playbackService as? ExoPlayerService)?.toggleSubtitles(enabled)
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

    override fun changeQuality(selectedQuality: VideoQuality?) {
        (playbackService as? ExoPlayerService)?.changeQuality(selectedQuality)
    }

    override fun startAudioOnly() {
        if (playbackService != null) {
            (playbackService as? ExoPlayerService)?.startAudioOnly()
            (playbackService as? ExoPlayerService)?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
        }
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService = null
    }

    override fun close() {
        player?.pause()
        player?.stop()
        viewModel.deletePlaybackStates()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    fun close2() {
        player?.pause()
        player?.stop()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun onStop() {
        super.onStop()
        if (playbackService != null) {
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            (playbackService as? ExoPlayerService)?.stop(isInPIPMode)
            (playbackService as? ExoPlayerService)?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (playbackService?.type == BasePlaybackService.STREAM) {
                restartPlayer()
            } else {
                player?.prepare()
            }
        }
    }

    override fun onNetworkLost() {
        if (playbackService?.type != BasePlaybackService.STREAM && isResumed) {
            player?.stop()
        }
    }
}