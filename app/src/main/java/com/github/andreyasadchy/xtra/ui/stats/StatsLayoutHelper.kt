package com.github.andreyasadchy.xtra.ui.stats

import kotlin.math.max

object StatsLayoutHelper {

    fun calculateSpanCount(availableWidthPx: Int, minItemWidthPx: Int): Int {
        if (availableWidthPx <= 0 || minItemWidthPx <= 0) {
            return 1
        }
        return max(1, availableWidthPx / minItemWidthPx)
    }
}
