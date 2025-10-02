package com.github.andreyasadchy.xtra.model.gql.suggested

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
        val edges: List<ProviderEdge>
    )

    @Serializable
    class ProviderEdge(
        val cursor: String?,
        val node: ProviderNode
    )

    @Serializable
    class ProviderNode(
        val id: String?,
        val content: Content
    )

    @Serializable
    class Content(
        val edges: List<UserEdge>
    )

    @Serializable
    class UserEdge(
        val node: UserNode
    )

    @Serializable
    class UserNode(
        val viewersCount: Int? = null,
        val broadcaster: Broadcaster? = null,
        val game: Game? = null,
        val type: String? = null
    )

    @Serializable
    class Broadcaster(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
        val broadcastSettings: BroadcastSettings
    )

    @Serializable
    class BroadcastSettings(
        val title: String? = null,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val name: String? = null,
        val displayName: String? = null,
        val slug: String? = null
    )
}