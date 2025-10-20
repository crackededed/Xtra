package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stream_filter")
class StreamFilter(
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val tags: String? = null,
    val languages : String? = null
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}