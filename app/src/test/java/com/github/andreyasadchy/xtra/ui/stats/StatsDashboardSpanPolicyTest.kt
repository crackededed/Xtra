package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsDashboardSpanPolicyTest {

    @Test
    fun `compact tier always uses one column`() {
        val spanCount = StatsDashboardSpanPolicy.spanCountFor(
            widthTier = WidthTier.COMPACT,
            isLandscape = false,
            screenHeightDp = 800,
        )

        assertEquals(1, spanCount)
        StatsCardType.entries.forEach { type ->
            assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.COMPACT, type, spanCount))
        }
    }

    @Test
    fun `medium portrait uses full width top cards and split lower pair`() {
        val spanCount = StatsDashboardSpanPolicy.spanCountFor(
            widthTier = WidthTier.MEDIUM,
            isLandscape = false,
            screenHeightDp = 900,
        )

        assertEquals(2, spanCount)
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.SCREEN_TIME, spanCount))
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.STREAK, spanCount))
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.CATEGORIES, spanCount))
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.HEATMAP, spanCount))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.LOYALTY, spanCount))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.TOP_STREAMS, spanCount))
    }

    @Test
    fun `medium compact landscape falls back to one column`() {
        val spanCount = StatsDashboardSpanPolicy.spanCountFor(
            widthTier = WidthTier.MEDIUM,
            isLandscape = true,
            screenHeightDp = 420,
        )

        assertEquals(1, spanCount)
        StatsCardType.entries.forEach { type ->
            assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, type, spanCount))
        }
    }

    @Test
    fun `expanded uses clean full width top cards and split lower pair`() {
        val spanCount = StatsDashboardSpanPolicy.spanCountFor(
            widthTier = WidthTier.EXPANDED,
            isLandscape = true,
            screenHeightDp = 700,
        )

        assertEquals(12, spanCount)
        assertEquals(12, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.SCREEN_TIME, spanCount))
        assertEquals(12, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.STREAK, spanCount))
        assertEquals(12, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.CATEGORIES, spanCount))
        assertEquals(12, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.HEATMAP, spanCount))
        assertEquals(6, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.LOYALTY, spanCount))
        assertEquals(6, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.TOP_STREAMS, spanCount))
    }
}
