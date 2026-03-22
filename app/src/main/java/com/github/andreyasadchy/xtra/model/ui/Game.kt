package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Game(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val boxArtURL: String? = null,
    var viewerCount: Int? = null,
    var broadcasterCount: Int? = null,
    val followerCount: Int? = null,
    var tags: List<Tag>? = null,
    val vodPosition: Int? = null,
    val vodDuration: Int? = null,
    var accountFollow: Boolean = false,
    val localFollow: Boolean = false,
) : Parcelable {

    val boxArt: String?
        get() = TwitchApiHelper.getGameBoxArt(boxArtURL)
}