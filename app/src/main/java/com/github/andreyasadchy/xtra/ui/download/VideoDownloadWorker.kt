package com.github.andreyasadchy.xtra.ui.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.prefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
import okio.appendingSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import javax.inject.Inject

@HiltWorker
class VideoDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    @Inject
    lateinit var repository: ApiRepository

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var graphQLRepository: GraphQLRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var json: Json

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var offlineVideo: OfflineVideo

    override suspend fun doWork(): Result {
        offlineVideo = offlineRepository.getVideoById(inputData.getInt(KEY_VIDEO_ID, 0)) ?: return Result.failure()
        offlineRepository.updateVideo(offlineVideo.apply { status = OfflineVideo.STATUS_DOWNLOADING })
        setForeground(createForegroundInfo())
        return runBlocking {
            val sourceUrl = offlineVideo.sourceUrl!!
            if (sourceUrl.endsWith(".m3u8")) {
                val path = offlineVideo.downloadPath!!
                val from = offlineVideo.fromTime!!
                val to = offlineVideo.toTime!!
                val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
                val playlist = okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                    response.body.byteStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
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
                val segments = ArrayList<Segment>()
                if (offlineVideo.progress < offlineVideo.maxProgress) {
                    for (i in fromIndex + offlineVideo.progress..toIndex) {
                        val segment = playlist.segments[i]
                        segments.add(segment.copy(uri = segment.uri.replace("-unmuted", "-muted")))
                    }
                }
                val requestSemaphore = Semaphore(context.prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
                val mutexMap = mutableMapOf<Int, Mutex>()
                val count = MutableStateFlow(0)
                val collector = launch {
                    count.collect {
                        mutexMap[it]?.unlock()
                        mutexMap.remove(it)
                    }
                }
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
                        val fileName = "${offlineVideo.videoId ?: ""}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}.${segments.first().uri.substringAfterLast(".")}"
                        val fileUri = if (isShared) {
                            val directory = DocumentFile.fromTreeUri(applicationContext, path.toUri())!!
                            (directory.findFile(fileName) ?: directory.createFile("", fileName))!!.uri.toString()
                        } else {
                            "$path${File.separator}$fileName"
                        }
                        val startPosition = relativeStartTimes[fromIndex]
                        val initSegmentBytes = if (playlist.initSegmentUri != null) {
                            okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
                                if (isShared) {
                                    context.contentResolver.openOutputStream(fileUri.toUri(), "wa")!!.sink().buffer()
                                } else {
                                    File(fileUri).appendingSink().buffer()
                                }.use { sink ->
                                    sink.writeAll(response.body.source())
                                }
                                response.body.contentLength()
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
                    segments.map {
                        async {
                            requestSemaphore.withPermit {
                                okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                    val mutex = Mutex()
                                    val id = segments.indexOf(it)
                                    if (count.value != id) {
                                        mutex.lock()
                                        mutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        if (isShared) {
                                            context.contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!.sink().buffer()
                                        } else {
                                            File(videoFileUri).appendingSink().buffer()
                                        }.use { sink ->
                                            sink.writeAll(response.body.source())
                                            offlineRepository.updateVideo(offlineVideo.apply {
                                                bytes += response.body.contentLength()
                                                progress += 1
                                            })
                                        }
                                    }
                                }
                                count.update { it + 1 }
                                setForeground(createForegroundInfo())
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
                        val directory = DocumentFile.fromTreeUri(applicationContext, path.toUri())!!
                        val videoDirectory = directory.findFile(videoDirectoryName) ?: directory.createDirectory(videoDirectoryName)!!
                        val playlistFileUri = if (!offlineVideo.url.isNullOrBlank()) {
                            offlineVideo.url!!
                        } else {
                            val sharedSegments = ArrayList<Segment>()
                            for (i in fromIndex..toIndex) {
                                val segment = playlist.segments[i]
                                sharedSegments.add(segment.copy(uri = videoDirectory.uri.toString() + "%2F" + segment.uri.replace("-unmuted", "-muted")))
                            }
                            val fileName = "${offlineVideo.downloadDate}.m3u8"
                            val playlistFile = videoDirectory.findFile(fileName) ?: videoDirectory.createFile("", fileName)!!
                            applicationContext.contentResolver.openOutputStream(playlistFile.uri)!!.use {
                                PlaylistUtils.writeMediaPlaylist(playlist.copy(
                                    initSegmentUri = playlist.initSegmentUri?.let { uri -> videoDirectory.uri.toString() + "%2F" + uri },
                                    segments = sharedSegments
                                ), it)
                            }
                            val playlistUri = playlistFile.uri.toString()
                            val startPosition = relativeStartTimes[fromIndex]
                            if (playlist.initSegmentUri != null) {
                                okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
                                    val file = videoDirectory.findFile(playlist.initSegmentUri) ?: videoDirectory.createFile("", playlist.initSegmentUri)!!
                                    context.contentResolver.openOutputStream(file.uri)!!.sink().buffer().use { sink ->
                                        sink.writeAll(response.body.source())
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
                        val playlists = videoDirectory.listFiles().filter { it.isFile && it.name?.endsWith(".m3u8") == true && it.uri.toString() != playlistFileUri }
                        playlists.forEach { file ->
                            val p = applicationContext.contentResolver.openInputStream(file.uri)!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { downloadedTracks.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                        }
                        segments.map {
                            async {
                                requestSemaphore.withPermit {
                                    if (videoDirectory.findFile(it.uri) == null || !downloadedTracks.contains(it.uri)) {
                                        okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                            val file = videoDirectory.findFile(it.uri) ?: videoDirectory.createFile("", it.uri)!!
                                            context.contentResolver.openOutputStream(file.uri)!!.sink().buffer().use { sink ->
                                                sink.writeAll(response.body.source())
                                            }
                                        }
                                    }
                                    val mutex = Mutex()
                                    val id = segments.indexOf(it)
                                    if (count.value != id) {
                                        mutex.lock()
                                        mutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        offlineRepository.updateVideo(offlineVideo.apply { progress += 1 })
                                    }
                                    count.update { it + 1 }
                                    setForeground(createForegroundInfo())
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
                                PlaylistUtils.writeMediaPlaylist(playlist.copy(segments = segments), it)
                            }
                            val startPosition = relativeStartTimes[fromIndex]
                            if (playlist.initSegmentUri != null) {
                                okHttpClient.newCall(Request.Builder().url(urlPath + playlist.initSegmentUri).build()).execute().use { response ->
                                    File(directory + playlist.initSegmentUri).sink().buffer().use { sink ->
                                        sink.writeAll(response.body.source())
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
                        val playlists = File(directory).listFiles(FileFilter { it.extension == "m3u8" && it.path != playlistFileUri })
                        playlists?.forEach { file ->
                            val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                            p.segments.forEach { downloadedTracks.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                        }
                        segments.map {
                            async {
                                requestSemaphore.withPermit {
                                    if (!File(directory + it.uri).exists() || !downloadedTracks.contains(it.uri)) {
                                        okHttpClient.newCall(Request.Builder().url(urlPath + it.uri).build()).execute().use { response ->
                                            File(directory + it.uri).sink().buffer().use { sink ->
                                                sink.writeAll(response.body.source())
                                            }
                                        }
                                    }
                                    val mutex = Mutex()
                                    val id = segments.indexOf(it)
                                    if (count.value != id) {
                                        mutex.lock()
                                        mutexMap[id] = mutex
                                    }
                                    mutex.withLock {
                                        offlineRepository.updateVideo(offlineVideo.apply { progress += 1 })
                                    }
                                    count.update { it + 1 }
                                    setForeground(createForegroundInfo())
                                }
                            }
                        }
                    }
                }
                val chatJob = startChatJob(this, path)
                jobs.awaitAll()
                collector.cancel()
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
                        val directory = DocumentFile.fromTreeUri(applicationContext, path.toUri())!!
                        (directory.findFile(fileName) ?: directory.createFile("", fileName))!!.uri.toString()
                    } else {
                        "$path${File.separator}$fileName"
                    }
                    offlineRepository.updateVideo(offlineVideo.apply {
                        url = fileUri
                    })
                    fileUri
                }
                val jobs = async {
                    if (offlineVideo.progress < offlineVideo.maxProgress) {
                        okHttpClient.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                            if (isShared) {
                                context.contentResolver.openOutputStream(videoFileUri.toUri())!!.sink().buffer()
                            } else {
                                File(videoFileUri).sink().buffer()
                            }.use { sink ->
                                sink.writeAll(response.body.source())
                            }
                        }
                        offlineRepository.updateVideo(offlineVideo.apply { progress = offlineVideo.maxProgress })
                        setForeground(createForegroundInfo())
                    }
                }
                val chatJob = startChatJob(this, path)
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
            Result.success()
        }
    }

    private fun startChatJob(coroutineScope: CoroutineScope, path: String): Job {
        return coroutineScope.launch {
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
                            val directory = DocumentFile.fromTreeUri(applicationContext, path.toUri())
                            (directory?.findFile(fileName) ?: directory?.createFile("", fileName))!!.uri.toString()
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
                    val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
                    val helixHeaders = TwitchApiHelper.getGQLHeaders(context)
                    val emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
                    val useWebp = context.prefs().getBoolean(C.CHAT_USE_WEBP, true)
                    val channelId = offlineVideo.channelId
                    val channelLogin = offlineVideo.channelLogin
                    val badgeList = mutableListOf<TwitchBadge>().apply {
                        if (downloadEmotes) {
                            val channelBadges = try { repository.loadChannelBadges(helixHeaders, gqlHeaders, channelId, channelLogin, emoteQuality, false) } catch (e: Exception) { emptyList() }
                            addAll(channelBadges)
                            val globalBadges = try { repository.loadGlobalBadges(helixHeaders, gqlHeaders, emoteQuality, false) } catch (e: Exception) { emptyList() }
                            addAll(globalBadges.filter { badge -> badge.setId !in channelBadges.map { it.setId } })
                        }
                    }
                    val cheerEmoteList = if (downloadEmotes) {
                        try {
                            repository.loadCheerEmotes(helixHeaders, gqlHeaders, channelId, channelLogin, animateGifs = true, checkIntegrity = false)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } else emptyList()
                    val emoteList = mutableListOf<Emote>().apply {
                        if (downloadEmotes) {
                            if (channelId != null) {
                                try { addAll(playerRepository.loadStvEmotes(channelId, useWebp).second) } catch (e: Exception) {}
                                try { addAll(playerRepository.loadBttvEmotes(channelId, useWebp)) } catch (e: Exception) {}
                                try { addAll(playerRepository.loadFfzEmotes(channelId, useWebp)) } catch (e: Exception) {}
                            }
                            try { addAll(playerRepository.loadGlobalStvEmotes(useWebp)) } catch (e: Exception) {}
                            try { addAll(playerRepository.loadGlobalBttvEmotes(useWebp)) } catch (e: Exception) {}
                            try { addAll(playerRepository.loadGlobalFfzEmotes(useWebp)) } catch (e: Exception) {}
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
                                    graphQLRepository.loadVideoMessagesDownload(gqlHeaders, videoId, offset = if (resumed) savedOffset else startTimeSeconds)
                                } else {
                                    graphQLRepository.loadVideoMessagesDownload(gqlHeaders, videoId, cursor = cursor)
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
                                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response.body.source().readByteArray(), Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("id".also { position += it.length + 4 }).value(emote.id.also { position += it.toString().toByteArray().size + it.toString().count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.endObject().also { position += 1 }
                                            }
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
                                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response.body.source().readByteArray(), Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("setId".also { position += it.length + 4 }).value(badge.setId.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("version".also { position += it.length + 4 }).value(badge.version.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.endObject().also { position += 1 }
                                            }
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
                                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response.body.source().readByteArray(), Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("name".also { position += it.length + 4 }).value(cheerEmote.name.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("minBits".also { position += it.length + 4 }).value(cheerEmote.minBits.also { position += it.toString().length })
                                                cheerEmote.color?.let { value -> writer.name("color".also { position += it.length + 4 }).value(value.also { position += it.toByteArray().size + it.count { c -> c == '"' || c == '\\' } + 2 }) }
                                                writer.endObject().also { position += 1 }
                                            }
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
                                            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                                writer.beginObject().also { position += 1 }
                                                writer.name("data".also { position += it.length + 3 }).value(Base64.encodeToString(response.body.source().readByteArray(), Base64.NO_WRAP or Base64.NO_PADDING).also { position += it.toByteArray().size + 2 })
                                                writer.name("name".also { position += it.length + 4 }).value(emote.name.also { position += it.toString().toByteArray().size + it.toString().count { c -> c == '"' || c == '\\' } + 2 })
                                                writer.name("isZeroWidth".also { position += it.length + 4 }).value(emote.isZeroWidth.also { position += it.toString().length })
                                                writer.endObject().also { position += 1 }
                                            }
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
