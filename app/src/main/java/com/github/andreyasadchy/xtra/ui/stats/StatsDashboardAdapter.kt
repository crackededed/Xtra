package com.github.andreyasadchy.xtra.ui.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemStatsCategoriesBinding
import com.github.andreyasadchy.xtra.databinding.ItemStatsHeatmapBinding
import com.github.andreyasadchy.xtra.databinding.ItemStatsLoyaltyBinding
import com.github.andreyasadchy.xtra.databinding.ItemStatsScreenTimeBinding
import com.github.andreyasadchy.xtra.databinding.ItemStatsStreakBinding
import com.github.andreyasadchy.xtra.databinding.ItemStatsTopStreamsBinding
import com.github.andreyasadchy.xtra.ui.adaptive.WidthTier
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager

class StatsDashboardAdapter(
    private val widthTier: WidthTier,
) : ListAdapter<StatsDashboardItem, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return getItem(position).cardType.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (StatsCardType.entries[viewType]) {
            StatsCardType.SCREEN_TIME -> ScreenTimeViewHolder(
                ItemStatsScreenTimeBinding.inflate(inflater, parent, false),
            )

            StatsCardType.STREAK -> StreakViewHolder(
                ItemStatsStreakBinding.inflate(inflater, parent, false),
            )

            StatsCardType.CATEGORIES -> CategoriesViewHolder(
                ItemStatsCategoriesBinding.inflate(inflater, parent, false),
            )

            StatsCardType.HEATMAP -> HeatmapViewHolder(
                ItemStatsHeatmapBinding.inflate(inflater, parent, false),
            )

            StatsCardType.LOYALTY -> LoyaltyViewHolder(
                ItemStatsLoyaltyBinding.inflate(inflater, parent, false),
                widthTier,
            )

            StatsCardType.TOP_STREAMS -> TopStreamsViewHolder(
                ItemStatsTopStreamsBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is StatsDashboardItem.ScreenTime -> (holder as ScreenTimeViewHolder).bind(item)
            is StatsDashboardItem.Streak -> (holder as StreakViewHolder).bind(item)
            is StatsDashboardItem.Categories -> (holder as CategoriesViewHolder).bind(item)
            is StatsDashboardItem.Heatmap -> (holder as HeatmapViewHolder).bind(item)
            is StatsDashboardItem.Loyalty -> (holder as LoyaltyViewHolder).bind(item)
            is StatsDashboardItem.TopStreams -> (holder as TopStreamsViewHolder).bind(item)
        }
    }

    class ScreenTimeViewHolder(
        private val binding: ItemStatsScreenTimeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StatsDashboardItem.ScreenTime) {
            binding.dailyAverageText.text = item.dailyAverageText
            binding.weekChangeText.text = item.weekChangeText
            binding.todayTimeText.text = item.todayTimeText
            binding.weekTotalText.text = item.weekTotalText
            binding.dailyBarChart.setData(item.chartData, animate = false)
        }
    }

    class StreakViewHolder(
        private val binding: ItemStatsStreakBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StatsDashboardItem.Streak) {
            binding.currentStreakText.text = item.currentStreakText
            binding.longestStreakText.text = item.longestStreakText
        }
    }

    class CategoriesViewHolder(
        private val binding: ItemStatsCategoriesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val legendAdapter = CategoryLegendAdapter().also {
            binding.categoryLegendRecyclerView.adapter = it
        }

        fun bind(item: StatsDashboardItem.Categories) {
            binding.categoryPieChart.setData(item.categories.map { (it.gameName ?: "Unknown") to it.totalSeconds })
            val slices = binding.categoryPieChart.getSlices()
            legendAdapter.submitList(slices)
            binding.categoryLegendRecyclerView.visibility =
                if (slices.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    class HeatmapViewHolder(
        private val binding: ItemStatsHeatmapBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StatsDashboardItem.Heatmap) {
            binding.hourlyHeatmap.setData(item.hourly.map { it.hourOfDay to it.totalSeconds })
        }
    }

    class LoyaltyViewHolder(
        private val binding: ItemStatsLoyaltyBinding,
        widthTier: WidthTier,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val loyaltyAdapter = StreamerLoyaltyAdapter().also {
            binding.loyaltyRecyclerView.adapter = it
        }

        init {
            val minItemWidthRes = if (widthTier == WidthTier.EXPANDED) {
                R.dimen.stats_loyalty_min_item_width_expanded
            } else {
                R.dimen.stats_loyalty_min_item_width
            }
            binding.loyaltyRecyclerView.layoutManager = GridAutofitLayoutManager(
                binding.root.context,
                binding.root.resources.getDimensionPixelSize(minItemWidthRes),
            )
        }

        fun bind(item: StatsDashboardItem.Loyalty) {
            loyaltyAdapter.submitList(item.loyalty)
            val empty = item.loyalty.isEmpty()
            binding.loyaltyRecyclerView.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
            binding.loyaltyEmptyText.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    class TopStreamsViewHolder(
        private val binding: ItemStatsTopStreamsBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val streamAdapter = StreamStatsAdapter().also {
            binding.topStreamsRecyclerView.adapter = it
        }

        init {
            binding.topStreamsRecyclerView.layoutManager = LinearLayoutManager(binding.root.context)
        }

        fun bind(item: StatsDashboardItem.TopStreams) {
            streamAdapter.submitList(item.streams)
            val empty = item.streams.isEmpty()
            binding.topStreamsRecyclerView.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
            binding.topStreamsEmptyText.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StatsDashboardItem>() {
        override fun areItemsTheSame(oldItem: StatsDashboardItem, newItem: StatsDashboardItem): Boolean {
            return oldItem.cardType == newItem.cardType
        }

        override fun areContentsTheSame(oldItem: StatsDashboardItem, newItem: StatsDashboardItem): Boolean {
            return oldItem == newItem
        }
    }
}
