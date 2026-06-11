package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.HttpEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.body
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class Media3PlayerViewModel(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val notificationsRepository: NotificationsRepository,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    val streamResult = MutableStateFlow<String?>(null)
    val stream = MutableStateFlow<Stream?>(null)
    private var streamJob: Job? = null
    var useCustomProxy = false
    var playingAds = false
    var usingProxy = false
    var stopProxy = false

    val videoResult = MutableStateFlow<String?>(null)
    var backupQualities: List<String>? = null
    var playbackPosition: Long? = null
    val savedPosition = MutableStateFlow<Long?>(null)
    val isBookmarked = MutableStateFlow<Boolean?>(null)
    val gamesList = MutableStateFlow<List<Game>?>(null)
    var shouldRetry = true

    val clipUrls = MutableStateFlow<List<VideoQuality>?>(null)

    val savedOfflineVideoPosition = MutableStateFlow<Long?>(null)

    var qualities: List<VideoQuality>? = null
    var quality: VideoQuality? = null
    var previousQuality: VideoQuality? = null
    var playlistUrl: Uri? = null
    var updateQualities = false
    var started = false
    var restoreQuality = false
    var resume = false
    var hidden = false
    val loaded = MutableStateFlow(false)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)

    suspend fun checkPlaylist(networkLibrary: String?, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val request = httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation)
                        ).build()
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(continuation),
                            cronetExecutor.value
                        ).build()
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
            }
            playlist.segments.lastOrNull()?.let { segment ->
                segment.title?.let { it.contains("Amazon") || it.contains("Adform") || it.contains("DCM") } == true ||
                        segment.programDateTime?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }?.let { segmentStartTime ->
                            playlist.dateRanges.find { dateRange ->
                                (dateRange.id.startsWith("stitched-ad-") || dateRange.rangeClass == "twitch-stitched-ad" || dateRange.ad) &&
                                        dateRange.endDate?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }?.let { endTime ->
                                            segmentStartTime < endTime
                                        } == true ||
                                        dateRange.startDate.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }?.let { startTime ->
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

    fun loadStreamResult(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean) {
        if (streamResult.value == null) {
            viewModelScope.launch {
                try {
                    streamResult.value = playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity)
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshStream")
                    }
                }
            }
        }
    }

    fun loadStreamInfo(channelId: String?, channelLogin: String?, viewerCount: Int?, loop: Boolean, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (loop) {
            streamJob?.cancel()
            streamJob = viewModelScope.launch {
                while (isActive) {
                    try {
                        updateStreamInfo(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders, enableIntegrity)
                        delay(5.minutes)
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("stream")
                        }
                        delay(1.minutes)
                    }
                }
            }
        } else {
            if (viewerCount == null) {
                viewModelScope.launch {
                    try {
                        updateStreamInfo(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders, enableIntegrity)
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("stream")
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateStreamInfo(channelId: String?, channelLogin: String?, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        stream.value = try {
            val response = graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null,
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            response.data!!.users?.firstOrNull()?.let {
                Stream(
                    id = it.stream?.id,
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    channelImageURL = it.profileImageURL,
                    gameId = it.stream?.game?.id,
                    gameSlug = it.stream?.game?.slug,
                    gameName = it.stream?.game?.displayName,
                    title = it.stream?.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = it.stream?.previewImageURL,
                    createdAt = it.stream?.createdAt?.toString(),
                    viewerCount = it.stream?.viewersCount,
                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                )
            }
        } catch (e: Exception) {
            if (e.message == C.FAILED_INTEGRITY_CHECK) throw e
            if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
            try {
                helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                ).data.firstOrNull()?.let {
                    Stream(
                        id = it.id,
                        channelId = it.channelId,
                        channelLogin = it.channelLogin,
                        channelName = it.channelName,
                        gameId = it.gameId,
                        gameName = it.gameName,
                        title = it.title,
                        thumbnailURL = it.thumbnailURL,
                        createdAt = it.startedAt,
                        viewerCount = it.viewerCount,
                        tags = it.tags,
                    )
                }
            } catch (e: Exception) {
                val response = graphQLRepository.loadViewerCount(networkLibrary, gqlHeaders, channelLogin)
                if (enableIntegrity) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
                }
                response.data!!.user.stream?.let {
                    Stream(
                        id = it.id,
                        viewerCount = it.viewersCount,
                    )
                }
            }
        }
    }

    fun loadVideo(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (videoResult.value == null) {
            viewModelScope.launch {
                try {
                    val result = playerRepository.loadVideoPlaylistUrl(networkLibrary, gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
                    videoResult.value = result.first
                    backupQualities = result.second
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshVideo")
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

    suspend fun savePosition(id: Long, position: Long) {
        playerRepository.saveVideoPosition(VideoPosition(id, position))
    }

    fun loadGamesList(videoId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (gamesList.value == null) {
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryVideoMoments(networkLibrary, gqlHeaders, videoId)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refreshVideo")
                            return@launch
                        }
                    }
                    gamesList.value = response.data!!.video!!.moments!!.edges!!.map { item ->
                        item.node!!.let {
                            Game(
                                id = it.details?.onGameChangeMomentDetails?.game?.id,
                                name = it.details?.onGameChangeMomentDetails?.game?.displayName,
                                boxArtURL = it.details?.onGameChangeMomentDetails?.game?.boxArtURL,
                                vodPosition = it.positionMilliseconds,
                                vodDuration = it.durationMilliseconds,
                            )
                        }
                    }
                } catch (e: Exception) {
                    try {
                        val response = graphQLRepository.loadVideoGames(networkLibrary, gqlHeaders, videoId)
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refreshVideo")
                                return@launch
                            }
                        }
                        gamesList.value = response.data!!.video.moments.edges.map { item ->
                            item.node.let {
                                Game(
                                    id = it.details?.game?.id,
                                    name = it.details?.game?.displayName,
                                    boxArtURL = it.details?.game?.boxArtURL,
                                    vodPosition = it.positionMilliseconds,
                                    vodDuration = it.durationMilliseconds,
                                )
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    fun checkBookmark(id: String) {
        viewModelScope.launch {
            isBookmarked.value = bookmarksRepository.getByVideoId(id) != null
        }
    }

    fun saveBookmark(filesDir: String, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, videoId: String?, title: String?, uploadDate: String?, durationSeconds: Int?, type: String?, animatedPreviewUrl: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        viewModelScope.launch {
            val item = videoId?.let { bookmarksRepository.getByVideoId(it) }
            if (item != null) {
                bookmarksRepository.delete(item)
            } else {
                val downloadedThumbnail = videoId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation)
                                            ).build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation),
                                                cronetExecutor.value
                                            ).build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
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
                    channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation)
                                            ).build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation),
                                                cronetExecutor.value
                                            ).build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val userTypes = channelId?.let {
                    try {
                        val response = graphQLRepository.loadQueryUsersType(networkLibrary, gqlHeaders, listOf(channelId))
                        response.data!!.users?.firstOrNull()?.let {
                            User(
                                id = it.id,
                                broadcasterType = when {
                                    it.roles?.isPartner == true -> "partner"
                                    it.roles?.isAffiliate == true -> "affiliate"
                                    else -> null
                                },
                                type = when {
                                    it.roles?.isStaff == true -> "staff"
                                    else -> null
                                },
                            )
                        }
                    } catch (e: Exception) {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            try {
                                helixRepository.getUsers(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    ids = listOf(channelId)
                                ).data.firstOrNull()?.let {
                                    User(
                                        id = it.id,
                                        login = it.login,
                                        name = it.displayName,
                                        profileImageURL = it.profileImageURL,
                                        type = it.type,
                                        broadcasterType = it.broadcasterType,
                                        createdAt = it.createdAt,
                                    )
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }
                }
                bookmarksRepository.save(
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
                        duration = durationSeconds.toString(),
                        animatedPreviewURL = animatedPreviewUrl
                    )
                )
            }
        }
    }

    fun loadClip(networkLibrary: String?, gqlHeaders: Map<String, String>, id: String?, enableIntegrity: Boolean) {
        if (clipUrls.value == null) {
            viewModelScope.launch {
                try {
                    clipUrls.value = playerRepository.loadClipQualities(networkLibrary, gqlHeaders, id, enableIntegrity) ?: emptyList()
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshClip")
                    } else {
                        clipUrls.value = emptyList()
                    }
                }
            }
        }
    }

    fun getOfflineVideoPosition(id: Int) {
        viewModelScope.launch {
            savedOfflineVideoPosition.value = offlineVideosRepository.getById(id)?.lastWatchPosition ?: 0
        }
    }

    fun saveOfflineVideoPosition(id: Int, position: Long) {
        if (loaded.value) {
            viewModelScope.launch {
                offlineVideosRepository.updatePosition(id, position)
            }
        }
    }

    fun isFollowingChannel(userId: String?, channelId: String?, channelLogin: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        if (_isFollowing.value == null) {
            viewModelScope.launch {
                try {
                    if (!channelId.isNullOrBlank()) {
                        if (setting == 0 && !userId.isNullOrBlank() && userId != channelId) {
                            try {
                                if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                                val follower = graphQLRepository.loadQueryFollowingUser(
                                    networkLibrary = networkLibrary,
                                    headers = gqlHeaders,
                                    id = channelId,
                                    login = channelLogin.takeIf { channelId.isBlank() },
                                ).data?.user?.self?.follower
                                _isFollowing.value = follower?.followedAt != null
                            } catch (e: Exception) {
                                val following = helixRepository.getUserFollows(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    userId = userId,
                                    targetId = channelId,
                                ).data.firstOrNull()?.id == channelId
                                _isFollowing.value = following
                            }
                        } else {
                            _isFollowing.value = localChannelFollowsRepository.getById(channelId) != null
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowChannel(userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int, liveNotificationsEnabled: Boolean, disableNotifications: Boolean, startedAt: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = graphQLRepository.loadFollowUser(networkLibrary, gqlHeaders, channelId, disableNotifications).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("follow")
                                    return@launch
                                }
                            }
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(true, errorMessage)
                        } else {
                            _isFollowing.value = true
                            follow.value = Pair(true, null)
                            if (liveNotificationsEnabled) {
                                startedAt.takeUnless { it.isNullOrBlank() }?.let {
                                    Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
                                }?.let {
                                    notificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                                }
                            }
                        }
                    } else {
                        localChannelFollowsRepository.save(LocalChannelFollow(channelId, channelLogin, channelName))
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                        if (!disableNotifications) {
                            notificationsRepository.saveUser(NotificationUser(channelId))
                        }
                        if (liveNotificationsEnabled) {
                            startedAt.takeUnless { it.isNullOrBlank() }?.let {
                                Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
                            }?.let {
                                notificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowChannel(userId: String?, channelId: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = graphQLRepository.loadUnfollowUser(networkLibrary, gqlHeaders, channelId).also { response ->
                            if (enableIntegrity) {
                                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                    integrity.emit("unfollow")
                                    return@launch
                                }
                            }
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(false, errorMessage)
                        } else {
                            _isFollowing.value = false
                            follow.value = Pair(false, null)
                        }
                    } else {
                        localChannelFollowsRepository.getById(channelId)?.let { localChannelFollowsRepository.delete(it) }
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                        notificationsRepository.deleteUser(NotificationUser(channelId))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    companion object {
        val Media3PlayerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                Media3PlayerViewModel(xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.bookmarksRepository, xtraModule.offlineVideosRepository, xtraModule.localChannelFollowsRepository, xtraModule.notificationsRepository)
            }
        }
    }
}