package com.github.andreyasadchy.xtra.ui.saved

import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import com.github.andreyasadchy.xtra.model.offline.OfflineVideo
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.iheartradio.m3u8.Encoding
import com.iheartradio.m3u8.Format
import com.iheartradio.m3u8.ParsingMode
import com.iheartradio.m3u8.PlaylistParser
import com.iheartradio.m3u8.PlaylistWriter
import com.iheartradio.m3u8.data.Playlist
import com.iheartradio.m3u8.data.TrackData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okio.use
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class SavedPagerViewModel @Inject constructor(
    private val offlineRepository: OfflineRepository) : ViewModel() {

    fun saveFolders(context: Context, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val directory = DocumentFile.fromTreeUri(context, url.substringBefore("/document/").toUri())
            directory?.listFiles()?.filter { it.isDirectory }?.forEach { videoDirectory ->
                videoDirectory.listFiles().filter { it.name?.endsWith(".m3u8") == true }.forEach { playlistFile ->
                    GlobalScope.launch {
                        val existingVideo = offlineRepository.getVideoByUrl(playlistFile.uri.toString())
                        if (existingVideo == null) {
                            val playlist = context.contentResolver.openInputStream(playlistFile.uri).use {
                                PlaylistParser(it, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT).parse().mediaPlaylist
                            }
                            var totalDuration = 0L
                            val tracks = ArrayList<TrackData>()
                            playlist.tracks.forEach { track ->
                                totalDuration += (track.trackInfo.duration * 1000f).toLong()
                                tracks.add(
                                    track.buildUpon()
                                        .withUri(videoDirectory?.uri.toString() + "%2F" + track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                        .build()
                                )
                            }
                            context.contentResolver.openOutputStream(playlistFile.uri).use {
                                PlaylistWriter(it, Format.EXT_M3U, Encoding.UTF_8).write(Playlist.Builder().withMediaPlaylist(playlist.buildUpon().withTracks(tracks).build()).build())
                            }
                            offlineRepository.saveVideo(OfflineVideo(
                                url = playlistFile.uri.toString(),
                                name = videoDirectory.name,
                                thumbnail = tracks.getOrNull(max(0,  (tracks.size / 2) - 1))?.uri,
                                duration = totalDuration,
                                progress = 100,
                                maxProgress = 100,
                                status = OfflineVideo.STATUS_DOWNLOADED
                            ))
                        }
                    }
                }
            }
        } else {
            File(url).listFiles()?.let { files ->
                files.forEach { file ->
                    if (file.isDirectory) {
                        file.listFiles()?.filter { it.name.endsWith(".m3u8") }?.forEach { playlistFile ->
                            GlobalScope.launch {
                                val existingVideo = offlineRepository.getVideoByUrl(playlistFile.path)
                                if (existingVideo == null) {
                                    val playlist = FileInputStream(playlistFile).use {
                                        PlaylistParser(it, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT).parse().mediaPlaylist
                                    }
                                    var totalDuration = 0L
                                    val tracks = ArrayList<TrackData>()
                                    playlist.tracks.forEach { track ->
                                        totalDuration += (track.trackInfo.duration * 1000f).toLong()
                                        tracks.add(
                                            track.buildUpon()
                                                .withUri(track.uri.substringAfterLast("%2F").substringAfterLast("/"))
                                                .build()
                                        )
                                    }
                                    FileOutputStream(playlistFile).use {
                                        PlaylistWriter(it, Format.EXT_M3U, Encoding.UTF_8).write(Playlist.Builder().withMediaPlaylist(playlist.buildUpon().withTracks(tracks).build()).build())
                                    }
                                    offlineRepository.saveVideo(OfflineVideo(
                                        url = playlistFile.path,
                                        name = file.name,
                                        thumbnail = file.path + File.separator + tracks.getOrNull(max(0,  (tracks.size / 2) - 1))?.uri,
                                        duration = totalDuration,
                                        progress = 100,
                                        maxProgress = 100,
                                        status = OfflineVideo.STATUS_DOWNLOADED
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun saveVideo(url: String) {
        GlobalScope.launch {
            val existingVideo = offlineRepository.getVideoByUrl(url)
            if (existingVideo == null) {
                offlineRepository.saveVideo(OfflineVideo(
                    url = url,
                    name = url.substringAfterLast("%2F").substringAfterLast("/").removeSuffix(".mp4"),
                    thumbnail = url,
                    progress = 100,
                    maxProgress = 100,
                    status = OfflineVideo.STATUS_DOWNLOADED
                ))
            }
        }
    }
}