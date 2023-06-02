package com.github.andreyasadchy.xtra.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.offline.Request
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ClipDownloadViewModel @Inject constructor(
    application: Application,
    private val graphQLRepository: GraphQLRepository,
    private val offlineRepository: OfflineRepository
) : AndroidViewModel(application) {

    private val _qualities = MutableLiveData<Map<String, String>>()
    val qualities: LiveData<Map<String, String>>
        get() = _qualities

    private lateinit var clip: Clip

    fun init(gqlHeaders: Map<String, String>, clip: Clip, qualities: Map<String, String>?, skipAccessToken: Int) {
        if (!this::clip.isInitialized) {
            this.clip = clip
            if (qualities.isNullOrEmpty()) {
                viewModelScope.launch {
                    try {
                        val urls = if (skipAccessToken <= 1 && !clip.thumbnailUrl.isNullOrBlank()) {
                            TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl)
                        } else {
                            graphQLRepository.loadClipUrls(
                                headers = gqlHeaders,
                                slug = clip.id
                            ) ?: if (skipAccessToken == 2 && !clip.thumbnailUrl.isNullOrBlank()) {
                                TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl)
                            } else mapOf()
                        }
                        _qualities.postValue(urls)
                    } catch (e: Exception) {

                    }
                }
            } else {
                _qualities.value = qualities!!
            }
        }
    }

    fun download(url: String, path: String, quality: String) {
        GlobalScope.launch {
            val context = getApplication<Application>()

            val filePath = "$path${File.separator}" +
                    if (!clip.id.isNullOrBlank()) {
                        "${clip.id}$quality"
                    } else {
                        System.currentTimeMillis()
                    }
            val startPosition = clip.duration.let { (it?.times(1000.0))?.toLong() }

            val offlineVideo = DownloadUtils.prepareDownload(context, clip, url, filePath, clip.duration?.toLong()?.times(1000L), startPosition)
            val videoId = offlineRepository.saveVideo(offlineVideo).toInt()
            val request = Request(videoId, url, offlineVideo.url)
            offlineRepository.saveRequest(request)

            DownloadUtils.download(context, request)
        }
    }
}