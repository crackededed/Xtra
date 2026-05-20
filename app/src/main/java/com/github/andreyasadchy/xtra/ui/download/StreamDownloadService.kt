package com.github.andreyasadchy.xtra.ui.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ext.SdkExtensions
import android.provider.DocumentsContract
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.ui.DownloadProgress
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

class StreamDownloadService : LifecycleService() {

    lateinit var xtraModule: XtraModule
    private val okHttpClient = lazy {
        xtraModule.okHttpClient.value.newBuilder().apply {
            connectTimeout(5, TimeUnit.MINUTES)
            writeTimeout(5, TimeUnit.MINUTES)
            readTimeout(5, TimeUnit.MINUTES)
        }.build()
    }

    private var notificationManager: NotificationManager? = null
    private val downloadJobs = mutableListOf<DownloadJob>()
    private val offlineVideos = mutableListOf<OfflineVideo>()
    val activeDownloads = mutableListOf<DownloadProgress>()
    var listener: Listener? = null

    class DownloadJob(
        var id: Int,
        var job: Job? = null,
        var chatReadWebSocket: ChatReadWebSocket? = null,
    )

    interface Listener {
        fun unbind()
    }

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
    }

    private fun start(videoId: Int) {
        if (activeDownloads.find { it.id == videoId } == null) {
            val downloadJob = DownloadJob(videoId)
            lifecycleScope.launch(Dispatchers.IO) {
                val offlineVideo = xtraModule.offlineVideosRepository.getById(videoId)
                if (offlineVideo != null) {
                    val downloadProgress = DownloadProgress(
                        id = videoId,
                        bytes = offlineVideo.bytes,
                        chatBytes = offlineVideo.chatBytes,
                        lastSegmentUrl = offlineVideo.lastSegmentUrl,
                        liveCommentsArrayStarted = offlineVideo.liveCommentsArrayStarted,
                    )
                    offlineVideos.add(offlineVideo)
                    activeDownloads.add(downloadProgress)
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = OfflineVideo.STATUS_WAITING_FOR_STREAM
                    })
                    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = getString(R.string.notification_downloads_channel_id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                        notificationManager?.createNotificationChannel(
                            NotificationChannel(
                                channelId,
                                ContextCompat.getString(this@StreamDownloadService, R.string.notification_downloads_channel_title),
                                NotificationManager.IMPORTANCE_DEFAULT
                            ).apply {
                                setSound(null, null)
                            }
                        )
                    }
                    sendNotification(offlineVideo, downloadProgress)
                    val done = try {
                        val channelLogin = offlineVideo.channelLogin!!
                        downloadStream(offlineVideo, downloadProgress, downloadJob, channelLogin)
                        true
                    } catch (e: CancellationException) {
                        ensureActive()
                        false
                    } catch (e: Exception) {
                        Log.e("StreamDownloadService", "Download failed", e)
                        false
                    }
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = if (done) {
                            OfflineVideo.STATUS_DOWNLOADED
                        } else {
                            val waitForWifi = if (prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            } else false
                            if (waitForWifi) {
                                OfflineVideo.STATUS_WAITING_FOR_WIFI
                            } else {
                                OfflineVideo.STATUS_PENDING
                            }
                        }
                        bytes = downloadProgress.bytes
                        chatBytes = downloadProgress.chatBytes
                        lastSegmentUrl = downloadProgress.lastSegmentUrl
                        liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
                    })
                    val nextOfflineVideo = offlineVideos.firstOrNull()
                    val nextDownload = activeDownloads.firstOrNull()
                    if (nextOfflineVideo != null && nextDownload != null) {
                        sendNotification(nextOfflineVideo, nextDownload)
                        notificationManager?.cancel(videoId)
                    } else {
                        listener?.unbind()
                        stopSelf()
                    }
                }
            }.also {
                it.invokeOnCompletion {
                    downloadJobs.remove(downloadJob)
                }
                downloadJob.job = it
                downloadJobs.add(downloadJob)
            }
        }
    }

    private suspend fun downloadStream(currentOfflineVideo: OfflineVideo, currentDownloadProgress: DownloadProgress, downloadJob: DownloadJob, channelLogin: String) = withContext(Dispatchers.IO) {
        val offlineCheck = max(prefs().getString(C.DOWNLOAD_STREAM_OFFLINE_CHECK, "10")?.toLongOrNull() ?: 10L, 2L) * 1000L
        val startWait = (prefs().getString(C.DOWNLOAD_STREAM_START_WAIT, "120")?.toLongOrNull())?.times(60000L)
        val endWait = (prefs().getString(C.DOWNLOAD_STREAM_END_WAIT, "15")?.toLongOrNull())?.times(60000L)
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(this@StreamDownloadService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true))
        val randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true)
        val xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason")
        val playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site")
        val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
        val proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false)
        val proxyMultivariantPlaylist = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false)
        val proxyHost = prefs().getString(C.PROXY_HOST, null)
        val proxyPort = prefs().getString(C.PROXY_PORT, null)?.toIntOrNull()
        val proxyUser = prefs().getString(C.PROXY_USER, null)
        val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
        var offlineVideo = currentOfflineVideo
        var downloadProgress = currentDownloadProgress
        val path = offlineVideo.downloadPath!!
        val quality = offlineVideo.quality
        var startTime = System.currentTimeMillis()
        var endTime = startWait?.let { System.currentTimeMillis() + it }
        var playlistUrl = xtraModule.playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, null, null, null, null, false)
        while (true) {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(playlistUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(playlistUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.string()
                        } else null
                    }
                }
            }
            if (!playlist.isNullOrBlank()) {
                val qualities = if ((proxyPlaybackAccessToken || proxyMultivariantPlaylist) && !proxyHost.isNullOrBlank() && proxyPort != null) {
                    val newPlaylist = proxyPlaylist(playlistUrl, networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, proxyPlaybackAccessToken, proxyMultivariantPlaylist, proxyHost, proxyPort, proxyUser, proxyPassword)
                    newPlaylist.takeIf { !it.isNullOrBlank() }?.let { getQualities(it).takeIf { it.isNotEmpty() } } ?: getQualities(playlist)
                } else {
                    getQualities(playlist)
                }
                if (qualities.isNotEmpty()) {
                    val selectedQuality = if (!quality.isNullOrBlank()) {
                        val audio = if (quality.startsWith("audio", true)) {
                            qualities.find { it.name == "audio_only" }
                        } else null
                        if (audio != null) {
                            audio
                        } else {
                            val targetQuality = quality.split("p")
                            targetQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
                                val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                val last = qualities.last { it.name != "audio_only" }
                                qualities.find { qualityString ->
                                    val quality = qualityString.name?.split("p")
                                    val resolution = quality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                    val fps = quality?.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                    resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || qualityString == last)
                                }
                            } ?: qualities.first()
                        }
                    } else qualities.first()
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = OfflineVideo.STATUS_DOWNLOADING
                    })
                    downloadProgress.isLive = true
                    sendNotification(offlineVideo, downloadProgress)
                    val done = try {
                        download(offlineVideo, downloadProgress, downloadJob, channelLogin, selectedQuality.url!!, path, networkLibrary)
                        true
                    } catch (e: CancellationException) {
                        ensureActive()
                        false
                    } catch (e: Exception) {
                        Log.e("StreamDownloadService", "Download failed", e)
                        false
                    } finally {
                        MainScope().launch(Dispatchers.IO) {
                            downloadJob.chatReadWebSocket?.disconnect(null)
                        }
                    }
                    val waitForWifi = if (prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    } else false
                    if (waitForWifi) {
                        throw Exception()
                    }
                    val continueDownloading = endWait == null || endWait > 0
                    if (done) {
                        if (offlineVideo.downloadChat && !offlineVideo.chatUrl.isNullOrBlank()) {
                            val chatUrl = offlineVideo.chatUrl!!
                            val isShared = chatUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT
                            if (isShared) {
                                contentResolver.openFileDescriptor(chatUrl.toUri(), "rw")!!.use {
                                    FileOutputStream(it.fileDescriptor).use { output ->
                                        output.channel.truncate(downloadProgress.chatBytes)
                                    }
                                }
                            } else {
                                FileOutputStream(chatUrl).use { output ->
                                    output.channel.truncate(downloadProgress.chatBytes)
                                }
                            }
                            if (isShared) {
                                contentResolver.openOutputStream(chatUrl.toUri(), "wa")!!.bufferedWriter()
                            } else {
                                FileOutputStream(chatUrl, true).bufferedWriter()
                            }.use { fileWriter ->
                                if (downloadProgress.liveCommentsArrayStarted) {
                                    fileWriter.write("]")
                                }
                                fileWriter.write("}")
                            }
                        }
                        if (continueDownloading) {
                            offlineVideos.remove(offlineVideo)
                            activeDownloads.remove(downloadProgress)
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_DOWNLOADED
                            })
                            val oldId = offlineVideo.id
                            val newId = xtraModule.offlineVideosRepository.save(OfflineVideo(
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
                            )).toInt()
                            val newVideo = xtraModule.offlineVideosRepository.getById(newId)!!
                            val newDownloadProgress = DownloadProgress(
                                id = newId,
                                bytes = offlineVideo.bytes,
                                chatBytes = offlineVideo.chatBytes,
                                lastSegmentUrl = offlineVideo.lastSegmentUrl,
                                liveCommentsArrayStarted = offlineVideo.liveCommentsArrayStarted,
                            )
                            offlineVideo = newVideo
                            downloadProgress = newDownloadProgress
                            downloadJob.id = newId
                            offlineVideos.add(offlineVideo)
                            activeDownloads.add(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
                            notificationManager?.cancel(oldId)
                        }
                    } else {
                        if (continueDownloading) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_WAITING_FOR_STREAM
                            })
                        }
                    }
                    endTime = endWait?.let { System.currentTimeMillis() + it }
                    if (continueDownloading) {
                        playlistUrl = xtraModule.playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, null, null, null, null, false)
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
                break
            }
        }
    }

    private suspend fun proxyPlaylist(playlistUrl: String, networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyMultivariantPlaylist: Boolean, proxyHost: String, proxyPort: Int, proxyUser: String?, proxyPassword: String?): String? = withContext(Dispatchers.IO) {
        val playlistUrl = if (proxyPlaybackAccessToken) {
            xtraModule.playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, true, proxyHost, proxyPort, proxyUser, proxyPassword, false)
        } else {
            playlistUrl
        }
        if (proxyMultivariantPlaylist) {
            okHttpClient.value.newBuilder().apply {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                    }
                }
            }.build().newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else null
            }
        } else {
            when {
                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(playlistUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(playlistUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.string()
                        } else null
                    }
                }
            }
        }
    }

    private suspend fun getQualities(playlist: String): List<VideoQuality> = withContext(Dispatchers.IO) {
        val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
        val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
        val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
        val list = names.mapIndexedNotNull { index, name ->
            urls.getOrNull(index)?.let { url ->
                VideoQuality(name, codecs.getOrNull(index), url)
            }
        }
        list
            .sortedByDescending {
                it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
            }
            .sortedByDescending {
                it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
            }
            .toMutableList().apply {
                find { it.name.equals("source", true) }?.let { source ->
                    remove(source)
                    add(0, VideoQuality("source", source.codecs, source.url))
                }
                find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                    remove(audio)
                    add(VideoQuality("audio_only", audio.codecs, audio.url))
                }
            }
    }

    private suspend fun download(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, downloadJob: DownloadJob, channelLogin: String, sourceUrl: String, path: String, networkLibrary: String?) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val liveCheck = max(prefs().getString(C.DOWNLOAD_STREAM_LIVE_CHECK, "2")?.toLongOrNull() ?: 2L, 2L) * 1000L
        val downloadDate = System.currentTimeMillis()
        var startTime = System.currentTimeMillis()
        var lastUrl = downloadProgress.lastSegmentUrl
        var initSegmentUri: String?
        val playlist = when {
            networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    xtraModule.httpEngine.value!!.newUrlRequestBuilder(sourceUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    response.second.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                } else {
                    return@withContext
                }
            }
            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    xtraModule.cronetEngine.value!!.newUrlRequestBuilder(sourceUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    response.second.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                } else {
                    return@withContext
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
            }
        }
        val firstUrls = if (playlist.segments.isNotEmpty()) {
            val urls = playlist.segments.takeLastWhile { it.uri != lastUrl }
            urls.lastOrNull()?.let { lastUrl = it.uri }
            val streamStartTime = urls.firstOrNull()?.programDateTime
            if (offlineVideo.downloadChat && !streamStartTime.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    startChatJob(offlineVideo, downloadProgress, downloadJob, channelLogin, path, downloadDate, streamStartTime, networkLibrary)
                }
            }
            initSegmentUri = playlist.initSegmentUri
            urls.map { it.uri }
        } else {
            return@withContext
        }
        val videoFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            val fileUri = offlineVideo.url!!
            if (isShared) {
                contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(downloadProgress.bytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(downloadProgress.bytes)
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
                    contentResolver.openOutputStream(fileUri.toUri())!!.close()
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, directoryUri, "", fileName)
                }
                fileUri
            } else {
                "$path${File.separator}$fileName"
            }
            val initSegmentBytes = initSegmentUri?.let {
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(it, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                        } else {
                            FileOutputStream(fileUri, true)
                        }.use {
                            it.write(response.second)
                        }
                        response.second.size.toLong()
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(it, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                        } else {
                            FileOutputStream(fileUri, true)
                        }.use {
                            it.write(response.second)
                        }
                        response.second.size.toLong()
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(it).build()).executeAsync().use { response ->
                            if (isShared) {
                                contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
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
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = fileUri
                initSegmentBytes?.let {
                    bytes += it
                    downloadProgress.bytes += it
                }
            })
            if (offlineVideo.name.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    updateStreamInfo(offlineVideo, channelLogin, networkLibrary)
                }
            }
            fileUri
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val firstJobs = firstUrls.mapIndexed { index, url ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        val mutex = Mutex()
                        if (count.value != index) {
                            mutex.lock()
                            mutexMap[index] = mutex
                        }
                        mutex.withLock {
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(videoFileUri, true)
                            }.use {
                                it.write(response.second)
                            }
                            downloadProgress.bytes += response.second.size
                            downloadProgress.lastSegmentUrl += lastUrl
                        }
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                        }
                        val mutex = Mutex()
                        if (count.value != index) {
                            mutex.lock()
                            mutexMap[index] = mutex
                        }
                        mutex.withLock {
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(videoFileUri, true)
                            }.use {
                                it.write(response.second)
                            }
                            downloadProgress.bytes += response.second.size
                            downloadProgress.lastSegmentUrl += lastUrl
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            val mutex = Mutex()
                            if (count.value != index) {
                                mutex.lock()
                                mutexMap[index] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                } else {
                                    FileOutputStream(videoFileUri)
                                }.use { outputStream ->
                                    response.body.byteStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                    downloadProgress.bytes += response.body.contentLength()
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                        }
                    }
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - downloadProgress.lastSaved >= 5000L) {
                    downloadProgress.lastSaved = currentTime
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        bytes = downloadProgress.bytes
                        chatBytes = downloadProgress.chatBytes
                        lastSegmentUrl = downloadProgress.lastSegmentUrl
                        liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
                    })
                }
                count.update { it + 1 }
                mutexMap.remove(count.value)?.unlock()
            }.also {
                it.invokeOnCompletion {
                    requestSemaphore.release()
                }
            }
        }
        firstJobs.joinAll()
        while (true) {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(sourceUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        response.second.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(sourceUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        response.second.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.byteStream().use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                        } else {
                            return@withContext
                        }
                    }
                }
            }
            if (playlist.segments.isNotEmpty()) {
                val urls = playlist.segments.map { it.uri }.takeLastWhile { it != lastUrl }
                urls.lastOrNull()?.let { lastUrl = it }
                val mutexMap = mutableMapOf<Int, Mutex>()
                val count = MutableStateFlow(0)
                val jobs = urls.mapIndexed { index, url ->
                    requestSemaphore.acquire()
                    launch(Dispatchers.IO) {
                        when {
                            networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                }
                                val mutex = Mutex()
                                if (count.value != index) {
                                    mutex.lock()
                                    mutexMap[index] = mutex
                                }
                                mutex.withLock {
                                    if (isShared) {
                                        contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                    } else {
                                        FileOutputStream(videoFileUri, true)
                                    }.use {
                                        it.write(response.second)
                                    }
                                    downloadProgress.bytes += response.second.size
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                }
                                val mutex = Mutex()
                                if (count.value != index) {
                                    mutex.lock()
                                    mutexMap[index] = mutex
                                }
                                mutex.withLock {
                                    if (isShared) {
                                        contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                    } else {
                                        FileOutputStream(videoFileUri, true)
                                    }.use {
                                        it.write(response.second)
                                    }
                                    downloadProgress.bytes += response.second.size
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                            else -> {
                                okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                    val mutex = Mutex()
                                    if (count.value != index) {
                                        mutex.lock()
                                        mutexMap[index] = mutex
                                    }
                                    mutex.withLock {
                                        if (isShared) {
                                            contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                        } else {
                                            FileOutputStream(videoFileUri)
                                        }.use { outputStream ->
                                            response.body.byteStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                            downloadProgress.bytes += response.body.contentLength()
                                            downloadProgress.lastSegmentUrl += lastUrl
                                        }
                                    }
                                }
                            }
                        }
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - downloadProgress.lastSaved >= 5000L) {
                            downloadProgress.lastSaved = currentTime
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                bytes = downloadProgress.bytes
                                chatBytes = downloadProgress.chatBytes
                                lastSegmentUrl = downloadProgress.lastSegmentUrl
                                liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
                            })
                        }
                        count.update { it + 1 }
                        mutexMap.remove(count.value)?.unlock()
                    }.also {
                        it.invokeOnCompletion {
                            requestSemaphore.release()
                        }
                    }
                }
                jobs.joinAll()
                if (playlist.end) {
                    return@withContext
                }
            } else {
                return@withContext
            }
            val timeTaken = System.currentTimeMillis() - startTime
            if ((timeTaken) < liveCheck) {
                delay(liveCheck - timeTaken)
            }
            startTime = System.currentTimeMillis()
        }
    }

    private suspend fun updateStreamInfo(offlineVideo: OfflineVideo, channelLogin: String, networkLibrary: String?) = withContext(Dispatchers.IO) {
        var attempt = 1
        while (attempt <= 10) {
            delay(10000L)
            val channelId = offlineVideo.channelId
            val stream = try {
                xtraModule.graphQLRepository.loadQueryUsersStream(
                    networkLibrary = networkLibrary,
                    headers = TwitchApiHelper.getGQLHeaders(this@StreamDownloadService),
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) listOf(channelLogin) else null,
                ).data!!.users?.firstOrNull()?.let {
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
                val helixHeaders = TwitchApiHelper.getHelixHeaders(this@StreamDownloadService)
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                try {
                    xtraModule.helixRepository.getStreams(
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
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.startedAt,
                            viewerCount = it.viewerCount,
                            tags = it.tags,
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (stream != null) {
                val downloadedThumbnail = stream.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                    stream.thumbnail.takeIf { !it.isNullOrBlank() }?.let {
                        val filesDir = filesDir.path
                        File(filesDir, "thumbnails").mkdir()
                        val filePath = filesDir + File.separator + "thumbnails" + File.separator + id
                        launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(it, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(filePath).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(it, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(filePath).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(it).build()).executeAsync().use { response ->
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
                xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                    name = stream.title
                    thumbnail = downloadedThumbnail
                    gameId = stream.gameId
                    gameSlug = stream.gameSlug
                    gameName = stream.gameName
                    uploadDate = stream.createdAt?.let { TwitchApiHelper.parseIso8601DateUTC(it) }
                })
                break
            }
            attempt += 1
        }
    }

    private suspend fun startChatJob(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, downloadJob: DownloadJob, channelLogin: String, path: String, downloadDate: Long, streamStartTime: String, networkLibrary: String?) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val resumed = !offlineVideo.chatUrl.isNullOrBlank() && downloadProgress.chatBytes > 0
        val fileUri = if (resumed) {
            offlineVideo.chatUrl!!
        } else {
            val fileName = "${channelLogin}${offlineVideo.quality ?: ""}${downloadDate}_chat.json"
            val fileUri = if (isShared) {
                val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
                val fileUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + fileName
                try {
                    contentResolver.openOutputStream(fileUri.toUri())!!.close()
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, directoryUri, "", fileName)
                }
                fileUri
            } else {
                "$path${File.separator}$fileName"
            }
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                chatUrl = fileUri
            })
            fileUri
        }
        val downloadEmotes = offlineVideo.downloadChatEmotes
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(this@StreamDownloadService, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(this@StreamDownloadService)
        val emoteQuality = prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val useWebp = prefs().getBoolean(C.CHAT_USE_WEBP, true)
        val channelId = offlineVideo.channelId
        val globalBadgeList = mutableListOf<TwitchBadge>()
        val channelBadgeList = mutableListOf<TwitchBadge>()
        val cheerEmoteList = mutableListOf<CheerEmote>()
        val emoteList = mutableListOf<Emote>()
        if (downloadEmotes) {
            val jobs = mutableListOf<Job>().apply {
                add(launch(Dispatchers.IO) {
                    try {
                        val badges = xtraModule.playerRepository.loadGlobalBadges(networkLibrary, helixHeaders, gqlHeaders, emoteQuality, false)
                        globalBadgeList.addAll(badges)
                    } catch (e: Exception) {

                    }
                })
                add(launch(Dispatchers.IO) {
                    try {
                        val emotes = xtraModule.playerRepository.loadGlobalSTVEmotes(networkLibrary, useWebp)
                        emoteList.addAll(emotes)
                        emoteList.sortBy { it.source }
                    } catch (e: Exception) {

                    }
                })
                add(launch(Dispatchers.IO) {
                    try {
                        val emotes = xtraModule.playerRepository.loadGlobalBTTVEmotes(networkLibrary, useWebp)
                        emoteList.addAll(emotes)
                        emoteList.sortBy { it.source }
                    } catch (e: Exception) {

                    }
                })
                add(launch(Dispatchers.IO) {
                    try {
                        val emotes = xtraModule.playerRepository.loadGlobalFFZEmotes(networkLibrary, useWebp)
                        emoteList.addAll(emotes)
                        emoteList.sortBy { it.source }
                    } catch (e: Exception) {

                    }
                })
                if (channelId != null) {
                    add(launch(Dispatchers.IO) {
                        try {
                            val emotes = xtraModule.playerRepository.loadSTVEmotes(networkLibrary, channelId, useWebp).second
                            emoteList.addAll(emotes)
                            emoteList.sortBy { it.source }
                        } catch (e: Exception) {

                        }
                    })
                    add(launch(Dispatchers.IO) {
                        try {
                            val emotes = xtraModule.playerRepository.loadBTTVEmotes(networkLibrary, channelId, useWebp)
                            emoteList.addAll(emotes)
                            emoteList.sortBy { it.source }
                        } catch (e: Exception) {

                        }
                    })
                    add(launch(Dispatchers.IO) {
                        try {
                            val emotes = xtraModule.playerRepository.loadFFZEmotes(networkLibrary, channelId, useWebp)
                            emoteList.addAll(emotes)
                            emoteList.sortBy { it.source }
                        } catch (e: Exception) {

                        }
                    })
                    add(launch(Dispatchers.IO) {
                        try {
                            val badges = xtraModule.playerRepository.loadChannelBadges(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, emoteQuality, false)
                            channelBadgeList.addAll(badges)
                        } catch (e: Exception) {

                        }
                    })
                    add(launch(Dispatchers.IO) {
                        try {
                            val emotes = xtraModule.playerRepository.loadCheerEmotes(networkLibrary, helixHeaders, gqlHeaders, channelId, channelLogin, animateGifs = true, enableIntegrity = false)
                            cheerEmoteList.addAll(emotes)
                        } catch (e: Exception) {

                        }
                    })
                }
            }
            jobs.joinAll()
        }
        var position = downloadProgress.chatBytes
        val savedTwitchEmotes = mutableListOf<String>()
        val savedBadges = mutableListOf<Pair<String, String>>()
        val savedEmotes = mutableListOf<String>()
        if (resumed) {
            if (isShared) {
                contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(downloadProgress.chatBytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(downloadProgress.chatBytes)
                }
            }
            if (isShared) {
                contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
            } else {
                FileOutputStream(fileUri, true).bufferedWriter()
            }.use { writer ->
                if (downloadProgress.liveCommentsArrayStarted) {
                    writer.write("]")
                }
                writer.write("}")
            }
            if (isShared) {
                contentResolver.openInputStream(fileUri.toUri())?.bufferedReader()
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
            if (isShared) {
                contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(downloadProgress.chatBytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(downloadProgress.chatBytes)
                }
            }
        } else {
            if (isShared) {
                contentResolver.openOutputStream(fileUri.toUri())!!.bufferedWriter()
            } else {
                FileOutputStream(fileUri).bufferedWriter()
            }.use { writer ->
                writer.write("{".also { position += 1 })
                writer.write("\"video\":".also { position += it.length })
                writer.write(
                    buildJsonObject {
                        offlineVideo.name?.let { put("title", it) }
                        offlineVideo.uploadDate?.let { put("uploadDate", it) }
                        offlineVideo.channelId?.let { put("channelId", it) }
                        offlineVideo.channelLogin?.let { put("channelLogin", it) }
                        offlineVideo.channelName?.let { put("channelName", it) }
                        offlineVideo.gameId?.let { put("gameId", it) }
                        offlineVideo.gameSlug?.let { put("gameSlug", it) }
                        offlineVideo.gameName?.let { put("gameName", it) }
                    }.toString().also { position += it.toByteArray().size }
                )
                writer.write(",".also { position += 1 })
                writer.write("\"liveStartTime\":\"$streamStartTime\"".also { position += it.length })
            }
        }
        downloadProgress.chatBytes = position
        downloadJob.chatReadWebSocket = ChatReadWebSocket(
            channelLogin = channelLogin,
            trustManager = xtraModule.trustManager,
            listener = object : ChatReadWebSocket.Listener {
                override suspend fun onChatMessage(message: String, userNotice: Boolean) {
                    saveMessage(offlineVideo, downloadProgress, message, isShared, fileUri, downloadEmotes, networkLibrary, emoteQuality, savedTwitchEmotes, savedBadges, savedEmotes, globalBadgeList, channelBadgeList, cheerEmoteList, emoteList)
                }

                override suspend fun onClearMessage(message: String) {
                    saveMessage(offlineVideo, downloadProgress, message, isShared, fileUri, downloadEmotes, networkLibrary, emoteQuality, savedTwitchEmotes, savedBadges, savedEmotes, globalBadgeList, channelBadgeList, cheerEmoteList, emoteList)
                }

                override suspend fun onClearChat(message: String) {
                    saveMessage(offlineVideo, downloadProgress, message, isShared, fileUri, downloadEmotes, networkLibrary, emoteQuality, savedTwitchEmotes, savedBadges, savedEmotes, globalBadgeList, channelBadgeList, cheerEmoteList, emoteList)
                }

                override suspend fun onNotice(message: String) {
                    saveMessage(offlineVideo, downloadProgress, message, isShared, fileUri, downloadEmotes, networkLibrary, emoteQuality, savedTwitchEmotes, savedBadges, savedEmotes, globalBadgeList, channelBadgeList, cheerEmoteList, emoteList)
                }
            }
        ).apply { connect(this@withContext) }
    }

    private suspend fun saveMessage(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, message: String, isShared: Boolean, fileUri: String, downloadEmotes: Boolean, networkLibrary: String?, emoteQuality: String, savedTwitchEmotes: MutableList<String>, savedBadges: MutableList<Pair<String, String>>, savedEmotes: MutableList<String>, globalBadgeList: List<TwitchBadge>, channelBadgeList: List<TwitchBadge>, cheerEmoteList: List<CheerEmote>, emoteList: List<Emote>) = withContext(Dispatchers.IO) {
        var position = downloadProgress.chatBytes
        var liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
        if (isShared) {
            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
        } else {
            FileOutputStream(fileUri, true).bufferedWriter()
        }.use { writer ->
            writer.write(",".also { position += 1 })
            if (!liveCommentsArrayStarted) {
                liveCommentsArrayStarted = true
                writer.write("\"liveComments\":".also { position += it.length })
                writer.write("[".also { position += 1 })
            }
            writer.write(JsonPrimitive(message).toString().also { position += it.toByteArray().size })
        }
        if (downloadEmotes) {
            val chatMessage = when {
                message.contains("PRIVMSG") -> ChatUtils.parseChatMessage(message, false)
                message.contains("USERNOTICE") -> ChatUtils.parseChatMessage(message, true)
                message.contains("CLEARMSG") -> ChatUtils.parseClearMessage(message).first
                message.contains("CLEARCHAT") -> ChatUtils.parseClearChat(this@StreamDownloadService, message)
                message.contains("NOTICE") -> ChatUtils.parseNotice(this@StreamDownloadService, message)
                else -> null
            }
            if (chatMessage != null) {
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
                        val badge = channelBadgeList.find { badge -> badge.setId == it.setId && badge.version == it.version }
                            ?: globalBadgeList.find { badge -> badge.setId == it.setId && badge.version == it.version }
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
                if (twitchEmotes.isNotEmpty() || twitchBadges.isNotEmpty() || cheerEmotes.isNotEmpty() || emotes.isNotEmpty()) {
                    if (isShared) {
                        contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                    } else {
                        FileOutputStream(fileUri, true).bufferedWriter()
                    }.use { writer ->
                        writer.write("]".also { position += 1 })
                    }
                    liveCommentsArrayStarted = false
                }
                val requestSemaphore = Semaphore(10)
                val mutexMap = mutableMapOf<Int, Mutex>()
                val count = MutableStateFlow(0)
                val twitchEmoteJobs = if (twitchEmotes.isNotEmpty()) {
                    val lastIndex = twitchEmotes.lastIndex
                    twitchEmotes.mapIndexed { index, emote ->
                        requestSemaphore.acquire()
                        launch(Dispatchers.IO) {
                            val url = when (emoteQuality) {
                                "4" -> emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x
                                "3" -> emote.url3x ?: emote.url2x ?: emote.url1x
                                "2" -> emote.url2x ?: emote.url1x
                                else -> emote.url1x
                            }!!
                            val response = when {
                                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    response.second
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    response.second
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        response.body.source().readByteArray()
                                    }
                                }
                            }
                            val mutex = Mutex()
                            if (count.value != index) {
                                mutex.lock()
                                mutexMap[index] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                                } else {
                                    FileOutputStream(fileUri, true).bufferedWriter()
                                }.use { writer ->
                                    writer.write(",".also { position += 1 })
                                    if (index == 0) {
                                        writer.write("\"twitchEmotes\":".also { position += it.length })
                                        writer.write("[".also { position += 1 })
                                    }
                                    writer.write(
                                        buildJsonObject {
                                            put("data", Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING))
                                            put("id", emote.id)
                                        }.toString().also { position += it.toByteArray().size }
                                    )
                                    if (index == lastIndex) {
                                        writer.write("]".also { position += 1 })
                                    }
                                }
                            }
                            count.update { it + 1 }
                            mutexMap.remove(count.value)?.unlock()
                        }.also {
                            it.invokeOnCompletion {
                                requestSemaphore.release()
                            }
                        }
                    }
                } else null
                val twitchBadgeJobs = if (twitchBadges.isNotEmpty()) {
                    val offset = twitchEmotes.size
                    val lastIndex = twitchBadges.lastIndex
                    twitchBadges.mapIndexed { index, badge ->
                        requestSemaphore.acquire()
                        launch(Dispatchers.IO) {
                            val url = when (emoteQuality) {
                                "4" -> badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x
                                "3" -> badge.url3x ?: badge.url2x ?: badge.url1x
                                "2" -> badge.url2x ?: badge.url1x
                                else -> badge.url1x
                            }!!
                            val response = when {
                                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    response.second
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    response.second
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        response.body.source().readByteArray()
                                    }
                                }
                            }
                            val mutex = Mutex()
                            val mutexIndex = index + offset
                            if (count.value != mutexIndex) {
                                mutex.lock()
                                mutexMap[mutexIndex] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                                } else {
                                    FileOutputStream(fileUri, true).bufferedWriter()
                                }.use { writer ->
                                    writer.write(",".also { position += 1 })
                                    if (index == 0) {
                                        writer.write("\"twitchBadges\":".also { position += it.length })
                                        writer.write("[".also { position += 1 })
                                    }
                                    writer.write(
                                        buildJsonObject {
                                            put("data", Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING))
                                            put("setId", badge.setId)
                                            put("version", badge.version)
                                        }.toString().also { position += it.toByteArray().size }
                                    )
                                    if (index == lastIndex) {
                                        writer.write("]".also { position += 1 })
                                    }
                                }
                            }
                            count.update { it + 1 }
                            mutexMap.remove(count.value)?.unlock()
                        }.also {
                            it.invokeOnCompletion {
                                requestSemaphore.release()
                            }
                        }
                    }
                } else null
                val cheerEmoteJobs = if (cheerEmotes.isNotEmpty()) {
                    val offset = twitchEmotes.size + twitchBadges.size
                    val lastIndex = cheerEmotes.lastIndex
                    cheerEmotes.mapIndexed { index, cheerEmote ->
                        requestSemaphore.acquire()
                        launch(Dispatchers.IO) {
                            val url = when (emoteQuality) {
                                "4" -> cheerEmote.url4x ?: cheerEmote.url3x ?: cheerEmote.url2x ?: cheerEmote.url1x
                                "3" -> cheerEmote.url3x ?: cheerEmote.url2x ?: cheerEmote.url1x
                                "2" -> cheerEmote.url2x ?: cheerEmote.url1x
                                else -> cheerEmote.url1x
                            }!!
                            val response = when {
                                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    response.second
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    response.second
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        response.body.source().readByteArray()
                                    }
                                }
                            }
                            val mutex = Mutex()
                            val mutexIndex = index + offset
                            if (count.value != mutexIndex) {
                                mutex.lock()
                                mutexMap[mutexIndex] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                                } else {
                                    FileOutputStream(fileUri, true).bufferedWriter()
                                }.use { writer ->
                                    writer.write(",".also { position += 1 })
                                    if (index == 0) {
                                        writer.write("\"cheerEmotes\":".also { position += it.length })
                                        writer.write("[".also { position += 1 })
                                    }
                                    writer.write(
                                        buildJsonObject {
                                            put("data", Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING))
                                            put("name", cheerEmote.name)
                                            put("minBits", cheerEmote.minBits)
                                            cheerEmote.color?.let { put("color", it) }
                                        }.toString().also { position += it.toByteArray().size }
                                    )
                                    if (index == lastIndex) {
                                        writer.write("]".also { position += 1 })
                                    }
                                }
                            }
                            count.update { it + 1 }
                            mutexMap.remove(count.value)?.unlock()
                        }.also {
                            it.invokeOnCompletion {
                                requestSemaphore.release()
                            }
                        }
                    }
                } else null
                val emoteJobs = if (emotes.isNotEmpty()) {
                    val offset = twitchEmotes.size + twitchBadges.size + cheerEmotes.size
                    val lastIndex = emotes.lastIndex
                    emotes.mapIndexed { index, emote ->
                        requestSemaphore.acquire()
                        launch(Dispatchers.IO) {
                            val url = when (emoteQuality) {
                                "4" -> emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x
                                "3" -> emote.url3x ?: emote.url2x ?: emote.url1x
                                "2" -> emote.url2x ?: emote.url1x
                                else -> emote.url1x
                            }!!
                            val response = when {
                                networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    response.second
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    response.second
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        response.body.source().readByteArray()
                                    }
                                }
                            }
                            val mutex = Mutex()
                            val mutexIndex = index + offset
                            if (count.value != mutexIndex) {
                                mutex.lock()
                                mutexMap[mutexIndex] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                                } else {
                                    FileOutputStream(fileUri, true).bufferedWriter()
                                }.use { writer ->
                                    writer.write(",".also { position += 1 })
                                    if (index == 0) {
                                        writer.write("\"emotes\":".also { position += it.length })
                                        writer.write("[".also { position += 1 })
                                    }
                                    writer.write(
                                        buildJsonObject {
                                            put("data", Base64.encodeToString(response, Base64.NO_WRAP or Base64.NO_PADDING))
                                            put("name", emote.name)
                                            put("isZeroWidth", emote.isOverlayEmote)
                                        }.toString().also { position += it.toByteArray().size }
                                    )
                                    if (index == lastIndex) {
                                        writer.write("]".also { position += 1 })
                                    }
                                }
                            }
                            count.update { it + 1 }
                            mutexMap.remove(count.value)?.unlock()
                        }.also {
                            it.invokeOnCompletion {
                                requestSemaphore.release()
                            }
                        }
                    }
                } else null
                twitchEmoteJobs?.joinAll()
                twitchBadgeJobs?.joinAll()
                cheerEmoteJobs?.joinAll()
                emoteJobs?.joinAll()
            }
        }
        downloadProgress.chatBytes = position
        downloadProgress.liveCommentsArrayStarted = liveCommentsArrayStarted
        val currentTime = System.currentTimeMillis()
        if (currentTime - downloadProgress.lastSaved >= 5000L) {
            downloadProgress.lastSaved = currentTime
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                bytes = downloadProgress.bytes
                chatBytes = downloadProgress.chatBytes
                lastSegmentUrl = downloadProgress.lastSegmentUrl
                this.liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
            })
        }
    }

    private fun sendNotification(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, getString(R.string.notification_downloads_channel_id))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(ContextCompat.getString(this@StreamDownloadService, if (downloadProgress.isLive) {
                R.string.downloading
            } else {
                R.string.download_waiting_for_stream
            }))
            setContentText(offlineVideo.channelName)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setGroup(GROUP_KEY)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setContentIntent(
                PendingIntent.getActivity(
                    this@StreamDownloadService,
                    offlineVideo.id,
                    Intent(this@StreamDownloadService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this@StreamDownloadService, android.R.drawable.ic_delete),
                    ContextCompat.getString(this@StreamDownloadService, R.string.stop),
                    PendingIntent.getService(
                        this@StreamDownloadService,
                        REQUEST_CODE_STOP,
                        Intent(this@StreamDownloadService, StreamDownloadService::class.java).apply {
                            action = INTENT_STOP
                            putExtra(KEY_VIDEO_ID, offlineVideo.id)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
        }.build()
        if (downloadProgress == activeDownloads.firstOrNull()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(offlineVideo.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(offlineVideo.id, notification)
            }
        } else {
            notificationManager?.notify(offlineVideo.id, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_STOP, INTENT_CANCEL -> {
                val videoId = intent.getIntExtra(KEY_VIDEO_ID, 0)
                downloadJobs.find { it.id == videoId }?.job?.cancel()
                val offlineVideo = offlineVideos.find { it.id == videoId }
                val downloadProgress = activeDownloads.find { it.id == videoId }
                if (offlineVideo != null && downloadProgress != null) {
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    if (intent.action == INTENT_STOP) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_PENDING
                                bytes = downloadProgress.bytes
                                chatBytes = downloadProgress.chatBytes
                                lastSegmentUrl = downloadProgress.lastSegmentUrl
                                liveCommentsArrayStarted = downloadProgress.liveCommentsArrayStarted
                            })
                        }
                    }
                }
                if (intent.action == INTENT_STOP) {
                    val nextOfflineVideo = offlineVideos.firstOrNull()
                    val nextDownload = activeDownloads.firstOrNull()
                    if (nextOfflineVideo != null && nextDownload != null) {
                        sendNotification(nextOfflineVideo, nextDownload)
                        notificationManager?.cancel(videoId)
                    } else {
                        listener?.unbind()
                        stopSelf()
                    }
                }
            }
            INTENT_START -> start(intent.getIntExtra(KEY_VIDEO_ID, 0))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@StreamDownloadService
    }

    override fun onDestroy() {
        super.onDestroy()
        activeDownloads.forEach {
            notificationManager?.cancel(it.id)
        }
    }

    companion object {
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.DOWNLOADS"

        private const val REQUEST_CODE_STOP = 0

        const val KEY_VIDEO_ID = "videoId"

        const val INTENT_STOP = "com.github.andreyasadchy.xtra.STOP"
        const val INTENT_CANCEL = "com.github.andreyasadchy.xtra.CANCEL"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_VIDEO_DOWNLOAD_SERVICE"
    }
}