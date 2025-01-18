package com.github.andreyasadchy.xtra.model.chat

class Prediction(
    val id: String?,
    val createdAt: Long?,
    val outcomes: List<PredictionOutcome>?,
    val predictionWindowSeconds: Int?,
    val status: String?,
    val title: String?,
    val winningOutcomeId: String?,
) {
    class PredictionOutcome(
        val id: String?,
        val title: String?,
        val totalPoints: Int?,
        val totalUsers: Int?,
    )
}