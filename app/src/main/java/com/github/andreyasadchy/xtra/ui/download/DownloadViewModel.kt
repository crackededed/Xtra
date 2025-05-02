package com.github.andreyasadchy.xtra.ui.download

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.toast
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private val _qualities = MutableStateFlow<Map<String, Pair<String, String>>?>(null)
    val qualities: StateFlow<Map<String, Pair<String, String>>?> = _qualities
    val dismiss = MutableStateFlow(false)

    fun setStream(useCronet: Boolean, gqlHeaders: Map<String, String>, channelLogin: String?, qualities: Map<String, String>?, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                val map = mutableMapOf<String, Pair<String, String>>()
                qualities.entries.forEach {
                    if (it.key.equals("source", true)) {
                        map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                    } else {
                        map[it.key] = Pair(it.key, it.value)
                    }
                }
                map.apply {
                    if (containsKey("audio_only")) {
                        remove("audio_only")?.let { url ->
                            put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                        }
                    }
                }
                _qualities.value = map
            } else {
                viewModelScope.launch {
                    val default = mutableMapOf("source" to "", "1080p60" to "", "1080p30" to "", "720p60" to "", "720p30" to "", "480p30" to "", "360p30" to "", "160p30" to "", "audio_only" to "")
                    try {
                        val urls = if (!channelLogin.isNullOrBlank()) {
                            val playlist = playerRepository.loadStreamPlaylist(useCronet, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, enableIntegrity)
                            if (!playlist.isNullOrBlank()) {
                                val names = "NAME=\"(.*)\"".toRegex().findAll(playlist).map { it.groupValues[1] }.toMutableList()
                                val urls = "https://.*\\.m3u8".toRegex().findAll(playlist).map(MatchResult::value).toMutableList()
                                names.zip(urls).toMap(mutableMapOf()).takeIf { it.isNotEmpty() } ?: default
                            } else default
                        } else default
                        val map = mutableMapOf<String, Pair<String, String>>()
                        urls.entries.forEach {
                            if (it.key.equals("source", true)) {
                                map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                            } else {
                                map[it.key] = Pair(it.key, it.value)
                            }
                        }
                        map.apply {
                            if (containsKey("audio_only")) {
                                remove("audio_only")?.let { url ->
                                    put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                }
                            }
                        }
                        _qualities.value = map
                    } catch (e: Exception) {
                        if (e.message == "failed integrity check") {
                            if (integrity.value == null) {
                                integrity.value = "refresh"
                            }
                        } else {
                            val map = mutableMapOf<String, Pair<String, String>>()
                            default.entries.forEach {
                                if (it.key.equals("source", true)) {
                                    map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                                } else {
                                    map[it.key] = Pair(it.key, it.value)
                                }
                            }
                            map.apply {
                                if (containsKey("audio_only")) {
                                    remove("audio_only")?.let { url ->
                                        put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                    }
                                }
                            }
                            _qualities.value = map
                        }
                    }
                }
            }
        }
    }

    fun setVideo(useCronet: Boolean, gqlHeaders: Map<String, String>, videoId: String?, animatedPreviewUrl: String?, videoType: String?, qualities: Map<String, String>?, playerType: String?, supportedCodecs: String?, skipAccessToken: Int, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                val map = mutableMapOf<String, Pair<String, String>>()
                qualities.entries.forEach {
                    if (it.key.equals("source", true)) {
                        map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                    } else {
                        map[it.key] = Pair(it.key, it.value)
                    }
                }
                map.apply {
                    if (containsKey("audio_only")) {
                        remove("audio_only")?.let { url ->
                            put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                        }
                    }
                }
                _qualities.value = map
            } else {
                viewModelScope.launch {
                    try {
                        val map = if (skipAccessToken <= 1 && !animatedPreviewUrl.isNullOrBlank()) {
                            val urls = TwitchApiHelper.getVideoUrlMapFromPreview(animatedPreviewUrl, videoType)
                            val map = mutableMapOf<String, Pair<String, String>>()
                            urls.entries.forEach {
                                if (it.key.equals("source", true)) {
                                    map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                                } else {
                                    map[it.key] = Pair(it.key, it.value)
                                }
                            }
                            map.apply {
                                if (containsKey("audio_only")) {
                                    remove("audio_only")?.let { url ->
                                        put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                    }
                                }
                            }
                        } else {
                            val playlist = playerRepository.loadVideoPlaylist(useCronet, gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
                            if (!playlist.isNullOrBlank()) {
                                val qualities = "NAME=\"(.+?)\"".toRegex().findAll(playlist).map { it.groupValues[1] }.toMutableList()
                                val codecs = "CODECS=\"(.+?)\\.".toRegex().findAll(playlist).map {
                                    when(it.groupValues[1]) {
                                        "av01" -> "AV1"
                                        "hvc1" -> "H.265"
                                        "avc1" -> "H.264"
                                        else -> it.groupValues[1]
                                    }
                                }.toMutableList()
                                if (codecs.all { it == "H.264" || it == "mp4a" }) {
                                    codecs.clear()
                                }
                                val urls = "https://.*\\.m3u8".toRegex().findAll(playlist).map(MatchResult::value).toMutableList()
                                val map = mutableMapOf<String, Pair<String, String>>()
                                qualities.forEachIndexed { index, quality ->
                                    if (quality.equals("source", true)) {
                                        map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(quality, urls[index])
                                    } else {
                                        if (!quality.startsWith("audio", true)) {
                                            val name = codecs.getOrNull(index)?.let { codec ->
                                                "$quality $codec"
                                            } ?: quality
                                            map[name] = Pair(quality, urls[index])
                                        } else {
                                            map["audio_only"] = Pair("audio_only", urls[index])
                                        }
                                    }
                                }
                                map.apply {
                                    if (containsKey("audio_only")) {
                                        remove("audio_only")?.let { url ->
                                            put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                        }
                                    }
                                }
                            } else {
                                if (skipAccessToken == 2 && !animatedPreviewUrl.isNullOrBlank()) {
                                    val urls = TwitchApiHelper.getVideoUrlMapFromPreview(animatedPreviewUrl, videoType)
                                    val map = mutableMapOf<String, Pair<String, String>>()
                                    urls.entries.forEach {
                                        if (it.key.equals("source", true)) {
                                            map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                                        } else {
                                            map[it.key] = Pair(it.key, it.value)
                                        }
                                    }
                                    map.apply {
                                        if (containsKey("audio_only")) {
                                            remove("audio_only")?.let { url ->
                                                put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                            }
                                        }
                                    }
                                } else {
                                    throw IllegalAccessException()
                                }
                            }
                        }
                        _qualities.value = map
                    } catch (e: Exception) {
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refresh"
                        }
                        if (e is IllegalAccessException) {
                            applicationContext.toast(ContextCompat.getString(applicationContext, R.string.video_subscribers_only))
                            dismiss.value = true
                        }
                    }
                }
            }
        }
    }

    fun setClip(useCronet: Boolean, gqlHeaders: Map<String, String>, clipId: String?, thumbnailUrl: String?, qualities: Map<String, String>?, skipAccessToken: Int, enableIntegrity: Boolean) {
        if (_qualities.value == null) {
            if (!qualities.isNullOrEmpty()) {
                val map = mutableMapOf<String, Pair<String, String>>()
                qualities.entries.forEach {
                    if (it.key.equals("source", true)) {
                        map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                    } else {
                        map[it.key] = Pair(it.key, it.value)
                    }
                }
                map.apply {
                    if (containsKey("audio_only")) {
                        remove("audio_only")?.let { url ->
                            put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                        }
                    }
                }
                _qualities.value = map
            } else {
                viewModelScope.launch {
                    try {
                        val urls = if (skipAccessToken <= 1 && !thumbnailUrl.isNullOrBlank()) {
                            TwitchApiHelper.getClipUrlMapFromPreview(thumbnailUrl)
                        } else {
                            playerRepository.loadClipUrls(useCronet, gqlHeaders, clipId, enableIntegrity) ?:
                            if (skipAccessToken == 2 && !thumbnailUrl.isNullOrBlank()) {
                                TwitchApiHelper.getClipUrlMapFromPreview(thumbnailUrl)
                            } else null
                        }
                        val map = mutableMapOf<String, Pair<String, String>>()
                        urls?.entries?.forEach {
                            if (it.key.equals("source", true)) {
                                map[ContextCompat.getString(applicationContext, R.string.source)] = Pair(it.key, it.value)
                            } else {
                                map[it.key] = Pair(it.key, it.value)
                            }
                        }
                        map.apply {
                            if (containsKey("audio_only")) {
                                remove("audio_only")?.let { url ->
                                    put(ContextCompat.getString(applicationContext, R.string.audio_only), url)
                                }
                            }
                        }
                        _qualities.value = map
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