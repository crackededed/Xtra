package com.github.andreyasadchy.xtra.ui.player

import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.floor

abstract class BasePlaybackService : LifecycleService() {

    lateinit var xtraModule: XtraModule

    val integrity = MutableSharedFlow<String?>()

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
    var videoCreatedAt: String? = null
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
    var currentCustomProxy = 0
    var useStreamProxy = false
    var currentStreamProxy = 0
    var skipAccessToken = false

    var chatUrl: String? = null
    var started = false
    var loaded = false

    protected suspend fun restorePlaybackState() {
        val savedState = xtraModule.playerRepository.getPlaybackStates().firstOrNull()
        xtraModule.playerRepository.deletePlaybackStates()
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
            videoCreatedAt = savedState.videoCreatedAt
            videoAnimatedPreviewURL = savedState.videoAnimatedPreviewURL
            savedPosition = savedState.position
            paused = savedState.paused
            qualities = savedState.qualities?.let { qualities ->
                xtraModule.json.decodeFromString<JsonArray>(qualities).map {
                    xtraModule.json.decodeFromJsonElement<VideoQuality>(it)
                }
            }
            quality = savedState.quality?.let { xtraModule.json.decodeFromString(it) }
            previousQuality = savedState.previousQuality?.let { xtraModule.json.decodeFromString(it) }
            restoreQuality = savedState.restoreQuality
            playlistUrl = savedState.playlistUrl
            restorePlaylist = savedState.restorePlaylist
            useCustomProxy = savedState.useCustomProxy
            currentCustomProxy = savedState.currentCustomProxy
            useStreamProxy = savedState.useStreamProxy
            currentStreamProxy = savedState.currentStreamProxy
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
            videoCreatedAt = videoCreatedAt,
            videoAnimatedPreviewURL = videoAnimatedPreviewURL,
            position = position,
            paused = paused,
            qualities = qualities?.let { qualities ->
                buildJsonArray {
                    qualities.forEach {
                        add(xtraModule.json.encodeToJsonElement(it))
                    }
                }.toString()
            },
            quality = quality?.let { xtraModule.json.encodeToString(it) },
            previousQuality = previousQuality?.let { xtraModule.json.encodeToString(it) },
            restoreQuality = restoreQuality,
            playlistUrl = playlistUrl,
            restorePlaylist = restorePlaylist,
            useCustomProxy = useCustomProxy,
            currentCustomProxy = currentCustomProxy,
            useStreamProxy = useStreamProxy,
            currentStreamProxy = currentStreamProxy,
            skipAccessToken = skipAccessToken,
        )
        xtraModule.playerRepository.savePlaybackStates(listOf(item))
    }

    protected fun setDefaultQuality() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val defaultQuality = if (cellular) {
            prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        } else {
            prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved")
        }?.substringBefore(" ")
        quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs().getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    VideoQuality.AUTO_QUALITY -> qualities?.find { it.name == VideoQuality.AUTO_QUALITY }
                    VideoQuality.AUDIO_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
                    VideoQuality.CHAT_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY }
                    else -> findQuality(savedQuality)
                }
            }
            VideoQuality.AUTO_QUALITY -> qualities?.find { it.name == VideoQuality.AUTO_QUALITY }
            "Source" -> qualities?.find { it.name != VideoQuality.AUTO_QUALITY }
            VideoQuality.AUDIO_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
            VideoQuality.CHAT_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY }
            else -> findQuality(defaultQuality)
        } ?: qualities?.firstOrNull()
    }

    protected fun isCastConnected(): Boolean {
        return try {
            xtraModule.castManager.isConnected
        } catch (_: Exception) {
            false
        }
    }

    protected fun isCastActive(): Boolean {
        return try {
            val manager = xtraModule.castManager
            manager.isConnected && manager.hasActiveMedia()
        } catch (_: Exception) {
            false
        }
    }

    protected fun isCastPlaying(): Boolean {
        return try {
            xtraModule.castManager.isRemotePlaying()
        } catch (_: Exception) {
            false
        }
    }

    protected fun castTogglePlayPause() {
        try {
            val manager = xtraModule.castManager
            if (manager.remoteMediaClient?.isPlaying == true) {
                manager.pauseRemote()
            } else {
                manager.playRemote()
            }
        } catch (_: Exception) {
        }
    }

    protected fun castPause() {
        try {
            xtraModule.castManager.pauseRemote()
        } catch (_: Exception) {
        }
    }

    protected fun castStop() {
        try {
            xtraModule.castManager.stopCasting()
        } catch (_: Exception) {
        }
    }

    protected fun castSeekRelative(deltaMs: Long) {
        try {
            val client = xtraModule.castManager.remoteMediaClient
            val target = client?.approximateStreamPosition?.plus(deltaMs)
            if (client != null && target != null && target >= 0) {
                client.seek(target)
            }
        } catch (_: Exception) {
        }
    }

    protected fun setCastControlsEnabled(enabled: Boolean) {
        try {
            xtraModule.castManager.setControlsEnabled(enabled)
        } catch (_: Exception) {
        }
    }

    private var volumeSession: MediaSession? = null
    private var castVolumeProvider: VolumeProvider? = null
    private var castVolumeSessionListener: SessionManagerListener<CastSession>? = null
    private var castVolumeTarget = 0
    private var castVolumeLastLocalChange = 0L

    protected fun setupCastVolumeControl(session: MediaSession) {
        volumeSession = session
        if (castVolumeProvider == null) {
            castVolumeTarget = castVolumePercent()
            val provider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, castVolumeTarget) {
                override fun onSetVolumeTo(volume: Int) {
                    if (isCastConnected()) {
                        setCastVolumeTarget(volume)
                    }
                }

                override fun onAdjustVolume(direction: Int) {
                    if (isCastConnected() && direction != 0) {
                        setCastVolumeTarget(castVolumeTarget + direction)
                    }
                }
            }
            castVolumeProvider = provider
            xtraModule.castManager.volumeChangedCallback = {
                try {
                    if (SystemClock.elapsedRealtime() - castVolumeLastLocalChange > 3000) {
                        val deviceVolume = castVolumePercent()
                        if (deviceVolume != castVolumeTarget) {
                            castVolumeTarget = deviceVolume
                            castVolumeProvider?.setCurrentVolume(deviceVolume)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        xtraModule.castManager.playbackStateCallback = {
            onCastPlaybackStateChanged()
        }
        if (castVolumeSessionListener == null) {
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    xtraModule.castManager.attachVolumeListener()
                    applyCastVolumeControl(true)
                }

                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    xtraModule.castManager.attachVolumeListener()
                    applyCastVolumeControl(true)
                }

                override fun onSessionEnded(session: CastSession, error: Int) {
                    xtraModule.castManager.detachVolumeListener()
                    applyCastVolumeControl(false)
                }

                override fun onSessionSuspended(session: CastSession, reason: Int) {
                    xtraModule.castManager.detachVolumeListener()
                    applyCastVolumeControl(false)
                }

                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
                override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            }
            castVolumeSessionListener = listener
            try {
                xtraModule.castManager.addSessionListener(listener)
            } catch (_: Exception) {
            }
        }
        if (isCastConnected()) {
            xtraModule.castManager.attachVolumeListener()
        }
        applyCastVolumeControl(isCastConnected())
    }

    protected open fun onCastPlaybackStateChanged() {}

    protected fun teardownCastVolumeControl() {
        castVolumeSessionListener?.let { listener ->
            try {
                xtraModule.castManager.removeSessionListener(listener)
            } catch (_: Exception) {
            }
        }
        castVolumeSessionListener = null
        xtraModule.castManager.detachVolumeListener()
        xtraModule.castManager.volumeChangedCallback = null
        xtraModule.castManager.playbackStateCallback = null
        castVolumeProvider = null
        volumeSession = null
    }

    private fun applyCastVolumeControl(castConnected: Boolean) {
        val session = volumeSession ?: return
        val provider = castVolumeProvider ?: return
        try {
            if (castConnected) {
                castVolumeTarget = castVolumePercent()
                provider.setCurrentVolume(castVolumeTarget)
                session.setPlaybackToRemote(provider)
            } else {
                session.setPlaybackToLocal(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun castVolumePercent(): Int {
        return try {
            Math.round(xtraModule.castManager.deviceVolume * 100).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    private fun setCastVolumeTarget(target: Int) {
        val clamped = target.coerceIn(0, 100)
        castVolumeTarget = clamped
        castVolumeLastLocalChange = SystemClock.elapsedRealtime()
        castVolumeProvider?.setCurrentVolume(clamped)
        castSetVolume(clamped / 100.0)
    }

    protected fun castSeekTo(positionMs: Long) {
        try {
            val client = xtraModule.castManager.remoteMediaClient
            if (client != null && positionMs >= 0) {
                client.seek(positionMs)
            }
        } catch (_: Exception) {
        }
    }

    protected fun castSetVolume(volume: Double) {
        try {
            xtraModule.castManager.setDeviceVolume(volume)
        } catch (_: Exception) {
        }
    }

    private fun findQuality(targetQualityString: String?): VideoQuality? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = qualities?.lastOrNull { it.name != VideoQuality.AUDIO_ONLY_QUALITY && it.name != VideoQuality.CHAT_ONLY_QUALITY }
            qualities?.find { quality ->
                quality.resolution != null
                        && ((targetResolution == quality.resolution
                        && targetFps >= (quality.frameRate?.let { fps -> floor(fps) } ?: 30f))
                        || targetResolution > quality.resolution
                        || quality == last)
            }
        }
    }

    companion object {
        const val STREAM = "stream"
        const val VIDEO = "video"
        const val CLIP = "clip"
        const val OFFLINE_VIDEO = "offlineVideo"
    }
}