package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class FFZResponse(
    val emoticons: List<Emote>? = null,
) {
    @Serializable
    class Emote(
        val name: String? = null,
        val animated: Urls? = null,
        val urls: Urls? = null,
    )

    @Serializable
    class Urls(
        @SerialName("1")
        val url1x: String? = null,
        @SerialName("2")
        val url2x: String? = null,
        @SerialName("4")
        val url4x: String? = null,
    )
}