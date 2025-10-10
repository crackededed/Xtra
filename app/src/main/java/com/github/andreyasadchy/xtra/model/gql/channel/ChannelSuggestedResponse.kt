package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelSuggestedResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val sideNav: SideNav,
    )

    @Serializable
    class SideNav(
        val sections: Sections
    )

    @Serializable
    class Sections(
        val edges: List<ShelfEdge>
    )

    @Serializable
    class ShelfEdge(
        val node: Shelf
    )

    @Serializable
    class Shelf(
        val id: String? = null,
        val content: Content
    )

    @Serializable
    class Content(
        val edges: List<Item>
    )

    @Serializable
    class Item(
        val node: Stream
    )

    @Serializable
    class Stream(
        val id: String? = null,
        val broadcaster: User? = null,
        val game: Game? = null,
        val viewersCount: Int? = null,
        val type: String? = null,
        val freeformTags: List<Tag>? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
        val broadcastSettings: BroadcastSettings? = null
    )

    @Serializable
    class BroadcastSettings(
        val title: String? = null,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val slug: String? = null,
        val displayName: String? = null,
    )

    @Serializable
    class Tag(
        val name: String? = null,
    )
}