package com.github.andreyasadchy.xtra.model

import kotlinx.serialization.Serializable

@Serializable
class VideoQuality(
    val name: String? = null,
    val resolution: Int? = null,
    val frameRate: Float? = null,
    val bitrate: Int? = null,
    val codecs: String? = null,
    val url: String? = null,
) {
    companion object {
        const val AUTO_QUALITY = "auto"
        const val SOURCE_QUALITY = "source"
        const val AUDIO_ONLY_QUALITY = "audio_only"
        const val CHAT_ONLY_QUALITY = "chat_only"
    }
}