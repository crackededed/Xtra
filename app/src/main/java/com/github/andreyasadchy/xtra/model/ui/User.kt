package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class User(
    val id: String? = null,
    val login: String? = null,
    val name: String? = null,
    var profileImageURL: String? = null,
    val type: String? = null,
    val broadcasterType: String? = null,
    val createdAt: String? = null,
    val followerCount: Int? = null,
    val bannerImageURL: String? = null,
    var lastBroadcast: String? = null,
    val isLive: Boolean? = false,
    var followedAt: String? = null,
    var accountFollow: Boolean = false,
    val localFollow: Boolean = false,
) : Parcelable {

    val profileImage: String?
        get() = TwitchApiHelper.getProfileImage(profileImageURL)
}