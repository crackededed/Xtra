package com.github.andreyasadchy.xtra.ui.player.stream

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.player.lowlatency.DefaultHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.HlsPlayerViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerMode.*
import com.github.andreyasadchy.xtra.util.*
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.source.hls.HlsManifest
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.HttpDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

var stream_id: String? = null

@HiltViewModel
class StreamPlayerViewModel @Inject constructor(
    context: Application,
    private val playerRepository: PlayerRepository,
    private val gql: GraphQLRepository,
    repository: ApiRepository,
    localFollowsChannel: LocalFollowChannelRepository) : HlsPlayerViewModel(context, repository, localFollowsChannel) {

    private val _stream = MutableLiveData<Stream?>()
    val stream: MutableLiveData<Stream?>
        get() = _stream
    override val userId: String?
        get() { return _stream.value?.channelId }
    override val userLogin: String?
        get() { return _stream.value?.channelLogin }
    override val userName: String?
        get() { return _stream.value?.channelName }
    override val channelLogo: String?
        get() { return _stream.value?.channelLogo }

    private var gqlClientId: String? = null
    private var gqlToken: String? = null
    private var useProxy: Int? = null
    private var proxyUrl: String? = null
    private var randomDeviceId: Boolean? = true
    private var xDeviceId: String? = null
    private var playerType: String? = null
    private var minSpeed: Float? = null
    private var maxSpeed: Float? = null
    private var targetOffset: Long? = null

    private val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
        .setAllowChunklessPreparation(true)
        .setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
        .setPlaylistTrackerFactory(DefaultHlsPlaylistTracker.FACTORY)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

    fun startStream(account: Account, includeToken: Boolean?, helixClientId: String?, gqlClientId: String?, stream: Stream, useProxy: Int?, proxyUrl: String?, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, minSpeed: String?, maxSpeed: String?, targetOffset: String?, updateStream: Boolean) {
        this.gqlClientId = gqlClientId
        if (includeToken == true) {
            this.gqlToken = account.gqlToken
        }
        this.useProxy = useProxy
        this.proxyUrl = proxyUrl
        this.randomDeviceId = randomDeviceId
        this.xDeviceId = xDeviceId
        this.playerType = playerType
        this.minSpeed = minSpeed?.toFloatOrNull()
        this.maxSpeed = maxSpeed?.toFloatOrNull()
        this.targetOffset = targetOffset?.toLongOrNull()
        if (_stream.value == null) {
            _stream.value = stream
            loadStream(stream)
            if (updateStream) {
                viewModelScope.launch {
                    while (isActive) {
                        try {
                            val s = repository.loadStream(stream.channelId, stream.channelLogin, helixClientId, account.helixToken, gqlClientId).let { get ->
                                _stream.value?.apply {
                                    if (!get?.id.isNullOrBlank()) {
                                        id = get?.id
                                    }
                                    viewerCount = get?.viewerCount
                                }
                            }
                            if (!s?.id.isNullOrBlank()) {
                                stream_id = s?.id
                            }
                            _stream.postValue(s)
                            delay(300000L)
                        } catch (e: Exception) {
                            delay(60000L)
                        }
                    }
                }
            }
        }
    }

    override fun changeQuality(index: Int) {
        previousQuality = qualityIndex
        super.changeQuality(index)
        when {
            index < qualities.size - 2 -> setVideoQuality(index)
            index < qualities.size - 1 -> startAudioOnly()
            else -> {
                if (playerMode.value == NORMAL) {
                    player.stop()
                } else {
                    stopBackgroundAudio()
                }
                _playerMode.value = DISABLED
            }
        }
    }

    fun startAudioOnly(showNotification: Boolean = false) {
        (player.currentManifest as? HlsManifest)?.let {
            _stream.value?.let { stream ->
                helper.urls.values.lastOrNull()?.let {
                    startBackgroundAudio(it, stream.channelName, stream.title, stream.channelLogo, false, AudioPlayerService.TYPE_STREAM, null, showNotification)
                    _playerMode.value = AUDIO_ONLY
                }
            }
        }
    }

    override fun onResume() {
        isResumed = true
        userLeaveHint = false
        if (playerMode.value == NORMAL) {
            loadStream(stream.value ?: return)
        } else if (playerMode.value == AUDIO_ONLY) {
            hideAudioNotification()
            if (qualityIndex < qualities.size - 2) {
                changeQuality(qualityIndex)
            }
        }
    }

    override fun onPause() {
        isResumed = false
        val context = getApplication<Application>()
        if (!userLeaveHint && !isPaused() && playerMode.value == NORMAL && context.prefs().getBoolean(C.PLAYER_LOCK_SCREEN_AUDIO, true)) {
            startAudioOnly(true)
        } else {
            super.onPause()
        }
    }

    override fun restartPlayer() {
        if (playerMode.value == NORMAL) {
            loadStream(stream.value ?: return)
        } else if (playerMode.value == AUDIO_ONLY) {
            binder?.restartPlayer()
        }
    }

    private fun loadStream(stream: Stream) {
        viewModelScope.launch {
            try {
                val result = stream.channelLogin?.let { playerRepository.loadStreamPlaylistUrl(gqlClientId, gqlToken, it, useProxy, proxyUrl, randomDeviceId, xDeviceId, playerType) }
                if (result != null) {
                    when (useProxy) {
                        0 -> {
                            if (result.second != 0) {
                                val context = getApplication<Application>()
                                context.toast(R.string.proxy_error)
                                useProxy = 2
                            }
                        }
                        1 -> {
                            if (result.second == 1) {
                                httpDataSourceFactory.setDefaultRequestProperties(hashMapOf("X-Donate-To" to "https://ttv.lol/donate"))
                            } else {
                                val context = getApplication<Application>()
                                context.toast(R.string.adblock_not_working)
                                useProxy = 2
                            }
                        }
                        3 -> {
                            httpDataSourceFactory.setDefaultRequestProperties(hashMapOf("X-Forwarded-For" to "::1"))
                        }
                    }
                    mediaSource = hlsMediaSourceFactory.createMediaSource(
                        MediaItem.Builder().setUri(result.first).setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                            minSpeed?.let { setMinPlaybackSpeed(it) }
                            maxSpeed?.let { setMaxPlaybackSpeed(it) }
                            targetOffset?.let { setTargetOffsetMs(it) }
                        }.build()).build())
                    play()
                }
            } catch (e: Exception) {
                val context = getApplication<Application>()
                context.toast(R.string.error_stream)
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val playerError = player.playerError
        Log.e(tag, "Player error", playerError)
        playbackPosition = player.currentPosition
        val context = getApplication<Application>()
        if (context.isNetworkAvailable) {
            try {
                val responseCode = try {
                    if (playerError?.type == ExoPlaybackException.TYPE_SOURCE) {
                        (playerError.sourceException as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    } else null
                } catch (e: IllegalStateException) {
//                    Crashlytics.log(Log.ERROR, tag, "onPlayerError: Stream end check error. Type: ${error.type}")
//                    Crashlytics.logException(e)
                    return
                }
                when {
                    responseCode == 404 -> {
                        context.toast(R.string.stream_ended)
                    }
                    useProxy == 0 && responseCode != null && responseCode >= 400 -> {
                        context.toast(R.string.proxy_error)
                        useProxy = 2
                        viewModelScope.launch {
                            delay(1500L)
                            try {
                                restartPlayer()
                            } catch (e: Exception) {
//                            Crashlytics.log(Log.ERROR, tag, "onPlayerError: Retry error. ${e.message}")
//                            Crashlytics.logException(e)
                            }
                        }
                    }
                    useProxy == 1 && responseCode != null && responseCode >= 400 -> {
                        context.toast(R.string.adblock_not_working)
                        useProxy = 2
                        viewModelScope.launch {
                            delay(1500L)
                            try {
                                restartPlayer()
                            } catch (e: Exception) {
//                            Crashlytics.log(Log.ERROR, tag, "onPlayerError: Retry error. ${e.message}")
//                            Crashlytics.logException(e)
                            }
                        }
                    }
                    else -> {
                        context.shortToast(R.string.player_error)
                        viewModelScope.launch {
                            delay(1500L)
                            try {
                                restartPlayer()
                            } catch (e: Exception) {
//                            Crashlytics.log(Log.ERROR, tag, "onPlayerError: Retry error. ${e.message}")
//                            Crashlytics.logException(e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
//                Crashlytics.log(Log.ERROR, tag, "onPlayerError ${e.message}")
//                Crashlytics.logException(e)
            }
        }
    }
}
