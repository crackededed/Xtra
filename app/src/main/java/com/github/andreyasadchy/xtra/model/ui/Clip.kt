package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Clip(
    val id: String? = null,
    val channelId: String? = null,
    var channelLogin: String? = null,
    val channelName: String? = null,
    var channelImageURL: String? = null,
    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    val title: String? = null,
    val thumbnailURL: String? = null,
    val createdAt: String? = null,
    val viewCount: Int? = null,
    val durationSeconds: Int? = null,
    val videoId: String? = null,
    val videoOffsetSeconds: Int? = null,
    val videoAnimatedPreviewURL: String? = null,
) : Parcelable {

    val channelImage: String?
        get() = TwitchApiHelper.getProfileImage(channelImageURL)
    val thumbnail: String?
        get() = TwitchApiHelper.getClipThumbnail(thumbnailURL)
}
