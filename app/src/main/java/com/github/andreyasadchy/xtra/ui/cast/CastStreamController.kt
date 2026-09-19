package com.github.andreyasadchy.xtra.ui.cast

import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient

class CastStreamController(private val castManager: CastManager) {

    data class StreamMetadata(
        val title: String? = null,
        val channelName: String? = null,
        val thumbnail: String? = null,
    )

    fun interface ErrorListener {
        fun onStreamError()
    }

    private var errorListener: ErrorListener? = null
    private var clientListener: RemoteMediaClient.Listener? = null

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

    fun play(
        url: String,
        metadata: StreamMetadata,
        isLive: Boolean = true,
        durationMs: Long? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        castManager.loadStream(
            url,
            metadata.title,
            metadata.channelName,
            metadata.thumbnail,
            currentTimeMs = null,
            isLive = isLive,
            durationMs = durationMs,
            onResult = onResult,
        )
    }

    fun changeQuality(
        url: String,
        metadata: StreamMetadata,
        keepPosition: Boolean,
        isLive: Boolean = true,
        durationMs: Long? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        val currentTime = if (keepPosition) {
            try {
                castManager.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        castManager.loadStream(
            url,
            metadata.title,
            metadata.channelName,
            metadata.thumbnail,
            currentTime,
            isLive,
            durationMs,
            onResult,
        )
    }

    fun stop() {
        castManager.setControlsEnabled(false)
    }

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
