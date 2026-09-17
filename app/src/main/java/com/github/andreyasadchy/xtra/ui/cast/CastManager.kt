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

    fun interface ConnectionCallback {
        fun onConnectionChanged(connected: Boolean)
    }

    fun warmUp() {
        castContextOrNull()
    }

    fun sessionManagerOrNull(): SessionManager? {
        return try {
            castContextOrNull()?.sessionManager
        } catch (_: Exception) {
            null
        }
    }

    val isConnected: Boolean
        get() = try {
            currentSession?.isConnected == true
        } catch (_: Exception) {
            false
        }

    val currentSession: CastSession?
        get() = try {
            sessionManagerOrNull()?.currentCastSession
        } catch (_: Exception) {
            null
        }

    val remoteMediaClient: RemoteMediaClient?
        get() = try {
            currentSession?.remoteMediaClient
        } catch (_: Exception) {
            null
        }

    fun loadStream(
        hlsUrl: String,
        title: String?,
        channelName: String?,
        thumbnail: String?,
        currentTimeMs: Long? = null,
        isLive: Boolean = true,
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
                .setMetadata(metadata)
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .apply {
                    if (currentTimeMs != null && currentTimeMs > 0) {
                        setCurrentTime(currentTimeMs)
                    }
                }
                .build()
            client.load(request).setResultCallback { result ->
                onResult(result.status.isSuccess)
            }
        } catch (_: Exception) {
            // Cast is optional: ignore errors, local playback continues.
            onResult(false)
        }
    }

    fun stopStream() {
        try {
            remoteMediaClient?.stop()
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var volumeChangedCallback: (() -> Unit)? = null
    var playbackStateCallback: (() -> Unit)? = null
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
            // Cast is optional: ignore errors.
        }
    }

    fun isRemotePlaying(): Boolean {
        return try {
            castIsPlaying
        } catch (_: Exception) {
            false
        }
    }

    fun hasActiveMedia(): Boolean {
        return try {
            remoteMediaClient?.hasMediaSession() == true
        } catch (_: Exception) {
            false
        }
    }

    fun playRemote() {
        try {
            remoteMediaClient?.play()
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun pauseRemote() {
        try {
            remoteMediaClient?.pause()
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun remoteSeekTo(positionMs: Long) {
        try {
            val client = remoteMediaClient
            if (client != null && positionMs >= 0) {
                client.seek(positionMs)
            }
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun stopCasting() {
        try {
            remoteMediaClient?.stop()
            sessionManagerOrNull()?.endCurrentSession(true)
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    val deviceVolume: Double
        get() = try {
            currentSession?.volume ?: 0.0
        } catch (_: Exception) {
            0.0
        }

    fun setDeviceVolume(volume: Double) {
        try {
            currentSession?.setVolume(volume.coerceIn(0.0, 1.0))
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun attachVolumeListener() {
        try {
            if (volumeListener != null) {
                return
            }
            val session = currentSession ?: return
            val listener = object : Cast.Listener() {
                override fun onVolumeChanged() {
                    try {
                        volumeChangedCallback?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
            volumeListener = listener
            volumeListenerSession = session
            session.addCastListener(listener)
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun detachVolumeListener() {
        volumeListener?.let { listener ->
            try {
                (volumeListenerSession ?: currentSession)?.removeCastListener(listener)
            } catch (_: Exception) {
            }
        }
        volumeListener = null
        volumeListenerSession = null
    }

    fun addMediaStatusListener(listener: RemoteMediaClient.Listener) {
        try {
            remoteMediaClient?.addListener(listener)
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun removeMediaStatusListener(listener: RemoteMediaClient.Listener) {
        try {
            remoteMediaClient?.removeListener(listener)
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    fun addSessionListener(listener: SessionManagerListener<CastSession>) {
        try {
            sessionManagerOrNull()?.addSessionManagerListener(listener, CastSession::class.java)
        } catch (_: Exception) {
            // Cast is optional: ignore errors, local playback continues.
        }
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
        try {
            callback.onConnectionChanged(isConnected)
        } catch (_: Exception) {
            // Cast is optional: ignore errors, local playback continues.
        }
        return listener
    }

    fun removeConnectionCallback(listener: SessionManagerListener<CastSession>) {
        removeSessionListener(listener)
    }

    fun removeSessionListener(listener: SessionManagerListener<CastSession>) {
        try {
            sessionManagerOrNull()?.removeSessionManagerListener(listener, CastSession::class.java)
        } catch (_: Exception) {
            // Cast is optional: ignore errors, local playback continues.
        }
    }

    private fun castContextOrNull(): CastContext? {
        return try {
            CastContext.getSharedInstance(appContext)
        } catch (_: Exception) {
            null
        }
    }
}
