package com.github.andreyasadchy.xtra.ui.player.video

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.offline.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.player.lowlatency.DefaultHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.HlsPlayerViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.toast
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.source.hls.HlsManifest
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    context: Application,
    private val playerRepository: PlayerRepository,
    repository: ApiRepository,
    localFollowsChannel: LocalFollowChannelRepository,
    private val bookmarksRepository: BookmarksRepository) : HlsPlayerViewModel(context, repository, localFollowsChannel) {

    private lateinit var video: Video
    val videoInfo: VideoDownloadInfo?
        get() {
            val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist ?: return null
            val segments = playlist.segments
            val size = segments.size
            val relativeTimes = ArrayList<Long>(size)
            val durations = ArrayList<Long>(size)
            for (i in 0 until size) {
                val segment = segments[i]
                relativeTimes.add(segment.relativeStartTimeUs / 1000L)
                durations.add(segment.durationUs / 1000L)
            }
            return VideoDownloadInfo(video, helper.urls, relativeTimes, durations, playlist.durationUs / 1000L, playlist.targetDurationUs / 1000L, player?.currentPosition ?: 0)
        }

    override val userId: String?
        get() { return video.channelId }
    override val userLogin: String?
        get() { return video.channelLogin }
    override val userName: String?
        get() { return video.channelName }
    override val channelLogo: String?
        get() { return video.channelLogo }

    val bookmarkItem = MutableLiveData<Bookmark>()
    val gamesList = MutableLiveData<List<Game>>()
    private var isLoading = false
    private var shouldRetry = true

    fun loadGamesList(clientId: String?, videoId: String?) {
        if (gamesList.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    val get = repository.loadVideoGames(clientId, videoId)
                    if (get.isNotEmpty()) {
                        gamesList.postValue(get)
                    }
                } catch (e: Exception) {
                    _errors.postValue(e)
                } finally {
                    isLoading = false
                }
            }
        }
    }

    fun setVideo(video: Video, offset: Double) {
        if (!this::video.isInitialized) {
            this.video = video
            playbackPosition = offset.toLong()
            playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1)
        }
    }

    private fun playVideo(skipAccessToken: Boolean) {
        initializePlayer()
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val url = if (skipAccessToken && !video.animatedPreviewURL.isNullOrBlank()) {
                    usingPlaylist = false
                    val urls = TwitchApiHelper.getVideoUrlMapFromPreview(video.animatedPreviewURL!!, video.type)
                    helper.urls = urls.apply {
                        if (containsKey("audio_only")) {
                            remove("audio_only")?.let { url ->
                                put(context.getString(R.string.audio_only), url) //move audio option to bottom
                            }
                        } else {
                            put(context.getString(R.string.audio_only), "")
                        }
                    }
                    qualities = LinkedList(urls.keys).apply {
                        addFirst(context.getString(R.string.auto))
                    }
                    qualityIndex = 1
                    helper.urls.values.first().toUri()
                } else {
                    usingPlaylist = true
                    playerRepository.loadVideoPlaylistUrl(
                        gqlClientId = prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"),
                        gqlToken = if (prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)) Account.get(context).gqlToken else null,
                        videoId = video.id,
                        playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live")
                    )
                }
                mediaSourceFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())).apply {
                    setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
                    if (usingPlaylist && (prefs.getBoolean(C.PLAYER_SUBTITLES, false) || prefs.getBoolean(C.PLAYER_MENU_SUBTITLES, false))) {
                        setAllowChunklessPreparation(false)
                    }
                }
                mediaItem = MediaItem.fromUri(url)
                play()
                player?.seekTo(playbackPosition)
            } catch (e: Exception) {

            }
        }
    }

    override fun changeQuality(index: Int) {
        previousQuality = qualityIndex
        qualityIndex = index
        qualities?.takeUnless { it.isEmpty() }?.let { qualities ->
            when {
                index <= (qualities.lastIndex - 1) -> {
                    val audioOnly = playerMode.value == PlayerMode.AUDIO_ONLY
                    if (audioOnly) {
                        player?.currentPosition?.let { playbackPosition = it }
                    }
                    setVideoQuality(index)
                    if (audioOnly) {
                        player?.seekTo(playbackPosition)
                    }
                }
                index == qualities.lastIndex -> startAudioOnly(quality = previousQuality)
            }
        }
    }

    fun startAudioOnly(showNotification: Boolean = false, quality: Int? = null) {
        (helper.urls.values.lastOrNull()?.takeUnless { it.isBlank() } ?: helper.urls.values.elementAtOrNull((quality ?: qualityIndex) - 1) ?: helper.urls.values.firstOrNull())?.let {
            startBackgroundAudio(it, video.channelName, video.title, video.channelLogo, true, AudioPlayerService.TYPE_VIDEO, video.id?.toLongOrNull(), showNotification)
            _playerMode.value = PlayerMode.AUDIO_ONLY
        }
    }

    fun resumePlayer() {
        initializePlayer()
        play()
        player?.seekTo(playbackPosition)
    }

    fun stopPlayer() {
        player?.currentPosition?.let { playbackPosition = it }
        helper.loaded.value = false
        player?.stop()
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
                helper.loaded.value = false
                releasePlayer()
            }
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            showAudioNotification()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val playerError = player?.playerError
        if (playerError?.type == ExoPlaybackException.TYPE_SOURCE && playerError.sourceException.let { it is HttpDataSource.InvalidResponseCodeException }) {
            val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
            when {
                skipAccessToken == 1 && !usingPlaylist && shouldRetry -> {
                    shouldRetry = false
                    player?.currentPosition?.let { playbackPosition = it }
                    playVideo(false)
                }
                skipAccessToken == 2 && shouldRetry -> {
                    shouldRetry = false
                    player?.currentPosition?.let { playbackPosition = it }
                    playVideo(true)
                }
                else -> {
                    if (playerError.sourceException.let { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 403 }) {
                        val context = getApplication<Application>()
                        context.toast(R.string.video_subscribers_only)
                    }
                }
            }
        } else {
            super.onPlayerError(error)
        }
    }

    override fun onCleared() {
        if (playerMode.value == PlayerMode.NORMAL && this::video.isInitialized) { //TODO
            if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                video.id?.toLongOrNull()?.let { id ->
                    player?.currentPosition?.let { position ->
                        playerRepository.saveVideoPosition(VideoPosition(id, position))
                    }
                }
            }
        }
        super.onCleared()
    }

    fun checkBookmark() {
        video.id?.let {
            viewModelScope.launch {
                bookmarkItem.postValue(bookmarksRepository.getBookmarkByVideoId(it))
            }
        }
    }

    fun saveBookmark() {
        GlobalScope.launch {
            val context = getApplication<Application>()
            if (bookmarkItem.value != null) {
                bookmarksRepository.deleteBookmark(context, bookmarkItem.value!!)
            } else {
                if (!video.id.isNullOrBlank()) {
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(video.thumbnail)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "thumbnails", video.id!!, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                }
                if (!video.channelId.isNullOrBlank()) {
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(video.channelLogo)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "profile_pics", video.channelId!!, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                }
                val userTypes = video.channelId?.let { repository.loadUserTypes(listOf(it), prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(context).helixToken, prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")) }?.first()
                val downloadedThumbnail = video.id?.let { File(context.filesDir.toString() + File.separator + "thumbnails" + File.separator + "${it}.png").absolutePath }
                val downloadedLogo = video.channelId?.let { File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${it}.png").absolutePath }
                bookmarksRepository.saveBookmark(Bookmark(
                    videoId = video.id,
                    userId = video.channelId,
                    userLogin = video.channelLogin,
                    userName = video.channelName,
                    userType = userTypes?.type,
                    userBroadcasterType = userTypes?.broadcasterType,
                    userLogo = downloadedLogo,
                    gameId = video.gameId,
                    gameName = video.gameName,
                    title = video.title,
                    createdAt = video.uploadDate,
                    thumbnail = downloadedThumbnail,
                    type = video.type,
                    duration = video.duration,
                    animatedPreviewURL = video.animatedPreviewURL
                ))
            }
        }
    }
}