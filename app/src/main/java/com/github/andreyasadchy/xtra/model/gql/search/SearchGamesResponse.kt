package com.github.andreyasadchy.xtra.model.gql.search

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class SearchGamesResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val searchFor: Search,
    )

    @Serializable
    class Search(
        val games: Games,
    )

    @Serializable
    class Games(
        val edges: List<Item>,
        val cursor: String? = null,
    )

    @Serializable
    class Item(
        val item: Game,
    )

    @Serializable
    class Game(
        val id: String? = null,
        val slug: String? = null,
        val displayName: String? = null,
        val boxArtURL: String? = null,
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