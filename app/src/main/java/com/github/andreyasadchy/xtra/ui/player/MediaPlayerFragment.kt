package com.github.andreyasadchy.xtra.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.IBinder
import android.text.format.DateUtils
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

@OptIn(UnstableApi::class)
class MediaPlayerFragment : PlayerFragment() {

    override var playbackService: MediaPlayerService? = null
    private var serviceConnection: ServiceConnection? = null
    private var surfaceHolderCallback: SurfaceHolder.Callback? = null
    private var surfaceCreated = false
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        val listener = object : MediaPlayerService.PlayerListener {
            override fun onPrepared(player: MediaPlayer) {
                val duration = player.duration.takeIf { it != -1 }?.toLong() ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                updatePlayingState()
                chatFragment?.startReplayChatLoad()
            }

            override fun onSeekComplete(player: MediaPlayer) {
                updatePlayingState()
                chatFragment?.updatePosition(player.currentPosition.toLong())
            }

            override fun onCompletion(player: MediaPlayer) {
                updatePlayingState(true)
            }

            override fun onInfo(player: MediaPlayer, what: Int, extra: Int) {
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> binding.bufferingIndicator.visibility = View.VISIBLE
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> binding.bufferingIndicator.visibility = View.GONE
                }
            }

            override fun onVideoSizeChanged(player: MediaPlayer, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    val aspectRatio = width.toFloat() / height
                    binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                }
            }

            override fun onError(player: MediaPlayer, what: Int, extra: Int) {
                updatePlayingState()
            }

            override fun onIsPlayingChanged() {
                updatePlayingState()
            }

            override fun onSpeedChanged(speed: Float) {
                chatFragment?.updateSpeed(speed)
            }
        }
        val serviceListener = object : MediaPlayerService.Listener {
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
                    this@MediaPlayerFragment.changePlayerMode()
                }
            }

            override fun toast(resId: Int, duration: Int) {
                if (view != null) {
                    Toast.makeText(requireContext(), resId, duration).show()
                }
            }

            override fun updateVideoInfo() {
                if (view != null) {
                    with(binding.playerControls) {
                        val titleText = playbackService?.title
                        if (!titleText.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                            title.visibility = View.VISIBLE
                            title.text = titleText
                        }
                        val gameName = playbackService?.gameName
                        if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                            category.visibility = View.VISIBLE
                            category.text = gameName
                            category.setOnClickListener {
                                findNavController().navigate(
                                    if (requireContext().prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                            gameId = playbackService?.gameId,
                                            gameSlug = playbackService?.gameSlug,
                                            gameName = gameName
                                        )
                                    } else {
                                        GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                            gameId = playbackService?.gameId,
                                            gameSlug = playbackService?.gameSlug,
                                            gameName = gameName
                                        )
                                    }
                                )
                                minimize()
                            }
                        }
                    }
                }
            }

            override fun changeSurfaceVisibility(visible: Boolean) {
                if (visible) {
                    if (surfaceCreated) {
                        playbackService?.player?.setDisplay(binding.playerSurface.holder)
                    }
                    binding.playerSurface.visibility = View.VISIBLE
                } else {
                    playbackService?.player?.setDisplay(null)
                    binding.playerSurface.visibility = View.GONE
                }
            }
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceCreated = true
                if (started && binding.playerSurface.isVisible) {
                    playbackService?.player?.setDisplay(binding.playerSurface.holder)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceCreated = false
            }
        }
        binding.playerSurface.holder.addCallback(callback)
        surfaceHolderCallback = callback
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as MediaPlayerService.ServiceBinder
                    playbackService = binder.getService()
                    playbackService?.serviceListener = serviceListener
                    if (surfaceCreated && binding.playerSurface.isVisible) {
                        playbackService?.player?.setDisplay(binding.playerSurface.holder)
                    }
                    playbackService?.playerListener = listener
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
                    playbackService?.setStopServiceTimer(false)
                    playbackService?.player?.let { player ->
                        if (!requireContext().prefs().getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                            requireView().keepScreenOn = player.isPlaying
                        }
                        updateProgress()
                        if (!player.isPlaying) {
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
                    playbackService?.player?.let { player ->
                        setPipActions(player.isPlaying)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService = null
            }
        }
        val intent = Intent(requireContext(), MediaPlayerService::class.java).apply {
            action = MediaPlayerService.INTENT_START
        }
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        serviceConnection = connection
    }

    private fun updatePlayingState(ended: Boolean = false) {
        playbackService?.player?.let { player ->
            val isPlaying = player.isPlaying
            if (!isPlaying) {
                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                binding.playerControls.playPause.visibility = View.VISIBLE
            } else {
                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                    binding.playerControls.playPause.visibility = View.GONE
                }
            }
            setPipActions(isPlaying)
            controllerAutoHide = isPlaying
            if (useController) {
                showController(show = playbackService?.type != BasePlaybackService.STREAM && ended)
            }
            updateProgress()
            if (!requireContext().prefs().getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                requireView().keepScreenOn = isPlaying
            }
        }
    }

    override fun getCurrentPosition(): Long? = playbackService?.player?.currentPosition?.toLong()

    override fun getCurrentSpeed(): Float {
        return if (playbackService?.type == BasePlaybackService.STREAM) {
            1f
        } else {
            requireContext().prefs().getFloat(C.PLAYER_SPEED, 1f)
        }
    }

    override fun getCurrentVolume(): Float = requireContext().prefs().getInt(C.PLAYER_VOLUME, 100) / 100f

    override fun getTotalDuration() = playbackService?.player?.duration?.toLong()

    override fun playPause() {
        playbackService?.player?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.start()
            }
            playbackService?.updatePlayingState()
            updatePlayingState()
        }
    }

    override fun rewind() {
        playbackService?.player?.let { player ->
            val rewindMs = requireContext().prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
            val position = player.currentPosition - rewindMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(position.toInt())
            }
        }
    }

    override fun fastForward() {
        playbackService?.player?.let { player ->
            val fastForwardMs = requireContext().prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
            val position = player.currentPosition + fastForwardMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(position.toInt())
            }
        }
    }

    override fun seek(position: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackService?.player?.seekTo(position, MediaPlayer.SEEK_CLOSEST)
        } else {
            playbackService?.player?.seekTo(position.toInt())
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val params = PlaybackParams()
        params.speed = speed
        playbackService?.player?.playbackParams = params
        chatFragment?.updateSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        playbackService?.player?.setVolume(volume, volume)
    }

    override fun updateProgress() {
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = playbackService?.player?.currentPosition?.toLong() ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                progressBar.setPosition(currentPosition)
                root.removeCallbacks(updateProgressAction)
                playbackService?.player?.let { player ->
                    if (player.isPlaying) {
                        val speed = player.playbackParams.speed
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
        playbackService?.restartPlayer()
    }

    override fun toggleAudioCompressor() {
        val enabled = playbackService?.toggleDynamicsProcessing()
        if (enabled == true) {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
        } else {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
        }
    }

    override fun changeQuality(selectedQuality: VideoQuality?) {
        playbackService?.changeQuality(selectedQuality)
    }

    override fun startAudioOnly() {
        if (playbackService != null) {
            playbackService?.startAudioOnly()
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            playbackService?.setStopServiceTimer(true)
        }
        playbackService?.playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService = null
    }

    override fun close(deleteStates: Boolean) {
        playbackService?.player?.pause()
        playbackService?.updatePlayingState()
        updatePlayingState()
        playbackService?.player?.stop()
        if (deleteStates) {
            viewModel.deletePlaybackStates()
        }
        playbackService?.playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun retry(item: String) {
        playbackService?.retry(item)
    }

    override fun onStop() {
        super.onStop()
        if (playbackService != null) {
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            playbackService?.stop(isInPIPMode)
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            playbackService?.setStopServiceTimer(true)
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playbackService?.playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (playbackService?.type == BasePlaybackService.STREAM) {
                restartPlayer()
            } else {
                val position = playbackService?.player?.currentPosition?.toLong()
                playbackService?.seekPosition = position
                playbackService?.player?.prepareAsync()
            }
        }
    }

    override fun onNetworkLost() {
        if (playbackService?.type != BasePlaybackService.STREAM && isResumed) {
            playbackService?.player?.stop()
            playbackService?.updatePlayingState()
            updatePlayingState()
        }
    }
}