package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier

object StatsDashboardSpanPolicy {

    fun spanCountFor(widthTier: WidthTier): Int {
        return when (widthTier) {
            WidthTier.COMPACT -> 1
            WidthTier.MEDIUM -> 2
            WidthTier.EXPANDED -> 12
        }
    }

    fun spanSizeFor(widthTier: WidthTier, cardType: StatsCardType): Int {
        return when (widthTier) {
            WidthTier.COMPACT -> 1
            WidthTier.MEDIUM -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 2
                StatsCardType.STREAK -> 1
                StatsCardType.CATEGORIES -> 1
                StatsCardType.HEATMAP -> 2
                StatsCardType.LOYALTY -> 1
                StatsCardType.TOP_STREAMS -> 1
            }

            WidthTier.EXPANDED -> when (cardType) {
                StatsCardType.SCREEN_TIME -> 8
                StatsCardType.STREAK -> 4
                StatsCardType.CATEGORIES -> 8
                StatsCardType.HEATMAP -> 12
                StatsCardType.LOYALTY -> 5
                StatsCardType.TOP_STREAMS -> 7
            }
        }
    }
}
