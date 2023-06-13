package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.model.offline.Downloadable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
data class Video(
    override val id: String? = null,
    override val channelId: String? = null,
    override val channelLogin: String? = null,
    override val channelName: String? = null,
    override val title: String? = null,
    override val uploadDate: String? = null,
    val thumbnailUrl: String? = null,
    val viewCount: Int? = null,
    override val type: String? = null,
    val duration: String? = null,

    override var gameId: String? = null,
    override var gameName: String? = null,
    var profileImageUrl: String? = null,
    val tags: List<Tag>? = null,
    val animatedPreviewURL: String? = null) : Parcelable, Downloadable {

        override val thumbnail: String?
                get() = TwitchApiHelper.getTemplateUrl(thumbnailUrl, "video")
        override val channelLogo: String?
                get() = TwitchApiHelper.getTemplateUrl(profileImageUrl, "profileimage")
}