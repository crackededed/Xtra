package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.Parcelize

@Parcelize
class Game(
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val boxArtUrl: String? = null,

    var viewersCount: Int? = null,
    var broadcastersCount: Int? = null,
    val followersCount: Int? = null,
    var tags: List<Tag>? = null,
    val vodPosition: Int? = null,
    val vodDuration: Int? = null,

    var followAccount: Boolean = false,
    val followLocal: Boolean = false,
) : Parcelable {

    val boxArt: String?
        get() = TwitchApiHelper.getTemplateUrl(boxArtUrl, "game")
}