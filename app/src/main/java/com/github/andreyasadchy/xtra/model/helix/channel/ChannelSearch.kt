package com.github.andreyasadchy.xtra.model.helix.channel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChannelSearch(
    val id: String? = null,
    @SerialName("broadcaster_login")
    val login: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("thumbnail_url")
    val profileImageURL: String? = null,
    @SerialName("is_live")
    val isLive: Boolean? = null,
    @SerialName("game_id")
    val gameId: String? = null,
    @SerialName("game_name")
    val gameName: String? = null,
    val title: String? = null,
    @SerialName("started_at")
    val startedAt: String? = null,
    val tags: List<String>? = null,
)