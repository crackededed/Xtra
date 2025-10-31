package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sort_game")
class SortGame(
    @PrimaryKey
    val id: String,
    var streamSort: String? = null,
    var streamTags: String? = null,
    var streamLanguages: String? = null,
    var videoSort: String? = null,
    var videoPeriod: String? = null,
    var videoType: String? = null,
    var videoLanguages: String? = null,
    var clipPeriod: String? = null,
    var clipLanguages: String? = null,
)
