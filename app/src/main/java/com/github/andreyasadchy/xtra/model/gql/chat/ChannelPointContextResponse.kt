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
        val name: String? = null,
        val image: RewardImage? = null,
        val customRewards: List<CustomReward> = emptyList(),
        val automaticRewards: List<AutomaticReward> = emptyList(),
        val emoteVariants: List<EmoteVariant> = emptyList(),
        val earning: Earning? = null,
    )

    @Serializable
    class CustomReward(
        val id: String? = null,
        val title: String? = null,
        val cost: Int? = null,
        val pricingType: String? = null,
        val prompt: String? = null,
        val isUserInputRequired: Boolean? = null,
        val backgroundColor: String? = null,
        val image: RewardImage? = null,
        val defaultImage: RewardImage? = null,
        val isEnabled: Boolean? = null,
        val isPaused: Boolean? = null,
        val isInStock: Boolean? = null,
    )

    @Serializable
    class AutomaticReward(
        val id: String? = null,
        val type: String? = null,
        val cost: Int? = null,
        val defaultCost: Int? = null,
        val pricingType: String? = null,
        val backgroundColor: String? = null,
        val defaultBackgroundColor: String? = null,
        val image: RewardImage? = null,
        val defaultImage: RewardImage? = null,
        val isEnabled: Boolean? = null,
        val isInStock: Boolean? = null,
    )

    @Serializable
    class RewardImage(
        val url: String? = null,
        val url1x: String? = null,
        val url2x: String? = null,
        val url4x: String? = null,
    )

    @Serializable
    class EmoteVariant(
        val id: String? = null,
        val isUnlockable: Boolean? = null,
        val emote: VariantEmote? = null,
        val modifications: List<EmoteModification> = emptyList(),
    )

    @Serializable
    class EmoteModification(
        val id: String? = null,
        val title: String? = null,
        val emote: VariantEmote? = null,
        val modifier: Modifier? = null,
    )

    @Serializable
    class VariantEmote(
        val id: String? = null,
        val token: String? = null,
    )

    @Serializable
    class Modifier(
        val id: String? = null,
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
