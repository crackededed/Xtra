package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class User(
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val type: String? = null,
    val broadcasterType: String? = null,
    var profileImageUrl: String? = null,
    val createdAt: String? = null,

    val followersCount: Int? = null,
    val bannerImageURL: String? = null,
    var followedAt: String? = null,
    var lastBroadcast: String? = null,
    val isLive: Boolean? = false,
    val stream: Stream? = null,

    var followAccount: Boolean = false,
    val followLocal: Boolean = false,
) : Parcelable {

    val channelLogo: String?
        get() = TwitchApiHelper.getProfileImage(profileImageUrl)
}