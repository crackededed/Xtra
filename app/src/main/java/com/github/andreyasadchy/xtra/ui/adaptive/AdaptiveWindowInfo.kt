package com.github.andreyasadchy.xtra.ui.adaptive

import android.content.Context

enum class WidthTier {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

object AdaptiveWindowInfo {

    fun widthTierFor(widthDp: Int): WidthTier {
        return when {
            widthDp >= EXPANDED_MIN_WIDTH_DP -> WidthTier.EXPANDED
            widthDp >= MEDIUM_MIN_WIDTH_DP -> WidthTier.MEDIUM
            else -> WidthTier.COMPACT
        }
    }

    fun widthTierFor(context: Context): WidthTier {
        return widthTierFor(context.resources.configuration.screenWidthDp)
    }

    private const val MEDIUM_MIN_WIDTH_DP = 600
    private const val EXPANDED_MIN_WIDTH_DP = 840
}
