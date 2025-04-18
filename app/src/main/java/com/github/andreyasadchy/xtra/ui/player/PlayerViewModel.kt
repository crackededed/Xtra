package com.github.andreyasadchy.xtra.ui.player

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.LocalFollowChannel
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.NotificationUsersRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.ShownNotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val shownNotificationsRepository: ShownNotificationsRepository,
    private val notificationUsersRepository: NotificationUsersRepository,
    private val okHttpClient: OkHttpClient,
    private val playerRepository: PlayerRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val offlineRepository: OfflineRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val streamResult = MutableStateFlow<String?>(null)
    val stream = MutableStateFlow<Stream?>(null)
    private var streamJob: Job? = null
    var useCustomProxy = false
    var playingAds = false
    var usingProxy = false
    var stopProxy = false

    val videoResult = MutableStateFlow<Uri?>(null)
    var playbackPosition: Long? = null
    val savedPosition = MutableStateFlow<Long?>(null)
    val isBookmarked = MutableStateFlow<Boolean?>(null)
    val gamesList = MutableStateFlow<List<Game>?>(null)
    var shouldRetry = true

    val clipUrls = MutableStateFlow<Map<String, String>?>(null)

    val savedOfflineVideoPosition = MutableStateFlow<Long?>(null)

    var qualities: Map<String, Pair<String, String?>> = emptyMap()
    var qualityIndex: Int = 0
    var previousIndex: Int = 0
    var playlistUrl: Uri? = null
    var started = false
    var restoreQuality = false
    var resume = false
    var hidden = false
    val loaded = MutableStateFlow(false)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)

    suspend fun checkPlaylist(url: String): Boolean {
        return try {
            val playlist = playerRepository.getMediaPlaylist(url)
            playlist.segments.lastOrNull()?.let { segment ->
                segment.title?.let { it.contains("Amazon") || it.contains("Adform") || it.contains("DCM") } == true ||
                        segment.programDateTime?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { segmentStartTime ->
                            playlist.dateRanges.find { dateRange ->
                                (dateRange.id.startsWith("stitched-ad-") || dateRange.rangeClass == "twitch-stitched-ad" || dateRange.ad) &&
                                        dateRange.endDate?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { endTime ->
                                            segmentStartTime < endTime
                                        } == true ||
                                        dateRange.startDate.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let { startTime ->
                                            (dateRange.duration ?: dateRange.plannedDuration)?.let { (it * 1000f).toLong() }?.let { duration ->
                                                segmentStartTime < (startTime + duration)
                                            } == true
                                        } == true
                            } != null
                        } == true
            } == true
        } catch (e: Exception) {
            false
        }
    }

    fun loadStreamResult(gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyMultivariantPlaylist: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean) {
        if (streamResult.value == null) {
            viewModelScope.launch {
                try {
                    val url = playerRepository.loadStreamPlaylistUrl(gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity)
                    streamResult.value = if (proxyMultivariantPlaylist) {
                        val response = playerRepository.loadStreamPlaylistResponse(url, true, proxyHost, proxyPort, proxyUser, proxyPassword)
                        Base64.encodeToString(response.toByteArray(), Base64.DEFAULT)
                    } else {
                        url
                    }
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshStream"
                    }
                }
            }
        }
    }

    fun loadStream(channelId: String?, channelLogin: String?, viewerCount: Int?, loop: Boolean, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, checkIntegrity: Boolean) {
        if (loop) {
            streamJob?.cancel()
            streamJob = viewModelScope.launch {
                while (isActive) {
                    try {
                        updateStream(channelId, channelLogin, helixHeaders, gqlHeaders, checkIntegrity)
                        delay(300000L)
                    } catch (e: Exception) {
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "stream"
                        }
                        delay(60000L)
                    }
                }
            }
        } else if (viewerCount == null) {
            viewModelScope.launch {
                try {
                    updateStream(channelId, channelLogin, helixHeaders, gqlHeaders, checkIntegrity)
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "stream"
                    }
                }
            }
        }
    }

    private suspend fun updateStream(channelId: String?, channelLogin: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, checkIntegrity: Boolean) {
        stream.value = repository.loadStream(channelId, channelLogin, helixHeaders, gqlHeaders, checkIntegrity)
    }

    fun loadVideo(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (videoResult.value == null) {
            viewModelScope.launch {
                try {
                    videoResult.value = playerRepository.loadVideoPlaylistUrl(gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshVideo"
                    }
                }
            }
        }
    }

    fun getVideoPosition(id: Long) {
        viewModelScope.launch {
            savedPosition.value = playerRepository.getVideoPosition(id)?.position ?: 0
        }
    }

    fun saveVideoPosition(id: Long, position: Long) {
        if (loaded.value) {
            viewModelScope.launch {
                playerRepository.saveVideoPosition(VideoPosition(id, position))
            }
        }
    }

    fun loadGamesList(gqlHeaders: Map<String, String>, videoId: String?) {
        if (gamesList.value == null) {
            viewModelScope.launch {
                try {
                    gamesList.value = repository.loadVideoGames(gqlHeaders, videoId)
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshVideo"
                    }
                }
            }
        }
    }

    fun checkBookmark(id: String) {
        viewModelScope.launch {
            isBookmarked.value = bookmarksRepository.getBookmarkByVideoId(id) != null
        }
    }

    fun saveBookmark(filesDir: String, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, videoId: String?, title: String?, uploadDate: String?, duration: String?, type: String?, animatedPreviewUrl: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        viewModelScope.launch {
            val item = videoId?.let { bookmarksRepository.getBookmarkByVideoId(it) }
            if (item != null) {
                bookmarksRepository.deleteBookmark(item)
            } else {
                val downloadedThumbnail = videoId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                    if (response.isSuccessful) {
                                        File(path).sink().buffer().use { sink ->
                                            sink.writeAll(response.body.source())
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    channelLogo.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                    if (response.isSuccessful) {
                                        File(path).sink().buffer().use { sink ->
                                            sink.writeAll(response.body.source())
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val userTypes = try {
                    channelId?.let { repository.loadUserTypes(listOf(it), helixHeaders, gqlHeaders) }?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
                bookmarksRepository.saveBookmark(
                    Bookmark(
                        videoId = videoId,
                        userId = channelId,
                        userLogin = channelLogin,
                        userName = channelName,
                        userType = userTypes?.type,
                        userBroadcasterType = userTypes?.broadcasterType,
                        userLogo = downloadedLogo,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = title,
                        createdAt = uploadDate,
                        thumbnail = downloadedThumbnail,
                        type = type,
                        duration = duration,
                        animatedPreviewURL = animatedPreviewUrl
                    )
                )
            }
        }
    }

    fun loadClip(gqlHeaders: Map<String, String>, id: String?) {
        if (clipUrls.value == null) {
            viewModelScope.launch {
                try {
                    clipUrls.value = playerRepository.loadClipUrls(gqlHeaders, id) ?: emptyMap()
                } catch (e: Exception) {
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refreshClip"
                    } else {
                        clipUrls.value = emptyMap()
                    }
                }
            }
        }
    }

    fun getOfflineVideoPosition(id: Int) {
        viewModelScope.launch {
            savedOfflineVideoPosition.value = offlineRepository.getVideoById(id)?.lastWatchPosition ?: 0
        }
    }

    fun saveOfflineVideoPosition(id: Int, position: Long) {
        if (loaded.value) {
            viewModelScope.launch {
                offlineRepository.updateVideoPosition(id, position)
            }
        }
    }

    fun isFollowingChannel(helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, setting: Int, userId: String?, channelId: String?, channelLogin: String?) {
        if (_isFollowing.value == null) {
            viewModelScope.launch {
                try {
                    if (!channelId.isNullOrBlank()) {
                        if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                            val response = repository.loadUserFollowing(helixHeaders, channelId, userId, gqlHeaders, channelLogin)
                            _isFollowing.value = response.first
                        } else {
                            _isFollowing.value = localFollowsChannel.getFollowByUserId(channelId) != null
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowChannel(gqlHeaders: Map<String, String>, setting: Int, userId: String?, channelId: String?, channelLogin: String?, channelName: String?, notificationsEnabled: Boolean, startedAt: String?) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = repository.followUser(gqlHeaders, channelId)
                        if (!errorMessage.isNullOrBlank()) {
                            if (errorMessage == "failed integrity check" && integrity.value == null) {
                                integrity.value = "follow"
                            } else {
                                follow.value = Pair(true, errorMessage)
                            }
                        } else {
                            _isFollowing.value = true
                            follow.value = Pair(true, errorMessage)
                            if (notificationsEnabled) {
                                startedAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                                    shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                                }
                            }
                        }
                    } else {
                        localFollowsChannel.saveFollow(LocalFollowChannel(channelId, channelLogin, channelName))
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                        notificationUsersRepository.saveUser(NotificationUser(channelId))
                        if (notificationsEnabled) {
                            startedAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                                shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowChannel(gqlHeaders: Map<String, String>, setting: Int, userId: String?, channelId: String?) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = repository.unfollowUser(gqlHeaders, channelId)
                        if (!errorMessage.isNullOrBlank()) {
                            if (errorMessage == "failed integrity check" && integrity.value == null) {
                                integrity.value = "unfollow"
                            } else {
                                follow.value = Pair(false, errorMessage)
                            }
                        } else {
                            _isFollowing.value = false
                            follow.value = Pair(false, errorMessage)
                        }
                    } else {
                        localFollowsChannel.getFollowByUserId(channelId)?.let { localFollowsChannel.deleteFollow(it) }
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                        notificationUsersRepository.deleteUser(NotificationUser(channelId))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }
}