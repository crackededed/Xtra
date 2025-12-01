package com.github.andreyasadchy.xtra.model.gql.followed

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class FollowedGamesResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val currentUser: FollowedData,
    )

    @Serializable
    class FollowedData(
        val followedGames: Games,
    )

    @Serializable
    class Games(
        val nodes: List<Game>,
    )

    @Serializable
    class Game(
        val id: String? = null,
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