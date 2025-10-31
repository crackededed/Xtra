package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sort_channel")
class SortChannel(
    @PrimaryKey
    val id: String,
    var videoSort: String? = null,
    var videoType: String? = null,
    var clipPeriod: String? = null,
)
