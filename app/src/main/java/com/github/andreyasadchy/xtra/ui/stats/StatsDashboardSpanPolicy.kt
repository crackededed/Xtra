package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier

object StatsDashboardSpanPolicy {

    private const val COMPACT_LANDSCAPE_MAX_HEIGHT_DP = 500

    fun spanCountFor(widthTier: WidthTier): Int {
        return spanCountFor(widthTier, isLandscape = false, screenHeightDp = Int.MAX_VALUE)
    }

    fun spanCountFor(widthTier: WidthTier, isLandscape: Boolean, screenHeightDp: Int): Int {
        if (isLandscape && screenHeightDp <= COMPACT_LANDSCAPE_MAX_HEIGHT_DP) {
            return when (widthTier) {
                WidthTier.COMPACT -> 2
                WidthTier.MEDIUM -> 2
                WidthTier.EXPANDED -> 12
            }
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
            WidthTier.COMPACT -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 2
                StatsCardType.STREAK -> 2
                StatsCardType.CATEGORIES -> 2
                StatsCardType.HEATMAP -> 2
                StatsCardType.FAVORITE_CHANNELS -> 2
            }

            WidthTier.MEDIUM -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 2
                StatsCardType.STREAK -> 2
                StatsCardType.CATEGORIES -> 2
                StatsCardType.HEATMAP -> 2
                StatsCardType.FAVORITE_CHANNELS -> 2
            }

            WidthTier.EXPANDED -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 12
                StatsCardType.STREAK -> 12
                StatsCardType.CATEGORIES -> 12
                StatsCardType.HEATMAP -> 12
                StatsCardType.FAVORITE_CHANNELS -> 12
            }
        }
    }
}
