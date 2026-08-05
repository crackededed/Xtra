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
        val unlockRandomSubscriberEmote: Payload? = null,
        val unlockChosenSubscriberEmote: Payload? = null,
        val unlockChosenModifiedSubscriberEmote: Payload? = null,
        val sendChatMessageThroughSubscriberMode: Payload? = null,
        val sendHighlightedChatMessage: Payload? = null,
    ) {
        fun errorCode(): String? {
            return listOf(
                redeemCommunityPointsCustomReward,
                unlockRandomSubscriberEmote,
                unlockChosenSubscriberEmote,
                unlockChosenModifiedSubscriberEmote,
                sendChatMessageThroughSubscriberMode,
                sendHighlightedChatMessage,
            ).firstNotNullOfOrNull { it?.error?.code }
        }

        fun hasPayload(): Boolean {
            return redeemCommunityPointsCustomReward != null ||
                    unlockRandomSubscriberEmote != null ||
                    unlockChosenSubscriberEmote != null ||
                    unlockChosenModifiedSubscriberEmote != null ||
                    sendChatMessageThroughSubscriberMode != null ||
                    sendHighlightedChatMessage != null
        }
    }

    @Serializable
    class Payload(
        val error: RedemptionError? = null,
    )

    @Serializable
    class RedemptionError(
        val code: String? = null,
    )
}
