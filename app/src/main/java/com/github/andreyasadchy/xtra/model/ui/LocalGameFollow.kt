package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_follows_games")
class LocalGameFollow(
    val gameId: String? = null,
    val gameSlug: String? = null,
    var gameName: String? = null,
    var boxArt: String? = null,
) {

    @PrimaryKey(autoGenerate = true)
    var id = 0
}
