package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.model.stats.StreamWatchStats
import com.github.andreyasadchy.xtra.model.stats.StreamerLoyalty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsDataHelperTest {

    // ==================== normalizeHeatmapData Tests ====================

    @Test
    fun `normalizeHeatmapData with empty list returns 24 zeros`() {
        val result = StatsDataHelper.normalizeHeatmapData(emptyList())
        
        assertEquals(24, result.size)
        assertTrue(result.all { it == 0f })
    }

    @Test
    fun `normalizeHeatmapData with all zeros returns 24 zeros`() {
        val data = listOf(0 to 0L, 12 to 0L, 23 to 0L)
        val result = StatsDataHelper.normalizeHeatmapData(data)
        
        assertEquals(24, result.size)
        assertTrue(result.all { it == 0f })
    }

    @Test
    fun `normalizeHeatmapData normalizes max value to 1`() {
        val data = listOf(
            0 to 100L,
            12 to 200L,  // max
            23 to 50L
        )
        val result = StatsDataHelper.normalizeHeatmapData(data)
        
        assertEquals(24, result.size)
        assertEquals(0.5f, result[0], 0.001f)   // 100/200
        assertEquals(1.0f, result[12], 0.001f)  // 200/200 (max)
        assertEquals(0.25f, result[23], 0.001f) // 50/200
        assertEquals(0f, result[6], 0.001f)     // missing hour = 0
    }

    @Test
    fun `normalizeHeatmapData handles single data point`() {
        val data = listOf(15 to 1000L)
        val result = StatsDataHelper.normalizeHeatmapData(data)
        
        assertEquals(24, result.size)
        assertEquals(1.0f, result[15], 0.001f)
        assertEquals(0f, result[0], 0.001f)
    }

    // ==================== calculateBarRatios Tests ====================

    @Test
    fun `calculateBarRatios with empty list returns empty ratios`() {
        val (ratios, maxValue) = StatsDataHelper.calculateBarRatios(emptyList())
        
        assertTrue(ratios.isEmpty())
        assertEquals(6 * 3600L, maxValue) // default min
    }

    @Test
    fun `calculateBarRatios uses minMaxValue when data is smaller`() {
        val values = listOf(1000L, 2000L, 3000L)
        val minMax = 10000L
        val (ratios, maxValue) = StatsDataHelper.calculateBarRatios(values, minMax)
        
        assertEquals(3, ratios.size)
        assertEquals(minMax, maxValue)
        assertEquals(0.1f, ratios[0], 0.001f)  // 1000/10000
        assertEquals(0.2f, ratios[1], 0.001f)  // 2000/10000
        assertEquals(0.3f, ratios[2], 0.001f)  // 3000/10000
    }

    @Test
    fun `calculateBarRatios uses actual max when data is larger`() {
        val values = listOf(10000L, 20000L, 30000L)
        val minMax = 5000L
        val (ratios, maxValue) = StatsDataHelper.calculateBarRatios(values, minMax)
        
        assertEquals(3, ratios.size)
        assertEquals(30000L, maxValue) // actual max
        assertEquals(1.0f, ratios[2], 0.001f)  // 30000/30000
    }

    @Test
    fun `calculateBarRatios with all zeros returns zeros`() {
        val values = listOf(0L, 0L, 0L)
        val (ratios, _) = StatsDataHelper.calculateBarRatios(values)
        
        assertEquals(3, ratios.size)
        assertTrue(ratios.all { it == 0f })
    }

    // ==================== interpolateColor Tests ====================

    @Test
    fun `interpolateColor at fraction 0 returns start color`() {
        val start = 0xFF112233.toInt()
        val end = 0xFFAABBCC.toInt()
        
        val result = StatsDataHelper.interpolateColor(start, end, 0f)
        
        assertEquals(start, result)
    }

    @Test
    fun `interpolateColor at fraction 1 returns end color`() {
        val start = 0xFF112233.toInt()
        val end = 0xFFAABBCC.toInt()
        
        val result = StatsDataHelper.interpolateColor(start, end, 1f)
        
        assertEquals(end, result)
    }

    @Test
    fun `interpolateColor at fraction 0_5 returns midpoint`() {
        val start = 0xFF000000.toInt()
        val end = 0xFF646464.toInt() // RGB(100, 100, 100)
        
        val result = StatsDataHelper.interpolateColor(start, end, 0.5f)
        
        // Midpoint should be RGB(50, 50, 50)
        val r = (result shr 16) and 0xFF
        val g = (result shr 8) and 0xFF
        val b = result and 0xFF
        
        assertEquals(50, r)
        assertEquals(50, g)
        assertEquals(50, b)
    }

    @Test
    fun `interpolateColor clamps fraction below 0`() {
        val start = 0xFF000000.toInt()
        val end = 0xFFFFFFFF.toInt()
        
        val result = StatsDataHelper.interpolateColor(start, end, -0.5f)
        
        assertEquals(start, result)
    }

    @Test
    fun `interpolateColor clamps fraction above 1`() {
        val start = 0xFF000000.toInt()
        val end = 0xFFFFFFFF.toInt()
        
        val result = StatsDataHelper.interpolateColor(start, end, 1.5f)
        
        assertEquals(end, result)
    }

    // ==================== calculateDailyAverage Tests ====================

    @Test
    fun `calculateDailyAverage with empty list returns 0`() {
        val result = StatsDataHelper.calculateDailyAverage(emptyList())
        assertEquals(0L, result)
    }

    @Test
    fun `calculateDailyAverage calculates correct average`() {
        val values = listOf(100L, 200L, 300L)
        val result = StatsDataHelper.calculateDailyAverage(values)
        assertEquals(200L, result) // (100+200+300) / 3 = 200
    }

    @Test
    fun `calculateDailyAverage with single value returns that value`() {
        val result = StatsDataHelper.calculateDailyAverage(listOf(500L))
        assertEquals(500L, result)
    }

    // ==================== formatSecondsToHoursMinutes Tests ====================

    @Test
    fun `formatSecondsToHoursMinutes converts correctly`() {
        // 2 hours and 30 minutes = 9000 seconds
        val (hours, minutes) = StatsDataHelper.formatSecondsToHoursMinutes(9000L)
        assertEquals(2L, hours)
        assertEquals(30L, minutes)
    }

    @Test
    fun `formatSecondsToHoursMinutes with 0 returns zeros`() {
        val (hours, minutes) = StatsDataHelper.formatSecondsToHoursMinutes(0L)
        assertEquals(0L, hours)
        assertEquals(0L, minutes)
    }

    @Test
    fun `formatSecondsToHoursMinutes with only minutes`() {
        // 45 minutes = 2700 seconds
        val (hours, minutes) = StatsDataHelper.formatSecondsToHoursMinutes(2700L)
        assertEquals(0L, hours)
        assertEquals(45L, minutes)
    }

    @Test
    fun `formatSecondsToHoursMinutes ignores extra seconds`() {
        // 1 hour, 30 minutes, 45 seconds = 5445 seconds
        val (hours, minutes) = StatsDataHelper.formatSecondsToHoursMinutes(5445L)
        assertEquals(1L, hours)
        assertEquals(30L, minutes) // 45 seconds are truncated
    }

    // ==================== buildFavoriteChannels Tests ====================

    @Test
    fun `buildFavoriteChannels keeps watch time order and merges loyalty metadata`() {
        val topStreams = listOf(
            StreamWatchStats(
                channelId = "alpha",
                channelName = "Alpha",
                totalSecondsWatched = 360L,
                lastWatchedTimestamp = 1L,
            ),
            StreamWatchStats(
                channelId = "beta",
                channelName = "Beta",
                totalSecondsWatched = 180L,
                lastWatchedTimestamp = 2L,
            ),
        )
        val loyalty = listOf(
            StreamerLoyalty(
                channelId = "beta",
                channelName = "Beta",
                channelLogin = "beta_login",
                totalWatchSeconds = 180L,
                sessionCount = 2,
                distinctDaysWatched = 2,
                loyaltyScore = 55f,
            ),
            StreamerLoyalty(
                channelId = "alpha",
                channelName = "Alpha",
                channelLogin = "alpha_login",
                totalWatchSeconds = 360L,
                sessionCount = 3,
                distinctDaysWatched = 3,
                loyaltyScore = 100f,
            ),
        )

        val rows = StatsDataHelper.buildFavoriteChannels(topStreams, loyalty)

        assertEquals(2, rows.size)
        assertEquals(
            FavoriteChannelRow(
                channelId = "alpha",
                channelName = "Alpha",
                totalSecondsWatched = 360L,
                sessionCount = 3,
                loyaltyScore = 100,
                watchTimeProgress = 1f,
            ),
            rows[0],
        )
        assertEquals(
            FavoriteChannelRow(
                channelId = "beta",
                channelName = "Beta",
                totalSecondsWatched = 180L,
                sessionCount = 2,
                loyaltyScore = 55,
                watchTimeProgress = 0.5f,
            ),
            rows[1],
        )
    }

    @Test
    fun `buildFavoriteChannels fills missing loyalty metadata with defaults`() {
        val rows = StatsDataHelper.buildFavoriteChannels(
            topStreams = listOf(
                StreamWatchStats(
                    channelId = "gamma",
                    channelName = "Gamma",
                    totalSecondsWatched = 120L,
                    lastWatchedTimestamp = 3L,
                ),
            ),
            loyalty = emptyList(),
        )

        assertEquals(
            listOf(
                FavoriteChannelRow(
                    channelId = "gamma",
                    channelName = "Gamma",
                    totalSecondsWatched = 120L,
                    sessionCount = 0,
                    loyaltyScore = 0,
                    watchTimeProgress = 1f,
                ),
            ),
            rows,
        )
    }
}
