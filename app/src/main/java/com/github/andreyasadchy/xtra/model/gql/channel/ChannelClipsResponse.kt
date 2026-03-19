package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class ChannelClipsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val user: Channel,
    )

    @Serializable
    class Channel(
        val clips: Clips? = null,
    )

    @Serializable
    class Clips(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Clip,
        val cursor: String? = null,
    )

    @Serializable
    class Clip(
        val slug: String? = null,
        val broadcaster: User? = null,
        val game: Game? = null,
        val title: String? = null,
        val viewCount: Int? = null,
        val createdAt: String? = null,
        val thumbnailURL: String? = null,
        val durationSeconds: Int? = null,
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
        val name: String? = null,
    )
}