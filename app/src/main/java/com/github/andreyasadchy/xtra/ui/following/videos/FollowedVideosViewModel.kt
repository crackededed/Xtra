package com.github.andreyasadchy.xtra.ui.following.videos

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.ChannelSort
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.ChannelSortRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedVideosDataSource
import com.github.andreyasadchy.xtra.ui.common.VideosSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

class FollowedVideosViewModel(
    private val applicationContext: Context,
    private val channelSortRepository: ChannelSortRepository,
    playerRepository: PlayerRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
) : ViewModel() {

    val filter = MutableStateFlow<Filter?>(null)
    val sortText = MutableStateFlow<CharSequence?>(null)
    val positions = playerRepository.loadVideoPositions()
    val bookmarks = bookmarksRepository.getAllFlow()

    val sort: String
        get() = filter.value?.sort ?: VideosSortDialog.SORT_TIME
    val period: String
        get() = filter.value?.period ?: VideosSortDialog.PERIOD_ALL
    val type: String
        get() = filter.value?.type ?: VideosSortDialog.VIDEO_TYPE_ALL

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest {
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        ) {
            FollowedVideosDataSource(
                gqlQueryType = when (type) {
                    VideosSortDialog.VIDEO_TYPE_ALL -> null
                    VideosSortDialog.VIDEO_TYPE_ARCHIVE -> BroadcastType.ARCHIVE
                    VideosSortDialog.VIDEO_TYPE_HIGHLIGHT -> BroadcastType.HIGHLIGHT
                    VideosSortDialog.VIDEO_TYPE_UPLOAD -> BroadcastType.UPLOAD
                    else -> null
                },
                gqlQuerySort = when (sort) {
                    VideosSortDialog.SORT_TIME -> VideoSort.TIME
                    VideosSortDialog.SORT_VIEWS -> VideoSort.VIEWS
                    else -> VideoSort.TIME
                },
                gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                graphQLRepository = graphQLRepository,
                enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            )
        }.flow
    }.cachedIn(viewModelScope)

    suspend fun getChannelSort(id: String): ChannelSort? {
        return channelSortRepository.getById(id)
    }

    suspend fun saveChannelSort(item: ChannelSort) {
        channelSortRepository.save(item)
    }

    fun setFilter(sort: String?, type: String?) {
        filter.value = Filter(sort, null, type)
    }

    class Filter(
        val sort: String?,
        val period: String?,
        val type: String?,
    )

    fun saveBookmark(filesDir: String, video: Video, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch {
            val item = video.id?.let { bookmarksRepository.getByVideoId(it) }
            if (item != null) {
                bookmarksRepository.delete(item)
            } else {
                val downloadedThumbnail = video.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                    video.thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
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
                val downloadedLogo = video.channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    video.channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
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
                val userTypes = video.channelId?.let {
                    try {
                        val response = graphQLRepository.loadQueryUsersType(networkLibrary, gqlHeaders, listOf(it))
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
                                    ids = listOf(it)
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
                        videoId = video.id,
                        userId = video.channelId,
                        userLogin = video.channelLogin,
                        userName = video.channelName,
                        userType = userTypes?.type,
                        userBroadcasterType = userTypes?.broadcasterType,
                        userLogo = downloadedLogo,
                        gameId = video.gameId,
                        gameSlug = video.gameSlug,
                        gameName = video.gameName,
                        title = video.title,
                        createdAt = video.createdAt,
                        thumbnail = downloadedThumbnail,
                        type = video.type,
                        duration = video.durationSeconds?.toString(),
                        animatedPreviewURL = video.animatedPreviewURL
                    )
                )
            }
        }
    }

    companion object {
        val FollowedVideosViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                FollowedVideosViewModel(application.applicationContext, xtraModule.channelSortRepository, xtraModule.playerRepository, xtraModule.bookmarksRepository, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient)
            }
        }
    }
}
