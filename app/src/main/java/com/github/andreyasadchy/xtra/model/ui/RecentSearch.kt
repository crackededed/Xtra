package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_search")
class RecentSearch(
    val query: String,
    val type: String,
    val lastSearched: Long,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0

    companion object {
        const val TYPE_CHANNEL = "channel"
        const val TYPE_GAME = "game"
        const val TYPE_STREAM = "stream"
        const val TYPE_VIDEO = "video"
    }
}