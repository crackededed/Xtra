package com.github.andreyasadchy.xtra.model.ui

data class ChannelPoints(
    val balance: Int,
    val rewards: List<ChannelPointReward> = emptyList(),
    val watchStreakRewards: List<WatchStreakReward> = emptyList(),
)

data class ChannelPointReward(
    val title: String,
    val cost: Int,
    val prompt: String? = null,
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
