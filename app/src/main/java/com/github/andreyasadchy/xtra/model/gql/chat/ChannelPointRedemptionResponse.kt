package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelPointRedemptionResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val redeemCommunityPointsCustomReward: Payload? = null,
    )

    @Serializable
    class Payload(
        val error: RedemptionError? = null,
    )

    @Serializable
    class RedemptionError(
        val code: String? = null,
    )
}
