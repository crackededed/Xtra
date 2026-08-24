package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stream_proxies")
class StreamProxy(
    var host: String? = null,
    var port: Int? = null,
    var username: String? = null,
    var password: String? = null,
    var proxyPlaybackAccessToken: Boolean = false,
    var proxyMultivariantPlaylist: Boolean = false,
    var proxyMediaPlaylist: Boolean = true,
    var position: Int,
    var enabled: Boolean = true,
) {
    @PrimaryKey(autoGenerate = true)
    var id = 0
}