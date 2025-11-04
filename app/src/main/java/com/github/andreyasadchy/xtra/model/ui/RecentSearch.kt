package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_search")
class RecentSearch(
    val query: String,
    var lastSearched: Long,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}