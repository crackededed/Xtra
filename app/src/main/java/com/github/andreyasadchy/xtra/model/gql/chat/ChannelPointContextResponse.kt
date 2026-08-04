package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelPointContextResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val community: Community,
    )

    @Serializable
    class Community(
        val channel: Channel,
    )

    @Serializable
    class Channel(
        val self: Self,
        val communityPointsSettings: CommunityPointsSettings? = null,
    )

    @Serializable
    class Self(
        val communityPoints: Points? = null,
    )

    @Serializable
    class Points(
        val balance: Int? = null,
        val availableClaim: Claim? = null,
    )

    @Serializable
    class Claim(
        val id: String? = null,
    )

    @Serializable
    class CommunityPointsSettings(
        val customRewards: List<CustomReward> = emptyList(),
        val automaticRewards: List<AutomaticReward> = emptyList(),
        val earning: Earning? = null,
    )

    @Serializable
    class CustomReward(
        val title: String? = null,
        val cost: Int? = null,
        val prompt: String? = null,
        val isEnabled: Boolean? = null,
        val isPaused: Boolean? = null,
        val isInStock: Boolean? = null,
    )

    @Serializable
    class AutomaticReward(
        val type: String? = null,
        val cost: Int? = null,
        val isEnabled: Boolean? = null,
        val isInStock: Boolean? = null,
    )

    @Serializable
    class Earning(
        val watchStreakPoints: List<WatchStreakPoint> = emptyList(),
    )

    @Serializable
    class WatchStreakPoint(
        val streakLength: Int? = null,
        val points: Int? = null,
    )
}
