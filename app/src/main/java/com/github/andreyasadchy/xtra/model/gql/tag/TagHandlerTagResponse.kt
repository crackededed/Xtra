package com.github.andreyasadchy.xtra.model.gql.tag

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class TagHandlerTagResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val contentTag: ContentTag
    )

    @Serializable
    class ContentTag(
        val id: String,
        val localizedName: String,
        val scope: String,
    )
}
