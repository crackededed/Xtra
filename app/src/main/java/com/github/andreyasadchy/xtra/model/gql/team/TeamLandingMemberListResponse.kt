package com.github.andreyasadchy.xtra.model.gql.team

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class TeamLandingMemberListResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val team: Team? = null
    )

    @Serializable
    class Team(
        val id: String,
        val liveMembers: LiveMembers? = null,
        val members: Members? = null
    )

    @Serializable
    class LiveMembers(
        val edges: List<UserEdge>,
        val pageInfo: PageInfo?
    )

    @Serializable
    class Members(
        val edges: List<UserEdge>,
        val pageInfo: PageInfo
    )

    @Serializable
    class PageInfo(
        val hasNextPage: Boolean
    )

    @Serializable
    class UserEdge(
        val node: User? = null,
        val cursor: String? = null
    )

    @Serializable
    class User(
        val id: String,
        val displayName: String,
        val login: String,
        val profileImageURL: String,
        val stream: Stream? = null
    )

    @Serializable
    class Stream(
        val id: String,
        val viewersCount: Int
    )
}