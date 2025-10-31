package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Video(
    val id: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val title: String? = null,
    val uploadDate: String? = null,
    val thumbnailUrl: String? = null,
    val viewCount: Int? = null,
    val type: String? = null,
    val duration: String? = null,

    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    var profileImageUrl: String? = null,
    val animatedPreviewURL: String? = null,
) : Parcelable {

    val thumbnail: String?
        get() = TwitchApiHelper.getTemplateUrl(thumbnailUrl, "video")
    val channelLogo: String?
        get() = TwitchApiHelper.getTemplateUrl(profileImageUrl, "profileimage")
}