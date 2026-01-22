package com.github.andreyasadchy.xtra.model.gql.search

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class SearchChannelsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val searchFor: Search,
    )

    @Serializable
    class Search(
        val channels: Users,
    )

    @Serializable
    class Users(
        val edges: List<Item>,
        val cursor: String? = null,
    )

    @Serializable
    class Item(
        val item: User,
    )

    @Serializable
    class User(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
        val followers: Followers? = null,
        val stream: Stream? = null,
    )

    @Serializable
    class Followers(
        val totalCount: Int? = null,
    )

    @Serializable
    class Stream(
        val viewersCount: Int? = null,
    )
}