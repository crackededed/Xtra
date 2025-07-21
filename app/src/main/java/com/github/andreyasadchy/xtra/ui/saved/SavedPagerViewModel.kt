package com.github.andreyasadchy.xtra.ui.saved

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class SavedPagerViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val offlineRepository: OfflineRepository,
) : ViewModel() {

    fun saveFolders(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val directoryUri = url + "/document/" + url.substringAfter("/tree/")
            val directoryUris = mutableListOf<Uri>()
            val chatFiles = mutableMapOf<String, String>()
            applicationContext.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri.toUri(), DocumentsContract.getDocumentId(directoryUri.toUri())),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ), null, null, null
            ).use { cursor ->
                while (cursor?.moveToNext() == true) {
                    val documentId = cursor.getString(0)
                    val mimeType = cursor.getString(1)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val directoryUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri.toUri(), documentId)
                        directoryUris.add(directoryUri)
                    } else {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri.toUri(), documentId)
                        if (documentUri.toString().endsWith(".json")) {
                            chatFiles[documentUri.toString().substringAfterLast("%2F").substringAfterLast("%3A").removeSuffix(".json").removeSuffix("_chat")] = documentUri.toString()
                        }
                    }
                }
            }
            val playlistFileUris = mutableListOf<Uri>()
            directoryUris.forEach { uri ->
                applicationContext.contentResolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null
                ).use { cursor ->
                    while (cursor?.moveToNext() == true) {
                        val documentId = cursor.getString(0)
                        val mimeType = cursor.getString(1)
                        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri.toUri(), documentId)
                            if (documentUri.toString().endsWith(".m3u8")) {
                                playlistFileUris.add(documentUri)
                            }
                        }
                    }
                }
            }
            playlistFileUris.forEach { uri ->
                val existingVideo = offlineRepository.getVideoByUrl(uri.toString())
                if (existingVideo == null) {
                    val videoDirectoryUri = uri.toString().substringBeforeLast("%2F")
                    val videoDirectoryName = videoDirectoryUri.substringAfterLast("%2F").substringAfterLast("%3A")
                    val playlist = applicationContext.contentResolver.openInputStream(uri)!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    var totalDuration = 0L
                    val segments = ArrayList<Segment>()
                    playlist.segments.forEach { segment ->
                        totalDuration += (segment.duration * 1000f).toLong()
                        segments.add(segment.copy(uri = videoDirectoryUri + "%2F" + segment.uri.substringAfterLast("%2F").substringAfterLast("/")))
                    }
                    applicationContext.contentResolver.openOutputStream(uri)!!.use {
                        PlaylistUtils.writeMediaPlaylist(playlist.copy(
                            initSegmentUri = playlist.initSegmentUri?.let { uri -> videoDirectoryUri + "%2F" + uri.substringAfterLast("%2F").substringAfterLast("/") },
                            segments = segments
                        ), it)
                    }
                    val chatFileUri = chatFiles[videoDirectoryName + uri.toString().substringAfterLast("%2F").removeSuffix(".m3u8")]
                    var id: String? = null
                    var title: String? = null
                    var uploadDate: Long? = null
                    var channelId: String? = null
                    var channelLogin: String? = null
                    var channelName: String? = null
                    var gameId: String? = null
                    var gameSlug: String? = null
                    var gameName: String? = null
                    chatFileUri?.let { chatFileUri ->
                        try {
                            applicationContext.contentResolver.openInputStream(chatFileUri.toUri())!!.bufferedReader().use { fileReader ->
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
                    }
                    offlineRepository.saveVideo(OfflineVideo(
                        url = uri.toString(),
                        name = if (!title.isNullOrBlank()) title else Uri.decode(videoDirectoryName),
                        channelId = if (!channelId.isNullOrBlank()) channelId else null,
                        channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                        channelName = if (!channelName.isNullOrBlank()) channelName else null,
                        thumbnail = segments.getOrNull(max(0, (segments.size / 2) - 1))?.uri,
                        gameId = if (!gameId.isNullOrBlank()) gameId else null,
                        gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                        gameName = if (!gameName.isNullOrBlank()) gameName else null,
                        duration = totalDuration,
                        uploadDate = uploadDate,
                        progress = 100,
                        maxProgress = 100,
                        status = OfflineVideo.STATUS_DOWNLOADED,
                        videoId = if (!id.isNullOrBlank()) id else null,
                        chatUrl = chatFileUri
                    ))
                }
            }
        }
    }

    fun saveVideos(list: List<String>) {
        viewModelScope.launch {
            val chatFiles = mutableMapOf<String, String>()
            list.filter { it.endsWith(".json") }.forEach {
                chatFiles[it.substringAfterLast("%2F").removeSuffix(".json").removeSuffix("_chat")] = it
            }
            list.filter { !it.endsWith(".json") }.forEach { url ->
                val existingVideo = offlineRepository.getVideoByUrl(url)
                if (existingVideo == null) {
                    val fileName = url.substringAfterLast("%2F").removeSuffix(".mp4").removeSuffix(".ts")
                    val chatFile = chatFiles[fileName]
                    var id: String? = null
                    var title: String? = null
                    var uploadDate: Long? = null
                    var channelId: String? = null
                    var channelLogin: String? = null
                    var channelName: String? = null
                    var gameId: String? = null
                    var gameSlug: String? = null
                    var gameName: String? = null
                    chatFile?.let { uri ->
                        try {
                            applicationContext.contentResolver.openInputStream(uri.toUri())?.bufferedReader()?.use { fileReader ->
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
                    }
                    offlineRepository.saveVideo(
                        OfflineVideo(
                            url = url,
                            name = if (!title.isNullOrBlank()) title else Uri.decode(fileName),
                            channelId = if (!channelId.isNullOrBlank()) channelId else null,
                            channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                            channelName = if (!channelName.isNullOrBlank()) channelName else null,
                            thumbnail = url,
                            gameId = if (!gameId.isNullOrBlank()) gameId else null,
                            gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                            gameName = if (!gameName.isNullOrBlank()) gameName else null,
                            uploadDate = uploadDate,
                            progress = 100,
                            maxProgress = 100,
                            status = OfflineVideo.STATUS_DOWNLOADED,
                            videoId = if (!id.isNullOrBlank()) id else null,
                            chatUrl = chatFile
                        )
                    )
                }
            }
        }
    }
}
