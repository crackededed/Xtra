package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.Serializable

@Serializable
class STVEmoteSetResponse(
    val id: String? = null,
    val emotes: List<STVResponse>,
)