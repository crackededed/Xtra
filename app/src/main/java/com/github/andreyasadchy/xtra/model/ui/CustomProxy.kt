package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_proxies")
class CustomProxy(
    var url: String?,
    var addQueryParams: Boolean,
    var position: Int,
    var enabled: Boolean,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}