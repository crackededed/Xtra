package com.github.andreyasadchy.xtra.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.github.andreyasadchy.xtra.model.ui.Stream

@Entity(tableName = "notification_events")
data class NotificationEvent(
    @PrimaryKey
    val eventId: String,
    val channelId: String,
    val streamId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImageURL: String?,
    val gameName: String?,
    val title: String?,
    val thumbnailURL: String?,
    val createdAt: String?,
    val viewerCount: Int?,
    val startedAt: Long,
    val queuedAt: Long,
) {
    fun toStream() = Stream(
        id = streamId,
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImageURL = channelImageURL,
        gameName = gameName,
        title = title,
        thumbnailURL = thumbnailURL,
        createdAt = createdAt,
        viewerCount = viewerCount,
    )

    companion object {
        fun fromStream(stream: Stream, startedAt: Long): NotificationEvent? {
            val channelId = stream.channelId?.takeIf { it.isNotBlank() } ?: return null
            return NotificationEvent(
                eventId = "$channelId:$startedAt",
                channelId = channelId,
                streamId = stream.id,
                channelLogin = stream.channelLogin,
                channelName = stream.channelName,
                channelImageURL = stream.channelImageURL,
                gameName = stream.gameName,
                title = stream.title,
                thumbnailURL = stream.thumbnailURL,
                createdAt = stream.createdAt,
                viewerCount = stream.viewerCount,
                startedAt = startedAt,
                queuedAt = System.currentTimeMillis(),
            )
        }
    }
}
