package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class ChannelVideosResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val user: Channel,
    )

    @Serializable
    class Channel(
        val videos: Videos? = null,
    )

    @Serializable
    class Videos(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Video,
        val cursor: String? = null,
    )

    @Serializable
    class Video(
        val id: String? = null,
        val owner: User? = null,
        val game: Game? = null,
        val title: String? = null,
        val publishedAt: String? = null,
        val previewThumbnailURL: String? = null,
        val viewCount: Int? = null,
        val lengthSeconds: Int? = null,
        val animatedPreviewURL: String? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val slug: String? = null,
        val displayName: String? = null,
    )
}