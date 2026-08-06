package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class WatchStreakShareResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val shareViewerMilestone: Payload? = null,
    ) {
        fun errorCode(): String? = shareViewerMilestone?.error?.code

        fun hasPayload(): Boolean = shareViewerMilestone != null
    }

    @Serializable
    class Payload(
        val error: ShareError? = null,
    )

    @Serializable
    class ShareError(
        val code: String? = null,
    )
}
