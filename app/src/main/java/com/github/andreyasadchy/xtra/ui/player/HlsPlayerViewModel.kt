package com.github.andreyasadchy.xtra.ui.player

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.ui.common.follow.FollowLiveData
import com.github.andreyasadchy.xtra.ui.common.follow.FollowViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerMode.AUDIO_ONLY
import com.github.andreyasadchy.xtra.ui.player.PlayerMode.NORMAL
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.hls.HlsManifest
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import java.util.*
import java.util.regex.Pattern


abstract class HlsPlayerViewModel(
    context: Application,
    val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository) : PlayerViewModel(context), FollowViewModel {

    protected val helper = PlayerHelper()
    val loaded: LiveData<Boolean>
        get() = helper.loaded
    var usingPlaylist = true
    override lateinit var follow: FollowLiveData

    protected fun setVideoQuality(index: Int) {
        val mode = _playerMode.value
        if (mode != NORMAL) {
            _playerMode.value = NORMAL
            if (mode == AUDIO_ONLY) {
                stopBackgroundAudio()
                releasePlayer()
                initializePlayer()
                play()
            } else {
                restartPlayer()
            }
        }
        val quality = if (index == 0 && usingPlaylist) {
            player?.let { player ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)
                    .build()
            }
            "Auto"
        } else {
            updateVideoQuality()
            qualities?.getOrNull(index)
        }
        if (prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") {
            quality?.let { prefs.edit { putString(C.PLAYER_QUALITY, it) } }
        }
    }

    private fun updateVideoQuality() {
        if (usingPlaylist) {
            player?.let { player ->
                if (!player.currentTracks.isEmpty) {
                    player.currentTracks.groups.find { it.type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO }?.let {
                        if (it.length >= qualityIndex - 1) {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, qualityIndex - 1))
                                .build()
                        }
                    }
                }
            }
        } else {
            helper.urls.values.elementAtOrNull(qualityIndex - 1)?.let { url ->
                player?.currentPosition?.let { playbackPosition = it }
                mediaItem = MediaItem.fromUri(url)
                initializePlayer()
                play()
                player?.seekTo(playbackPosition)
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        if (!tracks.isEmpty) {
            if (helper.loaded.value != true) {
                helper.loaded.value = true
            }
            if (qualityIndex != 0 && usingPlaylist) {
                updateVideoQuality()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val manifest = player?.currentManifest
        if (helper.urls.isEmpty() && manifest is HlsManifest) {
            manifest.multivariantPlaylist.let {
                val context = getApplication<Application>()
                val tags = it.tags
                val urls = LinkedHashMap<String, String>(tags.size)
                val audioOnly = context.getString(R.string.audio_only)
                val pattern = Pattern.compile("NAME=\"(.+)\"")
                var trackIndex = 0
                tags.forEach { tag ->
                    val matcher = pattern.matcher(tag)
                    if (matcher.find()) {
                        val quality = matcher.group(1)!!
                        val url = it.variants[trackIndex++].url.toString()
                        urls[if (!quality.startsWith("audio", true)) quality else audioOnly] = url
                    }
                }
                helper.urls = urls.apply {
                    if (containsKey(audioOnly)) {
                        remove(audioOnly)?.let { url ->
                            put(audioOnly, url) //move audio option to bottom
                        }
                    } else {
                        put(audioOnly, "")
                    }
                }
                qualities = LinkedList(urls.keys).apply {
                    addFirst(context.getString(R.string.auto))
                    if (this@HlsPlayerViewModel is StreamPlayerViewModel) {
                        add(context.getString(R.string.chat_only))
                    }
                }
                setQualityIndex()
            }
        }
    }

    override fun setUser(account: Account, helixClientId: String?, gqlClientId: String?, gqlClientId2: String?, setting: Int) {
        if (!this::follow.isInitialized) {
            follow = FollowLiveData(localFollowsChannel = localFollowsChannel, userId = userId, userLogin = userLogin, userName = userName, channelLogo = channelLogo, repository = repository, helixClientId = helixClientId, account = account, gqlClientId = gqlClientId, gqlClientId2 = gqlClientId2, setting = setting, viewModelScope = viewModelScope)
        }
    }

    override fun onCleared() {
        if (playerMode.value == AUDIO_ONLY && isResumed) {
            stopBackgroundAudio()
        }
        super.onCleared()
    }
}
