package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
class Bookmark(
    val videoId: String? = null,
    var userId: String? = null,
    var userLogin: String? = null,
    var userName: String? = null,
    var userType: String? = null,
    var userBroadcasterType: String? = null,
    var userLogo: String? = null,
    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    var title: String? = null,
    var createdAt: String? = null,
    var thumbnail: String? = null,
    var type: String? = null,
    var duration: String? = null,
    var animatedPreviewURL: String? = null,
) {

    @PrimaryKey(autoGenerate = true)
    var id = 0
}
