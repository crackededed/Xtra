package com.github.andreyasadchy.xtra.ui.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.json.JSONArray
import org.json.JSONException
import java.util.concurrent.ExecutorService

class DownloadViewModel(
    private val applicationContext: Context,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    private val _qualities = MutableStateFlow<List<VideoQuality>?>(null)
    val qualities: StateFlow<List<VideoQuality>?> = _qualities
    val dismiss = MutableStateFlow(false)
    var backupQualities: List<String>? = null
    var selectedQuality: String? = null

    fun setStream(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String?, qualities: List<VideoQuality>?, platform: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                _qualities.value = qualities
            } else {
                viewModelScope.launch {
                    val default = listOf("source", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "audio_only")
                    try {
                        val list = if (!channelLogin.isNullOrBlank()) {
                            val url = playerRepository.loadStreamPlaylistUrl(applicationContext, networkLibrary, gqlHeaders, channelLogin, platform, playerType, supportedCodecs, false, null, null, null, null, enableIntegrity)
                            val playlist = withContext(Dispatchers.IO) {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            response.body.decodeToString()
                                        } else null
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            response.body.decodeToString()
                                        } else null
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                response.body.string()
                                            } else null
                                        }
                                    }
                                }
                            }
                            if (!playlist.isNullOrBlank()) {
                                val stableVariantIds = Regex("STABLE-VARIANT-ID=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                                val resolutions = Regex("RESOLUTION=(\\d+x\\d+)").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                                val frameRates = Regex("FRAME-RATE=([\\d.]+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toFloatOrNull() }.toMutableList()
                                val bitrates = Regex("BANDWIDTH=(\\d+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toIntOrNull() }.toMutableList()
                                val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                                val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                                stableVariantIds.mapIndexedNotNull { index, variantId ->
                                    urls.getOrNull(index)?.let { url ->
                                        VideoQuality(variantId, resolutions.getOrNull(index)?.substringAfter('x')?.toIntOrNull(), frameRates.getOrNull(index), bitrates.getOrNull(index), codecs.getOrNull(index), url)
                                    }
                                }
                            } else {
                                default.map {
                                    VideoQuality(it, url = "")
                                }
                            }
                        } else {
                            default.map {
                                VideoQuality(it, url = "")
                            }
                        }
                        _qualities.value = list
                            .sortedWith(
                                compareByDescending<VideoQuality> { it.bitrate }
                                    .thenByDescending { it.frameRate }
                                    .thenByDescending { it.resolution }
                            )
                            .toMutableList().apply {
                                find { it.name.equals("source", true) }?.let { source ->
                                    remove(source)
                                    add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                }
                                find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                    remove(audio)
                                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio.resolution, audio.frameRate, audio.bitrate, audio.codecs, audio.url))
                                }
                            }
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("stream")
                        } else {
                            _qualities.value = default.map {
                                VideoQuality(it, url = "")
                            }
                        }
                    }
                }
            }
        }
    }

    fun setVideo(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, animatedPreviewUrl: String?, videoType: String?, qualities: List<VideoQuality>?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                _qualities.value = qualities
            } else {
                viewModelScope.launch {
                    try {
                        val result = playerRepository.loadVideoPlaylistUrl(networkLibrary, gqlHeaders, videoId, supportedCodecs, enableIntegrity)
                        val url = result.first
                        backupQualities = result.second
                        val playlist = withContext(Dispatchers.IO) {
                            when {
                                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body.decodeToString()
                                    } else null
                                }
                                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body.decodeToString()
                                    } else null
                                }
                                else -> {
                                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.string()
                                        } else null
                                    }
                                }
                            }
                        }
                        if (!playlist.isNullOrBlank()) {
                            val stableVariantIds = Regex("STABLE-VARIANT-ID=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                            val resolutions = Regex("RESOLUTION=(\\d+x\\d+)").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                            val frameRates = Regex("FRAME-RATE=([\\d.]+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toFloatOrNull() }.toMutableList()
                            val bitrates = Regex("BANDWIDTH=(\\d+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toIntOrNull() }.toMutableList()
                            val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                            val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                            playlist.lines().filter { it.startsWith("#EXT-X-SESSION-DATA") }.let { list ->
                                if (list.isNotEmpty()) {
                                    val url = urls.firstOrNull()?.takeIf { it.contains("/index-") }
                                    val variantId = stableVariantIds.firstOrNull()
                                    if (url != null && variantId != null) {
                                        list.forEach { line ->
                                            val id = Regex("DATA-ID=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                                            if (id == "com.amazon.ivs.unavailable-media") {
                                                val value = Regex("VALUE=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                                                if (value != null) {
                                                    val bytes = try {
                                                        Base64.decode(value, Base64.DEFAULT)
                                                    } catch (e: IllegalArgumentException) {
                                                        null
                                                    }
                                                    if (bytes != null) {
                                                        val string = String(bytes)
                                                        val array = try {
                                                            JSONArray(string)
                                                        } catch (e: JSONException) {
                                                            null
                                                        }
                                                        if (array != null) {
                                                            for (i in 0 until array.length()) {
                                                                val obj = array.optJSONObject(i)
                                                                if (obj != null) {
                                                                    var skip = false
                                                                    val filterReasons = obj.optJSONArray("FILTER_REASONS")
                                                                    if (filterReasons != null) {
                                                                        for (filterIndex in 0 until filterReasons.length()) {
                                                                            val filter = filterReasons.optString(filterIndex)
                                                                            if (filter == "FR_CODEC_NOT_REQUESTED") {
                                                                                skip = true
                                                                                break
                                                                            }
                                                                        }
                                                                    }
                                                                    if (!skip) {
                                                                        val newVariantId = obj.optString("STABLE-VARIANT-ID")
                                                                        val resolution = obj.optString("RESOLUTION")
                                                                        val frameRate = obj.optString("FRAME-RATE").toFloatOrNull()
                                                                        val bitrate = obj.optInt("BANDWIDTH")
                                                                        val codec = obj.optString("CODECS")
                                                                        if (!newVariantId.isNullOrBlank()) {
                                                                            stableVariantIds.add(newVariantId)
                                                                            if (!resolution.isNullOrBlank()) {
                                                                                resolutions.add(resolution)
                                                                            }
                                                                            if (frameRate != null && frameRate > 0) {
                                                                                frameRates.add(frameRate)
                                                                            }
                                                                            if (bitrate > 0) {
                                                                                bitrates.add(bitrate)
                                                                            }
                                                                            if (!codec.isNullOrBlank()) {
                                                                                codecs.add(codec)
                                                                            }
                                                                            urls.add(url.replace(
                                                                                "$variantId/index-",
                                                                                if (urls.find { it.contains("chunked/index-") } == null && newVariantId != "audio_only") {
                                                                                    "chunked/index-"
                                                                                } else {
                                                                                    "$newVariantId/index-"
                                                                                }
                                                                            ))
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            val list = stableVariantIds.mapIndexedNotNull { index, variantId ->
                                urls.getOrNull(index)?.let { url ->
                                    VideoQuality(variantId, resolutions.getOrNull(index)?.substringAfter('x')?.toIntOrNull(), frameRates.getOrNull(index), bitrates.getOrNull(index), codecs.getOrNull(index), url)
                                }
                            }
                            _qualities.value = list
                                .sortedWith(
                                    compareByDescending<VideoQuality> { it.bitrate }
                                        .thenByDescending { it.frameRate }
                                        .thenByDescending { it.resolution }
                                )
                                .toMutableList().apply {
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                    }
                                    find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                        remove(audio)
                                        add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio.resolution, audio.frameRate, audio.bitrate, audio.codecs, audio.url))
                                    }
                                }
                        } else {
                            if (!animatedPreviewUrl.isNullOrBlank()) {
                                val list = (backupQualities ?: TwitchApiHelper.defaultQualityList).map { quality ->
                                    val split = quality.split("p")
                                    val resolution = split.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                    val frameRate = split.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                    val url = animatedPreviewUrl
                                        .replace("storyboards", quality)
                                        .replaceAfterLast(
                                            "/",
                                            if (videoType?.lowercase() == "highlight") {
                                                "highlight-${animatedPreviewUrl.substringAfterLast("/").substringBefore("-")}.m3u8"
                                            } else {
                                                "index-dvr.m3u8"
                                            }
                                        )
                                    val name = if (quality == "chunked") {
                                        "source"
                                    } else {
                                        quality
                                    }
                                    VideoQuality(name, resolution, frameRate.toFloat(), url = url)
                                }
                                _qualities.value = list
                                    .sortedWith(
                                        compareByDescending<VideoQuality> { it.bitrate }
                                            .thenByDescending { it.frameRate }
                                            .thenByDescending { it.resolution }
                                    )
                                    .toMutableList().apply {
                                        find { it.name.equals("source", true) }?.let { source ->
                                            remove(source)
                                            add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                        }
                                        find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                            remove(audio)
                                            add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio.resolution, audio.frameRate, audio.bitrate, audio.codecs, audio.url))
                                        }
                                    }
                            } else {
                                throw IllegalAccessException()
                            }
                        }
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("video")
                        }
                        if (e is IllegalAccessException) {
                            dismiss.value = true
                        }
                    }
                }
            }
        }
    }

    fun setClip(networkLibrary: String?, gqlHeaders: Map<String, String>, clipId: String?, qualities: List<VideoQuality>?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                _qualities.value = qualities
            } else {
                viewModelScope.launch {
                    try {
                        val list = playerRepository.loadClipQualities(networkLibrary, gqlHeaders, clipId, enableIntegrity)
                        if (list != null) {
                            _qualities.value = list
                                .sortedWith(
                                    compareByDescending<VideoQuality> { it.bitrate }
                                        .thenByDescending { it.frameRate }
                                        .thenByDescending { it.resolution }
                                )
                        }
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("clip")
                        }
                    }
                }
            }
        }
    }

    companion object {
        val DownloadViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                DownloadViewModel(application.applicationContext, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.playerRepository)
            }
        }
    }
}