package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.Serializable

@Serializable
class FFZChannelResponse(
    val sets: Map<String, FFZResponse>,
)