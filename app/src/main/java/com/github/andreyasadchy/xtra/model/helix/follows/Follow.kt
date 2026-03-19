package com.github.andreyasadchy.xtra.model.helix.follows

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Follow(
    @SerialName("broadcaster_id")
    val id: String? = null,
    @SerialName("broadcaster_login")
    val login: String? = null,
    @SerialName("broadcaster_name")
    val displayName: String? = null,
    @SerialName("followed_at")
    val followedAt: String? = null,
)