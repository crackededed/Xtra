package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_swap")
class VideoSwap(
    var platform: String? = null,
    var playerType: String? = null,
    var position: Int,
    var enabled: Boolean = true,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}