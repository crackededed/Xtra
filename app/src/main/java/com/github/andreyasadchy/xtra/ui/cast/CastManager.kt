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

/**
 * Minimal isolated cast layer (phase 0/1).
 *
 * Wraps [CastContext] / [SessionManager], detects session status,
 * reports connect/disconnect events, and exposes [RemoteMediaClient].
 * Deliberately does not load media (no MediaInfo, no twitch URL) and does
 * not alter local playback.
 *
 * All cast access is wrapped in try/catch so the app still launches normally
 * without Google Play Services or a cast device, and the local player is
 * unaffected.
 *
 * Usage (lifecycle-safe, e.g. in Activity/Fragment):
 * - call [addConnectionCallback] in onResume/onStart
 * - remove the returned listener in onPause/onStop via [removeConnectionCallback]
 */
class CastManager(private val appContext: android.content.Context) {

    fun interface ConnectionCallback {
        fun onConnectionChanged(connected: Boolean)
    }

    /** Starts cast discovery; errors are silently swallowed. */
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

    /**
     * Loads a live HLS stream on the connected cast device.
     *
     * The URL must be a complete signed Twitch HLS URL (sig+token in query).
     * No custom HTTP headers are needed because Twitch authentication is
     * entirely URL-based, so the Default Media Receiver can fetch directly.
     *
     * Callers must ensure the URL points to a concrete (non-auto) quality
     * variant, because the receiver cannot handle LL-HLS partial segments.
     */
    fun loadStream(
        hlsUrl: String,
        title: String?,
        channelName: String?,
        thumbnail: String?,
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
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .setMetadata(metadata)
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .build()
            client.load(request)
            onResult(true)
        } catch (_: Exception) {
            // Cast is optional: ignore errors, local playback continues.
            onResult(false)
        }
    }

    /** Stops the current media on the cast device without ending the session. */
    fun stopStream() {
        try {
            remoteMediaClient?.stop()
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    /** Registers a listener for RemoteMediaClient status updates (errors, idle). */
    fun addMediaStatusListener(listener: RemoteMediaClient.Listener) {
        try {
            remoteMediaClient?.addListener(listener)
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }

    /** Removes a previously registered RemoteMediaClient status listener. */
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
