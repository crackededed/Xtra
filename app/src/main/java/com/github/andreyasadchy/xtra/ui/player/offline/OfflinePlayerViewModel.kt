package com.github.andreyasadchy.xtra.ui.player.offline

import android.app.Application
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.offline.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.google.android.exoplayer2.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OfflinePlayerViewModel @Inject constructor(
        context: Application,
        private val repository: OfflineRepository) : PlayerViewModel(context) {

    private lateinit var video: OfflineVideo
    override var qualities: List<String>? = listOf(context.getString(R.string.source), context.getString(R.string.audio_only))

    fun setVideo(video: OfflineVideo) {
        if (!this::video.isInitialized) {
            this.video = video
            mediaItem = MediaItem.fromUri(video.url.toUri())
            initializePlayer()
            play()
            player?.seekTo(if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) video.lastWatchPosition ?: 0 else 0)
        }
    }

    override fun onResume() {
        isResumed = true
        pauseHandled = false
        if (playerMode.value == PlayerMode.NORMAL) {
            initializePlayer()
            play()
            player?.seekTo(playbackPosition)
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            hideAudioNotification()
            if (qualityIndex != qualities?.lastIndex) {
                changeQuality(qualityIndex)
            }
        }
    }

    override fun onPause() {
        isResumed = false
        if (playerMode.value == PlayerMode.NORMAL) {
            player?.currentPosition?.let { playbackPosition = it }
            if (!pauseHandled && player?.isPlaying == true && prefs.getBoolean(C.PLAYER_LOCK_SCREEN_AUDIO, true)) {
                startAudioOnly(true)
            } else {
                releasePlayer()
            }
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            showAudioNotification()
        }
    }

    override fun changeQuality(index: Int) {
        previousQuality = qualityIndex
        qualityIndex = index
        qualities?.takeUnless { it.isEmpty() }?.let { qualities ->
            when {
                index <= (qualities.lastIndex - 1) -> {
                    player?.currentPosition?.let { playbackPosition = it }
                    stopBackgroundAudio()
                    releasePlayer()
                    initializePlayer()
                    play()
                    player?.seekTo(playbackPosition)
                    _playerMode.value = PlayerMode.NORMAL
                }
                index == qualities.lastIndex -> startAudioOnly()
            }
        }
    }

    fun startAudioOnly(showNotification: Boolean = false) {
        startBackgroundAudio(video.url, video.channelName, video.name, video.channelLogo, true, AudioPlayerService.TYPE_OFFLINE, video.id, showNotification)
        _playerMode.value = PlayerMode.AUDIO_ONLY
    }

    override fun onCleared() {
        if (playerMode.value == PlayerMode.NORMAL) {
            if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                player?.currentPosition?.let { position ->
                    repository.updateVideoPosition(video.id, position)
                }
            }
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY && isResumed) {
            stopBackgroundAudio()
        }
        super.onCleared()
    }
}
