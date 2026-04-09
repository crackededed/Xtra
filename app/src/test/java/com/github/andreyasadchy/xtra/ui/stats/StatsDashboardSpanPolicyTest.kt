package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsDashboardSpanPolicyTest {

    @Test
    fun `compact tier uses single column feed`() {
        assertEquals(1, StatsDashboardSpanPolicy.spanCountFor(WidthTier.COMPACT))
        StatsCardType.entries.forEach { type ->
            assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.COMPACT, type))
        }
    }

    @Test
    fun `medium tier uses documented two column layout`() {
        assertEquals(2, StatsDashboardSpanPolicy.spanCountFor(WidthTier.MEDIUM))
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.SCREEN_TIME))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.STREAK))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.CATEGORIES))
        assertEquals(2, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.HEATMAP))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.LOYALTY))
        assertEquals(1, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.MEDIUM, StatsCardType.TOP_STREAMS))
    }

    @Test
    fun `expanded tier uses 12 span dashboard`() {
        assertEquals(12, StatsDashboardSpanPolicy.spanCountFor(WidthTier.EXPANDED))
        assertEquals(8, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.SCREEN_TIME))
        assertEquals(4, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.STREAK))
        assertEquals(8, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.CATEGORIES))
        assertEquals(12, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.HEATMAP))
        assertEquals(5, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.LOYALTY))
        assertEquals(7, StatsDashboardSpanPolicy.spanSizeFor(WidthTier.EXPANDED, StatsCardType.TOP_STREAMS))
    }
}
