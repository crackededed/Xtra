package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier

object StatsDashboardSpanPolicy {

    private const val COMPACT_LANDSCAPE_MAX_HEIGHT_DP = 500

    fun spanCountFor(widthTier: WidthTier): Int {
        return spanCountFor(widthTier, isLandscape = false, screenHeightDp = Int.MAX_VALUE)
    }

    fun spanCountFor(widthTier: WidthTier, isLandscape: Boolean, screenHeightDp: Int): Int {
        if (widthTier == WidthTier.MEDIUM && isLandscape && screenHeightDp <= COMPACT_LANDSCAPE_MAX_HEIGHT_DP) {
            return 1
        }

        return when (widthTier) {
            WidthTier.COMPACT -> 1
            WidthTier.MEDIUM -> 2
            WidthTier.EXPANDED -> 12
        }
    }

    fun spanSizeFor(widthTier: WidthTier, cardType: StatsCardType): Int {
        return spanSizeFor(widthTier, cardType, spanCountFor(widthTier))
    }

    fun spanSizeFor(widthTier: WidthTier, cardType: StatsCardType, spanCount: Int): Int {
        if (spanCount == 1) return 1

        return when (widthTier) {
            WidthTier.COMPACT -> 1

            WidthTier.MEDIUM -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 2
                StatsCardType.STREAK -> 2
                StatsCardType.CATEGORIES -> 2
                StatsCardType.HEATMAP -> 2
                StatsCardType.LOYALTY -> 1
                StatsCardType.TOP_STREAMS -> 1
            }

            WidthTier.EXPANDED -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 12
                StatsCardType.STREAK -> 12
                StatsCardType.CATEGORIES -> 12
                StatsCardType.HEATMAP -> 12
                StatsCardType.LOYALTY -> 6
                StatsCardType.TOP_STREAMS -> 6
            }
        }
    }
}
