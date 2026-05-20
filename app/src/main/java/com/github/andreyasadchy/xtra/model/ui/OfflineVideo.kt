package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "videos")
class OfflineVideo(
    var url: String? = null,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String? = null,
    @ColumnInfo(name = "source_start_position")
    var sourceStartPosition: Long? = null,
    var name: String? = null,
    @ColumnInfo(name = "channel_id")
    var channelId: String? = null,
    @ColumnInfo(name = "channel_login")
    var channelLogin: String? = null,
    @ColumnInfo(name = "channel_name")
    var channelName: String? = null,
    @ColumnInfo(name = "channel_logo")
    var channelLogo: String? = null,
    var thumbnail: String? = null,
    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    var duration: Long? = null,
    @ColumnInfo(name = "upload_date")
    var uploadDate: Long? = null,
    @ColumnInfo(name = "download_date")
    val downloadDate: Long? = null,
    @ColumnInfo(name = "last_watch_position")
    var lastWatchPosition: Long? = null,
    var progress: Int = 0,
    @ColumnInfo(name = "max_progress")
    var maxProgress: Int = 100,
    var bytes: Long = 0,
    var downloadPath: String? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null,
    var status: Int = STATUS_PENDING,
    val type: String? = null,
    var videoId: String? = null,
    val clipId: String? = null,
    val quality: String? = null,
    val downloadChat: Boolean = false,
    val downloadChatEmotes: Boolean = false,
    var chatProgress: Int = 0,
    var maxChatProgress: Int = 100,
    var chatBytes: Long = 0,
    var chatOffsetSeconds: Int = 0,
    var chatUrl: String? = null,
    val playlistToFile: Boolean = false,
    val live: Boolean = false,
    var lastSegmentUrl: String? = null,
    var liveCommentsArrayStarted: Boolean = false,
) : Parcelable {

    @IgnoredOnParcel
    @PrimaryKey(autoGenerate = true)
    var id = 0

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_DOWNLOADED = 2
        const val STATUS_MOVING = 3
        const val STATUS_DELETING = 4
        const val STATUS_CONVERTING = 5
        const val STATUS_QUEUED = 6
        const val STATUS_WAITING_FOR_NETWORK = 7
        const val STATUS_WAITING_FOR_WIFI = 8
        const val STATUS_WAITING_FOR_STREAM = 9
    }
}
