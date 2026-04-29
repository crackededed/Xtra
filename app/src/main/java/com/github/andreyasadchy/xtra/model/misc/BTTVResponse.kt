package com.github.andreyasadchy.xtra.model.misc

import kotlinx.serialization.Serializable

@Serializable
class BTTVResponse(
    val id: String? = null,
    val code: String? = null,
    val animated: Boolean? = null,
)