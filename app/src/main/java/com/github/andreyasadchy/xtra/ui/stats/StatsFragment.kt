package com.github.andreyasadchy.xtra.ui.stats

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
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

@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private val viewModel: StatsViewModel by viewModels()
    private var binding: FragmentStatsBinding? = null
    private lateinit var dashboardAdapter: StatsDashboardAdapter
    private lateinit var widthTier: WidthTier

    private var screenTimeCard = StatsDashboardItem.ScreenTime(
        chartData = emptyList(),
        dailyAverageText = "0 min",
        weekChangeText = "\u2191 from last week",
        todayTimeText = "0 min",
        weekTotalText = "0 min",
    )
    private var streakCard = StatsDashboardItem.Streak(
        currentStreakText = "0",
        longestStreakText = "0",
    )
    private var categoriesCard = StatsDashboardItem.Categories(emptyList())
    private var heatmapCard = StatsDashboardItem.Heatmap(emptyList())
    private var loyaltyCard = StatsDashboardItem.Loyalty(emptyList())
    private var topStreamsCard = StatsDashboardItem.TopStreams(emptyList())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStatsBinding.bind(view)
        this.binding = binding

        widthTier = AdaptiveWindowInfo.widthTierFor(requireContext())
        dashboardAdapter = StatsDashboardAdapter(widthTier)

        val spanCount = StatsDashboardSpanPolicy.spanCountFor(widthTier)
        val layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val item = dashboardAdapter.currentList.getOrNull(position) ?: return spanCount
                    return StatsDashboardSpanPolicy.spanSizeFor(widthTier, item.cardType)
                }
            }
        }

        binding.statsRecyclerView.layoutManager = layoutManager
        binding.statsRecyclerView.adapter = dashboardAdapter
        binding.statsRecyclerView.setHasFixedSize(false)
        binding.statsRecyclerView.addItemDecoration(
            DashboardSpacingItemDecoration(
                resources.getDimensionPixelSize(R.dimen.stats_dashboard_item_spacing),
            ),
        )

        renderDashboard()

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
                        topStreamsCard = StatsDashboardItem.TopStreams(streams)
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
                        loyaltyCard = StatsDashboardItem.Loyalty(loyalty)
                        renderDashboard()
                    }
                }
            }
        }
    }

    private fun renderDashboard() {
        dashboardAdapter.submitList(
            listOf(
                screenTimeCard,
                streakCard,
                categoriesCard,
                heatmapCard,
                loyaltyCard,
                topStreamsCard,
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

        val avgSeconds = if (chartData.isNotEmpty()) weekTotalSeconds / 7 else 0L
        val avgHours = avgSeconds / 3600
        val avgMinutes = (avgSeconds % 3600) / 60

        val todaySeconds = timeMap[today]?.totalSeconds ?: 0L
        val todayHours = todaySeconds / 3600
        val todayMinutes = (todaySeconds % 3600) / 60

        val weekHours = weekTotalSeconds / 3600
        val weekMinutes = (weekTotalSeconds % 3600) / 60

        return StatsDashboardItem.ScreenTime(
            chartData = chartData,
            dailyAverageText = formatTimeShort(avgHours, avgMinutes),
            weekChangeText = "\u2191 from last week",
            todayTimeText = formatTimeShort(todayHours, todayMinutes),
            weekTotalText = formatTimeShort(weekHours, weekMinutes),
        )
    }

    private fun buildStreakCard(streak: WatchStreak?): StatsDashboardItem.Streak {
        return StatsDashboardItem.Streak(
            currentStreakText = (streak?.currentStreakDays ?: 0).toString(),
            longestStreakText = (streak?.longestStreakDays ?: 0).toString(),
        )
    }

    private fun formatTimeShort(hours: Long, minutes: Long): String {
        return buildString {
            if (hours > 0) append("$hours hr ")
            if (minutes > 0 || hours == 0L) append("$minutes min")
        }.trim()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
