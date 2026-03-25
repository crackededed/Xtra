package com.github.andreyasadchy.xtra.ui.download

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

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
                            val playlist = playerRepository.loadStreamPlaylist(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, enableIntegrity)
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
                        if (e.message == "failed integrity check") {
                            if (integrity.value == null) {
                                integrity.value = "refresh"
                            }
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
                        val result = playerRepository.loadVideoPlaylist(networkLibrary, gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
                        val playlist = result.first
                        backupQualities = result.second
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
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refresh"
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
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refresh"
                        }
                    }
                }
            }
        }
    }
}