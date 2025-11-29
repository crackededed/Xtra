package com.github.andreyasadchy.xtra.model.gql.team

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class TeamsLandingBodyResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val team: Team
    )

    @Serializable
    class Team(
        val id: String? = null,
        val backgroundImageURL: String? = null,
        val bannerURL: String? = null,
        val description: String? = null,
        val displayName: String,
        val logoURL: String? = null,
        val owner: Owner? = null
    )

    @Serializable
    class Owner(
        val id: String,
        val login: String
    )
}