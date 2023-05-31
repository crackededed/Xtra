package com.github.andreyasadchy.xtra.ui.player.stream

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    repository: ApiRepository,
    localFollowsChannel: LocalFollowChannelRepository) : PlayerViewModel(repository, localFollowsChannel) {

    val _stream = MutableLiveData<Stream?>()
    val stream: MutableLiveData<Stream?>
        get() = _stream

    var result = MutableLiveData<Pair<Uri, Int>>()

    var useProxy: Int? = null
    var playingAds = false

    fun load(gqlClientId: String?, gqlToken: String?, channelLogin: String, proxyUrl: String?, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?) {
        viewModelScope.launch {
            try {
                playerRepository.loadStreamPlaylistUrl(gqlClientId, gqlToken, channelLogin, useProxy, proxyUrl, randomDeviceId, xDeviceId, playerType)
            } catch (e: Exception) {
                null
            }.let { result.postValue(it) }
        }
    }

    fun loadStream(context: Context, stream: Stream) {
        val account = Account.get(context)
        if (context.prefs().getBoolean(C.CHAT_DISABLE, false) ||
                !context.prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                context.prefs().getBoolean(C.PLAYER_TITLE, true) ||
                context.prefs().getBoolean(C.PLAYER_CATEGORY, true) ||
                (context.prefs().getBoolean(C.CHAT_POINTS_COLLECT, true) && !account.id.isNullOrBlank() && !account.gqlToken.isNullOrBlank())) {
            viewModelScope.launch {
                while (isActive) {
                    try {
                        val s = repository.loadStream(
                            channelId = stream.channelId,
                            channelLogin = stream.channelLogin,
                            helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                            helixToken = account.helixToken,
                            gqlClientId = context.prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
                        ).let {
                            _stream.value?.apply {
                                if (!it?.id.isNullOrBlank()) {
                                    id = it?.id
                                }
                                viewerCount = it?.viewerCount
                                title = it?.title
                                gameName = it?.gameName
                                gameId = it?.gameId
                            }
                        }
                        _stream.postValue(s)
                        delay(60000L)
                    } catch (e: Exception) {
                        delay(60000L)
                    }
                }
            }
        }
    }
}
