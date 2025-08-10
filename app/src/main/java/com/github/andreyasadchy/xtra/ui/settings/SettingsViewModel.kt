package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.HttpEngine
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.NotificationUsersRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.ShownNotificationsRepository
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationWorker
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import okio.source
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.system.exitProcess

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val playerRepository: PlayerRepository,
    private val offlineRepository: OfflineRepository,
    private val shownNotificationsRepository: ShownNotificationsRepository,
    private val notificationUsersRepository: NotificationUsersRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val appDatabase: AppDatabase,
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {

    fun deletePositions() {
        viewModelScope.launch {
            playerRepository.deleteVideoPositions()
            offlineRepository.deletePositions()
        }
    }

    fun importDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val chatFiles = mutableMapOf<String, String>()
            applicationContext.getExternalFilesDirs(".downloads").forEach { storage ->
                storage?.absolutePath?.let { directory ->
                    File(directory).listFiles()?.let { files ->
                        files.filter { it.name.endsWith(".json") }.forEach { chatFile ->
                            chatFiles[chatFile.name.removeSuffix(".json").removeSuffix("_chat")] = chatFile.path
                        }
                        files.filter { !it.name.endsWith(".json") }.forEach { file ->
                            if (file.isDirectory) {
                                file.listFiles()?.filter { it.name.endsWith(".m3u8") }?.forEach { playlistFile ->
                                        val existingVideo = offlineRepository.getVideoByUrl(playlistFile.path)
                                        if (existingVideo == null) {
                                            val playlist = FileInputStream(playlistFile).use {
                                                PlaylistUtils.parseMediaPlaylist(it)
                                            }
                                            var totalDuration = 0L
                                            val segments = ArrayList<Segment>()
                                            playlist.segments.forEach { segment ->
                                                totalDuration += (segment.duration * 1000f).toLong()
                                                segments.add(segment.copy(uri = segment.uri.substringAfterLast("%2F").substringAfterLast("/")))
                                            }
                                            FileOutputStream(playlistFile).use {
                                                PlaylistUtils.writeMediaPlaylist(playlist.copy(
                                                    initSegmentUri = playlist.initSegmentUri?.substringAfterLast("%2F")?.substringAfterLast("/"),
                                                    segments = segments
                                                ), it)
                                            }
                                            val chatFile = chatFiles[file.name + playlistFile.name.removeSuffix(".m3u8")]
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
                                                    FileInputStream(File(uri)).bufferedReader().use { fileReader ->
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
                                                    url = playlistFile.path,
                                                    name = if (!title.isNullOrBlank()) title else Uri.decode(file.name),
                                                    channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                                    channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                                    channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                                    thumbnail = file.path + File.separator + segments.getOrNull(max(0, (segments.size / 2) - 1))?.uri,
                                                    gameId = if (!gameId.isNullOrBlank()) gameId else null,
                                                    gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                                                    gameName = if (!gameName.isNullOrBlank()) gameName else null,
                                                    duration = totalDuration,
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
                            } else if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))) {
                                val existingVideo = offlineRepository.getVideoByUrl(file.path)
                                if (existingVideo == null) {
                                    val fileName = file.name.removeSuffix(".mp4").removeSuffix(".ts")
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
                                            FileInputStream(File(uri)).bufferedReader().use { fileReader ->
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
                                            url = file.path,
                                            name = if (!title.isNullOrBlank()) title else Uri.decode(fileName),
                                            channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                            channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                            channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                            thumbnail = file.path,
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
            }
        }
    }

    fun backupSettings(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val directoryUri = url + "/document/" + url.substringAfter("/tree/")
            val preferences = File("${applicationContext.applicationInfo.dataDir}/shared_prefs/${applicationContext.packageName}_preferences.xml")
            val preferencesUri = directoryUri + "%2F" + preferences.name
            try {
                applicationContext.contentResolver.openOutputStream(preferencesUri.toUri())!!
            } catch (e: IllegalArgumentException) {
                DocumentsContract.createDocument(applicationContext.contentResolver, directoryUri.toUri(), "", preferences.name)
                applicationContext.contentResolver.openOutputStream(preferencesUri.toUri())!!
            }.sink().buffer().use { sink ->
                sink.writeAll(preferences.source().buffer())
            }
            appDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use {
                it.moveToPosition(-1)
            }
            val database = applicationContext.getDatabasePath("database")
            val databaseUri = directoryUri + "%2F" + database.name
            try {
                applicationContext.contentResolver.openOutputStream(databaseUri.toUri())!!
            } catch (e: IllegalArgumentException) {
                DocumentsContract.createDocument(applicationContext.contentResolver, directoryUri.toUri(), "", database.name)
                applicationContext.contentResolver.openOutputStream(databaseUri.toUri())!!
            }.sink().buffer().use { sink ->
                sink.writeAll(database.source().buffer())
            }
        }
    }

    fun restoreSettings(list: List<String>, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            list.take(2).forEach { url ->
                if (url.endsWith(".xml")) {
                    File("${applicationContext.applicationInfo.dataDir}/shared_prefs/${applicationContext.packageName}_preferences.xml").sink().buffer().use { sink ->
                        sink.writeAll(applicationContext.contentResolver.openInputStream(url.toUri())!!.source().buffer())
                    }
                    val prefs = applicationContext.contentResolver.openInputStream(url.toUri())!!.bufferedReader().use {
                        it.readText()
                    }
                    toggleNotifications(prefs.contains("name=\"${C.LIVE_NOTIFICATIONS_ENABLED}\" value=\"true\""), networkLibrary, gqlHeaders, helixHeaders)
                } else {
                    val database = applicationContext.getDatabasePath("database")
                    File(database.parent, "database-shm").delete()
                    File(database.parent, "database-wal").delete()
                    database.sink().buffer().use { sink ->
                        sink.writeAll(applicationContext.contentResolver.openInputStream(url.toUri())!!.source().buffer())
                    }
                    applicationContext.startActivity(
                        Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    exitProcess(0)
                }
            }
        }
    }

    fun toggleNotifications(enabled: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                shownNotificationsRepository.getNewStreams(notificationUsersRepository, networkLibrary, gqlHeaders, graphQLRepository, helixHeaders, helixRepository)
                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    "live_notifications",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                        .setInitialDelay(1, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            } else {
                WorkManager.getInstance(applicationContext).cancelUniqueWork("live_notifications")
            }
        }
    }
}