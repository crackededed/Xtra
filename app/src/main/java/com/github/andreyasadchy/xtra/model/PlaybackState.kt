package com.github.andreyasadchy.xtra.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_states")
class PlaybackState(
    val type: String? = null,
    val streamId: String? = null,
    val videoId: String? = null,
    val clipId: String? = null,
    val offlineVideoId: Int? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val channelImage: String? = null,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val createdAt: String? = null,
    val viewerCount: Int? = null,
    val durationSeconds: Int? = null,
    val videoType: String? = null,
    val videoOffsetSeconds: Int? = null,
    val videoAnimatedPreviewURL: String? = null,
    val position: Long? = null,
    val paused: Boolean = false,
    val qualities: String? = null,
    val quality: String? = null,
    val previousQuality: String? = null,
    val restoreQuality: Boolean = false,
    val playlistUrl: String? = null,
    val restorePlaylist: Boolean = false,
    val useCustomProxy: Boolean = false,
    val skipAccessToken: Boolean = false,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}