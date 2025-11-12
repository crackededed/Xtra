package com.github.andreyasadchy.xtra.model.gql.channel

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelPanelsResponse(
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
        val login: String,
        val panels: List<Panel> = emptyList(),
    )

    @Serializable
    class Panel(
        val id: String,
        val type: String,
        val title: String? = null,
        val imageURL: String? = null,
        val linkURL: String? = null,
        val description: String? = null,
        val altText: String? = null,
    )
}