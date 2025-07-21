package com.github.andreyasadchy.xtra.ui.saved.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.appendingSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class DownloadsViewModel @Inject internal constructor(
    @ApplicationContext private val applicationContext: Context,
    private val repository: OfflineRepository,
) : ViewModel() {

    var selectedVideo: OfflineVideo? = null
    private val videosInUse = mutableListOf<OfflineVideo>()
    private val currentDownloads = mutableListOf<Int>()
    private val liveDownloads = mutableListOf<String>()

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30),
    ) {
        repository.loadAllVideos()
    }.flow.cachedIn(viewModelScope)

    fun finishDownload(video: OfflineVideo) {
        video.chatUrl?.let { url ->
            val isShared = url.toUri().scheme == ContentResolver.SCHEME_CONTENT
            if (isShared) {
                applicationContext.contentResolver.openFileDescriptor(url.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(video.chatBytes)
                    }
                }
            } else {
                FileOutputStream(url).use { output ->
                    output.channel.truncate(video.chatBytes)
                }
            }
            if (isShared) {
                applicationContext.contentResolver.openOutputStream(url.toUri(), "wa")!!.bufferedWriter()
            } else {
                FileOutputStream(url, true).bufferedWriter()
            }.use { fileWriter ->
                fileWriter.write("}")
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateVideo(video.apply {
                status = OfflineVideo.STATUS_DOWNLOADED
            })
        }
    }

    fun checkLiveDownloadStatus(channelLogin: String) {
        if (!liveDownloads.contains(channelLogin)) {
            liveDownloads.add(channelLogin)
            viewModelScope.launch(Dispatchers.IO) {
                WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWorkFlow(channelLogin).collect { list ->
                    val work = list.lastOrNull()
                    when {
                        work == null || work.state.isFinished -> {
                            repository.getLiveDownload(channelLogin)?.let { video ->
                                if (video.status == OfflineVideo.STATUS_DOWNLOADING || video.status == OfflineVideo.STATUS_BLOCKED || video.status == OfflineVideo.STATUS_QUEUED || video.status == OfflineVideo.STATUS_QUEUED_WIFI || video.status == OfflineVideo.STATUS_WAITING_FOR_STREAM) {
                                    repository.updateVideo(video.apply {
                                        status = OfflineVideo.STATUS_PENDING
                                    })
                                }
                            }
                            cancel()
                        }
                        work.state == WorkInfo.State.ENQUEUED -> {
                            repository.getLiveDownload(channelLogin)?.let { video ->
                                repository.updateVideo(video.apply {
                                    status = if (work.constraints.requiredNetworkType == NetworkType.UNMETERED) {
                                        OfflineVideo.STATUS_QUEUED_WIFI
                                    } else {
                                        OfflineVideo.STATUS_QUEUED
                                    }
                                })
                            }
                        }
                        work.state == WorkInfo.State.BLOCKED -> {
                            repository.getLiveDownload(channelLogin)?.let { video ->
                                repository.updateVideo(video.apply {
                                    status = OfflineVideo.STATUS_BLOCKED
                                })
                            }
                        }
                    }
                }
            }.invokeOnCompletion {
                liveDownloads.remove(channelLogin)
            }
        }
    }

    fun checkDownloadStatus(videoId: Int) {
        if (!currentDownloads.contains(videoId)) {
            currentDownloads.add(videoId)
            viewModelScope.launch(Dispatchers.IO) {
                WorkManager.getInstance(applicationContext).getWorkInfosByTagFlow(videoId.toString()).collect { list ->
                    val work = list.lastOrNull()
                    when {
                        work == null || work.state.isFinished -> {
                            repository.getVideoById(videoId)?.let { video ->
                                if (video.status == OfflineVideo.STATUS_DOWNLOADING || video.status == OfflineVideo.STATUS_BLOCKED || video.status == OfflineVideo.STATUS_QUEUED || video.status == OfflineVideo.STATUS_QUEUED_WIFI) {
                                    repository.updateVideo(video.apply {
                                        status = OfflineVideo.STATUS_PENDING
                                    })
                                }
                            }
                            cancel()
                        }
                        work.state == WorkInfo.State.ENQUEUED -> {
                            repository.getVideoById(videoId)?.let { video ->
                                repository.updateVideo(video.apply {
                                    status = if (work.constraints.requiredNetworkType == NetworkType.UNMETERED) {
                                        OfflineVideo.STATUS_QUEUED_WIFI
                                    } else {
                                        OfflineVideo.STATUS_QUEUED
                                    }
                                })
                            }
                        }
                        work.state == WorkInfo.State.BLOCKED -> {
                            repository.getVideoById(videoId)?.let { video ->
                                repository.updateVideo(video.apply {
                                    status = OfflineVideo.STATUS_BLOCKED
                                })
                            }
                        }
                    }
                }
            }.invokeOnCompletion {
                currentDownloads.remove(videoId)
            }
        }
    }

    fun convertToFile(video: OfflineVideo) {
        val videoUrl = video.url
        if (!videosInUse.contains(video) && videoUrl != null) {
            videosInUse.add(video)
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateVideo(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_CONVERTING
                })
                if (videoUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
                    val oldVideoDirectoryUri = videoUrl.substringBeforeLast("%2F")
                    val oldDirectoryUri = oldVideoDirectoryUri.substringBeforeLast("%2F", oldVideoDirectoryUri.substringBeforeLast("%3A") + "%3A")
                    val oldPlaylist = applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    val videoFileName = "${video.videoId ?: ""}${video.quality ?: ""}${video.downloadDate}.${oldPlaylist.segments.first().uri.substringAfterLast(".")}"
                    val newVideoFileUri = oldDirectoryUri + (if (!oldDirectoryUri.endsWith("%3A")) "%2F" else "") + videoFileName
                    val tracksToDelete = mutableListOf<String>()
                    oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                    val playlists = repository.getPlaylists().mapNotNull { video ->
                        video.url?.takeIf {
                            it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                    && it.substringBeforeLast("%2F") == oldVideoDirectoryUri
                                    && it != videoUrl
                        }
                    }
                    playlists.forEach { uri ->
                        try {
                            val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                        } catch (e: Exception) {

                        }
                    }
                    val new = try {
                        applicationContext.contentResolver.openOutputStream(newVideoFileUri.toUri())!!.close()
                        false
                    } catch (e: IllegalArgumentException) {
                        DocumentsContract.createDocument(applicationContext.contentResolver, oldDirectoryUri.toUri(), "", videoFileName)
                        true
                    }
                    if (oldPlaylist.initSegmentUri != null && new) {
                        val oldFileUri = oldPlaylist.initSegmentUri
                        applicationContext.contentResolver.openOutputStream(newVideoFileUri.toUri(), "wa")!!.sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.source().buffer())
                        }
                    }
                    repository.updateVideo(video.apply {
                        maxProgress = tracksToDelete.count()
                    })
                    oldPlaylist.segments.forEach { track ->
                        val oldFileUri = track.uri
                        val oldFileName = oldFileUri.substringAfterLast("%2F").substringAfterLast("/")
                        applicationContext.contentResolver.openOutputStream(newVideoFileUri.toUri(), "wa")!!.sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.source().buffer())
                        }
                        if (tracksToDelete.contains(oldFileName)) {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, oldFileUri.toUri())
                            } catch (e: Exception) {

                            }
                        }
                        repository.updateVideo(video.apply {
                            progress += 1
                        })
                    }
                    repository.updateVideo(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                newVideoFileUri
                            }
                        }
                        url = newVideoFileUri
                    })
                    if (playlists.isNotEmpty()) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                        } catch (e: Exception) {

                        }
                    } else {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldVideoDirectoryUri.toUri())
                        } catch (e: Exception) {

                        }
                    }
                } else {
                    val oldPlaylistFile = File(videoUrl)
                    if (oldPlaylistFile.exists()) {
                        val oldVideoDirectory = oldPlaylistFile.parentFile
                        val oldDirectory = oldVideoDirectory?.parentFile
                        if (oldVideoDirectory != null && oldDirectory != null) {
                            val oldPlaylist = FileInputStream(oldPlaylistFile).use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            val videoFileName = "${video.videoId ?: ""}${video.quality ?: ""}${video.downloadDate}.${oldPlaylist.segments.first().uri.substringAfterLast(".")}"
                            val newVideoFileUri = "${oldDirectory.path}${File.separator}$videoFileName"
                            val tracksToDelete = mutableListOf<String>()
                            oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            val playlists = oldVideoDirectory.listFiles { it.extension == "m3u8" && it != oldPlaylistFile }
                            playlists?.forEach { file ->
                                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                                p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            }
                            if (oldPlaylist.initSegmentUri != null && File(newVideoFileUri).length() == 0L) {
                                val oldFile = File(oldVideoDirectory.path + File.separator + oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    File(newVideoFileUri).appendingSink().buffer().use { sink ->
                                        sink.writeAll(oldFile.source().buffer())
                                    }
                                }
                            }
                            repository.updateVideo(video.apply {
                                maxProgress = tracksToDelete.count()
                            })
                            oldPlaylist.segments.forEach { track ->
                                val oldFile = File(oldVideoDirectory.path + File.separator + track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    File(newVideoFileUri).appendingSink().buffer().use { sink ->
                                        sink.writeAll(oldFile.source().buffer())
                                    }
                                    if (tracksToDelete.contains(oldFile.name)) {
                                        oldFile.delete()
                                    }
                                }
                                repository.updateVideo(video.apply {
                                    progress += 1
                                })
                            }
                            repository.updateVideo(video.apply {
                                thumbnail.let {
                                    if (it == null || it == url || !File(it).exists()) {
                                        thumbnail = newVideoFileUri
                                    }
                                }
                                url = newVideoFileUri
                            })
                            if (playlists?.isNotEmpty() == true) {
                                oldPlaylistFile.delete()
                            } else {
                                oldVideoDirectory.deleteRecursively()
                            }
                        }
                    }
                }
            }.invokeOnCompletion {
                videosInUse.remove(video)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateVideo(video.apply {
                        status = OfflineVideo.STATUS_DOWNLOADED
                    })
                }
            }
        }
    }

    fun moveToSharedStorage(newUri: Uri, video: OfflineVideo) {
        val videoUrl = video.url
        if (!videosInUse.contains(video) && videoUrl != null) {
            videosInUse.add(video)
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateVideo(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_MOVING
                })
                if (videoUrl.endsWith(".m3u8")) {
                    val oldPlaylistFile = File(videoUrl)
                    if (oldPlaylistFile.exists()) {
                        val oldVideoDirectory = oldPlaylistFile.parentFile
                        if (oldVideoDirectory != null) {
                            val newDirectoryUri = newUri.toString() + "/document/" + newUri.toString().substringAfter("/tree/")
                            val newVideoDirectoryUri = newDirectoryUri + (if (!newDirectoryUri.endsWith("%3A")) "%2F" else "") + oldVideoDirectory.name
                            try {
                                applicationContext.contentResolver.openOutputStream(newVideoDirectoryUri.toUri())!!.close()
                            } catch (e: Exception) {
                                if (e is IllegalArgumentException) {
                                    DocumentsContract.createDocument(applicationContext.contentResolver, newDirectoryUri.toUri(), DocumentsContract.Document.MIME_TYPE_DIR, oldVideoDirectory.name)
                                }
                            }
                            val newPlaylistFileUri = newVideoDirectoryUri + "%2F" + oldPlaylistFile.name
                            val oldPlaylist = FileInputStream(oldPlaylistFile).use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            val segments = ArrayList<Segment>()
                            oldPlaylist.segments.forEach { segment ->
                                segments.add(segment.copy(uri = newVideoDirectoryUri + "%2F" + segment.uri.substringAfterLast("%2F").substringAfterLast("/")))
                            }
                            try {
                                applicationContext.contentResolver.openOutputStream(newPlaylistFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(applicationContext.contentResolver, newVideoDirectoryUri.toUri(), "", oldPlaylistFile.name)
                                applicationContext.contentResolver.openOutputStream(newPlaylistFileUri.toUri())!!
                            }.use {
                                PlaylistUtils.writeMediaPlaylist(oldPlaylist.copy(
                                    initSegmentUri = oldPlaylist.initSegmentUri?.let { uri -> newVideoDirectoryUri + "%2F" + uri.substringAfterLast("%2F").substringAfterLast("/") },
                                    segments = segments
                                ), it)
                            }
                            val tracksToDelete = mutableListOf<String>()
                            oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            val playlists = oldVideoDirectory.listFiles { it.extension == "m3u8" && it != oldPlaylistFile }
                            playlists?.forEach { file ->
                                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                                p.segments.forEach { tracksToDelete.remove(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                            }
                            if (oldPlaylist.initSegmentUri != null) {
                                val oldFile = File(oldVideoDirectory.path + File.separator + oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    val newFileUri = newVideoDirectoryUri + "%2F" + oldFile.name
                                    try {
                                        applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(applicationContext.contentResolver, newVideoDirectoryUri.toUri(), "", oldFile.name)
                                        applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                                    }.sink().buffer().use { sink ->
                                        sink.writeAll(oldFile.source().buffer())
                                    }
                                }
                            }
                            repository.updateVideo(video.apply {
                                maxProgress = tracksToDelete.count()
                            })
                            oldPlaylist.segments.forEach { track ->
                                val oldFile = File(oldVideoDirectory.path + File.separator + track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                if (oldFile.exists()) {
                                    val newFileUri = newVideoDirectoryUri + "%2F" + oldFile.name
                                    try {
                                        applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(applicationContext.contentResolver, newVideoDirectoryUri.toUri(), "", oldFile.name)
                                        applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                                    }.sink().buffer().use { sink ->
                                        sink.writeAll(oldFile.source().buffer())
                                    }
                                    if (tracksToDelete.contains(oldFile.name)) {
                                        oldFile.delete()
                                    }
                                }
                                repository.updateVideo(video.apply {
                                    progress += 1
                                })
                            }
                            val oldChatFile = video.chatUrl?.let { uri -> File(uri).takeIf { it.exists() } }
                            val newChatFileUri = oldChatFile?.let { newDirectoryUri + (if (!newDirectoryUri.endsWith("%3A")) "%2F" else "") + it.name }
                            if (newChatFileUri != null) {
                                try {
                                    applicationContext.contentResolver.openOutputStream(newChatFileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(applicationContext.contentResolver, newDirectoryUri.toUri(), "", oldChatFile.name)
                                    applicationContext.contentResolver.openOutputStream(newChatFileUri.toUri())!!
                                }.sink().buffer().use { sink ->
                                    sink.writeAll(oldChatFile.source().buffer())
                                }
                            }
                            repository.updateVideo(video.apply {
                                thumbnail.let {
                                    if (it == null || it == url || !File(it).exists()) {
                                        oldPlaylist.segments.getOrNull(
                                            max(0, (oldPlaylist.segments.size / 2) - 1)
                                        )?.uri?.substringAfterLast("%2F")?.substringAfterLast("/")?.let { trackUri ->
                                            thumbnail = "$newVideoDirectoryUri%2F$trackUri"
                                        }
                                    }
                                }
                                url = newPlaylistFileUri
                                chatUrl = newChatFileUri
                            })
                            if (playlists?.isNotEmpty() == true) {
                                oldPlaylistFile.delete()
                            } else {
                                oldVideoDirectory.deleteRecursively()
                            }
                            oldChatFile?.delete()
                        }
                    }
                } else {
                    val oldFile = File(videoUrl)
                    if (oldFile.exists()) {
                        val newDirectoryUri = newUri.toString() + "/document/" + newUri.toString().substringAfter("/tree/")
                        val newFileUri = newDirectoryUri + (if (!newDirectoryUri.endsWith("%3A")) "%2F" else "") + oldFile.name
                        try {
                            applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                        } catch (e: IllegalArgumentException) {
                            DocumentsContract.createDocument(applicationContext.contentResolver, newDirectoryUri.toUri(), "", oldFile.name)
                            applicationContext.contentResolver.openOutputStream(newFileUri.toUri())!!
                        }.sink().buffer().use { sink ->
                            sink.writeAll(oldFile.source().buffer())
                        }
                        val oldChatFile = video.chatUrl?.let { uri -> File(uri).takeIf { it.exists() } }
                        val newChatFileUri = oldChatFile?.let { newDirectoryUri + (if (!newDirectoryUri.endsWith("%3A")) "%2F" else "") + it.name }
                        if (newChatFileUri != null) {
                            try {
                                applicationContext.contentResolver.openOutputStream(newChatFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(applicationContext.contentResolver, newDirectoryUri.toUri(), "", oldChatFile.name)
                                applicationContext.contentResolver.openOutputStream(newChatFileUri.toUri())!!
                            }.sink().buffer().use { sink ->
                                sink.writeAll(oldChatFile.source().buffer())
                            }
                        }
                        repository.updateVideo(video.apply {
                            thumbnail.let {
                                if (it == null || it == url || !File(it).exists()) {
                                    thumbnail = newFileUri
                                }
                            }
                            url = newFileUri
                            chatUrl = newChatFileUri
                        })
                        oldFile.delete()
                        oldChatFile?.delete()
                    }
                }
            }.invokeOnCompletion {
                videosInUse.remove(video)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateVideo(video.apply {
                        status = OfflineVideo.STATUS_DOWNLOADED
                    })
                }
            }
        }
    }

    fun moveToAppStorage(path: String, video: OfflineVideo) {
        val videoUrl = video.url
        if (!videosInUse.contains(video) && videoUrl != null) {
            videosInUse.add(video)
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateVideo(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_MOVING
                })
                if (videoUrl.endsWith(".m3u8")) {
                    val oldPlaylistFileName = Uri.decode(videoUrl.substringAfterLast("%2F"))
                    val oldVideoDirectoryUri = videoUrl.substringBeforeLast("%2F")
                    val oldVideoDirectoryName = Uri.decode(oldVideoDirectoryUri.substringAfterLast("%2F").substringAfterLast("%3A"))
                    val newVideoDirectoryUri = path + File.separator + oldVideoDirectoryName
                    File(newVideoDirectoryUri).mkdir()
                    val newPlaylistFileUri = newVideoDirectoryUri + File.separator + oldPlaylistFileName
                    val oldPlaylist = applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    val segments = ArrayList<Segment>()
                    oldPlaylist.segments.forEach { segment ->
                        segments.add(segment.copy(uri = newVideoDirectoryUri + File.separator + Uri.decode(segment.uri.substringAfterLast("%2F").substringAfterLast("/"))))
                    }
                    FileOutputStream(newPlaylistFileUri).use {
                        PlaylistUtils.writeMediaPlaylist(oldPlaylist.copy(
                            initSegmentUri = oldPlaylist.initSegmentUri?.let { uri -> newVideoDirectoryUri + File.separator + Uri.decode(uri.substringAfterLast("%2F").substringAfterLast("/")) },
                            segments = segments
                        ), it)
                    }
                    val tracksToDelete = mutableListOf<String>()
                    oldPlaylist.segments.forEach { tracksToDelete.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                    val playlists = repository.getPlaylists().mapNotNull { video ->
                        video.url?.takeIf {
                            it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                    && it.substringBeforeLast("%2F") == oldVideoDirectoryUri
                                    && it != videoUrl
                        }
                    }
                    playlists.forEach { uri ->
                        try {
                            val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            p.segments.forEach { tracksToDelete.remove(Uri.decode(it.uri.substringAfterLast("%2F").substringAfterLast("/"))) }
                        } catch (e: Exception) {

                        }
                    }
                    if (oldPlaylist.initSegmentUri != null) {
                        val oldFileName = oldPlaylist.initSegmentUri.substringAfterLast("%2F").substringAfterLast("/")
                        val oldFileUri = "$oldVideoDirectoryUri%2F$oldFileName"
                        val newFileUri = newVideoDirectoryUri + File.separator + Uri.decode(oldFileName)
                        File(newFileUri).sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.source().buffer())
                        }
                    }
                    repository.updateVideo(video.apply {
                        maxProgress = tracksToDelete.count()
                    })
                    oldPlaylist.segments.forEach { track ->
                        val oldFileName = track.uri.substringAfterLast("%2F").substringAfterLast("/")
                        val oldFileUri = "$oldVideoDirectoryUri%2F$oldFileName"
                        val newFileUri = newVideoDirectoryUri + File.separator + Uri.decode(oldFileName)
                        File(newFileUri).sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldFileUri.toUri())!!.source().buffer())
                        }
                        if (tracksToDelete.contains(Uri.decode(oldFileName))) {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, oldFileUri.toUri())
                            } catch (e: Exception) {

                            }
                        }
                        repository.updateVideo(video.apply {
                            progress += 1
                        })
                    }
                    val oldChatUri = video.chatUrl
                    val oldChatFileName = oldChatUri?.substringAfterLast("%2F")?.substringAfterLast("/")?.substringAfterLast("%3A")?.let { Uri.decode(it) }
                    val newChatFileUri = oldChatFileName?.let { path + File.separator + it }
                    if (oldChatUri != null && newChatFileUri != null) {
                        File(newChatFileUri).sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldChatUri.toUri())!!.source().buffer())
                        }
                    }
                    repository.updateVideo(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                thumbnail = newVideoDirectoryUri + File.separator + oldPlaylist.segments.getOrNull(
                                    max(0, (oldPlaylist.segments.size / 2) - 1)
                                )?.uri?.substringAfterLast("%2F")?.substringAfterLast("/")?.let { Uri.decode(it) }
                            }
                        }
                        url = newPlaylistFileUri
                        chatUrl = newChatFileUri
                    })
                    if (playlists.isNotEmpty()) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                        } catch (e: Exception) {

                        }
                    } else {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldVideoDirectoryUri.toUri())
                        } catch (e: Exception) {

                        }
                    }
                    if (oldChatUri != null) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldChatUri.toUri())
                        } catch (e: Exception) {

                        }
                    }
                } else {
                    val oldFileName = Uri.decode(videoUrl.substringAfterLast("%2F").substringAfterLast("/").substringAfterLast("%3A"))
                    val newFileUri = path + File.separator + oldFileName
                    File(newFileUri).sink().buffer().use { sink ->
                        sink.writeAll(applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.source().buffer())
                    }
                    val oldChatUri = video.chatUrl
                    val oldChatFileName = oldChatUri?.substringAfterLast("%2F")?.substringAfterLast("/")?.substringAfterLast("%3A")?.let { Uri.decode(it) }
                    val newChatFileUri = oldChatFileName?.let { path + File.separator + it }
                    if (oldChatUri != null && newChatFileUri != null) {
                        File(newChatFileUri).sink().buffer().use { sink ->
                            sink.writeAll(applicationContext.contentResolver.openInputStream(oldChatUri.toUri())!!.source().buffer())
                        }
                    }
                    repository.updateVideo(video.apply {
                        thumbnail.let {
                            if (it == null || it == url || !File(it).exists()) {
                                thumbnail = newFileUri
                            }
                        }
                        url = newFileUri
                        chatUrl = newChatFileUri
                    })
                    try {
                        DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                    } catch (e: Exception) {

                    }
                    if (oldChatUri != null) {
                        try {
                            DocumentsContract.deleteDocument(applicationContext.contentResolver, oldChatUri.toUri())
                        } catch (e: Exception) {

                        }
                    }
                }
            }.invokeOnCompletion {
                videosInUse.remove(video)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.updateVideo(video.apply {
                        status = OfflineVideo.STATUS_DOWNLOADED
                    })
                }
            }
        }
    }

    fun updateChatUrl(newUri: Uri, video: OfflineVideo) {
        if (!videosInUse.contains(video)) {
            viewModelScope.launch(Dispatchers.IO) {
                var id: String? = null
                var title: String? = null
                var uploadDate: Long? = null
                var channelId: String? = null
                var channelLogin: String? = null
                var channelName: String? = null
                var gameId: String? = null
                var gameSlug: String? = null
                var gameName: String? = null
                try {
                    applicationContext.contentResolver.openInputStream(newUri)?.bufferedReader()?.use { fileReader ->
                        JsonReader(fileReader).use { reader ->
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "video" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "id" -> id = reader.nextString()
                                                "title" -> title = reader.nextString()
                                                "uploadDate" -> uploadDate = reader.nextLong()
                                                "channelId" -> channelId = reader.nextString()
                                                "channelLogin" -> channelLogin = reader.nextString()
                                                "channelName" -> channelName = reader.nextString()
                                                "gameId" -> gameId = reader.nextString()
                                                "gameSlug" -> gameSlug = reader.nextString()
                                                "gameName" -> gameName = reader.nextString()
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                } catch (e: Exception) {

                }
                repository.updateVideo(video.apply {
                    if (!title.isNullOrBlank()) this.name = title
                    if (!channelId.isNullOrBlank()) this.channelId = channelId
                    if (!channelLogin.isNullOrBlank()) this.channelLogin = channelLogin
                    if (!channelName.isNullOrBlank()) this.channelName = channelName
                    if (!gameId.isNullOrBlank()) this.gameId = gameId
                    if (!gameSlug.isNullOrBlank()) this.gameSlug = gameSlug
                    if (!gameName.isNullOrBlank()) this.gameName = gameName
                    if (uploadDate != null) this.uploadDate = uploadDate
                    if (!id.isNullOrBlank()) this.videoId = id
                    chatUrl = newUri.toString()
                })
            }
        }
    }

    fun delete(video: OfflineVideo, keepFiles: Boolean) {
        val videoUrl = video.url
        if (!videosInUse.contains(video)) {
            videosInUse.add(video)
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateVideo(video.apply {
                    progress = 0
                    maxProgress = 100
                    status = OfflineVideo.STATUS_DELETING
                })
                if (video.live) {
                    video.channelLogin?.let { WorkManager.getInstance(applicationContext).cancelUniqueWork(it) }
                } else {
                    WorkManager.getInstance(applicationContext).cancelAllWorkByTag(video.id.toString())
                }
                if (videoUrl != null && !keepFiles) {
                    if (videoUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
                        if (videoUrl.endsWith(".m3u8")) {
                            val videoDirectoryUri = videoUrl.substringBeforeLast("%2F")
                            val playlist = applicationContext.contentResolver.openInputStream(videoUrl.toUri())!!.use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                            val tracksToDelete = playlist.segments.toMutableSet()
                            val playlists = repository.getPlaylists().mapNotNull { video ->
                                video.url?.takeIf {
                                    it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                                            && it.substringBeforeLast("%2F") == videoDirectoryUri
                                            && it != videoUrl
                                }
                            }
                            playlists.forEach { uri ->
                                try {
                                    val p = applicationContext.contentResolver.openInputStream(uri.toUri())!!.use {
                                        PlaylistUtils.parseMediaPlaylist(it)
                                    }
                                    tracksToDelete.removeAll(p.segments.toSet())
                                } catch (e: Exception) {

                                }
                            }
                            repository.updateVideo(video.apply {
                                maxProgress = tracksToDelete.count()
                            })
                            tracksToDelete.forEach {
                                try {
                                    DocumentsContract.deleteDocument(applicationContext.contentResolver, it.uri.toUri())
                                } catch (e: Exception) {

                                }
                                repository.updateVideo(video.apply {
                                    progress += 1
                                })
                            }
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                            } catch (e: Exception) {

                            }
                            if (playlists.isEmpty()) {
                                try {
                                    DocumentsContract.deleteDocument(applicationContext.contentResolver, videoDirectoryUri.toUri())
                                } catch (e: Exception) {

                                }
                            }
                        } else {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, videoUrl.toUri())
                            } catch (e: Exception) {

                            }
                        }
                        video.chatUrl?.let {
                            try {
                                DocumentsContract.deleteDocument(applicationContext.contentResolver, it.toUri())
                            } catch (e: Exception) {

                            }
                        }
                    } else {
                        val playlistFile = File(videoUrl)
                        if (!playlistFile.exists()) {
                            return@launch
                        }
                        if (videoUrl.endsWith(".m3u8")) {
                            val directory = playlistFile.parentFile
                            if (directory != null) {
                                val playlists = directory.listFiles { it.extension == "m3u8" && it != playlistFile }
                                if (playlists != null) {
                                    val playlist = PlaylistUtils.parseMediaPlaylist(playlistFile.inputStream())
                                    val tracksToDelete = playlist.segments.toMutableSet()
                                    playlists.forEach {
                                        val p = PlaylistUtils.parseMediaPlaylist(it.inputStream())
                                        tracksToDelete.removeAll(p.segments.toSet())
                                    }
                                    repository.updateVideo(video.apply {
                                        maxProgress = tracksToDelete.count()
                                    })
                                    tracksToDelete.forEach {
                                        File(it.uri).delete()
                                        repository.updateVideo(video.apply {
                                            progress += 1
                                        })
                                    }
                                    playlistFile.delete()
                                    if (playlists.isEmpty()) {
                                        directory.deleteRecursively()
                                    }
                                }
                            }
                        } else {
                            playlistFile.delete()
                        }
                        video.chatUrl?.let { File(it).delete() }
                    }
                }
            }.invokeOnCompletion {
                videosInUse.remove(video)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.deleteVideo(video)
                }
            }
        }
    }
}