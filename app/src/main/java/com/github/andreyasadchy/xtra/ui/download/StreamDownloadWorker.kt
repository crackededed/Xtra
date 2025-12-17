package com.github.andreyasadchy.xtra.ui.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.http.HttpEngine
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.DocumentsContract
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocketOkHttp
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.prefs
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Random
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

@HiltWorker
class StreamDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    @Inject
    @JvmField
    var httpEngine: Lazy<HttpEngine>? = null

    @Inject
    @JvmField
    var cronetEngine: Lazy<CronetEngine>? = null

    @Inject
    lateinit var cronetExecutor: ExecutorService

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var graphQLRepository: GraphQLRepository

    @Inject
    lateinit var helixRepository: HelixRepository

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var offlineVideo: OfflineVideo
    private var chatReadWebSocketOkHttp: ChatReadWebSocketOkHttp? = null
    private var chatFileWriter: BufferedWriter? = null
    private var chatPosition: Long = 0

    override suspend fun doWork(): Result {
        val firstVideo = offlineRepository.getVideoById(inputData.getInt(KEY_VIDEO_ID, 0)) ?: return Result.failure()
        offlineVideo = firstVideo
        offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_WAITING_FOR_STREAM })
        setForeground(createForegroundInfo(false, firstVideo))
        val path = offlineVideo.downloadPath!!
        val channelLogin = offlineVideo.channelLogin!!
        val quality = offlineVideo.quality
        val offlineCheck = max(context.prefs().getString(C.DOWNLOAD_STREAM_OFFLINE_CHECK, "10")?.toLongOrNull() ?: 10L, 2L) * 1000L
        val startWait = (context.prefs().getString(C.DOWNLOAD_STREAM_START_WAIT, "120")?.toLongOrNull())?.times(60000L)
        val endWait = (context.prefs().getString(C.DOWNLOAD_STREAM_END_WAIT, "15")?.toLongOrNull())?.times(60000L)
        val proxyHost = context.prefs().getString(C.PROXY_HOST, null)
        val proxyPort = context.prefs().getString(C.PROXY_PORT, null)?.toIntOrNull()
        val proxyMultivariantPlaylist = context.prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false)
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, context.prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true))
        val randomDeviceId = context.prefs().getBoolean(C.TOKEN_RANDOM_DEVICEID, true)
        val xDeviceId = context.prefs().getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason")
        val playerType = context.prefs().getString(C.TOKEN_PLAYERTYPE, "site")
        val supportedCodecs = context.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
        val proxyPlaybackAccessToken = context.prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false)
        val proxyUser = context.prefs().getString(C.PROXY_USER, null)
        val proxyPassword = context.prefs().getString(C.PROXY_PASSWORD, null)
        var playlistUrl = playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, proxyHost, proxyPort, proxyUser, proxyPassword, false)
        var loop = true
        var startTime = System.currentTimeMillis()
        var endTime = startWait?.let { System.currentTimeMillis() + it }
        while (loop) {
            val playlist = when {
                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                        httpEngine!!.get().newUrlRequestBuilder(playlistUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
                networkLibrary == "Cronet" && cronetEngine != null -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                        cronetEngine!!.get().newUrlRequestBuilder(playlistUrl, request.callback, cronetExecutor).build().start()
                        val response = request.future.get()
                        if (response.urlResponseInfo.httpStatusCode in 200..299) {
                            response.responseBody as String
                        } else null
                    } else {
                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                            cronetEngine!!.get().newUrlRequestBuilder(playlistUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                        }
                        if (response.first.httpStatusCode in 200..299) {
                            String(response.second)
                        } else null
                    }
                }
                else -> {
                    okHttpClient.newCall(Request.Builder().url(playlistUrl).build()).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body.string()
                        } else null
                    }
                }
            }
            if (!playlist.isNullOrBlank()) {
                val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                val map = names.zip(urls)
                    .sortedByDescending {
                        it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first == "source"
                    }
                    .toMap()
                if (map.isNotEmpty()) {
                    val mediaPlaylistUrl = if (!quality.isNullOrBlank()) {
                        quality.split("p").let { targetQuality ->
                            targetQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
                                val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                val last = map.keys.last { it != "audio_only" && it != "chat_only" }
                                map.entries.find { entry ->
                                    val quality = entry.key.split("p")
                                    val resolution = quality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                    val fps = quality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                    resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || entry.key == last)
                                }
                            }
                        }?.value ?: map.values.first()
                    } else {
                        map.values.first()
                    }
                    val url = if ((proxyPlaybackAccessToken || proxyMultivariantPlaylist) && !proxyHost.isNullOrBlank() && proxyPort != null) {
                        val url = if (proxyPlaybackAccessToken) {
                            playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, true, proxyHost, proxyPort, proxyUser, proxyPassword, false)
                        } else {
                            playlistUrl
                        }
                        val newPlaylist = when {
                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null && !proxyMultivariantPlaylist -> {
                                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                    httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                }
                                String(response.second)
                            }
                            networkLibrary == "Cronet" && cronetEngine != null && !proxyMultivariantPlaylist -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                                    cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                    request.future.get().responseBody as String
                                } else {
                                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                        cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                    }
                                    String(response.second)
                                }
                            }
                            else -> {
                                okHttpClient.newBuilder().apply {
                                    if (proxyMultivariantPlaylist) {
                                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                        if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                            proxyAuthenticator { _, response ->
                                                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                                            }
                                        }
                                    }
                                }.build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                                    response.body.string()
                                }
                            }
                        }
                        val newNames = Regex("IVS-NAME=\"(.+?)\"").findAll(newPlaylist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        val newUrls = Regex("https://.*\\.m3u8").findAll(newPlaylist).map(MatchResult::value).toMutableList()
                        val newMap = newNames.zip(newUrls)
                            .sortedByDescending {
                                it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                            }
                            .sortedByDescending {
                                it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                            }
                            .sortedByDescending {
                                it.first == "source"
                            }
                            .toMap()
                        if (newMap.isNotEmpty()) {
                            if (!quality.isNullOrBlank()) {
                                quality.split("p").let { targetQuality ->
                                    targetQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
                                        val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                        val last = newMap.keys.last { it != "audio_only" && it != "chat_only" }
                                        newMap.entries.find { entry ->
                                            val quality = entry.key.split("p")
                                            val resolution = quality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            val fps = quality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                            resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || entry.key == last)
                                        }
                                    }
                                }?.value ?: newMap.values.first()
                            } else {
                                newMap.values.first()
                            }
                        } else mediaPlaylistUrl
                    } else {
                        mediaPlaylistUrl
                    }
                    offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_DOWNLOADING })
                    setForeground(createForegroundInfo(true, firstVideo))
                    val done = try {
                        download(channelLogin, url, path)
                    } finally {
                        chatReadWebSocketOkHttp?.disconnect()
                        chatFileWriter?.close()
                    }
                    if (done) {
                        if (offlineVideo.downloadChat && !offlineVideo.chatUrl.isNullOrBlank()) {
                            val chatUrl = offlineVideo.chatUrl!!
                            val isShared = chatUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT
                            if (isShared) {
                                context.contentResolver.openFileDescriptor(chatUrl.toUri(), "rw")!!.use {
                                    FileOutputStream(it.fileDescriptor).use { output ->
                                        output.channel.truncate(offlineVideo.chatBytes)
                                    }
                                }
                            } else {
                                FileOutputStream(chatUrl).use { output ->
                                    output.channel.truncate(offlineVideo.chatBytes)
                                }
                            }
                            if (isShared) {
                                context.contentResolver.openOutputStream(chatUrl.toUri(), "wa")!!.bufferedWriter()
                            } else {
                                FileOutputStream(chatUrl, true).bufferedWriter()
                            }.use { fileWriter ->
                                fileWriter.write("}")
                            }
                        }
                        offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_DOWNLOADED })
                        if (endWait == null || endWait > 0) {
                            val newId = offlineRepository.saveVideo(OfflineVideo(
                                channelId = offlineVideo.channelId,
                                channelLogin = offlineVideo.channelLogin,
                                channelName = offlineVideo.channelName,
                                channelLogo = offlineVideo.channelLogo,
                                downloadPath = offlineVideo.downloadPath,
                                status = OfflineVideo.STATUS_WAITING_FOR_STREAM,
                                quality = offlineVideo.quality,
                                downloadChat = offlineVideo.downloadChat,
                                downloadChatEmotes = offlineVideo.downloadChatEmotes,
                                live = true
                            ))
                            val newVideo = offlineRepository.getVideoById(newId.toInt())!!
                            offlineVideo = newVideo
                        }
                    } else {
                        offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_WAITING_FOR_STREAM })
                    }
                    setForeground(createForegroundInfo(false, firstVideo))
                    endTime = endWait?.let { System.currentTimeMillis() + it }
                    if (endWait == null || endWait > 0) {
                        playlistUrl = playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, proxyHost, proxyPort, proxyUser, proxyPassword, false)
                    }
                }
            }
            val currentTime = System.currentTimeMillis()
            if (endTime == null || currentTime < endTime) {
                val timeTaken = currentTime - startTime
                if (timeTaken < offlineCheck) {
                    delay(offlineCheck - timeTaken)
                }
                startTime = System.currentTimeMillis()
            } else {
                loop = false
            }
        }
        return Result.success()
    }

    private suspend fun download(channelLogin: String, sourceUrl: String, path: String): Boolean {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val liveCheck = max(context.prefs().getString(C.DOWNLOAD_STREAM_LIVE_CHECK, "2")?.toLongOrNull() ?: 2L, 2L) * 1000L
        val downloadDate = System.currentTimeMillis()
        var startTime = System.currentTimeMillis()
        var lastUrl = offlineVideo.lastSegmentUrl
        var initSegmentUri: String? = null
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
        val playlist = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine!!.get().newUrlRequestBuilder(sourceUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    try {
                        response.second.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } catch (e: Exception) {
                        return true
                    }
                } else {
                    return false
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                    cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, request.callback, cronetExecutor).build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        try {
                            (response.responseBody as ByteArray).inputStream().use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                        } catch (e: Exception) {
                            return true
                        }
                    } else {
                        return false
                    }
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        try {
                            response.second.inputStream().use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                        } catch (e: Exception) {
                            return true
                        }
                    } else {
                        return false
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        try {
                            response.body.byteStream().use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                        } catch (e: Exception) {
                            return true
                        }
                    } else {
                        return false
                    }
                }
            }
        }
        val firstUrls = if (playlist.segments.isNotEmpty()) {
            val urls = playlist.segments.takeLastWhile { it.uri != lastUrl }
            urls.lastOrNull()?.let { lastUrl = it.uri }
            val streamStartTime = urls.firstOrNull()?.programDateTime
            if (offlineVideo.downloadChat && !streamStartTime.isNullOrBlank()) {
                runBlocking {
                    launch {
                        startChatJob(channelLogin, path, downloadDate, streamStartTime)
                    }
                }
            }
            initSegmentUri = playlist.initSegmentUri
            urls.map { it.uri }
        } else {
            return false
        }
        val videoFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            val fileUri = offlineVideo.url!!
            if (isShared) {
                context.contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(offlineVideo.bytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(offlineVideo.bytes)
                }
            }
            fileUri
        } else {
            val fileName = "${offlineVideo.channelLogin ?: ""}${offlineVideo.quality ?: ""}${downloadDate}.${firstUrls.first().substringAfterLast(".").substringBefore("?")}"
            val fileUri = if (isShared) {
                val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
                val fileUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + fileName
                try {
                    context.contentResolver.openOutputStream(fileUri.toUri())!!.close()
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(context.contentResolver, directoryUri, "", fileName)
                }
                fileUri
            } else {
                "$path${File.separator}$fileName"
            }
            val initSegmentBytes = initSegmentUri?.let {
                when {
                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                            httpEngine!!.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (isShared) {
                            context.contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.use {
                                it.write(response.second)
                            }
                        } else {
                            FileOutputStream(fileUri, true).use {
                                it.write(response.second)
                            }
                        }
                        response.second.size.toLong()
                    }
                    networkLibrary == "Cronet" && cronetEngine != null -> {
                        val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                            cronetEngine!!.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                            request.future.get().responseBody as ByteArray
                        } else {
                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                cronetEngine!!.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                            }
                            response.second
                        }
                        if (isShared) {
                            context.contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.use {
                                it.write(response)
                            }
                        } else {
                            FileOutputStream(fileUri, true).use {
                                it.write(response)
                            }
                        }
                        response.size.toLong()
                    }
                    else -> {
                        okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                            if (isShared) {
                                context.contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(fileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            response.body.contentLength()
                        }
                    }
                }
            }
            offlineRepository.updateVideo(offlineVideo.apply {
                url = fileUri
                initSegmentBytes?.let { bytes += it }
            })
            if (offlineVideo.name.isNullOrBlank()) {
                runBlocking {
                    launch {
                        var attempt = 1
                        while (attempt <= 10) {
                            delay(10000L)
                            val channelId = offlineVideo.channelId
                            val stream = try {
                                graphQLRepository.loadQueryUsersStream(
                                    networkLibrary = networkLibrary,
                                    headers = TwitchApiHelper.getGQLHeaders(context),
                                    ids = channelId?.let { listOf(it) },
                                    logins = if (channelId.isNullOrBlank()) listOf(channelLogin) else null,
                                ).data!!.users?.firstOrNull()?.let {
                                    Stream(
                                        id = it.stream?.id,
                                        channelId = channelId,
                                        channelLogin = it.login,
                                        channelName = it.displayName,
                                        gameId = it.stream?.game?.id,
                                        gameSlug = it.stream?.game?.slug,
                                        gameName = it.stream?.game?.displayName,
                                        type = it.stream?.type,
                                        title = it.stream?.broadcaster?.broadcastSettings?.title,
                                        viewerCount = it.stream?.viewersCount,
                                        startedAt = it.stream?.createdAt?.toString(),
                                        thumbnailUrl = it.stream?.previewImageURL,
                                        profileImageUrl = it.profileImageURL,
                                        tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                                    )
                                }
                            } catch (e: Exception) {
                                val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
                                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                                try {
                                    helixRepository.getStreams(
                                        networkLibrary = networkLibrary,
                                        headers = helixHeaders,
                                        ids = channelId?.let { listOf(it) },
                                        logins = if (channelId.isNullOrBlank()) listOf(channelLogin) else null
                                    ).data.firstOrNull()?.let {
                                        Stream(
                                            id = it.id,
                                            channelId = it.channelId,
                                            channelLogin = it.channelLogin,
                                            channelName = it.channelName,
                                            gameId = it.gameId,
                                            gameName = it.gameName,
                                            type = it.type,
                                            title = it.title,
                                            viewerCount = it.viewerCount,
                                            startedAt = it.startedAt,
                                            thumbnailUrl = it.thumbnailUrl,
                                            tags = it.tags
                                        )
                                    }
                                } catch (e: Exception) {
                                    try {
                                        val response = graphQLRepository.loadViewerCount(
                                            networkLibrary,
                                            TwitchApiHelper.getGQLHeaders(context),
                                            channelLogin
                                        )
                                        response.data!!.user.stream?.let {
                                            Stream(
                                                id = it.id,
                                                viewerCount = it.viewersCount
                                            )
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }
                            if (stream != null) {
                                val downloadedThumbnail = stream.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                                    stream.thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                                        val filesDir = context.filesDir.path
                                        File(filesDir, "thumbnails").mkdir()
                                        val filePath = filesDir + File.separator + "thumbnails" + File.separator + id
                                        launch {
                                            try {
                                                when {
                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                            httpEngine!!.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                        }
                                                        if (response.first.httpStatusCode in 200..299) {
                                                            FileOutputStream(filePath).use {
                                                                it.write(response.second)
                                                            }
                                                        }
                                                    }
                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                            cronetEngine!!.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                                            val response = request.future.get()
                                                            if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                                                FileOutputStream(filePath).use {
                                                                    it.write(response.responseBody as ByteArray)
                                                                }
                                                            }
                                                        } else {
                                                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                                cronetEngine!!.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                            }
                                                            if (response.first.httpStatusCode in 200..299) {
                                                                FileOutputStream(filePath).use {
                                                                    it.write(response.second)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                                            if (response.isSuccessful) {
                                                                FileOutputStream(filePath).use { outputStream ->
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
                                        filePath
                                    }
                                }
                                offlineRepository.updateVideo(offlineVideo.apply {
                                    name = stream.title
                                    thumbnail = downloadedThumbnail
                                    gameId = stream.gameId
                                    gameSlug = stream.gameSlug
                                    gameName = stream.gameName
                                    uploadDate = stream.startedAt?.let { TwitchApiHelper.parseIso8601DateUTC(it) }
                                })
                                attempt += 10
                            }
                            attempt += 1
                        }
                    }
                }
            }
            fileUri
        }
        val requestSemaphore = Semaphore(context.prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        if (isShared) {
            context.contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
        } else {
            FileOutputStream(videoFileUri)
        }.use { outputStream ->
            val firstMutexMap = mutableMapOf<Int, Mutex>()
            val firstCount = MutableStateFlow(0)
            val firstJobs = runBlocking {
                firstUrls.map {
                    launch {
                        requestSemaphore.withPermit {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                        httpEngine!!.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    val mutex = Mutex()
                                    val id = firstUrls.indexOf(it)
                                    if (firstCount.value != id) {
                                        mutex.lock()
                                        firstMutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        outputStream.write(response.second)
                                        offlineRepository.updateVideo(offlineVideo.apply {
                                            bytes += response.second.size
                                            chatBytes = chatPosition
                                            lastSegmentUrl = lastUrl
                                        })
                                    }
                                }
                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                    val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                        cronetEngine!!.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                        request.future.get().responseBody as ByteArray
                                    } else {
                                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                            cronetEngine!!.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                        }
                                        response.second
                                    }
                                    val mutex = Mutex()
                                    val id = firstUrls.indexOf(it)
                                    if (firstCount.value != id) {
                                        mutex.lock()
                                        firstMutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        outputStream.write(response)
                                        offlineRepository.updateVideo(offlineVideo.apply {
                                            bytes += response.size
                                            chatBytes = chatPosition
                                            lastSegmentUrl = lastUrl
                                        })
                                    }
                                }
                                else -> {
                                    okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                        val mutex = Mutex()
                                        val id = firstUrls.indexOf(it)
                                        if (firstCount.value != id) {
                                            mutex.lock()
                                            firstMutexMap[id] = mutex
                                        }
                                        mutex.withLock {
                                            response.body.byteStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                            offlineRepository.updateVideo(offlineVideo.apply {
                                                bytes += response.body.contentLength()
                                                chatBytes = chatPosition
                                                lastSegmentUrl = lastUrl
                                            })
                                        }
                                    }
                                }
                            }
                            firstCount.update { it + 1 }
                            firstMutexMap.remove(firstCount.value)?.unlock()
                        }
                    }
                }
            }
            firstJobs.joinAll()
            while (true) {
                val playlist = when {
                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                            httpEngine!!.get().newUrlRequestBuilder(sourceUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (response.first.httpStatusCode in 200..299) {
                            try {
                                response.second.inputStream().use {
                                    PlaylistUtils.parseMediaPlaylist(it)
                                }
                            } catch (e: Exception) {
                                return true
                            }
                        } else {
                            return true
                        }
                    }
                    networkLibrary == "Cronet" && cronetEngine != null -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                            cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, request.callback, cronetExecutor).build().start()
                            val response = request.future.get()
                            if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                try {
                                    (response.responseBody as ByteArray).inputStream().use {
                                        PlaylistUtils.parseMediaPlaylist(it)
                                    }
                                } catch (e: Exception) {
                                    return true
                                }
                            } else {
                                return true
                            }
                        } else {
                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                            }
                            if (response.first.httpStatusCode in 200..299) {
                                try {
                                    response.second.inputStream().use {
                                        PlaylistUtils.parseMediaPlaylist(it)
                                    }
                                } catch (e: Exception) {
                                    return true
                                }
                            } else {
                                return true
                            }
                        }
                    }
                    else -> {
                        okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                            if (response.isSuccessful) {
                                try {
                                    response.body.byteStream().use {
                                        PlaylistUtils.parseMediaPlaylist(it)
                                    }
                                } catch (e: Exception) {
                                    return true
                                }
                            } else {
                                return true
                            }
                        }
                    }
                }
                if (playlist.segments.isNotEmpty()) {
                    val urls = playlist.segments.map { it.uri }.takeLastWhile { it != lastUrl }
                    urls.lastOrNull()?.let { lastUrl = it }
                    val mutexMap = mutableMapOf<Int, Mutex>()
                    val count = MutableStateFlow(0)
                    val jobs = runBlocking {
                        urls.map {
                            launch {
                                requestSemaphore.withPermit {
                                    when {
                                        networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                httpEngine!!.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                            }
                                            val mutex = Mutex()
                                            val id = urls.indexOf(it)
                                            if (count.value != id) {
                                                mutex.lock()
                                                mutexMap[id] = mutex
                                            }
                                            mutex.withLock {
                                                outputStream.write(response.second)
                                                offlineRepository.updateVideo(offlineVideo.apply {
                                                    bytes += response.second.size
                                                    chatBytes = chatPosition
                                                    lastSegmentUrl = lastUrl
                                                })
                                            }
                                        }
                                        networkLibrary == "Cronet" && cronetEngine != null -> {
                                            val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                cronetEngine!!.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                                request.future.get().responseBody as ByteArray
                                            } else {
                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                    cronetEngine!!.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                }
                                                response.second
                                            }
                                            val mutex = Mutex()
                                            val id = urls.indexOf(it)
                                            if (count.value != id) {
                                                mutex.lock()
                                                mutexMap[id] = mutex
                                            }
                                            mutex.withLock {
                                                outputStream.write(response)
                                                offlineRepository.updateVideo(offlineVideo.apply {
                                                    bytes += response.size
                                                    chatBytes = chatPosition
                                                    lastSegmentUrl = lastUrl
                                                })
                                            }
                                        }
                                        else -> {
                                            okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                                val mutex = Mutex()
                                                val id = urls.indexOf(it)
                                                if (count.value != id) {
                                                    mutex.lock()
                                                    mutexMap[id] = mutex
                                                }
                                                mutex.withLock {
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                    offlineRepository.updateVideo(offlineVideo.apply {
                                                        bytes += response.body.contentLength()
                                                        chatBytes = chatPosition
                                                        lastSegmentUrl = lastUrl
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    count.update { it + 1 }
                                    mutexMap.remove(count.value)?.unlock()
                                }
                            }
                        }
                    }
                    jobs.joinAll()
                    if (playlist.end) {
                        return true
                    }
                } else {
                    return true
                }
                val timeTaken = System.currentTimeMillis() - startTime
                if ((timeTaken) < liveCheck) {
                    delay(liveCheck - timeTaken)
                }
                startTime = System.currentTimeMillis()
            }
        }
        false
    }

    private suspend fun startChatJob(channelLogin: String, path: String, downloadDate: Long, streamStartTime: String) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val fileName = "${channelLogin}${offlineVideo.quality ?: ""}${downloadDate}_chat.json"
        val resumed = !offlineVideo.chatUrl.isNullOrBlank()
        val savedTwitchEmotes = mutableListOf<String>()
        val savedBadges = mutableListOf<Pair<String, String>>()
        val savedEmotes = mutableListOf<String>()
        val fileUri = if (resumed) {
            val fileUri = offlineVideo.chatUrl!!
            if (isShared) {
                context.contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(offlineVideo.chatBytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(offlineVideo.chatBytes)
                }
            }
            if (isShared) {
                context.contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
            } else {
                FileOutputStream(fileUri, true).bufferedWriter()
            }.use { fileWriter ->
                fileWriter.write("}")
            }
            if (isShared) {
                context.contentResolver.openInputStream(fileUri.toUri())?.bufferedReader()
            } else {
                FileInputStream(File(fileUri)).bufferedReader()
            }?.use { fileReader ->
                JsonReader(fileReader).use { reader ->
                    reader.isLenient = true
                    var token: JsonToken
                    do {
                        token = reader.peek()
                        when (token) {
                            JsonToken.END_DOCUMENT -> {}
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.peek()) {
                                        JsonToken.NAME -> {
                                            when (reader.nextName()) {
                                                "twitchEmotes" -> {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) {
                                                        reader.beginObject()
                                                        var id: String? = null
                                                        while (reader.hasNext()) {
                                                            when (reader.nextName()) {
                                                                "id" -> id = reader.nextString()
                                                                else -> reader.skipValue()
                                                            }
                                                        }
                                                        if (!id.isNullOrBlank()) {
                                                            savedTwitchEmotes.add(id)
                                                        }
                                                        reader.endObject()
                                                    }
                                                    reader.endArray()
                                                }
                                                "twitchBadges" -> {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) {
                                                        reader.beginObject()
                                                        var setId: String? = null
                                                        var version: String? = null
                                                        while (reader.hasNext()) {
                                                            when (reader.nextName()) {
                                                                "setId" -> setId = reader.nextString()
                                                                "version" -> version = reader.nextString()
                                                                else -> reader.skipValue()
                                                            }
                                                        }
                                                        if (!setId.isNullOrBlank() && !version.isNullOrBlank()) {
                                                            savedBadges.add(Pair(setId, version))
                                                        }
                                                        reader.endObject()
                                                    }
                                                    reader.endArray()
                                                }
                                                "cheerEmotes" -> {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) {
                                                        reader.beginObject()
                                                        var name: String? = null
                                                        while (reader.hasNext()) {
                                                            when (reader.nextName()) {
                                                                "name" -> name = reader.nextString()
                                                                else -> reader.skipValue()
                                                            }
                                                        }
                                                        if (!name.isNullOrBlank()) {
                                                            savedEmotes.add(name)
                                                        }
                                                        reader.endObject()
                                                    }
                                                    reader.endArray()
                                                }
                                                "emotes" -> {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) {
                                                        reader.beginObject()
                                                        var name: String? = null
                                                        while (reader.hasNext()) {
                                                            when (reader.nextName()) {
                                                                "name" -> name = reader.nextString()
                                                                else -> reader.skipValue()
                                                            }
                                                        }
                                                        if (!name.isNullOrBlank()) {
                                                            savedEmotes.add(name)
                                                        }
                                                        reader.endObject()
                                                    }
                                                    reader.endArray()
                                                }
                                                else -> reader.skipValue()
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    } while (token != JsonToken.END_DOCUMENT)
                }
            }
            fileUri
        } else {
            val fileUri = if (isShared) {
                val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
                val fileUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + fileName
                try {
                    context.contentResolver.openOutputStream(fileUri.toUri())!!.close()
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(context.contentResolver, directoryUri, "", fileName)
                }
                fileUri
            } else {
                "$path${File.separator}$fileName"
            }
            offlineRepository.updateVideo(offlineVideo.apply {
                chatUrl = fileUri
            })
            fileUri
        }
        val downloadEmotes = offlineVideo.downloadChatEmotes
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
        val emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val useWebp = context.prefs().getBoolean(C.CHAT_USE_WEBP, true)
        val channelId = offlineVideo.channelId
        val badgeList = mutableListOf<TwitchBadge>().apply {
            if (downloadEmotes) {
                val channelBadges = try { playerRepository.loadChannelBadges(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, emoteQuality, false) } catch (e: Exception) { emptyList() }
                addAll(channelBadges)
                val globalBadges = try { playerRepository.loadGlobalBadges(networkLibrary, helixHeaders, gqlHeaders, emoteQuality, false) } catch (e: Exception) { emptyList() }
                addAll(globalBadges.filter { badge -> badge.setId !in channelBadges.map { it.setId } })
            }
        }
        val cheerEmoteList = if (downloadEmotes) {
            try {
                playerRepository.loadCheerEmotes(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, animateGifs = true, enableIntegrity = false)
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
        val emoteList = mutableListOf<Emote>().apply {
            if (downloadEmotes) {
                if (channelId != null) {
                    try { addAll(playerRepository.loadStvEmotes(networkLibrary, channelId, useWebp).second) } catch (e: Exception) {}
                    try { addAll(playerRepository.loadBttvEmotes(networkLibrary, channelId, useWebp)) } catch (e: Exception) {}
                    try { addAll(playerRepository.loadFfzEmotes(networkLibrary, channelId, useWebp)) } catch (e: Exception) {}
                }
                try { addAll(playerRepository.loadGlobalStvEmotes(networkLibrary, useWebp)) } catch (e: Exception) {}
                try { addAll(playerRepository.loadGlobalBttvEmotes(networkLibrary, useWebp)) } catch (e: Exception) {}
                try { addAll(playerRepository.loadGlobalFfzEmotes(networkLibrary, useWebp)) } catch (e: Exception) {}
            }
        }
        chatFileWriter = if (isShared) {
            context.contentResolver.openOutputStream(fileUri.toUri(), if (resumed) "wa" else "w")!!.bufferedWriter()
        } else {
            FileOutputStream(fileUri, resumed).bufferedWriter()
        }
        chatPosition = offlineVideo.chatBytes
        JsonWriter(chatFileWriter).let { writer ->
            var position = chatPosition
            writer.beginObject().also { position += 1 }
            if (!resumed) {
                writer.name("video".also { position += it.length + 3 })
                writer.beginObject().also { position += 1 }
                offlineVideo.name?.let { value -> writer.name("title".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                offlineVideo.uploadDate?.let { value -> writer.name("uploadDate".also { position += it.length + 4 }).value(value.also { position += it.toString().length }) }
                offlineVideo.channelId?.let { value -> writer.name("channelId".also { position += it.length + 4 }).value(value.also { position += it.length + 2 }) }
                offlineVideo.channelLogin?.let { value -> writer.name("channelLogin".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                offlineVideo.channelName?.let { value -> writer.name("channelName".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                offlineVideo.gameId?.let { value -> writer.name("gameId".also { position += it.length + 4 }).value(value.also { position += it.length + 2 }) }
                offlineVideo.gameSlug?.let { value -> writer.name("gameSlug".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                offlineVideo.gameName?.let { value -> writer.name("gameName".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                writer.endObject()
                writer.name("liveStartTime".also { position += it.length + 4 }).value(streamStartTime.also { position += it.length + 2 })
            }
            chatPosition = position
            chatReadWebSocketOkHttp = ChatReadWebSocketOkHttp(false, channelLogin, okHttpClient,
                webSocketListener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        chatReadWebSocketOkHttp?.apply {
                            write("CAP REQ :twitch.tv/tags twitch.tv/commands")
                            write("NICK justinfan${Random().nextInt(((9999 - 1000) + 1)) + 1000}") //random number between 1000 and 9999
                            write("JOIN $hashChannelName")
                            pingTimer?.cancel()
                            pongTimer?.cancel()
                            startPingTimer()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val list = mutableListOf<String>()
                        text.removeSuffix("\r\n").split("\r\n").forEach {
                            it.run {
                                when {
                                    contains("PRIVMSG") -> list.add(this)
                                    contains("USERNOTICE") -> list.add(this)
                                    contains("CLEARMSG") -> list.add(this)
                                    contains("CLEARCHAT") -> list.add(this)
                                    contains("NOTICE") -> {}
                                    contains("ROOMSTATE") -> {}
                                    startsWith("PING") -> chatReadWebSocketOkHttp?.write("PONG")
                                    startsWith("PONG") -> {
                                        chatReadWebSocketOkHttp?.apply {
                                            pingTimer?.cancel()
                                            pongTimer?.cancel()
                                            startPingTimer()
                                        }
                                    }
                                    startsWith("RECONNECT") -> {
                                        chatReadWebSocketOkHttp?.apply {
                                            pingTimer?.cancel()
                                            pongTimer?.cancel()
                                            reconnect()
                                        }
                                    }
                                }
                            }
                        }
                        if (list.isNotEmpty()) {
                            writer.name("liveComments".also { position += it.length + 4 })
                            writer.beginArray().also { position += 1 }
                            list.forEach { message ->
                                writer.value(message.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 3 })
                            }
                            writer.endArray()
                            if (downloadEmotes) {
                                list.forEach { message ->
                                    val userNotice = when {
                                        message.contains("PRIVMSG") -> false
                                        message.contains("USERNOTICE") -> true
                                        else -> null
                                    }
                                    if (userNotice != null) {
                                        val chatMessage = ChatUtils.parseChatMessage(message, userNotice)
                                        val twitchEmotes = mutableListOf<TwitchEmote>()
                                        val twitchBadges = mutableListOf<TwitchBadge>()
                                        val cheerEmotes = mutableListOf<CheerEmote>()
                                        val emotes = mutableListOf<Emote>()
                                        chatMessage.emotes?.forEach {
                                            if (it.id != null && !savedTwitchEmotes.contains(it.id)) {
                                                savedTwitchEmotes.add(it.id)
                                                twitchEmotes.add(it)
                                            }
                                        }
                                        chatMessage.badges?.forEach {
                                            val pair = Pair(it.setId, it.version)
                                            if (!savedBadges.contains(pair)) {
                                                savedBadges.add(pair)
                                                val badge = badgeList.find { badge -> badge.setId == it.setId && badge.version == it.version }
                                                if (badge != null) {
                                                    twitchBadges.add(badge)
                                                }
                                            }
                                        }
                                        chatMessage.message?.split(" ")?.forEach { word ->
                                            if (!savedEmotes.contains(word)) {
                                                val cheerEmote = if (chatMessage.bits != null) {
                                                    val bitsCount = word.takeLastWhile { it.isDigit() }
                                                    val bitsName = word.substringBeforeLast(bitsCount)
                                                    if (bitsCount.isNotEmpty()) {
                                                        cheerEmoteList.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
                                                    } else null
                                                } else null
                                                if (cheerEmote != null) {
                                                    savedEmotes.add(word)
                                                    cheerEmotes.add(cheerEmote)
                                                } else {
                                                    val emote = emoteList.find { it.name == word }
                                                    if (emote != null) {
                                                        savedEmotes.add(word)
                                                        emotes.add(emote)
                                                    }
                                                }
                                            }
                                        }
                                        if (twitchEmotes.isNotEmpty()) {
                                            writer.name("twitchEmotes".also { position += it.length + 4 })
                                            writer.beginArray().also { position += 1 }
                                            val last = twitchEmotes.lastOrNull()
                                            twitchEmotes.forEach { emote ->
                                                val url = when (emoteQuality) {
                                                    "4" -> emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x
                                                    "3" -> emote.url3x ?: emote.url2x ?: emote.url1x
                                                    "2" -> emote.url2x ?: emote.url1x
                                                    else -> emote.url1x
                                                }!!
                                                val response = when {
                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                        runBlocking {
                                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                                httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                            }
                                                            response.second
                                                        }
                                                    }
                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                            cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                            request.future.get().responseBody as ByteArray
                                                        } else {
                                                            runBlocking {
                                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                                    cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                                }
                                                                response.second
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                            response.body.source().readByteArray()
                                                        }
                                                    }
                                                }
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("id".also { position += it.length + 4 }).value(emote.id.also { position += it.toString().toByteArray().size + it.toString().count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.endObject().also { position += 1 }
                                                if (emote != last) {
                                                    position += 1
                                                }
                                            }
                                            writer.endArray().also { position += 1 }
                                        }
                                        if (twitchBadges.isNotEmpty()) {
                                            writer.name("twitchBadges".also { position += it.length + 4 })
                                            writer.beginArray().also { position += 1 }
                                            val last = twitchBadges.lastOrNull()
                                            twitchBadges.forEach { badge ->
                                                val url = when (emoteQuality) {
                                                    "4" -> badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x
                                                    "3" -> badge.url3x ?: badge.url2x ?: badge.url1x
                                                    "2" -> badge.url2x ?: badge.url1x
                                                    else -> badge.url1x
                                                }!!
                                                val response = when {
                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                        runBlocking {
                                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                                httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                            }
                                                            response.second
                                                        }
                                                    }
                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                            cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                            request.future.get().responseBody as ByteArray
                                                        } else {
                                                            runBlocking {
                                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                                    cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                                }
                                                                response.second
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                            response.body.source().readByteArray()
                                                        }
                                                    }
                                                }
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("setId".also { position += it.length + 4 }).value(badge.setId.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("version".also { position += it.length + 4 }).value(badge.version.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.endObject().also { position += 1 }
                                                if (badge != last) {
                                                    position += 1
                                                }
                                            }
                                            writer.endArray().also { position += 1 }
                                        }
                                        if (cheerEmotes.isNotEmpty()) {
                                            writer.name("cheerEmotes".also { position += it.length + 4 })
                                            writer.beginArray().also { position += 1 }
                                            val last = cheerEmotes.lastOrNull()
                                            cheerEmotes.forEach { cheerEmote ->
                                                val url = when (emoteQuality) {
                                                    "4" -> cheerEmote.url4x ?: cheerEmote.url3x ?: cheerEmote.url2x ?: cheerEmote.url1x
                                                    "3" -> cheerEmote.url3x ?: cheerEmote.url2x ?: cheerEmote.url1x
                                                    "2" -> cheerEmote.url2x ?: cheerEmote.url1x
                                                    else -> cheerEmote.url1x
                                                }!!
                                                val response = when {
                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                        runBlocking {
                                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                                httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                            }
                                                            response.second
                                                        }
                                                    }
                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                            cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                            request.future.get().responseBody as ByteArray
                                                        } else {
                                                            runBlocking {
                                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                                    cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                                }
                                                                response.second
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                            response.body.source().readByteArray()
                                                        }
                                                    }
                                                }
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("name".also { position += it.length + 4 }).value(cheerEmote.name.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("minBits".also { position += it.length + 4 }).value(cheerEmote.minBits.also { position += it.toString().length })
                                                cheerEmote.color?.let { value -> writer.name("color".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                                                writer.endObject().also { position += 1 }
                                                if (cheerEmote != last) {
                                                    position += 1
                                                }
                                            }
                                            writer.endArray().also { position += 1 }
                                        }
                                        if (emotes.isNotEmpty()) {
                                            writer.name("emotes".also { position += it.length + 4 })
                                            writer.beginArray().also { position += 1 }
                                            val last = emotes.lastOrNull()
                                            emotes.forEach { emote ->
                                                val url = when (emoteQuality) {
                                                    "4" -> emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x
                                                    "3" -> emote.url3x ?: emote.url2x ?: emote.url1x
                                                    "2" -> emote.url2x ?: emote.url1x
                                                    else -> emote.url1x
                                                }!!
                                                val response = when {
                                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                        runBlocking {
                                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                                httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                            }
                                                            response.second
                                                        }
                                                    }
                                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                            cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                            request.future.get().responseBody as ByteArray
                                                        } else {
                                                            runBlocking {
                                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                                    cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                                }
                                                                response.second
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                            response.body.source().readByteArray()
                                                        }
                                                    }
                                                }
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("name".also { position += it.length + 4 }).value(emote.name.also { position += it.toString().toByteArray().size + it.toString().count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("isZeroWidth".also { position += it.length + 4 }).value(emote.isOverlayEmote.also { position += it.toString().length })
                                                writer.endObject().also { position += 1 }
                                                if (emote != last) {
                                                    position += 1
                                                }
                                            }
                                            writer.endArray().also { position += 1 }
                                        }
                                    }
                                }
                            }
                            chatPosition = position
                        }
                    }
                }
            ).apply { connect() }
        }
    }

    private fun createForegroundInfo(live: Boolean, offlineVideo: OfflineVideo): ForegroundInfo {
        val channelId = context.getString(R.string.notification_downloads_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                NotificationChannel(channelId, ContextCompat.getString(context, R.string.notification_downloads_channel_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    notificationManager.createNotificationChannel(this)
                }
            }
        }
        val notification = NotificationCompat.Builder(context, channelId).apply {
            setGroup(GROUP_KEY)
            setContentTitle(ContextCompat.getString(context, if (live) R.string.downloading else R.string.download_waiting_for_stream))
            setContentText(offlineVideo.channelName)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    offlineVideo.id,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            addAction(android.R.drawable.ic_delete, ContextCompat.getString(context, R.string.stop), WorkManager.getInstance(context).createCancelPendingIntent(id))
        }.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(offlineVideo.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(offlineVideo.id, notification)
        }
    }

    companion object {
        const val GROUP_KEY = "com.github.andreyasadchy.xtra.DOWNLOADS"

        const val KEY_VIDEO_ID = "KEY_VIDEO_ID"
    }
}
