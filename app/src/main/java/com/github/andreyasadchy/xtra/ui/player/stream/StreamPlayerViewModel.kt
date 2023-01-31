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
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.HlsPlayerViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.HttpDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamPlayerViewModel @Inject constructor(
    context: Application,
    private val playerRepository: PlayerRepository,
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

    private var useProxy: Int? = null

    fun startStream(stream: Stream) {
        useProxy = prefs.getString(C.PLAYER_PROXY, "1")?.toIntOrNull() ?: 1
        if (_stream.value == null) {
            _stream.value = stream
            loadStream(stream)
            val account = Account.get(getApplication<Application>())
            if (prefs.getBoolean(C.CHAT_DISABLE, false) || !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) || (prefs.getBoolean(C.CHAT_POINTS_COLLECT, true) && !account.id.isNullOrBlank() && !account.gqlToken.isNullOrBlank())) {
                viewModelScope.launch {
                    while (isActive) {
                        try {
                            val s = repository.loadStream(
                                channelId = stream.channelId,
                                channelLogin = stream.channelLogin,
                                helixClientId = prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                                helixToken = account.helixToken,
                                gqlClientId = prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
                            ).let {
                                _stream.value?.apply {
                                    if (!it?.id.isNullOrBlank()) {
                                        id = it?.id
                                    }
                                    viewerCount = it?.viewerCount
                                }
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
        qualityIndex = index
        qualities?.takeUnless { it.isEmpty() }?.let { qualities ->
            when {
                index <= (qualities.lastIndex - 2) -> setVideoQuality(index)
                index == (qualities.lastIndex - 1) -> startAudioOnly(quality = previousQuality)
                index == qualities.lastIndex -> {
                    if (playerMode.value == PlayerMode.NORMAL) {
                        player?.stop()
                    } else {
                        stopBackgroundAudio()
                        releasePlayer()
                        initializePlayer()
                        play()
                        player?.stop()
                    }
                    _playerMode.value = PlayerMode.DISABLED
                }
            }
        }
    }

    fun startAudioOnly(showNotification: Boolean = false, quality: Int? = null) {
        _stream.value?.let { stream ->
            (helper.urls.values.lastOrNull()?.takeUnless { it.isBlank() } ?: helper.urls.values.elementAtOrNull((quality ?: qualityIndex) - 1) ?: helper.urls.values.firstOrNull())?.let {
                startBackgroundAudio(it, stream.channelName, stream.title, stream.channelLogo, false, AudioPlayerService.TYPE_STREAM, null, showNotification)
                _playerMode.value = PlayerMode.AUDIO_ONLY
            }
        }
    }

    fun resumePlayer() {
        if (playerMode.value == PlayerMode.NORMAL) {
            loadStream(stream.value ?: return)
        }
    }

    override fun onResume() {
        isResumed = true
        pauseHandled = false
        if (playerMode.value == PlayerMode.NORMAL) {
            loadStream(stream.value ?: return)
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            hideAudioNotification()
            if (qualityIndex != qualities?.lastIndex?.minus(1)) {
                changeQuality(qualityIndex)
            }
        }
    }

    override fun onPause() {
        isResumed = false
        if (playerMode.value == PlayerMode.NORMAL) {
            if (!pauseHandled && player?.isPlaying == true && prefs.getBoolean(C.PLAYER_LOCK_SCREEN_AUDIO, true)) {
                startAudioOnly(true)
            } else {
                releasePlayer()
            }
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            showAudioNotification()
        }
    }

    override fun restartPlayer() {
        if (playerMode.value == PlayerMode.NORMAL) {
            loadStream(stream.value ?: return)
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            binder?.restartPlayer()
        }
    }

    private fun loadStream(stream: Stream) {
        initializePlayer()
        viewModelScope.launch {
            try {
                val result = stream.channelLogin?.let { playerRepository.loadStreamPlaylistUrl(
                    gqlClientId = prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"),
                    gqlToken = if (prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, false)) Account.get(getApplication<Application>()).gqlToken else null,
                    channelLogin = it,
                    useProxy = useProxy,
                    proxyUrl = prefs.getString(C.PLAYER_PROXY_URL, "https://api.ttv.lol/playlist/\$channel.m3u8?allow_source=true&allow_audio_only=true&fast_bread=true"),
                    randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                    xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                    playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site")
                ) }
                if (result != null) {
                    val context = getApplication<Application>()
                    when (useProxy) {
                        0 -> {
                            if (result.second != 0) {
                                context.toast(R.string.proxy_error)
                                useProxy = 2
                            }
                        }
                        1 -> {
                            if (result.second != 1) {
                                context.toast(R.string.adblock_not_working)
                                useProxy = 2
                            }
                        }
                        3 -> {
                            httpDataSourceFactory.setDefaultRequestProperties(hashMapOf("X-Forwarded-For" to "::1"))
                        }
                    }
                    mediaSourceFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().apply {
                        if (result.second == 1) {
                            setDefaultRequestProperties(hashMapOf("X-Donate-To" to "https://ttv.lol/donate"))
                        }
                    })).apply {
                        setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                        setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                        if (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false)) {
                            setAllowChunklessPreparation(false)
                        }
                    }
                    mediaItem = MediaItem.Builder().apply {
                        setUri(result.first)
                        setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                            prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                            prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                            prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "5000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                        }.build())
                    }.build()
                    play()
                }
            } catch (e: Exception) {
                val context = getApplication<Application>()
                context.toast(R.string.error_stream)
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val playerError = player?.playerError
        Log.e(tag, "Player error", playerError)
        val context = getApplication<Application>()
        if (context.isNetworkAvailable) {
            try {
                val responseCode = if (playerError?.type == ExoPlaybackException.TYPE_SOURCE) {
                    try {
                        (playerError.sourceException as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    } catch (e: IllegalStateException) {
//                    Crashlytics.log(Log.ERROR, tag, "onPlayerError: Stream end check error. Type: ${error.type}")
//                    Crashlytics.logException(e)
                        return
                    }
                } else null
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
