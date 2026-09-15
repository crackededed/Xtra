package com.github.andreyasadchy.xtra.ui.cast

import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Kapselt den Lifecycle eines Twitch-Live-Streams auf dem Cast-Gerät.
 *
 * Wraps load/stop on the cast device and detects playback errors (e.g.,
 * expired URL, 403) so the caller can refresh the URL and replay.
 *
 * All cast access is wrapped in try/catch so the app stays normal without
 * Google Play Services or a cast device, and the local player is unaffected.
 *
 * Logging policy: no URLs or tokens are logged. Only boolean state changes
 * and error callbacks are forwarded to the caller.
 */
class CastStreamController(private val castManager: CastManager) {

    /** Metadata for the currently playing stream shown on the TV. */
    data class StreamMetadata(
        val title: String? = null,
        val channelName: String? = null,
        val thumbnail: String? = null,
    )

    /** Callback for playback errors reported by the cast receiver. */
    fun interface ErrorListener {
        fun onStreamError()
    }

    private var errorListener: ErrorListener? = null
    private var clientListener: RemoteMediaClient.Listener? = null

    /**
     * Sets the error listener. When the receiver reports a playback error
     * (e.g., expired URL, IP mismatch), [ErrorListener.onStreamError] is
     * invoked so the caller can refresh the URL and replay.
     */
    fun setErrorListener(listener: ErrorListener?) {
        clientListener?.let { castManager.removeMediaStatusListener(it) }
        clientListener = null
        errorListener = listener
        if (listener != null) {
            val remoteListener = object : RemoteMediaClient.Listener {
                override fun onStatusUpdated() {
                    checkForError()
                }

                override fun onMetadataUpdated() = Unit
                override fun onQueueStatusUpdated() = Unit
                override fun onPreloadStatusUpdated() = Unit
                override fun onSendingRemoteMediaRequest() = Unit
                override fun onAdBreakStatusUpdated() = Unit
            }
            clientListener = remoteListener
            castManager.addMediaStatusListener(remoteListener)
        }
    }

    /**
     * Starts playback of the given HLS URL as a live stream on the cast device.
     * The URL must be a complete signed Twitch HLS URL (sig+token in query).
     */
    fun play(url: String, metadata: StreamMetadata, onResult: (Boolean) -> Unit = {}) {
        castManager.loadStream(url, metadata.title, metadata.channelName, metadata.thumbnail, onResult)
    }

    /** Stops playback on the cast device without ending the session. */
    fun stop() {
        castManager.stopStream()
    }

    /**
     * Checks the current MediaStatus for a playback error.
     * Fires [ErrorListener.onStreamError] if the receiver is idle with
     * IDLE_REASON_ERROR (e.g., expired URL, 403 from CDN).
     */
    private fun checkForError() {
        try {
            val status = castManager.remoteMediaClient?.mediaStatus ?: return
            if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                status.idleReason == MediaStatus.IDLE_REASON_ERROR
            ) {
                errorListener?.onStreamError()
            }
        } catch (_: Exception) {
            // Cast is optional: ignore errors.
        }
    }
}
