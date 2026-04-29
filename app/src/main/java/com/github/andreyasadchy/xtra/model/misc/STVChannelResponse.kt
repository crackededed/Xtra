package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class STVChannelResponse(
    @SerialName("emote_set")
    val emoteSet: STVEmoteSetResponse,
)