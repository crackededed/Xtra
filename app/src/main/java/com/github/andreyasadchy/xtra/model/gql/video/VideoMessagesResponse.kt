package com.github.andreyasadchy.xtra.model.gql.video

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class VideoMessagesResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val video: Video,
    )

    @Serializable
    class Video(
        val comments: Comments,
    )

    @Serializable
    class Comments(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Comment,
        val cursor: String? = null,
    )

    @Serializable
    class Comment(
        val id: String? = null,
        val contentOffsetSeconds: Int? = null,
        val createdAt: String? = null,
        val message: Message? = null,
        val commenter: Commenter? = null,
    )

    @Serializable
    class Message(
        val fragments: List<Fragment>? = null,
        val userBadges: List<Badge>? = null,
        val userColor: String? = null,
    )

    @Serializable
    class Fragment(
        val text: String? = null,
        val emote: Emote? = null,
    )

    @Serializable
    class Emote(
        val emoteID: String? = null,
    )

    @Serializable
    class Badge(
        val setID: String? = null,
        val version: String? = null,
    )

    @Serializable
    class Commenter(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
    )
}