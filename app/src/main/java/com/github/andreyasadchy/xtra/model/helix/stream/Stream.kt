package com.github.andreyasadchy.xtra.model.helix.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Stream(
    val id: String? = null,
    @SerialName("user_id")
    val channelId: String? = null,
    @SerialName("user_login")
    val channelLogin: String? = null,
    @SerialName("user_name")
    val channelName: String? = null,
    @SerialName("game_id")
    val gameId: String? = null,
    @SerialName("game_name")
    val gameName: String? = null,
    val title: String? = null,
    @SerialName("viewer_count")
    val viewerCount: Int? = null,
    @SerialName("started_at")
    val startedAt: String? = null,
    @SerialName("thumbnail_url")
    val thumbnailUrl: String? = null,
    val tags: List<String>? = null,
)