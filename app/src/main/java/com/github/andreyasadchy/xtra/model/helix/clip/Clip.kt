package com.github.andreyasadchy.xtra.model.helix.clip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Clip(
    val id: String? = null,
    @SerialName("broadcaster_id")
    val channelId: String? = null,
    @SerialName("broadcaster_name")
    val channelName: String? = null,
    @SerialName("video_id")
    val videoId: String? = null,
    @SerialName("game_id")
    val gameId: String? = null,
    val title: String? = null,
    @SerialName("view_count")
    val viewCount: Int? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("thumbnail_url")
    val thumbnailURL: String? = null,
    val duration: Double? = null,
    @SerialName("vod_offset")
    val vodOffset: Int? = null,
)