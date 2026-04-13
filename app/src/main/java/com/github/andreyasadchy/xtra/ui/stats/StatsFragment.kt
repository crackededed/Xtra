package com.github.andreyasadchy.xtra.ui.stats

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStatsBinding
import com.github.andreyasadchy.xtra.model.stats.CategoryWatchTime
import com.github.andreyasadchy.xtra.model.stats.HourlyWatchTime
import com.github.andreyasadchy.xtra.model.stats.ScreenTime
import com.github.andreyasadchy.xtra.model.stats.StreamWatchStats
import com.github.andreyasadchy.xtra.model.stats.StreamerLoyalty
import com.github.andreyasadchy.xtra.model.stats.WatchStreak
import com.github.andreyasadchy.xtra.ui.adaptive.AdaptiveWindowInfo
import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier
import com.github.andreyasadchy.xtra.ui.view.DailyBarChartView
import com.github.andreyasadchy.xtra.ui.view.DashboardSpacingItemDecoration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private val viewModel: StatsViewModel by viewModels()
    private var binding: FragmentStatsBinding? = null
    private lateinit var dashboardAdapter: StatsDashboardAdapter
    private lateinit var widthTier: WidthTier

    private var screenTimeCard = StatsDashboardItem.ScreenTime(
        chartData = emptyList(),
        dailyAverageText = "0m",
        weekChangeText = "",
        todayTimeText = "0m",
        weekTotalText = "0m",
    )
    private var streakCard = StatsDashboardItem.Streak(
        currentStreakText = "0",
        longestStreakText = "0",
    )
    private var categoriesCard = StatsDashboardItem.Categories(emptyList())
    private var heatmapCard = StatsDashboardItem.Heatmap(emptyList())
    private var favoriteChannelsCard = StatsDashboardItem.FavoriteChannels(emptyList())
    private var topStreams: List<StreamWatchStats> = emptyList()
    private var streamerLoyalty: List<StreamerLoyalty> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStatsBinding.bind(view)
        this.binding = binding

        initializeDefaultCards()
        configureDashboard(binding)
        renderDashboard()
        viewModel.refresh()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screenTime.collectLatest { screenTimes ->
                        screenTimeCard = buildScreenTimeCard(screenTimes)
                        renderDashboard()
                    }
                }
                launch {
                    viewModel.topStreams.collectLatest { streams ->
                        topStreams = streams
                        favoriteChannelsCard = buildFavoriteChannelsCard()
                        renderDashboard()
                    }
                }
                launch {
                    viewModel.watchStreak.collectLatest { streak ->
                        streakCard = buildStreakCard(streak)
                        renderDashboard()
                    }
                }
                launch {
                    viewModel.categoryBreakdown.collectLatest { categories ->
                        categoriesCard = StatsDashboardItem.Categories(categories)
                        renderDashboard()
                    }
                }
                launch {
                    viewModel.hourlyBreakdown.collectLatest { hourly ->
                        heatmapCard = StatsDashboardItem.Heatmap(hourly)
                        renderDashboard()
                    }
                }
                launch {
                    viewModel.streamerLoyalty.collectLatest { loyalty ->
                        streamerLoyalty = loyalty
                        favoriteChannelsCard = buildFavoriteChannelsCard()
                        renderDashboard()
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding?.let { binding ->
            configureDashboard(binding)
            renderDashboard()
        }
    }

    private fun renderDashboard() {
        dashboardAdapter.submitList(
            listOf(
                screenTimeCard,
                streakCard,
                categoriesCard,
                heatmapCard,
                favoriteChannelsCard,
            ),
        )
    }

    private fun configureDashboard(binding: FragmentStatsBinding) {
        widthTier = AdaptiveWindowInfo.widthTierFor(requireContext())
        dashboardAdapter = StatsDashboardAdapter()

        val configuration = resources.configuration
        val spanCount = StatsDashboardSpanPolicy.spanCountFor(
            widthTier = widthTier,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            screenHeightDp = configuration.screenHeightDp,
        )

        binding.statsRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = dashboardAdapter.currentList.getOrNull(position) ?: return spanCount
                    return StatsDashboardSpanPolicy.spanSizeFor(widthTier, item.cardType, spanCount)
                }
            }
        }
        binding.statsRecyclerView.adapter = dashboardAdapter
        binding.statsRecyclerView.setHasFixedSize(false)
        replaceDashboardSpacingDecoration(binding.statsRecyclerView)
    }

    private fun replaceDashboardSpacingDecoration(recyclerView: RecyclerView) {
        while (recyclerView.itemDecorationCount > 0) {
            recyclerView.removeItemDecorationAt(0)
        }
        recyclerView.addItemDecoration(
            DashboardSpacingItemDecoration(
                resources.getDimensionPixelSize(R.dimen.stats_dashboard_item_spacing),
            ),
        )
    }

    private fun initializeDefaultCards() {
        screenTimeCard = StatsDashboardItem.ScreenTime(
            chartData = emptyList(),
            dailyAverageText = "0m",
            weekChangeText = getString(R.string.stats_week_trend_none),
            todayTimeText = "0m",
            weekTotalText = "0m",
        )
        streakCard = StatsDashboardItem.Streak("0", "0")
        categoriesCard = StatsDashboardItem.Categories(emptyList())
        heatmapCard = StatsDashboardItem.Heatmap(emptyList())
        favoriteChannelsCard = StatsDashboardItem.FavoriteChannels(emptyList())
        topStreams = emptyList()
        streamerLoyalty = emptyList()
    }

    private fun buildFavoriteChannelsCard(): StatsDashboardItem.FavoriteChannels {
        return StatsDashboardItem.FavoriteChannels(
            channels = StatsDataHelper.buildFavoriteChannels(
                topStreams = topStreams,
                loyalty = streamerLoyalty,
            ),
        )
    }

    private fun buildScreenTimeCard(screenTimes: List<ScreenTime>): StatsDashboardItem.ScreenTime {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(Date())
        val timeMap = screenTimes.associateBy { it.date }
        val calendar = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val chartData = mutableListOf<DailyBarChartView.DayData>()

        var weekTotalSeconds = 0L
        calendar.add(Calendar.DAY_OF_YEAR, -6)

        repeat(7) {
            val dateStr = sdf.format(calendar.time)
            val seconds = timeMap[dateStr]?.totalSeconds ?: 0L
            weekTotalSeconds += seconds
            val label = if (dateStr == today) "Today" else dayFormat.format(calendar.time)
            chartData.add(DailyBarChartView.DayData(label, seconds))
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        var previousWeekTotalSeconds = 0L
        calendar.time = Date()
        calendar.add(Calendar.DAY_OF_YEAR, -13)

        repeat(7) {
            val dateStr = sdf.format(calendar.time)
            previousWeekTotalSeconds += timeMap[dateStr]?.totalSeconds ?: 0L
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val avgSeconds = if (chartData.isNotEmpty()) weekTotalSeconds / 7 else 0L
        val todaySeconds = timeMap[today]?.totalSeconds ?: 0L

        return StatsDashboardItem.ScreenTime(
            chartData = chartData,
            dailyAverageText = formatDurationShort(avgSeconds),
            weekChangeText = buildWeekTrendText(
                currentWeekSeconds = weekTotalSeconds,
                previousWeekSeconds = previousWeekTotalSeconds,
            ),
            todayTimeText = formatDurationShort(todaySeconds),
            weekTotalText = formatDurationShort(weekTotalSeconds),
        )
    }

    private fun buildStreakCard(streak: WatchStreak?): StatsDashboardItem.Streak {
        return StatsDashboardItem.Streak(
            currentStreakText = (streak?.currentStreakDays ?: 0).toString(),
            longestStreakText = (streak?.longestStreakDays ?: 0).toString(),
        )
    }

    private fun buildWeekTrendText(currentWeekSeconds: Long, previousWeekSeconds: Long): String {
        return when {
            currentWeekSeconds == 0L && previousWeekSeconds == 0L ->
                getString(R.string.stats_week_trend_none)

            previousWeekSeconds == 0L ->
                getString(R.string.stats_week_trend_new)

            currentWeekSeconds == previousWeekSeconds ->
                getString(R.string.stats_week_trend_flat)

            currentWeekSeconds > previousWeekSeconds -> {
                val percentage = (
                    (currentWeekSeconds - previousWeekSeconds).toDouble() /
                        previousWeekSeconds * 100
                    ).roundToInt()
                getString(R.string.stats_week_trend_up, percentage)
            }

            else -> {
                val percentage = (
                    (previousWeekSeconds - currentWeekSeconds).toDouble() /
                        previousWeekSeconds * 100
                    ).roundToInt()
                getString(R.string.stats_week_trend_down, percentage)
            }
        }
    }

    private fun formatDurationShort(totalSeconds: Long): String {
        val safeSeconds = abs(totalSeconds)
        val hours = safeSeconds / 3600
        val minutes = (safeSeconds % 3600) / 60

        return buildString {
            if (hours > 0) append("${hours}h")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0 || hours == 0L) append("${minutes}m")
        }.trim()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
