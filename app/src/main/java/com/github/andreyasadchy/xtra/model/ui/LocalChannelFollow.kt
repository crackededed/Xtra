package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_follows")
class LocalChannelFollow(
    val userId: String? = null,
    var userLogin: String? = null,
    var userName: String? = null,
    var channelLogo: String? = null,
) {

    @PrimaryKey(autoGenerate = true)
    var id = 0
}
