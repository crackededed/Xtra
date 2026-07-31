package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.Serializable

@Serializable
class STVEmoteSetResponse(
    val id: String? = null,
    val emotes: List<Emote>,
) {
    @Serializable
    class Emote(
        val name: String? = null,
        val flags: Int? = null,
        val data: Data? = null,
    )

    @Serializable
    class Data(
        val host: Host? = null,
        val animated: Boolean? = null,
    )

    @Serializable
    class Host(
        val url: String? = null,
        val files: List<File>? = null,
    )

    @Serializable
    class File(
        val name: String? = null,
        val format: String? = null,
    )
}