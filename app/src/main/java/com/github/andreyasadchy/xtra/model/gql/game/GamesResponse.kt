package com.github.andreyasadchy.xtra.model.gql.game

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class GamesResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val directoriesWithTags: Games,
    )

    @Serializable
    class Games(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Game,
        val cursor: String? = null,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val slug: String? = null,
        val displayName: String? = null,
        val avatarURL: String? = null,
        val viewersCount: Int? = null,
        val followersCount: Int? = null,
        val tags: List<Tag>? = null,
    )

    @Serializable
    class Tag(
        val id: String? = null,
        val localizedName: String? = null,
    )
}