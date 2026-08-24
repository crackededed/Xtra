package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_proxies")
class CustomProxy(
    var url: String? = null,
    var addQueryParams: Boolean = true,
    var position: Int,
    var enabled: Boolean = true,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}