package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Clip(
    val id: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val videoId: String? = null,
    var gameId: String? = null,
    val title: String? = null,
    val viewCount: Int? = null,
    val uploadDate: String? = null,
    val thumbnailUrl: String? = null,
    val duration: Double? = null,
    val vodOffset: Int? = null,

    var gameSlug: String? = null,
    var gameName: String? = null,
    var channelLogin: String? = null,
    var profileImageUrl: String? = null,
    val videoAnimatedPreviewURL: String? = null,
) : Parcelable {

    val thumbnail: String?
        get() = TwitchApiHelper.getClipThumbnail(thumbnailUrl)
    val channelLogo: String?
        get() = TwitchApiHelper.getProfileImage(profileImageUrl)
}
