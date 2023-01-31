package com.github.andreyasadchy.xtra.ui.player.clip

import android.app.Application
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.ui.common.follow.FollowLiveData
import com.github.andreyasadchy.xtra.ui.common.follow.FollowViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerHelper
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.shortToast
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ClipPlayerViewModel @Inject constructor(
    context: Application,
    private val graphQLRepository: GraphQLRepository,
    private val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository) : PlayerViewModel(context), FollowViewModel {

    private lateinit var clip: Clip
    private val helper = PlayerHelper()
    val qualityMap: Map<String, String>
        get() = helper.urls
    val loaded: LiveData<Boolean>
        get() = helper.loaded

    override val userId: String?
        get() { return clip.channelId }
    override val userLogin: String?
        get() { return clip.channelLogin }
    override val userName: String?
        get() { return clip.channelName }
    override val channelLogo: String?
        get() { return clip.channelLogo }
    override lateinit var follow: FollowLiveData

    override fun changeQuality(index: Int) {
        qualityIndex = index
        player?.currentPosition?.let { playbackPosition = it }
        helper.urls.values.elementAtOrNull(index)?.let { play(it) }
        if (prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") {
            qualities?.getOrNull(index)?.let { prefs.edit { putString(C.PLAYER_QUALITY, it) } }
        }
    }

    override fun onResume() {
        initializePlayer()
        play()
        player?.seekTo(playbackPosition)
    }

    override fun onPause() {
        player?.currentPosition?.let { playbackPosition = it }
        releasePlayer()
    }

    fun setClip(clip: Clip) {
        if (!this::clip.isInitialized) {
            this.clip = clip
            viewModelScope.launch {
                try {
                    val skipAccessToken = prefs.getString(C.TOKEN_SKIP_CLIP_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                    val urls = if (skipAccessToken <= 1 && !clip.thumbnailUrl.isNullOrBlank()) {
                        TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl)
                    } else {
                        graphQLRepository.loadClipUrls(
                            clientId = prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"),
                            slug = clip.id
                        ) ?: if (skipAccessToken == 2 && !clip.thumbnailUrl.isNullOrBlank()) {
                            TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl)
                        } else mapOf()
                    }
                    helper.urls = urls
                    qualities = LinkedList(urls.keys)
                    setQualityIndex()
                    (urls.values.elementAtOrNull(qualityIndex) ?: urls.values.firstOrNull())?.let {
                        play(it)
                        helper.loaded.value = true
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    override fun setUser(account: Account, helixClientId: String?, gqlClientId: String?, gqlClientId2: String?, setting: Int) {
        if (!this::follow.isInitialized) {
            follow = FollowLiveData(localFollowsChannel = localFollowsChannel, userId = userId, userLogin = userLogin, userName = userName, channelLogo = channelLogo, repository = repository, helixClientId = helixClientId, account = account, gqlClientId = gqlClientId, gqlClientId2 = gqlClientId2, setting = setting, viewModelScope = viewModelScope)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val playerError = player?.playerError
        if (playerError?.type == ExoPlaybackException.TYPE_UNEXPECTED && playerError.unexpectedException is IllegalStateException) {
            val context = getApplication<Application>()
            context.shortToast(R.string.player_error)
            if (qualityIndex < helper.urls.size - 1) {
                changeQuality(++qualityIndex)
            }
        }
    }

    private fun play(url: String) {
        mediaItem = MediaItem.fromUri(url.toUri())
        initializePlayer()
        play()
        player?.seekTo(playbackPosition)
    }
}
