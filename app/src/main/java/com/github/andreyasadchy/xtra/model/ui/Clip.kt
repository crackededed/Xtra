package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.model.offline.Downloadable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
data class Clip(
        override val id: String? = null,
        override val channelId: String? = null,
        override val channelName: String? = null,
        val videoId: String? = null,
        override var gameId: String? = null,
        override val title: String? = null,
        val viewCount: Int? = null,
        override val uploadDate: String? = null,
        val thumbnailUrl: String? = null,
        val duration: Double? = null,
        val vodOffset: Int? = null,

        override var gameName: String? = null,
        override var channelLogin: String? = null,
        var profileImageUrl: String? = null,
        val videoAnimatedPreviewURL: String? = null) : Parcelable, Downloadable {

        override val thumbnail: String?
                get() = TwitchApiHelper.getTemplateUrl(thumbnailUrl, "clip")
        override val channelLogo: String?
                get() = TwitchApiHelper.getTemplateUrl(profileImageUrl, "profileimage")
        override val type: String?
                get() = null
}
