package com.github.andreyasadchy.xtra.model.chat

import java.time.Instant

class ChannelPrediction(
    val id: String?,
    val channelId: String?,
    val createdAt: Long?,
    val createdBy: PredictionTrigger?,
    val endedAt: Long?,
    val endedBy: PredictionTrigger?,
    val lockedAt: Long?,
    val lockedBy: PredictionTrigger?,
    val outcomes: List<PredictionOutcome>?,
    val predictionWindowSeconds: Int?,
    val status: Status?,
    val title: String?,
    val winningOutcomeId: String?,
) {
    class PredictionTrigger(
        val type: String?,
        val userId: String?,
        val userDisplayName: String?,
        val extensionClientId: String?,
    )

    class PredictionOutcome(
        val id: String?,
        val color: String?,
        val title: String?,
        val totalPoints: Int?,
        val totalUsers: Int?,
        val topPredictors: List<Prediction>?,
        val badge: Badge?,
    )

    class Prediction(
        val id: String?,
        val eventId: String?,
        val outcomeId: String?,
        val channelId: String?,
        val points: Int?,
        val predictedAt: Long?,
        val updatedAt: Long?,
        val userId: String?,
        val result: PredictionResult?,
        val userDisplayName: String?,
    )

    class PredictionResult(
        /**
         * The result type (e.g., "WIN", "LOSE", "REFUND")
         */
        val type: String?,
        val pointsWon: Int?,
        val isAcknowledged: Boolean,
    )

    enum class Status {
        ACTIVE,
        CANCELED,
        CANCEL_PENDING,
        LOCKED,
        RESOLVE_PENDING,
        RESOLVED,
    }
}