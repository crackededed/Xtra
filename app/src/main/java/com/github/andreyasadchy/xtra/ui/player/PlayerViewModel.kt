package com.github.andreyasadchy.xtra.ui.player

import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
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
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import kotlin.time.Duration.Companion.minutes

class PlayerViewModel(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val notificationsRepository: NotificationsRepository,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    val stream = MutableStateFlow<Stream?>(null)
    private var streamUpdateJob: Job? = null
    val isBookmarked = MutableStateFlow<Boolean?>(null)
    val gamesList = MutableStateFlow<List<Game>?>(null)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)

    fun deletePlaybackStates() {
        viewModelScope.launch {
            playerRepository.deletePlaybackStates()
        }
    }

    fun loadStreamInfo(channelId: String?, channelLogin: String?, viewerCount: Int?, loop: Boolean, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (loop) {
            if (streamUpdateJob?.isActive != true) {
                streamUpdateJob?.cancel()
                streamUpdateJob = viewModelScope.launch {
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
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            httpEngine.value!!.newUrlRequestBuilder(it, cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            cronetEngine.value!!.newUrlRequestBuilder(it, NetworkUtils.byteArrayCronetUrlCallback(continuation), cronetExecutor.value).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(it).build()).executeAsync().use { response ->
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
                    channelImage.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            httpEngine.value!!.newUrlRequestBuilder(it, cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            cronetEngine.value!!.newUrlRequestBuilder(it, NetworkUtils.byteArrayCronetUrlCallback(continuation), cronetExecutor.value).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(it).build()).executeAsync().use { response ->
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
                                startedAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
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
                            startedAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
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
        val PlayerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                PlayerViewModel(xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.bookmarksRepository, xtraModule.localChannelFollowsRepository, xtraModule.notificationsRepository)
            }
        }
    }
}