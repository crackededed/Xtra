package com.github.andreyasadchy.xtra.model.ui

data class ChannelPoints(
    val balance: Int,
    val iconUrl: String? = null,
    val rewards: List<ChannelPointReward> = emptyList(),
    val watchStreakRewards: List<WatchStreakReward> = emptyList(),
)

data class ChannelPointReward(
    val id: String,
    val title: String,
    val cost: Int,
    val prompt: String? = null,
    val imageUrl: String? = null,
    val backgroundColor: String? = null,
    val inputType: ChannelPointRewardInput = ChannelPointRewardInput.NONE,
    val redemptionType: ChannelPointRewardRedemption = ChannelPointRewardRedemption.CUSTOM,
)

enum class ChannelPointRewardInput {
    NONE,
    TEXT,
    EMOTE,
}

enum class ChannelPointRewardRedemption {
    CUSTOM,
    RANDOM_SUB_EMOTE,
    CHOSEN_SUB_EMOTE,
    CHOSEN_MODIFIED_SUB_EMOTE,
    SUBSCRIBER_MODE_MESSAGE,
    HIGHLIGHTED_MESSAGE,
}

data class ChannelPointRedemptionResult(
    val rewardTitle: String,
    val success: Boolean,
    val message: String? = null,
    val rewardId: String? = null,
)

data class WatchStreakReward(
    val streakLength: Int?,
    val points: Int,
)

data class WatchStreak(
    val streakCount: Int,
    val nextMilestone: Int? = null,
    val rewardPoints: Int? = null,
    val pointsAwarded: Int? = null,
    val milestoneId: String? = null,
    val shareStatus: String? = null,
) {
    companion object {
        const val SHARE_STATUS_CAN_SHARE = "CAN_SHARE"
        const val SHARE_STATUS_SHARED = "SHARED"
    }
}

data class WatchStreakShareResult(
    val success: Boolean,
    val message: String? = null,
    val milestoneId: String? = null,
)
