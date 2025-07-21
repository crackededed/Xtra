package com.github.andreyasadchy.xtra.model.chat

class Image(
    val localData: ByteArray? = null,
    val url1x: String? = null,
    val url2x: String? = null,
    val url3x: String? = null,
    val url4x: String? = null,
    val format: String? = null,
    val isAnimated: Boolean = false,
    val isEmote: Boolean = false,
    var overlayEmote: Image? = null,
    var start: Int,
    var end: Int,
)