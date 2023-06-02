package com.github.andreyasadchy.xtra.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.offline.Request
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.toast
import com.iheartradio.m3u8.Encoding
import com.iheartradio.m3u8.Format
import com.iheartradio.m3u8.ParsingMode
import com.iheartradio.m3u8.PlaylistParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class VideoDownloadViewModel @Inject constructor(
    application: Application,
    private val playerRepository: PlayerRepository,
    private val offlineRepository: OfflineRepository
) : AndroidViewModel(application) {

    private val _videoInfo = MutableLiveData<VideoDownloadInfo?>()
    val videoInfo: LiveData<VideoDownloadInfo?>
        get() = _videoInfo

    fun setVideo(gqlHeaders: Map<String, String>, gqlToken: String?, video: Video, playerType: String?, skipAccessToken: Int) {
        if (_videoInfo.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val map = if (skipAccessToken <= 1 && !video.animatedPreviewURL.isNullOrBlank()) {
                        TwitchApiHelper.getVideoUrlMapFromPreview(video.animatedPreviewURL, video.type)
                    } else {
                        val response = playerRepository.loadVideoPlaylist(gqlHeaders, gqlToken, video.id, playerType)
                        if (response.isSuccessful) {
                            val playlist = response.body()!!.string()
                            val qualities = "NAME=\"(.*)\"".toRegex().findAll(playlist).map { it.groupValues[1] }.toMutableList()
                            val urls = "https://.*\\.m3u8".toRegex().findAll(playlist).map(MatchResult::value).toMutableList()
                            qualities.zip(urls).toMap(mutableMapOf())
                        } else {
                            if (skipAccessToken == 2 && !video.animatedPreviewURL.isNullOrBlank()) {
                                TwitchApiHelper.getVideoUrlMapFromPreview(video.animatedPreviewURL, video.type)
                            } else {
                                throw IllegalAccessException()
                            }
                        }
                    }.apply {
                        entries.find { it.key.startsWith("audio", true) }?.let {
                            remove(it.key)
                            put(getApplication<Application>().getString(R.string.audio_only), it.value)
                        }
                    }
                    val mediaPlaylist = URL(map.values.elementAt(0)).openStream().use {
                        PlaylistParser(it, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT).parse().mediaPlaylist
                    }
                    var totalDuration = 0L
                    val size = mediaPlaylist.tracks.size
                    val relativeTimes = ArrayList<Long>(size)
                    val durations = ArrayList<Long>(size)
                    var time = 0L
                    mediaPlaylist.tracks.forEach {
                        val duration = (it.trackInfo.duration * 1000f).toLong()
                        durations.add(duration)
                        totalDuration += duration
                        relativeTimes.add(time)
                        time += duration
                    }
                    _videoInfo.postValue(VideoDownloadInfo(video, map, relativeTimes, durations, totalDuration, mediaPlaylist.targetDuration * 1000L, 0))
                } catch (e: Exception) {
                    if (e is IllegalAccessException) {
                        launch(Dispatchers.Main) {
                            val context = getApplication<Application>()
                            context.toast(R.string.video_subscribers_only)
                            _videoInfo.value = null
                        }
                    }
                }
            }
        }
    }

    fun setVideoInfo(videoInfo: VideoDownloadInfo) {
        if (_videoInfo.value != videoInfo) {
            _videoInfo.value = videoInfo
        }
    }

    fun download(url: String, path: String, quality: String, fromIndex: Int, toIndex: Int) {
        GlobalScope.launch {
            with(_videoInfo.value!!) {
                val context = getApplication<Application>()

                val startPosition = relativeStartTimes[fromIndex]
                val duration = (relativeStartTimes[toIndex] + durations[toIndex] - startPosition) - 1000L
                val directory = "$path${File.separator}" +
                        if (!video.id.isNullOrBlank()) {
                            "${video.id}${if (!quality.contains("Audio", true)) quality else "audio"}"
                        } else {
                            System.currentTimeMillis()
                        } + File.separator

                val offlineVideo = DownloadUtils.prepareDownload(context, video, url, directory, duration, startPosition, fromIndex, toIndex)
                val videoId = offlineRepository.saveVideo(offlineVideo).toInt()
                val request = Request(videoId, url, directory, video.id, video.type, fromIndex, toIndex)
                offlineRepository.saveRequest(request)

                DownloadUtils.download(context, request)
            }
        }
    }
}