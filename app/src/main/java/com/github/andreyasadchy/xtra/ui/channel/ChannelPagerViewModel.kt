package com.github.andreyasadchy.xtra.ui.channel

import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.LocalFollowChannel
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.NotificationUsersRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.ShownNotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@HiltViewModel
class ChannelPagerViewModel @Inject constructor(
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val shownNotificationsRepository: ShownNotificationsRepository,
    private val notificationUsersRepository: NotificationUsersRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private val args = ChannelPagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val _notificationsEnabled = MutableStateFlow<Boolean?>(null)
    val notificationsEnabled: StateFlow<Boolean?> = _notificationsEnabled
    val notifications = MutableStateFlow<Pair<Boolean, String?>?>(null)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)
    private var updatedLocalUser = false

    private val _stream = MutableStateFlow<Stream?>(null)
    val stream: StateFlow<Stream?> = _stream
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    fun loadStream(networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_stream.value == null) {
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryUserChannelPage(networkLibrary, gqlHeaders, args.channelId, if (args.channelId.isNullOrBlank()) args.channelLogin else null)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            return@launch
                        }
                    }
                    response.data!!.user?.let {
                        _stream.value = Stream(
                            id = it.stream?.id,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameId = it.stream?.game?.id,
                            gameSlug = it.stream?.game?.slug,
                            gameName = it.stream?.game?.displayName,
                            title = it.stream?.title,
                            thumbnailURL = it.stream?.previewImageURL,
                            createdAt = it.stream?.createdAt?.toString(),
                            viewerCount = it.stream?.viewersCount,
                        )
                        _user.value = User(
                            id = it.id,
                            login = it.login,
                            name = it.displayName,
                            profileImageURL = it.profileImageURL,
                            type = when {
                                it.roles?.isStaff == true -> "staff"
                                else -> null
                            },
                            broadcasterType = when {
                                it.roles?.isPartner == true -> "partner"
                                it.roles?.isAffiliate == true -> "affiliate"
                                else -> null
                            },
                            createdAt = it.createdAt?.toString(),
                            followerCount = it.followers?.totalCount,
                            bannerImageURL = it.bannerImageURL,
                            lastBroadcast = it.lastBroadcast?.startedAt?.toString(),
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getStreams(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = args.channelId?.let { listOf(it) },
                                logins = if (args.channelId.isNullOrBlank()) args.channelLogin?.let { listOf(it) } else null
                            ).data.firstOrNull()?.let {
                                _stream.value = Stream(
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
                            helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = args.channelId?.let { listOf(it) },
                                logins = if (args.channelId.isNullOrBlank()) args.channelLogin?.let { listOf(it) } else null
                            ).data.firstOrNull()?.let {
                                _user.value = User(
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

                        }
                    }
                }
            }
        }
    }

    fun enableNotifications(userId: String?, channelId: String, setting: Int, notificationsEnabled: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && _isFollowing.value == true && userId != channelId) {
                    val errorMessage = graphQLRepository.loadToggleNotificationsUser(networkLibrary, gqlHeaders, channelId, false).also { response ->
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "enableNotifications"
                                return@launch
                            }
                        }
                    }.errors?.firstOrNull()?.message
                    if (!errorMessage.isNullOrBlank()) {
                        notifications.value = Pair(true, errorMessage)
                    } else {
                        _notificationsEnabled.value = true
                        notifications.value = Pair(true, errorMessage)
                        if (notificationsEnabled) {
                            _stream.value?.createdAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                                shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                            }
                        }
                    }
                } else {
                    notificationUsersRepository.saveUser(NotificationUser(channelId))
                    _notificationsEnabled.value = true
                    notifications.value = Pair(true, null)
                    if (notificationsEnabled) {
                        _stream.value?.createdAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                            shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun disableNotifications(userId: String?, channelId: String, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && _isFollowing.value == true && userId != channelId) {
                    val errorMessage = graphQLRepository.loadToggleNotificationsUser(networkLibrary, gqlHeaders, channelId, true).also { response ->
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "disableNotifications"
                                return@launch
                            }
                        }
                    }.errors?.firstOrNull()?.message
                    if (!errorMessage.isNullOrBlank()) {
                        notifications.value = Pair(false, errorMessage)
                    } else {
                        _notificationsEnabled.value = false
                        notifications.value = Pair(false, errorMessage)
                    }
                } else {
                    notificationUsersRepository.deleteUser(NotificationUser(channelId))
                    _notificationsEnabled.value = false
                    notifications.value = Pair(false, null)
                }
            } catch (e: Exception) {

            }
        }
    }

    fun updateNotifications(networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch {
            shownNotificationsRepository.getNewStreams(notificationUsersRepository, networkLibrary, gqlHeaders, graphQLRepository, helixHeaders, helixRepository)
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
                                _notificationsEnabled.value = follower?.notificationSettings?.isEnabled == true
                            } catch (e: Exception) {
                                val following = helixRepository.getUserFollows(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    userId = userId,
                                    targetId = channelId,
                                ).data.firstOrNull()?.id == channelId
                                _isFollowing.value = following
                                _notificationsEnabled.value = notificationUsersRepository.getByUserId(channelId) != null
                            }
                        } else {
                            _isFollowing.value = localFollowsChannel.getFollowByUserId(channelId) != null
                            _notificationsEnabled.value = notificationUsersRepository.getByUserId(channelId) != null
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowChannel(userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int, liveNotificationsEnabled: Boolean, disableNotifications: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = graphQLRepository.loadFollowUser(networkLibrary, gqlHeaders, channelId, disableNotifications).also { response ->
                            if (enableIntegrity && integrity.value == null) {
                                response.errors?.find { it.message == "failed integrity check" }?.let {
                                    integrity.value = "follow"
                                    return@launch
                                }
                            }
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(true, errorMessage)
                        } else {
                            _isFollowing.value = true
                            follow.value = Pair(true, null)
                            if (!disableNotifications) {
                                _notificationsEnabled.value = true
                            }
                            if (liveNotificationsEnabled) {
                                _stream.value?.createdAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                                    shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                                }
                            }
                        }
                    } else {
                        localFollowsChannel.saveFollow(LocalFollowChannel(channelId, channelLogin, channelName))
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                        if (!disableNotifications) {
                            notificationUsersRepository.saveUser(NotificationUser(channelId))
                            _notificationsEnabled.value = true
                        }
                        if (liveNotificationsEnabled) {
                            _stream.value?.createdAt.takeUnless { it.isNullOrBlank() }?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                                shownNotificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
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
                            if (enableIntegrity && integrity.value == null) {
                                response.errors?.find { it.message == "failed integrity check" }?.let {
                                    integrity.value = "unfollow"
                                    return@launch
                                }
                            }
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(false, errorMessage)
                        } else {
                            _isFollowing.value = false
                            follow.value = Pair(false, null)
                            _notificationsEnabled.value = false
                        }
                    } else {
                        localFollowsChannel.getFollowByUserId(channelId)?.let { localFollowsChannel.deleteFollow(it) }
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                        notificationUsersRepository.deleteUser(NotificationUser(channelId))
                        _notificationsEnabled.value = false
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun updateLocalUser(networkLibrary: String?, filesDir: String, user: User) {
        if (!updatedLocalUser) {
            updatedLocalUser = true
            user.id.takeIf { !it.isNullOrBlank() }?.let { userId ->
                viewModelScope.launch {
                    val downloadedLogo = user.profileImage.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + userId
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            httpEngine.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                            cronetEngine.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                            val response = request.future.get()
                                            if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.responseBody as ByteArray)
                                                }
                                            }
                                        } else {
                                            val response = suspendCancellableCoroutine { continuation ->
                                                cronetEngine.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                            }
                                            if (response.first.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.second)
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
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
                    localFollowsChannel.getFollowByUserId(userId)?.let {
                        localFollowsChannel.updateFollow(it.apply {
                            userLogin = user.login
                            userName = user.name
                        })
                    }
                    offlineRepository.getVideosByUserId(userId).forEach {
                        offlineRepository.updateVideo(it.apply {
                            channelLogin = user.login
                            channelName = user.name
                            channelLogo = downloadedLogo
                        })
                    }
                    bookmarksRepository.getBookmarksByUserId(userId).forEach {
                        bookmarksRepository.updateBookmark(it.apply {
                            userLogin = user.login
                            userName = user.name
                            userLogo = downloadedLogo
                        })
                    }
                }
            }
        }
    }
}
