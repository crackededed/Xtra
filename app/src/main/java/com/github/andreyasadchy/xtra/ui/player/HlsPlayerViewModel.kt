package com.github.andreyasadchy.xtra.ui.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.LocalFollowChannel
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.ui.player.PlayerMode.AUDIO_ONLY
import com.github.andreyasadchy.xtra.ui.player.PlayerMode.NORMAL
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.hls.HlsManifest
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedList
import java.util.regex.Pattern


abstract class HlsPlayerViewModel(
    context: Application,
    val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository) : PlayerViewModel(context) {

    val follow = MutableLiveData<Pair<Boolean, String?>>()
    protected val helper = PlayerHelper()
    val loaded: LiveData<Boolean>
        get() = helper.loaded
    var usingPlaylist = true

    protected fun setVideoQuality(index: Int) {
        val mode = _playerMode.value
        if (mode != NORMAL) {
            _playerMode.value = NORMAL
            if (mode == AUDIO_ONLY) {
                stopBackgroundAudio()
                releasePlayer()
                initializePlayer()
                play()
                player?.seekTo(playbackPosition)
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
                val urls = mutableMapOf<String, String>()
                val audioOnly = context.getString(R.string.audio_only)
                val pattern = Pattern.compile("NAME=\"(.+?)\"")
                var trackIndex = 0
                tags.forEach { tag ->
                    if (tag.startsWith("#EXT-X-MEDIA")) {
                        val matcher = pattern.matcher(tag)
                        if (matcher.find()) {
                            val quality = matcher.group(1)!!
                            val url = it.variants[trackIndex++].url.toString()
                            urls[if (!quality.startsWith("audio", true)) quality else audioOnly] = url
                        }
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

    override fun onCleared() {
        if (playerMode.value == AUDIO_ONLY && isResumed) {
            stopBackgroundAudio()
        }
        super.onCleared()
    }

    fun isFollowingChannel(context: Context, channelId: String?, channelLogin: String?) {
        if (!follow.isInitialized) {
            viewModelScope.launch {
                try {
                    val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
                    val account = Account.get(context)
                    val helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
                    val gqlClientId = context.prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
                    val isFollowing = if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                        if ((!helixClientId.isNullOrBlank() && !account.helixToken.isNullOrBlank() && !account.id.isNullOrBlank() && !channelId.isNullOrBlank() && account.id != channelId) ||
                            (!account.login.isNullOrBlank() && !channelLogin.isNullOrBlank() && account.login != channelLogin)) {
                            repository.loadUserFollowing(helixClientId, account.helixToken, channelId, account.id, gqlClientId, account.gqlToken, channelLogin)
                        } else false
                    } else {
                        channelId?.let {
                            localFollowsChannel.getFollowByUserId(it)
                        } != null
                    }
                    follow.postValue(Pair(isFollowing, null))
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowChannel(context: Context, userId: String?, userLogin: String?, userName: String?, channelLogo: String?) {
        GlobalScope.launch {
            val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
            val account = Account.get(context)
            val gqlClientId = context.prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
            val gqlClientId2 = context.prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
            try {
                if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                    val errorMessage = if (!gqlClientId2.isNullOrBlank() && !account.gqlToken2.isNullOrBlank()) {
                        repository.followUser(gqlClientId2, account.gqlToken2, userId)
                    } else {
                        repository.followUser(gqlClientId, account.gqlToken, userId)
                    }
                    follow.postValue(Pair(true, errorMessage))
                } else {
                    if (userId != null) {
                        try {
                            Glide.with(context)
                                .asBitmap()
                                .load(channelLogo)
                                .into(object: CustomTarget<Bitmap>() {
                                    override fun onLoadCleared(placeholder: Drawable?) {

                                    }

                                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                        DownloadUtils.savePng(context, "profile_pics", userId, resource)
                                    }
                                })
                        } catch (e: Exception) {

                        }
                        val downloadedLogo = File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${userId}.png").absolutePath
                        localFollowsChannel.saveFollow(LocalFollowChannel(userId, userLogin, userName, downloadedLogo))
                        follow.postValue(Pair(true, null))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowChannel(context: Context, userId: String?) {
        GlobalScope.launch {
            val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
            val account = Account.get(context)
            val gqlClientId = context.prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
            val gqlClientId2 = context.prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
            try {
                if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                    val errorMessage = if (!gqlClientId2.isNullOrBlank() && !account.gqlToken2.isNullOrBlank()) {
                        repository.unfollowUser(gqlClientId2, account.gqlToken2, userId)
                    } else {
                        repository.unfollowUser(gqlClientId, account.gqlToken, userId)
                    }
                    follow.postValue(Pair(false, errorMessage))
                } else {
                    if (userId != null) {
                        localFollowsChannel.getFollowByUserId(userId)?.let { localFollowsChannel.deleteFollow(context, it) }
                        follow.postValue(Pair(false, null))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }
}
