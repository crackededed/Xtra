package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelRootAboutPanelResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val user: User,
    )

    @Serializable
    class User(
        val id: String,
        val description: String? = null,
        val channel: Channel? = null,
    )

    @Serializable
    class Channel(
        val id: String,
        val socialMedias: List<SocialMedia> = emptyList(),
    )

    @Serializable
    class SocialMedia(
        val id: String,
        val name: String,
        val title: String,
        val url: String
    )
}