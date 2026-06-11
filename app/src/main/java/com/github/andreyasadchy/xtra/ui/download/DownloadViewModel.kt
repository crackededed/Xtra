package com.github.andreyasadchy.xtra.ui.download

import android.annotation.SuppressLint
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
import com.github.andreyasadchy.xtra.util.NetworkUtils.body
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

    fun setStream(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String?, qualities: List<VideoQuality>?, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                _qualities.value = qualities
            } else {
                viewModelScope.launch {
                    val default = listOf("source", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "audio_only")
                    try {
                        val list = if (!channelLogin.isNullOrBlank()) {
                            val url = playerRepository.loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, null, null, null, null, enableIntegrity)
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
                                val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                                val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                                val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                                names.mapIndexedNotNull { index, name ->
                                    urls.getOrNull(index)?.let { url ->
                                        VideoQuality(name, codecs.getOrNull(index), url)
                                    }
                                }
                            } else {
                                default.map {
                                    VideoQuality(it, null, "")
                                }
                            }
                        } else {
                            default.map {
                                VideoQuality(it, null, "")
                            }
                        }
                        _qualities.value = list
                            .sortedByDescending {
                                it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .sortedByDescending {
                                it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .toMutableList().apply {
                                find { it.name.equals("source", true) }?.let { source ->
                                    remove(source)
                                    add(0, VideoQuality("source", source.codecs, source.url))
                                }
                                find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                    remove(audio)
                                    add(VideoQuality("audio_only", audio.codecs, audio.url))
                                }
                            }
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("stream")
                        } else {
                            _qualities.value = default.map {
                                VideoQuality(it, null, "")
                            }
                        }
                    }
                }
            }
        }
    }

    fun setVideo(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, animatedPreviewUrl: String?, videoType: String?, qualities: List<VideoQuality>?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                _qualities.value = qualities
            } else {
                viewModelScope.launch {
                    try {
                        val result = playerRepository.loadVideoPlaylistUrl(networkLibrary, gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
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
                            val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                            val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                            val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                            playlist.lines().filter { it.startsWith("#EXT-X-SESSION-DATA") }.let { list ->
                                if (list.isNotEmpty()) {
                                    val url = urls.firstOrNull()?.takeIf { it.contains("/index-") }
                                    val variantId = Regex("STABLE-VARIANT-ID=\"(.+?)\"").find(playlist)?.groups?.get(1)?.value
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
                                                                        val name = obj.optString("IVS_NAME")
                                                                        val codec = obj.optString("CODECS")
                                                                        val newVariantId = obj.optString("STABLE-VARIANT-ID")
                                                                        if (!name.isNullOrBlank() && !newVariantId.isNullOrBlank()) {
                                                                            names.add(name)
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
                            val list = names.mapIndexedNotNull { index, name ->
                                urls.getOrNull(index)?.let { url ->
                                    VideoQuality(name, codecs.getOrNull(index), url)
                                }
                            }
                            _qualities.value = list
                                .sortedByDescending {
                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .sortedByDescending {
                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .toMutableList().apply {
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(0, VideoQuality("source", source.codecs, source.url))
                                    }
                                    find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                        remove(audio)
                                        add(VideoQuality("audio_only", audio.codecs, audio.url))
                                    }
                                }
                        } else {
                            if (!animatedPreviewUrl.isNullOrBlank()) {
                                val urls = TwitchApiHelper.getVideoUrlsFromPreview(animatedPreviewUrl, videoType, backupQualities)
                                val list = urls.map {
                                    VideoQuality(it.key, null, it.value)
                                }
                                _qualities.value = list
                                    .sortedByDescending {
                                        it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                    }
                                    .sortedByDescending {
                                        it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                    }
                                    .toMutableList().apply {
                                        find { it.name.equals("source", true) }?.let { source ->
                                            remove(source)
                                            add(0, VideoQuality("source", source.codecs, source.url))
                                        }
                                        find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                            remove(audio)
                                            add(VideoQuality("audio_only", audio.codecs, audio.url))
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
                                .sortedByDescending {
                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .sortedByDescending {
                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
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
                DownloadViewModel(xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.playerRepository)
            }
        }
    }
}