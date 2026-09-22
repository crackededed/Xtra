package com.github.andreyasadchy.xtra.ui.cast

class CastStreamController(private val castManager: CastManager) {

    data class StreamMetadata(
        val title: String? = null,
        val channelName: String? = null,
        val thumbnail: String? = null,
    )

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
        isLive: Boolean = true,
        durationMs: Long? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        val currentTime = if (!isLive) {
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

    }
