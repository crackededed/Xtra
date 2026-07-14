package com.github.andreyasadchy.xtra.model

import kotlinx.serialization.Serializable

@Serializable
class VideoQuality(
    val name: String? = null,
    val codecs: String? = null,
    val bitrate: Int? = null,
    val url: String? = null,
)