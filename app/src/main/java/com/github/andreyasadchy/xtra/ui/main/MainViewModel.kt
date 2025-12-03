package com.github.andreyasadchy.xtra.ui.main

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.http.HttpEngine
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.download.StreamDownloadWorker
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadWorker
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.io.File
import java.io.FileOutputStream
import java.util.Timer
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val offlineRepository: OfflineRepository,
    private val authRepository: AuthRepository,
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val checkNetworkStatus = MutableStateFlow(false)
    val isNetworkAvailable = MutableStateFlow<Boolean?>(null)

    var isPlayerOpened = false

    var sleepTimer: Timer? = null
    var sleepTimerEndTime = 0L

    val video = MutableStateFlow<Video?>(null)
    val clip = MutableStateFlow<Clip?>(null)
    val user = MutableStateFlow<User?>(null)

    val updateUrl = MutableSharedFlow<String?>()

    fun loadVideo(videoId: String?, offset: Long?, saveVideoPositions: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (video.value == null) {
            viewModelScope.launch {
                val item = try {
                    val response = graphQLRepository.loadQueryVideo(networkLibrary, gqlHeaders, videoId)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
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
                                type = it.broadcastType?.toString(),
                                title = it.title,
                                uploadDate = it.createdAt?.toString(),
                                duration = it.lengthSeconds?.toString(),
                                thumbnailUrl = it.previewThumbnailURL,
                                profileImageUrl = it.owner?.profileImageURL,
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
                                ids = videoId?.let { listOf(it) }
                            ).data.firstOrNull()?.let {
                                Video(
                                    id = it.id,
                                    channelId = it.channelId,
                                    channelLogin = it.channelLogin,
                                    channelName = it.channelName,
                                    title = it.title,
                                    viewCount = it.viewCount,
                                    uploadDate = it.uploadDate,
                                    duration = it.duration,
                                    thumbnailUrl = it.thumbnailUrl,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
                if (item != null && saveVideoPositions) {
                    videoId?.toLongOrNull()?.let { id ->
                        playerRepository.saveVideoPosition(VideoPosition(id, offset ?: 0))
                    }
                }
                video.value = item
            }
        }
    }

    fun loadClip(clipId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (clip.value == null) {
            viewModelScope.launch {
                clip.value = try {
                    val user = try {
                        graphQLRepository.loadClipData(networkLibrary, gqlHeaders, clipId).data?.clip
                    } catch (e: Exception) {
                        null
                    }
                    val clip = graphQLRepository.loadClipVideo(networkLibrary, gqlHeaders, clipId).also { response ->
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "refresh"
                                return@launch
                            }
                        }
                    }.data?.clip
                    Clip(
                        id = clipId,
                        channelId = user?.broadcaster?.id,
                        channelLogin = user?.broadcaster?.login,
                        channelName = user?.broadcaster?.displayName,
                        profileImageUrl = user?.broadcaster?.profileImageURL,
                        videoId = clip?.video?.id,
                        duration = clip?.durationSeconds,
                        vodOffset = (clip?.videoOffsetSeconds ?: user?.videoOffsetSeconds).let {
                            if (it != null && clip?.durationSeconds != null) {
                                max(it - clip.durationSeconds.toInt(), 0)
                            } else {
                                it
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getClips(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = clipId?.let { listOf(it) }
                            ).data.firstOrNull()?.let {
                                Clip(
                                    id = it.id,
                                    channelId = it.channelId,
                                    channelName = it.channelName,
                                    videoId = it.videoId,
                                    vodOffset = if (it.vodOffset != null && it.duration != null) {
                                        max(it.vodOffset - it.duration.toInt(), 0)
                                    } else {
                                        it.vodOffset
                                    },
                                    gameId = it.gameId,
                                    title = it.title,
                                    viewCount = it.viewCount,
                                    uploadDate = it.createdAt,
                                    duration = it.duration,
                                    thumbnailUrl = it.thumbnailUrl,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
            }
        }
    }

    fun loadUser(login: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (user.value == null) {
            viewModelScope.launch {
                user.value = try {
                    val response = graphQLRepository.loadQueryUser(networkLibrary, gqlHeaders, login = login)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            return@launch
                        }
                    }
                    response.data!!.user?.let {
                        User(
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            profileImageUrl = it.profileImageURL
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                logins = login?.let { listOf(it) }
                            ).data.firstOrNull()?.let {
                                User(
                                    channelId = it.channelId,
                                    channelLogin = it.channelLogin,
                                    channelName = it.channelName,
                                    type = it.type,
                                    broadcasterType = it.broadcasterType,
                                    profileImageUrl = it.profileImageUrl,
                                    createdAt = it.createdAt,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
            }
        }
    }

    fun downloadStream(networkLibrary: String?, filesDir: String, id: String?, title: String?, startedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            if (!channelLogin.isNullOrBlank()) {
                val downloadedThumbnail = id.takeIf { !it.isNullOrBlank() }?.let { id ->
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
                val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    channelLogo.takeIf { !it.isNullOrBlank() }?.let {
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
                val videoId = offlineRepository.saveVideo(
                    OfflineVideo(
                        name = title,
                        channelId = channelId,
                        channelLogin = channelLogin,
                        channelName = channelName,
                        channelLogo = downloadedLogo,
                        thumbnail = downloadedThumbnail,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        uploadDate = startedAt?.let { TwitchApiHelper.parseIso8601DateUTC(it) },
                        downloadDate = System.currentTimeMillis(),
                        downloadPath = downloadPath,
                        status = OfflineVideo.STATUS_BLOCKED,
                        quality = if (!quality.contains("Audio", true)) quality else "audio",
                        downloadChat = downloadChat,
                        downloadChatEmotes = downloadChatEmotes,
                        live = true
                    )
                ).toInt()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    channelLogin,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<StreamDownloadWorker>()
                        .setInputData(workDataOf(StreamDownloadWorker.KEY_VIDEO_ID to videoId))
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            }
        }
    }

    fun downloadVideo(networkLibrary: String?, filesDir: String, id: String?, title: String?, uploadDate: String?, type: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, from: Long, to: Long, downloadChat: Boolean, downloadChatEmotes: Boolean, playlistToFile: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            val downloadedThumbnail = id.takeIf { !it.isNullOrBlank() }?.let { id ->
                thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                    File(filesDir, "thumbnails").mkdir()
                    val path = filesDir + File.separator + "thumbnails" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
            val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                channelLogo.takeIf { !it.isNullOrBlank() }?.let {
                    File(filesDir, "profile_pics").mkdir()
                    val path = filesDir + File.separator + "profile_pics" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
            val videoId = offlineRepository.saveVideo(
                OfflineVideo(
                    sourceUrl = url,
                    name = title,
                    channelId = channelId,
                    channelLogin = channelLogin,
                    channelName = channelName,
                    channelLogo = downloadedLogo,
                    thumbnail = downloadedThumbnail,
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    uploadDate = uploadDate?.let { TwitchApiHelper.parseIso8601DateUTC(it) },
                    downloadDate = System.currentTimeMillis(),
                    downloadPath = downloadPath,
                    fromTime = from,
                    toTime = to,
                    status = OfflineVideo.STATUS_BLOCKED,
                    type = type,
                    videoId = id,
                    quality = if (!quality.contains("Audio", true)) quality else "audio",
                    downloadChat = downloadChat,
                    downloadChatEmotes = downloadChatEmotes,
                    playlistToFile = playlistToFile
                )
            ).toInt()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "download",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                    .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to videoId))
                    .addTag(videoId.toString())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    fun downloadClip(networkLibrary: String?, filesDir: String, clipId: String?, title: String?, uploadDate: String?, duration: Double?, videoId: String?, vodOffset: Int?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            val downloadedThumbnail = clipId.takeIf { !it.isNullOrBlank() }?.let { id ->
                thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                    File(filesDir, "thumbnails").mkdir()
                    val path = filesDir + File.separator + "thumbnails" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
            val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                channelLogo.takeIf { !it.isNullOrBlank() }?.let {
                    File(filesDir, "profile_pics").mkdir()
                    val path = filesDir + File.separator + "profile_pics" + File.separator + id
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
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
                                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
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
            val videoId = offlineRepository.saveVideo(
                OfflineVideo(
                    sourceUrl = url,
                    sourceStartPosition = vodOffset?.toLong()?.times(1000L),
                    name = title,
                    channelId = channelId,
                    channelLogin = channelLogin,
                    channelName = channelName,
                    channelLogo = downloadedLogo,
                    thumbnail = downloadedThumbnail,
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    duration = duration?.toLong()?.times(1000L),
                    uploadDate = uploadDate?.let { TwitchApiHelper.parseIso8601DateUTC(it) },
                    downloadDate = System.currentTimeMillis(),
                    downloadPath = downloadPath,
                    status = OfflineVideo.STATUS_BLOCKED,
                    videoId = videoId,
                    clipId = clipId,
                    quality = if (!quality.contains("Audio", true)) quality else "audio",
                    downloadChat = downloadChat,
                    downloadChatEmotes = downloadChatEmotes
                )
            ).toInt()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "download",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                    .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to videoId))
                    .addTag(videoId.toString())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    fun validate(networkLibrary: String?, gqlHeaders: Map<String, String>, webGQLToken: String?, helixHeaders: Map<String, String>, accountId: String?, accountLogin: String?, activity: Activity) {
        viewModelScope.launch {
            try {
                val helixToken = helixHeaders[C.HEADER_TOKEN]
                if (!helixToken.isNullOrBlank()) {
                    val response = authRepository.validate(networkLibrary, helixToken)
                    if (response.clientId.isNotBlank() && response.clientId == helixHeaders[C.HEADER_CLIENT_ID]) {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
                val gqlToken = gqlHeaders[C.HEADER_TOKEN]
                if (!gqlToken.isNullOrBlank()) {
                    val response = authRepository.validate(networkLibrary, gqlToken)
                    if (response.clientId.isNotBlank() && response.clientId == gqlHeaders[C.HEADER_CLIENT_ID]) {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
                if (!webGQLToken.isNullOrBlank()) {
                    val response = authRepository.validate(networkLibrary, webGQLToken)
                    if (response.clientId.isNotBlank() && response.clientId == "kimne78kx3ncx6brgo4mv6wki5h1ko") {
                        if ((!response.userId.isNullOrBlank() && response.userId != accountId) || (!response.login.isNullOrBlank() && response.login != accountLogin)) {
                            activity.tokenPrefs().edit {
                                putString(C.USER_ID, response.userId?.takeIf { it.isNotBlank() } ?: accountId)
                                putString(C.USERNAME, response.login?.takeIf { it.isNotBlank() } ?: accountLogin)
                            }
                        }
                    } else {
                        throw IllegalStateException("401")
                    }
                }
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message == "401") {
                    activity.toast(R.string.token_expired)
                    (activity as? MainActivity)?.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                }
            }
        }
        TwitchApiHelper.checkedValidation = true
    }

    fun checkUpdates(networkLibrary: String?, url: String, lastChecked: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            updateUrl.emit(
                try {
                    val response = when {
                        networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                            }
                            json.decodeFromString<JsonObject>(String(response.second))
                        }
                        networkLibrary == "Cronet" && cronetEngine != null -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                                cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                val response = request.future.get().responseBody as String
                                json.decodeFromString<JsonObject>(response)
                            } else {
                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                    cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                }
                                json.decodeFromString<JsonObject>(String(response.second))
                            }
                        }
                        else -> {
                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                json.decodeFromString<JsonObject>(response.body.string())
                            }
                        }
                    }
                    response["assets"]?.jsonArray?.find {
                        it.jsonObject.getValue("content_type").jsonPrimitive.contentOrNull == "application/vnd.android.package-archive"
                    }?.jsonObject?.let { obj ->
                        obj.getValue("updated_at").jsonPrimitive.contentOrNull?.let { TwitchApiHelper.parseIso8601DateUTC(it) }?.let {
                            if (it > lastChecked) {
                                obj.getValue("browser_download_url").jsonPrimitive.contentOrNull
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            )
        }
        TwitchApiHelper.checkedUpdates = true
    }

    fun downloadUpdate(networkLibrary: String?, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = when {
                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                            httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (response.first.httpStatusCode in 200..299) {
                            response.second
                        } else null
                    }
                    networkLibrary == "Cronet" && cronetEngine != null -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                            cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                            val response = request.future.get()
                            if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                response.responseBody as ByteArray
                            } else null
                        } else {
                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                            }
                            if (response.first.httpStatusCode in 200..299) {
                                response.second
                            } else null
                        }
                    }
                    else -> {
                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body.bytes()
                            } else null
                        }
                    }
                }
                if (response != null && response.isNotEmpty()) {
                    val packageInstaller = applicationContext.packageManager.packageInstaller
                    val sessionId = packageInstaller.createSession(
                        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    )
                    val session = packageInstaller.openSession(sessionId)
                    session.openWrite("package", 0, response.size.toLong()).use {
                        it.write(response)
                    }
                    session.commit(
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            Intent(applicationContext, MainActivity::class.java).apply {
                                setAction(MainActivity.INTENT_INSTALL_UPDATE)
                            },
                            PendingIntent.FLAG_MUTABLE
                        ).intentSender
                    )
                    session.close()
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteOldImages() {
        viewModelScope.launch(Dispatchers.IO) {
            offlineRepository.deleteOldImages()
        }
    }
}