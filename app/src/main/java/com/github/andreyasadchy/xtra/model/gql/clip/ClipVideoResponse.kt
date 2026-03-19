package com.github.andreyasadchy.xtra.model.gql.clip

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ClipVideoResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val clip: Clip,
    )

    @Serializable
    class Clip(
        val video: Video? = null,
        val durationSeconds: Int? = null,
        val videoOffsetSeconds: Int? = null,
    )

    @Serializable
    class Video(
        val id: String? = null,
    )
}