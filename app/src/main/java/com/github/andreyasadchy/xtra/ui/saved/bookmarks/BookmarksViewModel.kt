package com.github.andreyasadchy.xtra.ui.saved.bookmarks

import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.VodBookmarkIgnoredUser
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.SortChannelRepository
import com.github.andreyasadchy.xtra.repository.VodBookmarkIgnoredUsersRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
class BookmarksViewModel @Inject internal constructor(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val sortChannelRepository: SortChannelRepository,
    playerRepository: PlayerRepository,
    private val vodBookmarkIgnoredUsersRepository: VodBookmarkIgnoredUsersRepository,
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val positions = playerRepository.loadVideoPositions()
    val ignoredUsers = vodBookmarkIgnoredUsersRepository.loadUsersFlow()
    private var updatedUsers = false
    private var updatedVideos = false

    val filter = MutableStateFlow<Filter?>(null)
    val sortText = MutableStateFlow<CharSequence?>(null)

    val sort: String
        get() = filter.value?.sort ?: BookmarksSortDialog.SORT_SAVED_AT
    val order: String
        get() = filter.value?.order ?: BookmarksSortDialog.ORDER_DESC

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        bookmarksRepository.loadBookmarksFlow()
    }

    fun delete(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarksRepository.deleteBookmark(bookmark)
        }
    }

    fun vodIgnoreUser(userId: String) {
        viewModelScope.launch {
            if (vodBookmarkIgnoredUsersRepository.getUserById(userId) != null) {
                vodBookmarkIgnoredUsersRepository.deleteUser(VodBookmarkIgnoredUser(userId))
            } else {
                vodBookmarkIgnoredUsersRepository.saveUser(VodBookmarkIgnoredUser(userId))
            }
        }
    }

    fun updateUsers(networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (!updatedUsers) {
            viewModelScope.launch {
                val bookmarks = bookmarksRepository.loadBookmarks()
                val ignored = vodBookmarkIgnoredUsersRepository.loadUsers()
                bookmarks.mapNotNull { bookmark ->
                    bookmark.userId?.takeIf { ignored.find { it.userId == bookmark.userId } == null }
                }.chunked(100).forEach { ids ->
                    try {
                        val response = graphQLRepository.loadQueryUsersType(networkLibrary, gqlHeaders, ids)
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "users"
                                return@launch
                            }
                        }
                        response.data!!.users?.mapNotNull {
                            if (it != null) {
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
                            } else null
                        }
                    } catch (e: Exception) {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            try {
                                helixRepository.getUsers(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    ids = ids,
                                ).data.map {
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
                    }?.forEach { user ->
                        user.id?.let { id ->
                            bookmarks.filter { it.userId == id }
                        }?.forEach { bookmark ->
                            if (user.type != bookmark.userType || user.broadcasterType != bookmark.userBroadcasterType) {
                                bookmarksRepository.updateBookmark(bookmark.apply {
                                    userType = user.type
                                    userBroadcasterType = user.broadcasterType
                                })
                            }
                        }
                    }
                }
                updatedUsers = true
            }
        }
    }

    fun updateVideo(filesDir: String, videoId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            if (!videoId.isNullOrBlank()) {
                val video = try {
                    val response = graphQLRepository.loadQueryVideo(networkLibrary, gqlHeaders, videoId)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "video"
                            return@launch
                        }
                    }
                    response.data!!.let { item ->
                        item.video?.let {
                            Video(
                                id = videoId,
                                channelId = it.owner?.id,
                                channelLogin = it.owner?.login,
                                channelName = it.owner?.displayName,
                                channelImageURL = it.owner?.profileImageURL,
                                title = it.title,
                                thumbnailURL = it.previewThumbnailURL,
                                createdAt = it.createdAt?.toString(),
                                durationSeconds = it.lengthSeconds,
                                type = it.broadcastType?.toString(),
                                animatedPreviewURL = it.animatedPreviewURL,
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getVideos(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = listOf(videoId),
                            ).data.firstOrNull()?.let {
                                Video(
                                    id = it.id,
                                    channelId = it.channelId,
                                    channelLogin = it.channelLogin,
                                    channelName = it.channelName,
                                    title = it.title,
                                    thumbnailURL = it.thumbnailURL,
                                    createdAt = it.createdAt,
                                    viewCount = it.viewCount,
                                    durationSeconds = it.duration?.let { duration -> TwitchApiHelper.getDuration(duration) },
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
                val bookmark = bookmarksRepository.getBookmarkByVideoId(videoId)
                if (video != null && bookmark != null) {
                    val downloadedThumbnail = video.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                        video.thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                            File(filesDir, "thumbnails").mkdir()
                            val path = filesDir + File.separator + "thumbnails" + File.separator + id
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
                    }
                    bookmarksRepository.updateBookmark(
                        Bookmark(
                            videoId = bookmark.videoId,
                            userId = video.channelId ?: bookmark.userId,
                            userLogin = video.channelLogin ?: bookmark.userLogin,
                            userName = video.channelName ?: bookmark.userName,
                            userType = bookmark.userType,
                            userBroadcasterType = bookmark.userBroadcasterType,
                            userLogo = bookmark.userLogo,
                            gameId = video.gameId ?: bookmark.gameId,
                            gameSlug = video.gameSlug ?: bookmark.gameSlug,
                            gameName = video.gameName ?: bookmark.gameName,
                            title = video.title ?: bookmark.title,
                            createdAt = video.createdAt ?: bookmark.createdAt,
                            thumbnail = downloadedThumbnail,
                            type = video.type ?: bookmark.type,
                            duration = video.durationSeconds?.toString() ?: bookmark.duration,
                            animatedPreviewURL = video.animatedPreviewURL ?: bookmark.animatedPreviewURL
                        )
                    )
                }
            }
        }
    }

    fun updateVideos(filesDir: String, networkLibrary: String?, helixHeaders: Map<String, String>) {
        if (!updatedVideos) {
            viewModelScope.launch {
                val bookmarks = bookmarksRepository.loadBookmarks()
                bookmarks.mapNotNull { it.videoId }.chunked(100).forEach { ids ->
                    helixRepository.getVideos(
                        networkLibrary = networkLibrary,
                        headers = helixHeaders,
                        ids = ids,
                    ).data.map {
                        Video(
                            id = it.id,
                            channelId = it.channelId,
                            channelLogin = it.channelLogin,
                            channelName = it.channelName,
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.createdAt,
                            viewCount = it.viewCount,
                            durationSeconds = it.duration?.let { duration -> TwitchApiHelper.getDuration(duration) },
                        )
                    }.forEach { video ->
                        video.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                            bookmarks.find { it.videoId == id }
                        }?.let { bookmark ->
                            if (bookmark.userId != video.channelId ||
                                bookmark.userLogin != video.channelLogin ||
                                bookmark.userName != video.channelName ||
                                bookmark.title != video.title ||
                                bookmark.createdAt != video.createdAt ||
                                bookmark.type != video.type ||
                                bookmark.duration != video.durationSeconds?.toString()
                            ) {
                                val downloadedThumbnail = video.thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                                    File(filesDir, "thumbnails").mkdir()
                                    val path = filesDir + File.separator + "thumbnails" + File.separator + video.id
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
                                bookmarksRepository.updateBookmark(
                                    Bookmark(
                                        videoId = bookmark.videoId,
                                        userId = video.channelId ?: bookmark.userId,
                                        userLogin = video.channelLogin ?: bookmark.userLogin,
                                        userName = video.channelName ?: bookmark.userName,
                                        userType = bookmark.userType,
                                        userBroadcasterType = bookmark.userBroadcasterType,
                                        userLogo = bookmark.userLogo,
                                        gameId = bookmark.gameId,
                                        gameSlug = bookmark.gameSlug,
                                        gameName = bookmark.gameName,
                                        title = video.title ?: bookmark.title,
                                        createdAt = video.createdAt ?: bookmark.createdAt,
                                        thumbnail = downloadedThumbnail,
                                        type = video.type ?: bookmark.type,
                                        duration = video.durationSeconds?.toString() ?: bookmark.duration,
                                        animatedPreviewURL = video.animatedPreviewURL ?: bookmark.animatedPreviewURL
                                    )
                                )
                            }
                        }
                    }
                }
                updatedVideos = true
            }
        }
    }

    suspend fun getSortChannel(id: String): SortChannel? {
        return sortChannelRepository.getById(id)
    }

    suspend fun saveSortChannel(item: SortChannel) {
        sortChannelRepository.save(item)
    }

    fun setFilter(sort: String?, order: String?) {
        filter.value = Filter(sort, order)
    }

    class Filter(
        val sort: String?,
        val order: String?,
    )
}