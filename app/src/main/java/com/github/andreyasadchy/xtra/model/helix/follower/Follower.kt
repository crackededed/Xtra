package com.github.andreyasadchy.xtra.model.helix.follower

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Follower(
    @SerialName("user_id")
    val id: String? = null,
    @SerialName("user_login")
    val login: String? = null,
    @SerialName("user_name")
    val displayName: String? = null,
    @SerialName("followed_at")
    val followedAt: String? = null,
)