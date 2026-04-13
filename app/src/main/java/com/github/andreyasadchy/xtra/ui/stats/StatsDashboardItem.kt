package com.github.andreyasadchy.xtra.ui.stats

import com.github.andreyasadchy.xtra.model.stats.CategoryWatchTime
import com.github.andreyasadchy.xtra.model.stats.HourlyWatchTime
import com.github.andreyasadchy.xtra.ui.view.DailyBarChartView

sealed interface StatsDashboardItem {
    val cardType: StatsCardType

    data class ScreenTime(
        val chartData: List<DailyBarChartView.DayData>,
        val dailyAverageText: String,
        val weekChangeText: String,
        val todayTimeText: String,
        val weekTotalText: String,
    ) : StatsDashboardItem {
        override val cardType = StatsCardType.SCREEN_TIME
    }

    data class Streak(
        val currentStreakText: String,
        val longestStreakText: String,
    ) : StatsDashboardItem {
        override val cardType = StatsCardType.STREAK
    }

    data class Categories(
        val categories: List<CategoryWatchTime>,
    ) : StatsDashboardItem {
        override val cardType = StatsCardType.CATEGORIES
    }

    data class Heatmap(
        val hourly: List<HourlyWatchTime>,
    ) : StatsDashboardItem {
        override val cardType = StatsCardType.HEATMAP
    }

    data class FavoriteChannels(
        val channels: List<FavoriteChannelRow>,
    ) : StatsDashboardItem {
        override val cardType = StatsCardType.FAVORITE_CHANNELS
    }
}
