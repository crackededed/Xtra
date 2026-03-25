package com.github.andreyasadchy.xtra.ui.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpEngine
import androidx.annotation.OptIn
import androidx.lifecycle.LifecycleService
import androidx.media3.common.util.UnstableApi
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
abstract class BasePlaybackService : LifecycleService() {

    @Inject
    @JvmField
    var httpEngine: Lazy<HttpEngine>? = null

    @Inject
    @JvmField
    var cronetEngine: Lazy<CronetEngine>? = null

    @Inject
    lateinit var cronetExecutor: ExecutorService

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var json: Json

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var offlineRepository: OfflineRepository

    val integrity = MutableStateFlow<String?>(null)

    var type: String? = null
    var streamId: String? = null
    var videoId: String? = null
    var clipId: String? = null
    var offlineVideoId: Int? = null
    var channelId: String? = null
    var channelLogin: String? = null
    var channelName: String? = null
    var channelImage: String? = null
    var gameId: String? = null
    var gameSlug: String? = null
    var gameName: String? = null
    var title: String? = null
    var thumbnail: String? = null
    var createdAt: String? = null
    var viewerCount: Int? = null
    var durationSeconds: Int? = null
    var videoType: String? = null
    var videoOffsetSeconds: Int? = null
    var videoAnimatedPreviewURL: String? = null
    var savedPosition: Long? = null
    var paused = false
    var qualities: List<VideoQuality>? = null
    var quality: VideoQuality? = null
    var previousQuality: VideoQuality? = null
    var restoreQuality = false
    var playlistUrl: String? = null
    var restorePlaylist = false
    var useCustomProxy = false
    var skipAccessToken = false

    var chatUrl: String? = null
    var started = false
    var loaded = false

    interface Listener {
        fun started()
        fun loaded()
        fun changePlayerMode()
        fun toast(resId: Int, duration: Int)
    }

    var serviceListener: Listener? = null

    open fun retry(item: String) {}

    protected suspend fun restorePlaybackState() {
        val savedState = playerRepository.getPlaybackStates().firstOrNull()
        if (savedState != null) {
            type = savedState.type
            streamId = savedState.streamId
            videoId = savedState.videoId
            clipId = savedState.clipId
            offlineVideoId = savedState.offlineVideoId
            channelId = savedState.channelId
            channelLogin = savedState.channelLogin
            channelName = savedState.channelName
            channelImage = savedState.channelImage
            gameId = savedState.gameId
            gameSlug = savedState.gameSlug
            gameName = savedState.gameName
            title = savedState.title
            thumbnail = savedState.thumbnail
            createdAt = savedState.createdAt
            viewerCount = savedState.viewerCount
            durationSeconds = savedState.durationSeconds
            videoType = savedState.videoType
            videoOffsetSeconds = savedState.videoOffsetSeconds
            videoAnimatedPreviewURL = savedState.videoAnimatedPreviewURL
            savedPosition = savedState.position
            paused = savedState.paused
            qualities = savedState.qualities?.let { qualities ->
                json.decodeFromString<JsonArray>(qualities).map {
                    json.decodeFromJsonElement<VideoQuality>(it)
                }
            }
            quality = savedState.quality?.let { json.decodeFromString(it) }
            previousQuality = savedState.previousQuality?.let { json.decodeFromString(it) }
            restoreQuality = savedState.restoreQuality
            playlistUrl = savedState.playlistUrl
            restorePlaylist = savedState.restorePlaylist
            useCustomProxy = savedState.useCustomProxy
            skipAccessToken = savedState.skipAccessToken
        }
    }

    protected suspend fun savePlaybackState(position: Long?, paused: Boolean) {
        val item = PlaybackState(
            type = type,
            streamId = streamId,
            videoId = videoId,
            clipId = clipId,
            offlineVideoId = offlineVideoId,
            channelId = channelId,
            channelLogin = channelLogin,
            channelName = channelName,
            channelImage = channelImage,
            gameId = gameId,
            gameSlug = gameSlug,
            gameName = gameName,
            title = title,
            thumbnail = thumbnail,
            createdAt = createdAt,
            viewerCount = viewerCount,
            durationSeconds = durationSeconds,
            videoType = videoType,
            videoOffsetSeconds = videoOffsetSeconds,
            videoAnimatedPreviewURL = videoAnimatedPreviewURL,
            position = position,
            paused = paused,
            qualities = qualities?.let { qualities ->
                buildJsonArray {
                    qualities.forEach {
                        add(json.encodeToJsonElement(it))
                    }
                }.toString()
            },
            quality = quality?.let { json.encodeToString(it) },
            previousQuality = previousQuality?.let { json.encodeToString(it) },
            restoreQuality = restoreQuality,
            playlistUrl = playlistUrl,
            restorePlaylist = restorePlaylist,
            useCustomProxy = useCustomProxy,
            skipAccessToken = skipAccessToken,
        )
        playerRepository.savePlaybackStates(listOf(item))
    }

    protected fun setDefaultQuality() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val defaultQuality = if (cellular) {
            prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        } else {
            prefs().getString(C.PLAYER_DEFAULTQUALITY, "saved")
        }?.substringBefore(" ")
        quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs().getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    AUTO_QUALITY -> qualities?.find { it.name == AUTO_QUALITY }
                    AUDIO_ONLY_QUALITY -> qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    CHAT_ONLY_QUALITY -> qualities?.find { it.name == CHAT_ONLY_QUALITY }
                    else -> findQuality(savedQuality)
                }
            }
            AUTO_QUALITY -> qualities?.find { it.name == AUTO_QUALITY }
            "Source" -> qualities?.find { it.name != AUTO_QUALITY }
            AUDIO_ONLY_QUALITY -> qualities?.find { it.name == AUDIO_ONLY_QUALITY }
            CHAT_ONLY_QUALITY -> qualities?.find { it.name == CHAT_ONLY_QUALITY }
            else -> findQuality(defaultQuality)
        } ?: qualities?.firstOrNull()
    }

    private fun findQuality(targetQualityString: String?): VideoQuality? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = qualities?.last { it.name != AUDIO_ONLY_QUALITY && it.name != CHAT_ONLY_QUALITY }
            qualities?.find { qualityString ->
                val quality = qualityString.name?.split("p")
                val resolution = quality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                val fps = quality?.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || qualityString == last)
            }
        }
    }

    companion object {
        const val AUTO_QUALITY = "auto"
        const val SOURCE_QUALITY = "source"
        const val AUDIO_ONLY_QUALITY = "audio_only"
        const val CHAT_ONLY_QUALITY = "chat_only"

        const val STREAM = "stream"
        const val VIDEO = "video"
        const val CLIP = "clip"
        const val OFFLINE_VIDEO = "offlineVideo"
    }
}