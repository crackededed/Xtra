package com.github.andreyasadchy.xtra.model.ui

data class ChannelPoints(
    val balance: Int,
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
)

enum class ChannelPointRewardInput {
    NONE,
    TEXT,
    EMOTE,
}

data class ChannelPointRedemptionResult(
    val rewardTitle: String,
    val success: Boolean,
    val message: String? = null,
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
)
