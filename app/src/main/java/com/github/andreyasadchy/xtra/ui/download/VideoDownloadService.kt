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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.ui.DownloadProgress
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.body
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.m3u8.MediaPlaylist
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class VideoDownloadService : LifecycleService() {

    lateinit var xtraModule: XtraModule
    private val okHttpClient = lazy {
        xtraModule.okHttpClient.value.newBuilder().apply {
            connectTimeout(5, TimeUnit.MINUTES)
            writeTimeout(5, TimeUnit.MINUTES)
            readTimeout(5, TimeUnit.MINUTES)
        }.build()
    }

    private var notificationManager: NotificationManager? = null
    private lateinit var downloadSemaphore: Semaphore
    private val downloadJobs = mutableMapOf<Int, Job>()
    private val offlineVideos = mutableListOf<OfflineVideo>()
    val activeDownloads = mutableListOf<DownloadProgress>()
    var listener: Listener? = null

    interface Listener {
        fun update(downloadProgress: DownloadProgress)
        fun unbind()
    }

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        downloadSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_LIMIT, 2))
    }

    private fun start(videoId: Int) {
        if (activeDownloads.find { it.id == videoId } == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val offlineVideo = xtraModule.offlineVideosRepository.getById(videoId)
                if (offlineVideo != null) {
                    val downloadProgress = DownloadProgress(
                        id = videoId,
                        progress = offlineVideo.progress,
                        maxProgress = offlineVideo.maxProgress,
                        bytes = offlineVideo.bytes,
                        chatProgress = offlineVideo.chatProgress,
                        maxChatProgress = offlineVideo.maxChatProgress,
                        chatBytes = offlineVideo.chatBytes,
                        chatOffsetSeconds = offlineVideo.chatOffsetSeconds,
                    )
                    offlineVideos.add(offlineVideo)
                    activeDownloads.add(downloadProgress)
                    if (downloadSemaphore.availablePermits <= 0) {
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            status = OfflineVideo.STATUS_QUEUED
                        })
                    }
                    downloadSemaphore.withPermit {
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            status = OfflineVideo.STATUS_DOWNLOADING
                        })
                        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        val channelId = getString(R.string.notification_downloads_channel_id)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                            notificationManager?.createNotificationChannel(
                                NotificationChannel(
                                    channelId,
                                    ContextCompat.getString(this@VideoDownloadService, R.string.notification_downloads_channel_title),
                                    NotificationManager.IMPORTANCE_DEFAULT
                                ).apply {
                                    setSound(null, null)
                                }
                            )
                        }
                        sendNotification(offlineVideo, downloadProgress)
                        try {
                            val sourceUrl = offlineVideo.sourceUrl!!
                            if (sourceUrl.endsWith(".m3u8")) {
                                downloadVideo(offlineVideo, downloadProgress, sourceUrl)
                            } else {
                                downloadClip(offlineVideo, downloadProgress, sourceUrl)
                            }
                        } catch (e: CancellationException) {
                            ensureActive()
                        } catch (e: Exception) {
                            Log.e("VideoDownloadService", "Download failed", e)
                        }
                        offlineVideos.remove(offlineVideo)
                        activeDownloads.remove(downloadProgress)
                        val done = downloadProgress.progress >= downloadProgress.maxProgress && (!offlineVideo.downloadChat || downloadProgress.chatProgress >= downloadProgress.maxChatProgress)
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            status = if (done) {
                                OfflineVideo.STATUS_DOWNLOADED
                            } else {
                                val waitForWifi = if (prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                        networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                    } else {
                                        false
                                    }
                                } else false
                                if (waitForWifi) {
                                    OfflineVideo.STATUS_WAITING_FOR_WIFI
                                } else {
                                    OfflineVideo.STATUS_PENDING
                                }
                            }
                            progress = downloadProgress.progress
                            maxProgress = downloadProgress.maxProgress
                            bytes = downloadProgress.bytes
                            chatProgress = downloadProgress.chatProgress
                            maxChatProgress = downloadProgress.maxChatProgress
                            chatBytes = downloadProgress.chatBytes
                            chatOffsetSeconds = downloadProgress.chatOffsetSeconds
                        })
                        if (done) {
                            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                Notification.Builder(this@VideoDownloadService, getString(R.string.notification_downloads_channel_id))
                            } else {
                                @Suppress("DEPRECATION")
                                Notification.Builder(this@VideoDownloadService)
                            }.apply {
                                setContentTitle(ContextCompat.getString(this@VideoDownloadService, R.string.downloaded))
                                setContentText(offlineVideo.name)
                                setSmallIcon(android.R.drawable.stat_sys_download_done)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                                    setGroup(GROUP_KEY)
                                }
                                setAutoCancel(true)
                                setContentIntent(
                                    PendingIntent.getActivity(
                                        this@VideoDownloadService,
                                        -offlineVideo.id,
                                        Intent(this@VideoDownloadService, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            action = MainActivity.INTENT_OPEN_DOWNLOADED_VIDEO
                                            putExtra(MainActivity.KEY_VIDEO, offlineVideo)
                                        },
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                    )
                                )
                            }.build()
                            notificationManager?.notify(-offlineVideo.id, notification)
                        }
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
            }.also {
                it.invokeOnCompletion {
                    downloadJobs.remove(videoId)
                }
                downloadJobs[videoId] = it
            }
        }
    }

    private suspend fun downloadVideo(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, sourceUrl: String) = withContext(Dispatchers.IO) {
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val path = offlineVideo.downloadPath!!
        val from = offlineVideo.fromTime!!
        val to = offlineVideo.toTime!!
        val playlist = when {
            networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    xtraModule.httpEngine.value!!.newUrlRequestBuilder(sourceUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                }
                response.second.inputStream().use {
                    PlaylistUtils.parseMediaPlaylist(it)
                }
            }
            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    xtraModule.cronetEngine.value!!.newUrlRequestBuilder(sourceUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                }
                response.second.inputStream().use {
                    PlaylistUtils.parseMediaPlaylist(it)
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                    response.body.byteStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
            }
        }
        val segments = mutableListOf<Segment>()
        var totalDuration = 0L
        var downloadDuration = 0L
        var startPosition = -1L
        for (segment in playlist.segments) {
            val startTime = totalDuration
            val duration = (segment.duration * 1000f).toLong()
            if (startTime < from) {
                totalDuration += duration
            } else {
                if (startTime < to) {
                    segments.add(segment.copy(uri = segment.uri.replace("-unmuted", "-muted")))
                    totalDuration += duration
                    downloadDuration += duration
                    if (startPosition == -1L) {
                        startPosition = startTime
                    }
                } else {
                    break
                }
            }
        }
        if (offlineVideo.duration == null) {
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                duration = downloadDuration
                sourceStartPosition = startPosition
                maxProgress = segments.size
            })
            downloadProgress.maxProgress = offlineVideo.maxProgress
        }
        val urlPath = sourceUrl.substringBeforeLast('/') + "/"
        if (offlineVideo.playlistToFile) {
            downloadPlaylistToFile(offlineVideo, downloadProgress, networkLibrary, urlPath, path, playlist, segments)
        } else {
            downloadPlaylist(offlineVideo, downloadProgress, networkLibrary, urlPath, path, playlist, segments)
        }
    }

    private suspend fun downloadPlaylistToFile(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, networkLibrary: String?, urlPath: String, path: String, playlist: MediaPlaylist, segments: List<Segment>) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
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
            val fileName = "${offlineVideo.videoId ?: ""}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}.${segments.first().uri.substringAfterLast(".")}"
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
            val initSegmentBytes = if (playlist.initSegmentUri != null) {
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(urlPath + playlist.initSegmentUri, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
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
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(urlPath + playlist.initSegmentUri, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
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
                        okHttpClient.value.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).executeAsync().use { response ->
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
            } else null
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = fileUri
                initSegmentBytes?.let {
                    bytes += it
                    downloadProgress.bytes += it
                }
            })
            fileUri
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val chatJob = launch(Dispatchers.IO) {
            startChatJob(offlineVideo, downloadProgress, path)
        }
        val jobs = segments.drop(downloadProgress.progress).mapIndexed { index, segment ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(urlPath + segment.uri, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
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
                            downloadProgress.progress += 1
                            listener?.update(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
                        }
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(urlPath + segment.uri, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
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
                            downloadProgress.progress += 1
                            listener?.update(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(urlPath + segment.uri).build()).executeAsync().use { response ->
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
                                    downloadProgress.progress += 1
                                    listener?.update(downloadProgress)
                                    sendNotification(offlineVideo, downloadProgress)
                                }
                            }
                        }
                    }
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - downloadProgress.lastSaved >= 5000L) {
                    downloadProgress.lastSaved = currentTime
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        progress = downloadProgress.progress
                        maxProgress = downloadProgress.maxProgress
                        bytes = downloadProgress.bytes
                        chatProgress = downloadProgress.chatProgress
                        maxChatProgress = downloadProgress.maxChatProgress
                        chatBytes = downloadProgress.chatBytes
                        chatOffsetSeconds = downloadProgress.chatOffsetSeconds
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
        chatJob.join()
    }

    private suspend fun downloadPlaylist(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, networkLibrary: String?, urlPath: String, path: String, playlist: MediaPlaylist, segments: List<Segment>) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val videoDirectoryName = if (!offlineVideo.videoId.isNullOrBlank()) {
            "${offlineVideo.videoId}${offlineVideo.quality ?: ""}"
        } else {
            "${offlineVideo.downloadDate}"
        }
        val videoDirectoryUri = if (isShared) {
            val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
            val videoDirectoryUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + videoDirectoryName
            try {
                contentResolver.openOutputStream(videoDirectoryUri.toUri())!!.close()
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, directoryUri, DocumentsContract.Document.MIME_TYPE_DIR, videoDirectoryName)
                }
            }
            videoDirectoryUri
        } else {
            val videoDirectoryUri = "$path${File.separator}$videoDirectoryName${File.separator}"
            File(videoDirectoryUri).mkdir()
            videoDirectoryUri
        }
        val playlistFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            offlineVideo.url!!
        } else {
            val playlistFileUri = if (isShared) {
                val fileName = "${offlineVideo.downloadDate}.m3u8"
                val playlistFileUri = "$videoDirectoryUri%2F$fileName"
                try {
                    contentResolver.openOutputStream(playlistFileUri.toUri())!!
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", fileName)
                    contentResolver.openOutputStream(playlistFileUri.toUri())!!
                }.use {
                    PlaylistUtils.writeMediaPlaylist(playlist.copy(
                        initSegmentUri = playlist.initSegmentUri?.let { uri -> "$videoDirectoryUri%2F$uri" },
                        segments = segments.map { segment -> segment.copy(uri = videoDirectoryUri + "%2F" + segment.uri) }
                    ), it)
                }
                playlistFileUri
            } else {
                val playlistFileUri = "$videoDirectoryUri${offlineVideo.downloadDate}.m3u8"
                FileOutputStream(playlistFileUri).use {
                    PlaylistUtils.writeMediaPlaylist(playlist.copy(segments = segments), it)
                }
                playlistFileUri
            }
            if (playlist.initSegmentUri != null) {
                val initSegmentFileUri = if (isShared) {
                    videoDirectoryUri + "%2F" + playlist.initSegmentUri
                } else {
                    videoDirectoryUri + playlist.initSegmentUri
                }
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(urlPath + playlist.initSegmentUri, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (isShared) {
                            try {
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            }
                        } else {
                            FileOutputStream(initSegmentFileUri)
                        }.use {
                            it.write(response.second)
                        }
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(urlPath + playlist.initSegmentUri, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                        }
                        if (isShared) {
                            try {
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            }
                        } else {
                            FileOutputStream(initSegmentFileUri)
                        }.use {
                            it.write(response.second)
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).executeAsync().use { response ->
                            if (isShared) {
                                try {
                                    contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                    contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(initSegmentFileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                    }
                }
            }
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = playlistFileUri
            })
            playlistFileUri
        }
        val downloadedSegments = mutableListOf<String>()
        if (isShared) {
            val playlists = xtraModule.offlineVideosRepository.getPlaylists().mapNotNull { video ->
                video.url?.takeIf {
                    it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                            && it.substringBeforeLast("%2F") == videoDirectoryUri
                            && it != playlistFileUri
                }
            }
            playlists.forEach { uri ->
                try {
                    val p = contentResolver.openInputStream(uri.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    p.segments.forEach { downloadedSegments.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                } catch (e: Exception) {

                }
            }
        } else {
            val playlists = File(videoDirectoryUri).listFiles { it.extension == "m3u8" && it.path != playlistFileUri }
            playlists?.forEach { file ->
                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                p.segments.forEach { downloadedSegments.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
            }
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val chatJob = launch(Dispatchers.IO) {
            startChatJob(offlineVideo, downloadProgress, path)
        }
        val jobs = segments.drop(downloadProgress.progress).mapIndexed { index, segment ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                val fileUri = if (isShared) {
                    videoDirectoryUri + "%2F" + segment.uri
                } else {
                    videoDirectoryUri + segment.uri
                }
                val exists = if (isShared) {
                    try {
                        contentResolver.openOutputStream(fileUri.toUri())!!.close()
                        true
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                } else {
                    File(fileUri).exists()
                }
                if (!exists || !downloadedSegments.contains(segment.uri)) {
                    when {
                        networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                            val response = suspendCancellableCoroutine { continuation ->
                                xtraModule.httpEngine.value!!.newUrlRequestBuilder(urlPath + segment.uri, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                            }
                            if (isShared) {
                                try {
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(fileUri)
                            }.use {
                                it.write(response.second)
                            }
                        }
                        networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                            val response = suspendCancellableCoroutine { continuation ->
                                xtraModule.cronetEngine.value!!.newUrlRequestBuilder(urlPath + segment.uri, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                            }
                            if (isShared) {
                                try {
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(fileUri)
                            }.use {
                                it.write(response.second)
                            }
                        }
                        else -> {
                            okHttpClient.value.newCall(Request.Builder().url(urlPath + segment.uri).build()).executeAsync().use { response ->
                                if (isShared) {
                                    try {
                                        contentResolver.openOutputStream(fileUri.toUri())!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                        contentResolver.openOutputStream(fileUri.toUri())!!
                                    }
                                } else {
                                    FileOutputStream(fileUri)
                                }.use { outputStream ->
                                    response.body.byteStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                            }
                        }
                    }
                }
                val mutex = Mutex()
                if (count.value != index) {
                    mutex.lock()
                    mutexMap[index] = mutex
                }
                mutex.withLock {
                    downloadProgress.progress += 1
                    listener?.update(downloadProgress)
                    sendNotification(offlineVideo, downloadProgress)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - downloadProgress.lastSaved >= 5000L) {
                        downloadProgress.lastSaved = currentTime
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            progress = downloadProgress.progress
                            maxProgress = downloadProgress.maxProgress
                            bytes = downloadProgress.bytes
                            chatProgress = downloadProgress.chatProgress
                            maxChatProgress = downloadProgress.maxChatProgress
                            chatBytes = downloadProgress.chatBytes
                            chatOffsetSeconds = downloadProgress.chatOffsetSeconds
                        })
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
        jobs.joinAll()
        chatJob.join()
    }

    private suspend fun downloadClip(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, sourceUrl: String) = withContext(Dispatchers.IO) {
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val path = offlineVideo.downloadPath!!
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val videoFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            offlineVideo.url!!
        } else {
            val fileName = if (!offlineVideo.clipId.isNullOrBlank()) {
                "${offlineVideo.clipId}${offlineVideo.quality ?: ""}.mp4"
            } else {
                "${offlineVideo.downloadDate}.mp4"
            }
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
                url = fileUri
            })
            fileUri
        }
        downloadProgress.lastSaved = System.currentTimeMillis()
        val chatJob = launch(Dispatchers.IO) {
            startChatJob(offlineVideo, downloadProgress, path)
        }
        val job = launch(Dispatchers.IO) {
            if (downloadProgress.progress < downloadProgress.maxProgress) {
                when {
                    networkLibrary == C.HTTP_ENGINE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.httpEngine.value!!.newUrlRequestBuilder(sourceUrl, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(videoFileUri.toUri())!!
                        } else {
                            FileOutputStream(videoFileUri)
                        }.use {
                            it.write(response.second)
                        }
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            xtraModule.cronetEngine.value!!.newUrlRequestBuilder(sourceUrl, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(videoFileUri.toUri())!!
                        } else {
                            FileOutputStream(videoFileUri)
                        }.use {
                            it.write(response.second)
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri())!!
                            } else {
                                FileOutputStream(videoFileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                    }
                }
                downloadProgress.progress = downloadProgress.maxProgress
                listener?.update(downloadProgress)
                sendNotification(offlineVideo, downloadProgress)
            }
        }
        job.join()
        chatJob.join()
    }

    private suspend fun startChatJob(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, path: String) = withContext(Dispatchers.IO) {
        if (offlineVideo.downloadChat && downloadProgress.chatProgress < downloadProgress.maxChatProgress) {
            val videoId = offlineVideo.videoId
            if (videoId != null) {
                val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
                val startTimeSeconds = (offlineVideo.sourceStartPosition!! / 1000).toInt()
                val durationSeconds = (offlineVideo.duration!! / 1000).toInt()
                val endTimeSeconds = startTimeSeconds + durationSeconds
                val resumed = !offlineVideo.chatUrl.isNullOrBlank() && downloadProgress.chatBytes > 0
                val fileUri = if (resumed) {
                    offlineVideo.chatUrl!!
                } else {
                    val fileName = "${videoId}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}_chat.json"
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
                        maxChatProgress = durationSeconds
                        chatOffsetSeconds = startTimeSeconds
                        chatUrl = fileUri
                    })
                    downloadProgress.maxChatProgress = durationSeconds
                    downloadProgress.chatOffsetSeconds = startTimeSeconds
                    fileUri
                }
                val downloadEmotes = offlineVideo.downloadChatEmotes
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val gqlHeaders = TwitchApiHelper.getGQLHeaders(this@VideoDownloadService, true)
                val helixHeaders = TwitchApiHelper.getGQLHeaders(this@VideoDownloadService)
                val emoteQuality = prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
                val useWebp = prefs().getBoolean(C.CHAT_USE_WEBP, true)
                val channelId = offlineVideo.channelId
                val channelLogin = offlineVideo.channelLogin
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
                                val response = xtraModule.playerRepository.loadGlobalSTVEmotesResponse(networkLibrary)
                                val emotes = xtraModule.playerRepository.loadGlobalSTVEmotes(response, useWebp)
                                emoteList.addAll(emotes)
                                emoteList.sortBy { it.source }
                            } catch (e: Exception) {

                            }
                        })
                        add(launch(Dispatchers.IO) {
                            try {
                                val response = xtraModule.playerRepository.loadGlobalBTTVEmotesResponse(networkLibrary)
                                val emotes = xtraModule.playerRepository.loadGlobalBTTVEmotes(response, useWebp)
                                emoteList.addAll(emotes)
                                emoteList.sortBy { it.source }
                            } catch (e: Exception) {

                            }
                        })
                        add(launch(Dispatchers.IO) {
                            try {
                                val response = xtraModule.playerRepository.loadGlobalFFZEmotesResponse(networkLibrary)
                                val emotes = xtraModule.playerRepository.loadGlobalFFZEmotes(response, useWebp)
                                emoteList.addAll(emotes)
                                emoteList.sortBy { it.source }
                            } catch (e: Exception) {

                            }
                        })
                        if (channelId != null) {
                            add(launch(Dispatchers.IO) {
                                try {
                                    val response = xtraModule.playerRepository.loadSTVEmotesResponse(networkLibrary, channelId)
                                    val emotes = xtraModule.playerRepository.loadSTVEmotes(response, useWebp).second
                                    emoteList.addAll(emotes)
                                    emoteList.sortBy { it.source }
                                } catch (e: Exception) {

                                }
                            })
                            add(launch(Dispatchers.IO) {
                                try {
                                    val response = xtraModule.playerRepository.loadBTTVEmotesResponse(networkLibrary, channelId)
                                    val emotes = xtraModule.playerRepository.loadBTTVEmotes(response, useWebp)
                                    emoteList.addAll(emotes)
                                    emoteList.sortBy { it.source }
                                } catch (e: Exception) {

                                }
                            })
                            add(launch(Dispatchers.IO) {
                                try {
                                    val response = xtraModule.playerRepository.loadFFZEmotesResponse(networkLibrary, channelId)
                                    val emotes = xtraModule.playerRepository.loadFFZEmotes(response, useWebp)
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
                val startOffset = downloadProgress.chatOffsetSeconds
                val latestSavedMessageIds = mutableListOf<String>()
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
                                                        "comments" -> {
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
                                                                    latestSavedMessageIds.add(id)
                                                                }
                                                                reader.endObject()
                                                            }
                                                            reader.endArray()
                                                        }
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
                                put("id", videoId)
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
                        writer.write("\"startTime\":$startTimeSeconds".also { position += it.length })
                    }
                }
                var cursor: String? = null
                while (true) {
                    val response = if (cursor == null) {
                        xtraModule.graphQLRepository.loadQueryVideoCommentsDownload(networkLibrary, gqlHeaders, videoId, offset = startOffset)
                    } else {
                        xtraModule.graphQLRepository.loadQueryVideoCommentsDownload(networkLibrary, gqlHeaders, videoId, cursor = cursor)
                    }
                    val comments = response.data!!.video.comments
                    val messages = if (cursor == null && resumed) {
                        comments.edges.filter { item ->
                            val id = item.node.id
                            val offset = item.node.contentOffsetSeconds
                            id != null && offset != null && ((offset == startOffset && !latestSavedMessageIds.contains(id)) || offset > startOffset)
                        }
                    } else {
                        comments.edges
                    }
                    cursor = if (comments.pageInfo?.hasNextPage != false) comments.edges.lastOrNull()?.cursor else null
                    if (messages.isNotEmpty()) {
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                        } else {
                            FileOutputStream(fileUri, true).bufferedWriter()
                        }.use { writer ->
                            writer.write(",".also { position += 1 })
                            writer.write("\"comments\":".also { position += it.length })
                            writer.write("[".also { position += 1 })
                            messages.forEachIndexed { index, message ->
                                if (index > 0) {
                                    writer.write(",".also { position += 1 })
                                }
                                writer.write(xtraModule.json.encodeToString(message.node).also { position += it.toByteArray().size })
                            }
                            writer.write("]".also { position += 1 })
                        }
                        if (downloadEmotes) {
                            val twitchEmotes = mutableListOf<TwitchEmote>()
                            val twitchBadges = mutableListOf<TwitchBadge>()
                            val cheerEmotes = mutableListOf<CheerEmote>()
                            val emotes = mutableListOf<Emote>()
                            val words = mutableListOf<String>()
                            messages.forEach { comment ->
                                comment.node.let { item ->
                                    item.message?.let { message ->
                                        val chatMessage = StringBuilder()
                                        message.fragments?.forEach { fragment ->
                                            fragment.text?.let { text ->
                                                fragment.emote?.emoteID?.let { id ->
                                                    if (!savedTwitchEmotes.contains(id)) {
                                                        savedTwitchEmotes.add(id)
                                                        twitchEmotes.add(TwitchEmote(id = id))
                                                    }
                                                }
                                                chatMessage.append(text)
                                            }
                                        }
                                        message.userBadges?.forEach { badge ->
                                            badge.setID?.let { setId ->
                                                badge.version?.let { version ->
                                                    val pair = Pair(setId, version)
                                                    if (!savedBadges.contains(pair)) {
                                                        savedBadges.add(pair)
                                                        val badge = channelBadgeList.find { badge -> badge.setId == setId && badge.version == version }
                                                            ?: globalBadgeList.find { badge -> badge.setId == setId && badge.version == version }
                                                        if (badge != null) {
                                                            twitchBadges.add(badge)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        chatMessage.toString().split(" ").forEach { string ->
                                            if (!words.contains(string)) {
                                                words.add(string)
                                                if (!savedEmotes.contains(string)) {
                                                    val bitsCount = string.takeLastWhile { it.isDigit() }
                                                    val cheerEmote = if (bitsCount.isNotEmpty()) {
                                                        val bitsName = string.substringBeforeLast(bitsCount)
                                                        cheerEmoteList.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
                                                    } else null
                                                    if (cheerEmote != null) {
                                                        savedEmotes.add(string)
                                                        cheerEmotes.add(cheerEmote)
                                                    } else {
                                                        val emote = emoteList.find { it.name == string }
                                                        if (emote != null) {
                                                            savedEmotes.add(string)
                                                            emotes.add(emote)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
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
                    val lastOffsetSeconds = comments.edges.lastOrNull()?.node?.contentOffsetSeconds
                    if (lastOffsetSeconds != null && lastOffsetSeconds < endTimeSeconds && !cursor.isNullOrBlank()) {
                        downloadProgress.chatProgress = lastOffsetSeconds - startTimeSeconds
                        downloadProgress.chatBytes = position
                        downloadProgress.chatOffsetSeconds = lastOffsetSeconds
                        listener?.update(downloadProgress)
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - downloadProgress.lastSaved >= 5000L) {
                            downloadProgress.lastSaved = currentTime
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                progress = downloadProgress.progress
                                maxProgress = downloadProgress.maxProgress
                                bytes = downloadProgress.bytes
                                chatProgress = downloadProgress.chatProgress
                                maxChatProgress = downloadProgress.maxChatProgress
                                chatBytes = downloadProgress.chatBytes
                                chatOffsetSeconds = downloadProgress.chatOffsetSeconds
                            })
                        }
                    } else {
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.bufferedWriter()
                        } else {
                            FileOutputStream(fileUri, true).bufferedWriter()
                        }.use { writer ->
                            writer.write("}".also { position += 1 })
                        }
                        downloadProgress.chatProgress = downloadProgress.maxChatProgress
                        downloadProgress.chatBytes = position
                        if (lastOffsetSeconds != null) {
                            downloadProgress.chatOffsetSeconds = lastOffsetSeconds
                        }
                        listener?.update(downloadProgress)
                        break
                    }
                }
            } else {
                downloadProgress.chatProgress = downloadProgress.maxChatProgress
                listener?.update(downloadProgress)
            }
        }
    }

    private fun sendNotification(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, paused: Boolean = false) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, getString(R.string.notification_downloads_channel_id))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(ContextCompat.getString(this@VideoDownloadService, R.string.downloading))
                setContentText(offlineVideo.name)
                setSmallIcon(android.R.drawable.stat_sys_download)
                setGroup(GROUP_KEY)
                setOngoing(true)
                setOnlyAlertOnce(true)
                setProgress(downloadProgress.maxProgress, downloadProgress.progress, false)
                setContentIntent(
                    PendingIntent.getActivity(
                        this@VideoDownloadService,
                        offlineVideo.id,
                        Intent(this@VideoDownloadService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (paused) {
                        addAction(
                            Notification.Action.Builder(
                                Icon.createWithResource(this@VideoDownloadService, R.drawable.baseline_play_arrow_black_48),
                                ContextCompat.getString(this@VideoDownloadService, R.string.resume),
                                PendingIntent.getService(
                                    this@VideoDownloadService,
                                    REQUEST_CODE_RESUME,
                                    Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                        action = INTENT_RESUME
                                        putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                    },
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            ).build()
                        )
                    } else {
                        addAction(
                            Notification.Action.Builder(
                                Icon.createWithResource(this@VideoDownloadService, R.drawable.baseline_pause_black_48),
                                ContextCompat.getString(this@VideoDownloadService, R.string.pause),
                                PendingIntent.getService(
                                    this@VideoDownloadService,
                                    REQUEST_CODE_PAUSE,
                                    Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                        action = INTENT_PAUSE
                                        putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                    },
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            ).build()
                        )
                    }
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@VideoDownloadService, android.R.drawable.ic_delete),
                            ContextCompat.getString(this@VideoDownloadService, R.string.stop),
                            PendingIntent.getService(
                                this@VideoDownloadService,
                                REQUEST_CODE_STOP,
                                Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                    action = INTENT_STOP
                                    putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        ).build()
                    )
                } else @Suppress("DEPRECATION") {
                    if (paused) {
                        addAction(
                            Notification.Action.Builder(
                                R.drawable.baseline_play_arrow_black_48,
                                ContextCompat.getString(this@VideoDownloadService, R.string.resume),
                                PendingIntent.getService(
                                    this@VideoDownloadService,
                                    REQUEST_CODE_RESUME,
                                    Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                        action = INTENT_RESUME
                                        putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                    },
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            ).build()
                        )
                    } else {
                        addAction(
                            Notification.Action.Builder(
                                R.drawable.baseline_pause_black_48,
                                ContextCompat.getString(this@VideoDownloadService, R.string.pause),
                                PendingIntent.getService(
                                    this@VideoDownloadService,
                                    REQUEST_CODE_PAUSE,
                                    Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                        action = INTENT_PAUSE
                                        putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                    },
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            ).build()
                        )
                    }
                    addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_delete,
                            ContextCompat.getString(this@VideoDownloadService, R.string.stop),
                            PendingIntent.getService(
                                this@VideoDownloadService,
                                REQUEST_CODE_STOP,
                                Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                    action = INTENT_STOP
                                    putExtra(KEY_VIDEO_ID, offlineVideo.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        ).build()
                    )
                }
            }.build()
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(this).apply {
                setContentTitle(ContextCompat.getString(this@VideoDownloadService, R.string.downloading))
                setContentText(offlineVideo.name)
                setSmallIcon(android.R.drawable.stat_sys_download)
                setGroup(GROUP_KEY)
                setOngoing(true)
                setOnlyAlertOnce(true)
                setProgress(downloadProgress.maxProgress, downloadProgress.progress, false)
                setContentIntent(
                    PendingIntent.getActivity(
                        this@VideoDownloadService,
                        offlineVideo.id,
                        Intent(this@VideoDownloadService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                if (paused) {
                    addAction(
                        R.drawable.baseline_play_arrow_black_48,
                        ContextCompat.getString(this@VideoDownloadService, R.string.resume),
                        PendingIntent.getService(
                            this@VideoDownloadService,
                            REQUEST_CODE_RESUME,
                            Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                action = INTENT_RESUME
                                putExtra(KEY_VIDEO_ID, offlineVideo.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                } else {
                    addAction(
                        R.drawable.baseline_pause_black_48,
                        ContextCompat.getString(this@VideoDownloadService, R.string.pause),
                        PendingIntent.getService(
                            this@VideoDownloadService,
                            REQUEST_CODE_PAUSE,
                            Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                action = INTENT_PAUSE
                                putExtra(KEY_VIDEO_ID, offlineVideo.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                addAction(
                    android.R.drawable.ic_delete,
                    ContextCompat.getString(this@VideoDownloadService, R.string.stop),
                    PendingIntent.getService(
                        this@VideoDownloadService,
                        REQUEST_CODE_STOP,
                        Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                            action = INTENT_STOP
                            putExtra(KEY_VIDEO_ID, offlineVideo.id)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }.build()
        }
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
            INTENT_PAUSE, INTENT_STOP, INTENT_CANCEL -> {
                val videoId = intent.getIntExtra(KEY_VIDEO_ID, 0)
                downloadJobs[videoId]?.cancel()
                val offlineVideo = offlineVideos.find { it.id == videoId }
                val downloadProgress = activeDownloads.find { it.id == videoId }
                if (offlineVideo != null && downloadProgress != null) {
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    if (intent.action != INTENT_CANCEL) {
                        if (intent.action == INTENT_PAUSE) {
                            sendNotification(offlineVideo, downloadProgress, paused = true)
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_PENDING
                                progress = downloadProgress.progress
                                maxProgress = downloadProgress.maxProgress
                                bytes = downloadProgress.bytes
                                chatProgress = downloadProgress.chatProgress
                                maxChatProgress = downloadProgress.maxChatProgress
                                chatBytes = downloadProgress.chatBytes
                                chatOffsetSeconds = downloadProgress.chatOffsetSeconds
                            })
                        }
                    }
                }
                if (intent.action != INTENT_PAUSE) {
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
            INTENT_START, INTENT_RESUME -> start(intent.getIntExtra(KEY_VIDEO_ID, 0))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@VideoDownloadService
    }

    override fun onDestroy() {
        super.onDestroy()
        activeDownloads.forEach {
            notificationManager?.cancel(it.id)
        }
    }

    companion object {
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.DOWNLOADS"

        private const val REQUEST_CODE_PAUSE = 0
        private const val REQUEST_CODE_RESUME = 1
        private const val REQUEST_CODE_STOP = 2

        const val KEY_VIDEO_ID = "videoId"

        private const val INTENT_PAUSE = "com.github.andreyasadchy.xtra.PAUSE"
        private const val INTENT_RESUME = "com.github.andreyasadchy.xtra.RESUME"
        const val INTENT_STOP = "com.github.andreyasadchy.xtra.STOP"
        const val INTENT_CANCEL = "com.github.andreyasadchy.xtra.CANCEL"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_VIDEO_DOWNLOAD_SERVICE"
    }
}