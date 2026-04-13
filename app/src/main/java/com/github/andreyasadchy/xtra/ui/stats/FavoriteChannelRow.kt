package com.github.andreyasadchy.xtra.ui.stats

data class FavoriteChannelRow(
    val channelId: String,
    val channelName: String,
    val totalSecondsWatched: Long,
    val sessionCount: Int,
    val loyaltyScore: Int,
    val watchTimeProgress: Float,
)
