package com.github.andreyasadchy.xtra.model.helix.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class User(
    val id: String? = null,
    val login: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    val type: String? = null,
    @SerialName("broadcaster_type")
    val broadcasterType: String? = null,
    @SerialName("profile_image_url")
    val profileImageURL: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)