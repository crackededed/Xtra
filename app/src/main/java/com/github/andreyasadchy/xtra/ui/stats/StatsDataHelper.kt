package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.model.stats.StreamWatchStats
import com.github.andreyasadchy.xtra.model.stats.StreamerLoyalty
import kotlin.math.max

/**
 * Helper object containing pure calculation logic for stats views.
 * Extracted to enable unit testing without Android dependencies.
 */
object StatsDataHelper {

    /**
     * Normalizes hourly watch time data to values between 0.0 and 1.0.
     * 
     * @param data List of pairs where first is hour (0-23) and second is watch time in seconds
     * @return List of 24 floats (one per hour) normalized to 0.0-1.0 range
     */
    fun normalizeHeatmapData(data: List<Pair<Int, Long>>): List<Float> {
        val maxValue = data.maxOfOrNull { it.second }?.toFloat() ?: 0f
        if (maxValue == 0f) {
            return List(24) { 0f }
        }

        val dataMap = data.associate { it.first to it.second }
        return (0..23).map { hour ->
            (dataMap[hour]?.toFloat() ?: 0f) / maxValue
        }
    }

    /**
     * Calculates bar height ratios for a bar chart.
     * 
     * @param values List of values to display as bars
     * @param minMaxValue Minimum value to use as the max scale (default 6 hours)
     * @return Pair of (list of ratios 0.0-1.0, actual max value used for scale)
     */
    fun calculateBarRatios(
        values: List<Long>,
        minMaxValue: Long = 6 * 3600L
    ): Pair<List<Float>, Long> {
        val actualMax = max(values.maxOrNull() ?: 0L, minMaxValue)
        if (actualMax == 0L) {
            return Pair(values.map { 0f }, minMaxValue)
        }
        val ratios = values.map { it.toFloat() / actualMax }
        return Pair(ratios, actualMax)
    }

    /**
     * Interpolates between two colors based on a fraction.
     * 
     * @param startColor Starting color (ARGB int)
     * @param endColor Ending color (ARGB int)
     * @param fraction Value between 0.0 and 1.0
     * @return Interpolated color as ARGB int
     */
    fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        
        val startA = (startColor shr 24) and 0xFF
        val startR = (startColor shr 16) and 0xFF
        val startG = (startColor shr 8) and 0xFF
        val startB = startColor and 0xFF
        
        val endA = (endColor shr 24) and 0xFF
        val endR = (endColor shr 16) and 0xFF
        val endG = (endColor shr 8) and 0xFF
        val endB = endColor and 0xFF
        
        val a = (startA + (endA - startA) * f).toInt()
        val r = (startR + (endR - startR) * f).toInt()
        val g = (startG + (endG - startG) * f).toInt()
        val b = (startB + (endB - startB) * f).toInt()
        
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Calculates daily average from a list of daily values.
     * 
     * @param dailySeconds List of daily watch times in seconds
     * @return Average seconds per day
     */
    fun calculateDailyAverage(dailySeconds: List<Long>): Long {
        if (dailySeconds.isEmpty()) return 0L
        return dailySeconds.sum() / dailySeconds.size
    }

    /**
     * Formats seconds into hours and minutes components.
     * 
     * @param totalSeconds Total seconds to format
     * @return Pair of (hours, minutes)
     */
    fun formatSecondsToHoursMinutes(totalSeconds: Long): Pair<Long, Long> {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return Pair(hours, minutes)
    }

    fun buildFavoriteChannels(
        topStreams: List<StreamWatchStats>,
        loyalty: List<StreamerLoyalty>
    ): List<FavoriteChannelRow> {
        if (topStreams.isEmpty()) return emptyList()

        val loyaltyByKey = loyalty.associateBy { loyaltyKey(it.channelId, it.channelName, it.channelLogin) }
        val maxWatchSeconds = topStreams.maxOfOrNull { it.totalSecondsWatched } ?: 0L

        return topStreams.map { stream ->
            val loyaltyData = loyaltyByKey[loyaltyKey(stream.channelId, stream.channelName, null)]
            FavoriteChannelRow(
                channelId = stream.channelId,
                channelName = stream.channelName,
                totalSecondsWatched = stream.totalSecondsWatched,
                sessionCount = loyaltyData?.sessionCount ?: 0,
                loyaltyScore = loyaltyData?.loyaltyScore?.toInt() ?: 0,
                watchTimeProgress = if (maxWatchSeconds > 0L) {
                    stream.totalSecondsWatched.toFloat() / maxWatchSeconds.toFloat()
                } else {
                    0f
                },
            )
        }
    }

    private fun loyaltyKey(channelId: String?, channelName: String?, channelLogin: String?): String {
        return when {
            !channelId.isNullOrBlank() -> "id:$channelId"
            !channelName.isNullOrBlank() -> "name:${channelName.lowercase()}"
            !channelLogin.isNullOrBlank() -> "login:${channelLogin.lowercase()}"
            else -> ""
        }
    }
}
