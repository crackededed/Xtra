package com.github.andreyasadchy.xtra.ui.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.http.HttpEngine
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
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.model.gql.video.VideoMessagesResponse
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.body
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.prefs
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

@HiltWorker
class VideoDownloadWorker @AssistedInject constructor(
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
    lateinit var json: Json

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var graphQLRepository: GraphQLRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var offlineVideo: OfflineVideo

    override suspend fun doWork(): Result {
        offlineVideo = offlineRepository.getVideoById(inputData.getInt(KEY_VIDEO_ID, 0)) ?: return Result.failure()
        offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_DOWNLOADING })
        setForeground(createForegroundInfo())
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
        val sourceUrl = offlineVideo.sourceUrl!!
        if (sourceUrl.endsWith(".m3u8")) {
            val path = offlineVideo.downloadPath!!
            val from = offlineVideo.fromTime!!
            val to = offlineVideo.toTime!!
            val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
            val playlist = when {
                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                    val response = suspendCoroutine { continuation ->
                        httpEngine!!.get().newUrlRequestBuilder(sourceUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    response.second.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                networkLibrary == "Cronet" && cronetEngine != null -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                        cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, request.callback, cronetExecutor).build().start()
                        val response = request.future.get().responseBody as ByteArray
                        response.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        val response = suspendCoroutine { continuation ->
                            cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                        }
                        response.second.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
                else -> {
                    okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
            }
            val targetDuration = playlist.targetDuration * 1000L
            var totalDuration = 0L
            val size = playlist.segments.size
            val relativeStartTimes = ArrayList<Long>(size)
            val durations = ArrayList<Long>(size)
            var relativeTime = 0L
            playlist.segments.forEach {
                val duration = (it.duration * 1000f).toLong()
                durations.add(duration)
                totalDuration += duration
                relativeStartTimes.add(relativeTime)
                relativeTime += duration
            }
            val fromIndex = if (from == 0L) {
                0
            } else {
                val min = from - targetDuration
                relativeStartTimes.binarySearch(comparison = { time ->
                    when {
                        time > from -> 1
                        time < min -> -1
                        else -> 0
                    }
                }).let { if (it < 0) -it else it }
            }
            val toIndex = if (to in relativeStartTimes.last()..totalDuration) {
                relativeStartTimes.lastIndex
            } else {
                val max = to + targetDuration
                relativeStartTimes.binarySearch(comparison = { time ->
                    when {
                        time > max -> 1
                        time < to -> -1
                        else -> 0
                    }
                }).let { if (it < 0) -it else it }
            }
            val urlPath = sourceUrl.substringBeforeLast('/') + "/"
            val remainingSegments = ArrayList<Segment>()
            if (offlineVideo.progress < offlineVideo.maxProgress) {
                for (i in fromIndex + offlineVideo.progress..toIndex) {
                    val segment = playlist.segments[i]
                    remainingSegments.add(segment.copy(uri = segment.uri.replace("-unmuted", "-muted")))
                }
            }
            val requestSemaphore = Semaphore(context.prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
            val mutexMap = mutableMapOf<Int, Mutex>()
            val count = MutableStateFlow(0)
            val jobs = if (offlineVideo.playlistToFile) {
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
                    val fileName = "${offlineVideo.videoId ?: ""}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}.${remainingSegments.first().uri.substringAfterLast(".")}"
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
                    val startPosition = relativeStartTimes[fromIndex]
                    val initSegmentBytes = if (playlist.initSegmentUri != null) {
                        when {
                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                val response = suspendCoroutine { continuation ->
                                    httpEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
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
                                    cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, request.callback, cronetExecutor).build().start()
                                    request.future.get().responseBody as ByteArray
                                } else {
                                    val response = suspendCoroutine { continuation ->
                                        cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
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
                                okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
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
                    } else null
                    offlineRepository.updateVideo(offlineVideo.apply {
                        url = fileUri
                        duration = (relativeStartTimes[toIndex] + durations[toIndex] - startPosition) - 1000L
                        sourceStartPosition = startPosition
                        maxProgress = toIndex - fromIndex + 1
                        initSegmentBytes?.let { bytes += it }
                    })
                    fileUri
                }
                runBlocking {
                    remainingSegments.map {
                        launch {
                            requestSemaphore.withPermit {
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        val response = suspendCoroutine { continuation ->
                                            httpEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        val mutex = Mutex()
                                        val id = remainingSegments.indexOf(it)
                                        if (count.value != id) {
                                            mutex.lock()
                                            mutexMap[id] = mutex
                                        }
                                        mutex.withLock {
                                            if (isShared) {
                                                context.contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!.use {
                                                    it.write(response.second)
                                                }
                                            } else {
                                                FileOutputStream(videoFileUri, true).use {
                                                    it.write(response.second)
                                                }
                                            }
                                            offlineRepository.updateVideo(offlineVideo.apply {
                                                bytes += response.second.size
                                                progress += 1
                                            })
                                        }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                            cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, request.callback, cronetExecutor).build().start()
                                            request.future.get().responseBody as ByteArray
                                        } else {
                                            val response = suspendCoroutine { continuation ->
                                                cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                            }
                                            response.second
                                        }
                                        val mutex = Mutex()
                                        val id = remainingSegments.indexOf(it)
                                        if (count.value != id) {
                                            mutex.lock()
                                            mutexMap[id] = mutex
                                        }
                                        mutex.withLock {
                                            if (isShared) {
                                                context.contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!.use {
                                                    it.write(response)
                                                }
                                            } else {
                                                FileOutputStream(videoFileUri, true).use {
                                                    it.write(response)
                                                }
                                            }
                                            offlineRepository.updateVideo(offlineVideo.apply {
                                                bytes += response.size
                                                progress += 1
                                            })
                                        }
                                    }
                                    else -> {
                                        okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                            val mutex = Mutex()
                                            val id = remainingSegments.indexOf(it)
                                            if (count.value != id) {
                                                mutex.lock()
                                                mutexMap[id] = mutex
                                            }
                                            mutex.withLock {
                                                if (isShared) {
                                                    context.contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                                } else {
                                                    FileOutputStream(videoFileUri)
                                                }.use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                    offlineRepository.updateVideo(offlineVideo.apply {
                                                        bytes += response.body.contentLength()
                                                        progress += 1
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                                count.update { it + 1 }
                                mutexMap.remove(count.value)?.unlock()
                                setForeground(createForegroundInfo())
                            }
                        }
                    }
                }
            } else {
                val videoDirectoryName = if (!offlineVideo.videoId.isNullOrBlank()) {
                    "${offlineVideo.videoId}${offlineVideo.quality ?: ""}"
                } else {
                    "${offlineVideo.downloadDate}"
                }
                if (isShared) {
                    val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
                    val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
                    val videoDirectoryUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + videoDirectoryName
                    try {
                        context.contentResolver.openOutputStream(videoDirectoryUri.toUri())!!.close()
                    } catch (e: Exception) {
                        if (e is IllegalArgumentException) {
                            DocumentsContract.createDocument(context.contentResolver, directoryUri, DocumentsContract.Document.MIME_TYPE_DIR, videoDirectoryName)
                        }
                    }
                    val playlistFileUri = if (!offlineVideo.url.isNullOrBlank()) {
                        offlineVideo.url!!
                    } else {
                        val sharedSegments = ArrayList<Segment>()
                        for (i in fromIndex..toIndex) {
                            val segment = playlist.segments[i]
                            sharedSegments.add(segment.copy(uri = videoDirectoryUri + "%2F" + segment.uri.replace("-unmuted", "-muted")))
                        }
                        val fileName = "${offlineVideo.downloadDate}.m3u8"
                        val playlistFileUri = "$videoDirectoryUri%2F$fileName"
                        try {
                            context.contentResolver.openOutputStream(playlistFileUri.toUri())!!
                        } catch (e: IllegalArgumentException) {
                            DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", fileName)
                            context.contentResolver.openOutputStream(playlistFileUri.toUri())!!
                        }.use {
                            PlaylistUtils.writeMediaPlaylist(playlist.copy(
                                initSegmentUri = playlist.initSegmentUri?.let { uri -> "$videoDirectoryUri%2F$uri" },
                                segments = sharedSegments
                            ), it)
                        }
                        val startPosition = relativeStartTimes[fromIndex]
                        if (playlist.initSegmentUri != null) {
                            val initSegmentFileUri = (videoDirectoryUri + "%2F" + playlist.initSegmentUri).toUri()
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine { continuation ->
                                        httpEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    try {
                                        context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                        context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                    }.use {
                                        it.write(response.second)
                                    }
                                }
                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                    val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                        cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, request.callback, cronetExecutor).build().start()
                                        request.future.get().responseBody as ByteArray
                                    } else {
                                        val response = suspendCoroutine { continuation ->
                                            cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                        }
                                        response.second
                                    }
                                    try {
                                        context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                        context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                    }.use {
                                        it.write(response)
                                    }
                                }
                                else -> {
                                    okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
                                        try {
                                            context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                        } catch (e: IllegalArgumentException) {
                                            DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                            context.contentResolver.openOutputStream(initSegmentFileUri)!!
                                        }.use { outputStream ->
                                            response.body.byteStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        offlineRepository.updateVideo(offlineVideo.apply {
                            url = playlistFileUri
                            duration = (relativeStartTimes[toIndex] + durations[toIndex] - startPosition) - 1000L
                            sourceStartPosition = startPosition
                            maxProgress = toIndex - fromIndex + 1
                        })
                        playlistFileUri
                    }
                    val downloadedTracks = mutableListOf<String>()
                    val playlists = offlineRepository.getPlaylists().mapNotNull { video ->
                        video.url?.takeIf {
                            it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                    && it.substringBeforeLast("%2F") == videoDirectoryUri
                                    && it != playlistFileUri
                        }
                    }
                    playlists.forEach { uri ->
                        try {
                            val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { downloadedTracks.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                        } catch (e: Exception) {

                        }
                    }
                    runBlocking {
                        remainingSegments.map {
                            launch {
                                requestSemaphore.withPermit {
                                    val fileUri = (videoDirectoryUri + "%2F" + it.uri).toUri()
                                    try {
                                        context.contentResolver.openOutputStream(fileUri)!!
                                    } catch (e: IllegalArgumentException) {
                                        null
                                    }.use { outputStream ->
                                        if (outputStream == null || !downloadedTracks.contains(it.uri)) {
                                            when {
                                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                    val response = suspendCoroutine { continuation ->
                                                        httpEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                    }
                                                    if (outputStream != null) {
                                                        outputStream
                                                    } else {
                                                        DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", it.uri)
                                                        context.contentResolver.openOutputStream(fileUri)!!
                                                    }.use {
                                                        it.write(response.second)
                                                    }
                                                }
                                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                                    val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                        cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, request.callback, cronetExecutor).build().start()
                                                        request.future.get().responseBody as ByteArray
                                                    } else {
                                                        val response = suspendCoroutine { continuation ->
                                                            cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                        }
                                                        response.second
                                                    }
                                                    if (outputStream != null) {
                                                        outputStream
                                                    } else {
                                                        DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", it.uri)
                                                        context.contentResolver.openOutputStream(fileUri)!!
                                                    }.use {
                                                        it.write(response)
                                                    }
                                                }
                                                else -> {
                                                    okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                                        if (outputStream != null) {
                                                            outputStream
                                                        } else {
                                                            DocumentsContract.createDocument(context.contentResolver, videoDirectoryUri.toUri(), "", it.uri)
                                                            context.contentResolver.openOutputStream(fileUri)!!
                                                        }.use { outputStream ->
                                                            response.body.byteStream().use { inputStream ->
                                                                inputStream.copyTo(outputStream)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val mutex = Mutex()
                                    val id = remainingSegments.indexOf(it)
                                    if (count.value != id) {
                                        mutex.lock()
                                        mutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        offlineRepository.updateVideo(offlineVideo.apply { progress += 1 })
                                    }
                                    count.update { it + 1 }
                                    mutexMap.remove(count.value)?.unlock()
                                    setForeground(createForegroundInfo())
                                }
                            }
                        }
                    }
                } else {
                    val directory = "$path${File.separator}$videoDirectoryName${File.separator}"
                    val playlistFileUri = if (!offlineVideo.url.isNullOrBlank()) {
                        offlineVideo.url!!
                    } else {
                        File(directory).mkdir()
                        val playlistUri = "$directory${offlineVideo.downloadDate}.m3u8"
                        FileOutputStream(playlistUri).use {
                            PlaylistUtils.writeMediaPlaylist(playlist.copy(segments = remainingSegments), it)
                        }
                        val startPosition = relativeStartTimes[fromIndex]
                        if (playlist.initSegmentUri != null) {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                    val response = suspendCoroutine { continuation ->
                                        httpEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    FileOutputStream(directory + playlist.initSegmentUri).use {
                                        it.write(response.second)
                                    }
                                }
                                networkLibrary == "Cronet" && cronetEngine != null -> {
                                    val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                        cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, request.callback, cronetExecutor).build().start()
                                        request.future.get().responseBody as ByteArray
                                    } else {
                                        val response = suspendCoroutine { continuation ->
                                            cronetEngine!!.get().newUrlRequestBuilder(urlPath + playlist.initSegmentUri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                        }
                                        response.second
                                    }
                                    FileOutputStream(directory + playlist.initSegmentUri).use {
                                        it.write(response)
                                    }
                                }
                                else -> {
                                    okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
                                        FileOutputStream(directory + playlist.initSegmentUri).use { outputStream ->
                                            response.body.byteStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        offlineRepository.updateVideo(offlineVideo.apply {
                            url = playlistUri
                            duration = (relativeStartTimes[toIndex] + durations[toIndex] - startPosition) - 1000L
                            sourceStartPosition = startPosition
                            maxProgress = toIndex - fromIndex + 1
                        })
                        playlistUri
                    }
                    val downloadedTracks = mutableListOf<String>()
                    val playlists = File(directory).listFiles { it.extension == "m3u8" && it.path != playlistFileUri }
                    playlists?.forEach { file ->
                        val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                        p.segments.forEach { downloadedTracks.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                    }
                    runBlocking {
                        remainingSegments.map {
                            launch {
                                requestSemaphore.withPermit {
                                    if (!File(directory + it.uri).exists() || !downloadedTracks.contains(it.uri)) {
                                        when {
                                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                                val response = suspendCoroutine { continuation ->
                                                    httpEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                }
                                                FileOutputStream(directory + it.uri).use {
                                                    it.write(response.second)
                                                }
                                            }
                                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                                val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                    cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, request.callback, cronetExecutor).build().start()
                                                    request.future.get().responseBody as ByteArray
                                                } else {
                                                    val response = suspendCoroutine { continuation ->
                                                        cronetEngine!!.get().newUrlRequestBuilder(urlPath + it.uri, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                    }
                                                    response.second
                                                }
                                                FileOutputStream(directory + it.uri).use {
                                                    it.write(response)
                                                }
                                            }
                                            else -> {
                                                okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                                    FileOutputStream(directory + it.uri).use { outputStream ->
                                                        response.body.byteStream().use { inputStream ->
                                                            inputStream.copyTo(outputStream)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val mutex = Mutex()
                                    val id = remainingSegments.indexOf(it)
                                    if (count.value != id) {
                                        mutex.lock()
                                        mutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        offlineRepository.updateVideo(offlineVideo.apply { progress += 1 })
                                    }
                                    count.update { it + 1 }
                                    mutexMap.remove(count.value)?.unlock()
                                    setForeground(createForegroundInfo())
                                }
                            }
                        }
                    }
                }
            }
            val chatJob = runBlocking {
                launch {
                    startChatJob(path)
                }
            }
            jobs.joinAll()
            chatJob.join()
        } else {
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
                        context.contentResolver.openOutputStream(fileUri.toUri())!!.close()
                    } catch (e: IllegalArgumentException) {
                        DocumentsContract.createDocument(context.contentResolver, directoryUri, "", fileName)
                    }
                    fileUri
                } else {
                    "$path${File.separator}$fileName"
                }
                offlineRepository.updateVideo(offlineVideo.apply {
                    url = fileUri
                })
                fileUri
            }
            val jobs = runBlocking {
                launch {
                    if (offlineVideo.progress < offlineVideo.maxProgress) {
                        when {
                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                val response = suspendCoroutine { continuation ->
                                    httpEngine!!.get().newUrlRequestBuilder(sourceUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                }
                                if (isShared) {
                                    context.contentResolver.openOutputStream(videoFileUri.toUri())!!.use {
                                        it.write(response.second)
                                    }
                                } else {
                                    FileOutputStream(videoFileUri).use {
                                        it.write(response.second)
                                    }
                                }
                            }
                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                    cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, request.callback, cronetExecutor).build().start()
                                    request.future.get().responseBody as ByteArray
                                } else {
                                    val response = suspendCoroutine { continuation ->
                                        cronetEngine!!.get().newUrlRequestBuilder(sourceUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                    }
                                    response.second
                                }
                                if (isShared) {
                                    context.contentResolver.openOutputStream(videoFileUri.toUri())!!.use {
                                        it.write(response)
                                    }
                                } else {
                                    FileOutputStream(videoFileUri).use {
                                        it.write(response)
                                    }
                                }
                            }
                            else -> {
                                okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                                    if (isShared) {
                                        context.contentResolver.openOutputStream(videoFileUri.toUri())!!
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
                        offlineRepository.updateVideo(offlineVideo.apply { progress = offlineVideo.maxProgress })
                        setForeground(createForegroundInfo())
                    }
                }
            }
            val chatJob = runBlocking {
                launch {
                    startChatJob(path)
                }
            }
            jobs.join()
            chatJob.join()
        }
        if (offlineVideo.progress < offlineVideo.maxProgress || offlineVideo.downloadChat && offlineVideo.chatProgress < offlineVideo.maxChatProgress) {
            offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_PENDING })
        } else {
            offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_DOWNLOADED })
            val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_downloads_channel_id)).apply {
                setGroup(GROUP_KEY)
                setContentTitle(ContextCompat.getString(context, R.string.downloaded))
                setContentText(offlineVideo.name)
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setAutoCancel(true)
                setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        -offlineVideo.id,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_DOWNLOADED_VIDEO
                            putExtra(MainActivity.KEY_VIDEO, offlineVideo)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }.build()
            notificationManager.notify(-offlineVideo.id, notification)
        }
        return Result.success()
    }

    private suspend fun startChatJob(path: String) {
        if (offlineVideo.downloadChat && offlineVideo.chatProgress < offlineVideo.maxChatProgress) {
            offlineVideo.videoId?.let { videoId ->
                val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
                val startTimeSeconds = (offlineVideo.sourceStartPosition!! / 1000).toInt()
                val durationSeconds = (offlineVideo.duration!! / 1000).toInt()
                val endTimeSeconds = startTimeSeconds + durationSeconds
                val fileName = "${videoId}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}_chat.json"
                val resumed = !offlineVideo.chatUrl.isNullOrBlank()
                val savedOffset = offlineVideo.chatOffsetSeconds
                val latestSavedMessages = mutableListOf<VideoChatMessage>()
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
                                                        "comments" -> {
                                                            reader.beginArray()
                                                            while (reader.hasNext()) {
                                                                readMessageObject(reader)?.let {
                                                                    if (it.offsetSeconds == savedOffset) {
                                                                        latestSavedMessages.add(it)
                                                                    }
                                                                }
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
                        maxChatProgress = durationSeconds
                        chatUrl = fileUri
                    })
                    fileUri
                }
                val downloadEmotes = offlineVideo.downloadChatEmotes
                val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
                val helixHeaders = TwitchApiHelper.getGQLHeaders(context)
                val emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
                val useWebp = context.prefs().getBoolean(C.CHAT_USE_WEBP, true)
                val channelId = offlineVideo.channelId
                val channelLogin = offlineVideo.channelLogin
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
                if (isShared) {
                    context.contentResolver.openOutputStream(fileUri.toUri(), if (resumed) "wa" else "w")!!.bufferedWriter()
                } else {
                    FileOutputStream(fileUri, resumed).bufferedWriter()
                }.use { fileWriter ->
                    JsonWriter(fileWriter).use { writer ->
                        var position = offlineVideo.chatBytes
                        if (!resumed) {
                            writer.beginObject().also { position += 1 }
                            writer.name("video".also { position += it.length + 3 })
                            writer.beginObject().also { position += 1 }
                            writer.name("id".also { position += it.length + 3 }).value(videoId.also { position += it.length + 2 })
                            offlineVideo.name?.let { value -> writer.name("title".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                            offlineVideo.uploadDate?.let { value -> writer.name("uploadDate".also { position += it.length + 4 }).value(value.also { position += it.toString().length }) }
                            offlineVideo.channelId?.let { value -> writer.name("channelId".also { position += it.length + 4 }).value(value.also { position += it.length + 2 }) }
                            offlineVideo.channelLogin?.let { value -> writer.name("channelLogin".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                            offlineVideo.channelName?.let { value -> writer.name("channelName".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                            offlineVideo.gameId?.let { value -> writer.name("gameId".also { position += it.length + 4 }).value(value.also { position += it.length + 2 }) }
                            offlineVideo.gameSlug?.let { value -> writer.name("gameSlug".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                            offlineVideo.gameName?.let { value -> writer.name("gameName".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                            writer.endObject().also { position += 1 }
                            writer.name("startTime".also { position += it.length + 4 }).value(startTimeSeconds.also { position += it.toString().length })
                        }
                        var cursor: String? = null
                        do {
                            val response = if (cursor == null) {
                                graphQLRepository.loadVideoMessagesDownload(networkLibrary, gqlHeaders, videoId, offset = if (resumed) savedOffset else startTimeSeconds)
                            } else {
                                graphQLRepository.loadVideoMessagesDownload(networkLibrary, gqlHeaders, videoId, cursor = cursor)
                            }
                            val messageObjects = response.jsonObject["data"]?.jsonObject?.get("video")?.jsonObject?.get("comments")?.jsonObject?.get("edges")?.jsonArray?.mapNotNull {
                                it.jsonObject["node"]?.jsonObject
                            } ?: emptyList()
                            val data = json.decodeFromJsonElement<VideoMessagesResponse>(response).data!!.video.comments
                            val comments = if (cursor == null && resumed) {
                                writer.beginObject().also { position += 1 }
                                val list = mutableListOf<JsonObject>()
                                messageObjects.forEach { json ->
                                    StringReader(json.toString()).use { string ->
                                        JsonReader(string).use { reader ->
                                            readMessageObject(reader)?.let {
                                                it.offsetSeconds?.let { offset ->
                                                    if ((offset == savedOffset && !latestSavedMessages.contains(it)) || offset > savedOffset) {
                                                        list.add(json)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                list
                            } else messageObjects
                            cursor = data.edges.lastOrNull()?.cursor
                            if (comments.isNotEmpty()) {
                                writer.name("comments".also { position += it.length + 4 })
                                writer.beginArray().also { position += 1 }
                                var empty = true
                                comments.forEach {
                                    val length = writeJsonElement(null, it, writer)
                                    if (length > 0L) {
                                        position += length + 1
                                        empty = false
                                    }
                                }
                                writer.endArray().also { if (empty) { position += 1 } }
                            }
                            if (downloadEmotes) {
                                val words = mutableListOf<String>()
                                val emoteIds = mutableListOf<String>()
                                val badges = mutableListOf<Badge>()
                                data.edges.mapNotNull { comment ->
                                    comment.node.let { item ->
                                        item.message?.let { message ->
                                            val chatMessage = StringBuilder()
                                            message.fragments?.mapNotNull { fragment ->
                                                fragment.text?.let { text ->
                                                    fragment.emote?.emoteID.also { chatMessage.append(text) }
                                                }
                                            }?.let { emoteIds.addAll(it) }
                                            message.userBadges?.mapNotNull { badge ->
                                                badge.setID?.let { setId ->
                                                    badge.version?.let { version ->
                                                        Badge(
                                                            setId = setId,
                                                            version = version,
                                                        )
                                                    }
                                                }
                                            }?.let { badges.addAll(it) }
                                            chatMessage.toString().split(" ").forEach {
                                                if (!words.contains(it)) {
                                                    words.add(it)
                                                }
                                            }
                                        }
                                    }
                                }
                                val twitchEmotes = mutableListOf<TwitchEmote>()
                                val twitchBadges = mutableListOf<TwitchBadge>()
                                val cheerEmotes = mutableListOf<CheerEmote>()
                                val emotes = mutableListOf<Emote>()
                                emoteIds.forEach {
                                    if (!savedTwitchEmotes.contains(it)) {
                                        savedTwitchEmotes.add(it)
                                        twitchEmotes.add(TwitchEmote(id = it))
                                    }
                                }
                                badges.forEach {
                                    val pair = Pair(it.setId, it.version)
                                    if (!savedBadges.contains(pair)) {
                                        savedBadges.add(pair)
                                        val badge = badgeList.find { badge -> badge.setId == it.setId && badge.version == it.version }
                                        if (badge != null) {
                                            twitchBadges.add(badge)
                                        }
                                    }
                                }
                                words.forEach { word ->
                                    if (!savedEmotes.contains(word)) {
                                        val bitsCount = word.takeLastWhile { it.isDigit() }
                                        val cheerEmote = if (bitsCount.isNotEmpty()) {
                                            val bitsName = word.substringBeforeLast(bitsCount)
                                            cheerEmoteList.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
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
                                                val response = suspendCoroutine { continuation ->
                                                    httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                }
                                                response.second
                                            }
                                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                    cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                    request.future.get().responseBody as ByteArray
                                                } else {
                                                    val response = suspendCoroutine { continuation ->
                                                        cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                    }
                                                    response.second
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
                                                val response = suspendCoroutine { continuation ->
                                                    httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                }
                                                response.second
                                            }
                                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                    cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                    request.future.get().responseBody as ByteArray
                                                } else {
                                                    val response = suspendCoroutine { continuation ->
                                                        cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                    }
                                                    response.second
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
                                                val response = suspendCoroutine { continuation ->
                                                    httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                }
                                                response.second
                                            }
                                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                    cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                    request.future.get().responseBody as ByteArray
                                                } else {
                                                    val response = suspendCoroutine { continuation ->
                                                        cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                    }
                                                    response.second
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
                                                val response = suspendCoroutine { continuation ->
                                                    httpEngine!!.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                                }
                                                response.second
                                            }
                                            networkLibrary == "Cronet" && cronetEngine != null -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                    val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                    cronetEngine!!.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                                                    request.future.get().responseBody as ByteArray
                                                } else {
                                                    val response = suspendCoroutine { continuation ->
                                                        cronetEngine!!.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                    }
                                                    response.second
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
                            val lastOffsetSeconds = data.edges.lastOrNull()?.node?.contentOffsetSeconds
                            if (lastOffsetSeconds != null) {
                                offlineRepository.updateVideo(offlineVideo.apply {
                                    chatProgress = lastOffsetSeconds - startTimeSeconds
                                    chatBytes = position
                                    chatOffsetSeconds = lastOffsetSeconds
                                })
                            }
                        } while (lastOffsetSeconds?.let { it < endTimeSeconds } != false && !data.edges.lastOrNull()?.cursor.isNullOrBlank() && data.pageInfo?.hasNextPage != false)
                        offlineRepository.updateVideo(offlineVideo.apply {
                            chatProgress = offlineVideo.maxChatProgress
                        })
                        writer.endObject().also { position += 1 }
                    }
                }
            }
        }
    }

    private fun writeJsonElement(key: String?, value: JsonElement, writer: JsonWriter): Long {
        var position = 0L
        if (key != "__typename") {
            when (value) {
                is JsonObject -> {
                    if (key != null) {
                        writer.name(key.also { position += it.length + 3 })
                    }
                    writer.beginObject().also { position += 1 }
                    var empty = true
                    value.jsonObject.entries.forEach {
                        val length = writeJsonElement(it.key, it.value, writer)
                        if (length > 0L) {
                            position += length + 1
                            empty = false
                        }
                    }
                    writer.endObject().also { if (empty) { position += 1 } }
                }
                is JsonArray -> {
                    if (key != null) {
                        writer.name(key.also { position += it.length + 3 })
                    }
                    writer.beginArray().also { position += 1 }
                    var empty = true
                    value.jsonArray.forEach {
                        val length = writeJsonElement(null, it, writer)
                        if (length > 0L) {
                            position += length + 1
                            empty = false
                        }
                    }
                    writer.endArray().also { if (empty) { position += 1 } }
                }
                is JsonPrimitive -> {
                    if (value !is JsonNull) {
                        if (value.isString) {
                            if (key != null) {
                                writer.name(key.also { position += it.length + 3 })
                            }
                            writer.value(value.content.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                        } else {
                            value.intOrNull?.let { int ->
                                if (key != null) {
                                    writer.name(key.also { position += it.length + 3 })
                                }
                                writer.value(int.also { position += it.toString().length })
                            }
                            value.booleanOrNull?.let { boolean ->
                                if (key != null) {
                                    writer.name(key.also { position += it.length + 3 })
                                }
                                writer.value(boolean.also { position += it.toString().length })
                            }
                        }
                    }
                }
            }
        }
        return position
    }

    private fun readMessageObject(reader: JsonReader): VideoChatMessage? {
        var chatMessage: VideoChatMessage? = null
        reader.beginObject()
        val message = StringBuilder()
        var id: String? = null
        var offsetSeconds: Int? = null
        var userId: String? = null
        var userLogin: String? = null
        var userName: String? = null
        var color: String? = null
        val emotesList = mutableListOf<TwitchEmote>()
        val badgesList = mutableListOf<Badge>()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "commenter" -> {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> userId = reader.nextString()
                                    "login" -> userLogin = reader.nextString()
                                    "displayName" -> userName = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                "contentOffsetSeconds" -> offsetSeconds = reader.nextInt()
                "message" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "fragments" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.beginObject()
                                    var emoteId: String? = null
                                    var fragmentText: String? = null
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "emote" -> {
                                                when (reader.peek()) {
                                                    JsonToken.BEGIN_OBJECT -> {
                                                        reader.beginObject()
                                                        while (reader.hasNext()) {
                                                            when (reader.nextName()) {
                                                                "emoteID" -> emoteId = reader.nextString()
                                                                else -> reader.skipValue()
                                                            }
                                                        }
                                                        reader.endObject()
                                                    }
                                                    else -> reader.skipValue()
                                                }
                                            }
                                            "text" -> fragmentText = reader.nextString()
                                            else -> reader.skipValue()
                                        }
                                    }
                                    if (fragmentText != null && !emoteId.isNullOrBlank()) {
                                        emotesList.add(TwitchEmote(
                                            id = emoteId,
                                            begin = message.codePointCount(0, message.length),
                                            end = message.codePointCount(0, message.length) + fragmentText.lastIndex
                                        ))
                                    }
                                    message.append(fragmentText)
                                    reader.endObject()
                                }
                                reader.endArray()
                            }
                            "userBadges" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.beginObject()
                                    var set: String? = null
                                    var version: String? = null
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "setID" -> set = reader.nextString()
                                            "version" -> version = reader.nextString()
                                            else -> reader.skipValue()
                                        }
                                    }
                                    if (!set.isNullOrBlank() && !version.isNullOrBlank()) {
                                        badgesList.add(
                                            Badge(set, version)
                                        )
                                    }
                                    reader.endObject()
                                }
                                reader.endArray()
                            }
                            "userColor" -> {
                                when (reader.peek()) {
                                    JsonToken.STRING -> color = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    chatMessage = VideoChatMessage(
                        id = id,
                        offsetSeconds = offsetSeconds,
                        userId = userId,
                        userLogin = userLogin,
                        userName = userName,
                        message = message.toString(),
                        color = color,
                        emotes = emotesList,
                        badges = badgesList,
                        fullMsg = null
                    )
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return chatMessage
    }

    private fun createForegroundInfo(): ForegroundInfo {
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
            setContentTitle(ContextCompat.getString(context, R.string.downloading))
            setContentText(offlineVideo.name)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setProgress(offlineVideo.maxProgress, offlineVideo.progress, false)
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
