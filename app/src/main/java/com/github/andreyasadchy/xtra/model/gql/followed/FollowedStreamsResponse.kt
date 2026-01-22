package com.github.andreyasadchy.xtra.model.gql.followed

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class FollowedStreamsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val currentUser: FollowedData,
    )

    @Serializable
    class FollowedData(
        val followedLiveUsers: Users,
    )

    @Serializable
    class Users(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: User,
        val cursor: String? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
        val stream: Stream? = null,
    )

    @Serializable
    class Stream(
        val id: String? = null,
        val game: Game? = null,
        val title: String? = null,
        val viewersCount: Int? = null,
        val createdAt: String? = null,
        val previewImageURL: String? = null,
        val freeformTags: List<Tag>? = null,
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