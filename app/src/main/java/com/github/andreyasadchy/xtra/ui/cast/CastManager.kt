package com.github.andreyasadchy.xtra.ui.cast

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

class CastManager(private val appContext: android.content.Context) {

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    fun interface ConnectionCallback {
        fun onConnectionChanged(connected: Boolean)
    }

    fun warmUp() {
        castContextOrNull()
    }

    fun sessionManagerOrNull(): SessionManager? = castContextOrNull()?.sessionManager

    val isConnected: Boolean
        get() = currentSession?.isConnected == true

    val currentSession: CastSession?
        get() = sessionManagerOrNull()?.currentCastSession

    val remoteMediaClient: RemoteMediaClient?
        get() = currentSession?.remoteMediaClient

    private var castedType: String? = null
    private var castedChannelId: String? = null
    private var castedVideoId: String? = null
    private var castedClipId: String? = null

    fun setCastedContent(type: String?, channelId: String?, videoId: String?, clipId: String?) {
        castedType = type
        castedChannelId = channelId
        castedVideoId = videoId
        castedClipId = clipId
    }

    fun hasCastedContent(): Boolean = castedType != null

    fun isCastingContent(type: String?, channelId: String?, videoId: String?, clipId: String?): Boolean {
        if (!isConnected || castedType == null) return false
        return castedType == type && castedChannelId == channelId && castedVideoId == videoId && castedClipId == clipId
    }

    fun clearCastedContent() {
        castedType = null
        castedChannelId = null
        castedVideoId = null
        castedClipId = null
    }

    fun loadStream(
        hlsUrl: String,
        title: String?,
        channelName: String?,
        thumbnail: String?,
        currentTimeMs: Long? = null,
        isLive: Boolean = true,
        durationMs: Long? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        try {
            val client = remoteMediaClient ?: run {
                onResult(false)
                return
            }
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                if (!title.isNullOrBlank()) putString(MediaMetadata.KEY_TITLE, title)
                if (!channelName.isNullOrBlank()) putString(MediaMetadata.KEY_SUBTITLE, channelName)
                if (!thumbnail.isNullOrBlank()) {
                    addImage(WebImage(Uri.parse(thumbnail)))
                }
            }
            val mediaInfo = MediaInfo.Builder(hlsUrl)
                .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("application/x-mpegURL")
                .apply {
                    if (!isLive && durationMs != null && durationMs > 0) {
                        setStreamDuration(durationMs)
                    }
                }
                .setMetadata(metadata)
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .apply {
                    if (currentTimeMs != null && currentTimeMs > 0) {
                        setCurrentTime(currentTimeMs)
                    }
                    if (!isLive) {
                        setAutoplay(true)
                    }
                }
                .build()
            client.load(request).setResultCallback { result ->
                onResult(result.status.isSuccess)
            }
        } catch (_: Exception) {
            onResult(false)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var volumeChangedCallback: (() -> Unit)? = null
    var playbackStateCallback: (() -> Unit)? = null
    var playCurrentRequest: (() -> Unit)? = null
    private var volumeListener: Cast.Listener? = null
    private var volumeListenerSession: CastSession? = null
    private var playPauseListener: RemoteMediaClient.Listener? = null
    private var playPauseListenerClient: RemoteMediaClient? = null
    private var castIsPlaying = false
    private var castPollRunnable: Runnable? = null

    fun setControlsEnabled(enabled: Boolean) {
        try {
            val client = remoteMediaClient
            if (enabled) {
                if (playPauseListener == null && client != null) {
                    val listener = object : RemoteMediaClient.Listener {
                        override fun onStatusUpdated() {
                            try {
                                castIsPlaying = remoteMediaClient?.isPlaying == true
                            } catch (_: Exception) {
                                castIsPlaying = false
                            }
                            try {
                                playbackStateCallback?.invoke()
                            } catch (_: Exception) {
                            }
                        }

                        override fun onMetadataUpdated() = Unit
                        override fun onQueueStatusUpdated() = Unit
                        override fun onPreloadStatusUpdated() = Unit
                        override fun onSendingRemoteMediaRequest() = Unit
                        override fun onAdBreakStatusUpdated() = Unit
                    }
                    playPauseListener = listener
                    playPauseListenerClient = client
                    client.addListener(listener)
                }
                if (client != null) {
                    castIsPlaying = client.isPlaying
                } else if (castPollRunnable == null) {
                    val poll = Runnable {
                        castPollRunnable = null
                        if (playPauseListener == null) {
                            setControlsEnabled(true)
                        }
                    }
                    castPollRunnable = poll
                    mainHandler.postDelayed(poll, 5000)
                }
            } else {
                castPollRunnable?.let { mainHandler.removeCallbacks(it) }
                castPollRunnable = null
                playPauseListener?.let { listener ->
                    try {
                        (playPauseListenerClient ?: client)?.removeListener(listener)
                    } catch (_: Exception) {
                    }
                }
                playPauseListener = null
                playPauseListenerClient = null
                castIsPlaying = false
            }
        } catch (_: Exception) {
        }
    }

    fun isRemotePlaying(): Boolean = castIsPlaying

    fun hasActiveMedia(): Boolean = remoteMediaClient?.hasMediaSession() == true

    fun playRemote() {
        safe { remoteMediaClient?.play() }
    }

    fun pauseRemote() {
        safe { remoteMediaClient?.pause() }
    }

    fun stopCasting() {
        safe {
            remoteMediaClient?.stop()
            sessionManagerOrNull()?.endCurrentSession(true)
            clearCastedContent()
        }
    }

    val deviceVolume: Double
        get() = currentSession?.volume ?: 0.0

    fun setDeviceVolume(volume: Double) {
        safe { currentSession?.setVolume(volume.coerceIn(0.0, 1.0)) }
    }

    fun attachVolumeListener() {
        if (volumeListener != null) return
        val session = currentSession ?: return
        val listener = object : Cast.Listener() {
            override fun onVolumeChanged() {
                safe { volumeChangedCallback?.invoke() }
            }
        }
        safe {
            volumeListener = listener
            volumeListenerSession = session
            session.addCastListener(listener)
        }
    }

    fun detachVolumeListener() {
        volumeListener?.let { listener ->
            safe { (volumeListenerSession ?: currentSession)?.removeCastListener(listener) }
        }
        volumeListener = null
        volumeListenerSession = null
    }

    fun addSessionListener(listener: SessionManagerListener<CastSession>) {
        safe { sessionManagerOrNull()?.addSessionManagerListener(listener, CastSession::class.java) }
    }

    fun addConnectionCallback(callback: ConnectionCallback): SessionManagerListener<CastSession> {
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                callback.onConnectionChanged(true)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                callback.onConnectionChanged(true)
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                callback.onConnectionChanged(false)
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) {
                callback.onConnectionChanged(false)
            }

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
        }
        addSessionListener(listener)
        safe { callback.onConnectionChanged(isConnected) }
        return listener
    }

    fun removeConnectionCallback(listener: SessionManagerListener<CastSession>) {
        removeSessionListener(listener)
    }

    fun removeSessionListener(listener: SessionManagerListener<CastSession>) {
        safe { sessionManagerOrNull()?.removeSessionManagerListener(listener, CastSession::class.java) }
    }

    private fun castContextOrNull(): CastContext? {
        return safe { CastContext.getSharedInstance(appContext) }
    }
}
