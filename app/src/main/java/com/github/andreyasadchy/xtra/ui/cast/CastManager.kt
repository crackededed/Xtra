package com.github.andreyasadchy.xtra.ui.cast

import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
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

    private fun removeSessionListener(listener: SessionManagerListener<CastSession>) {
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
