package com.github.andreyasadchy.xtra.ui.stats

import androidx.core.view.isVisible
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemFavoriteChannelBinding

class FavoriteChannelsAdapter : ListAdapter<FavoriteChannelRow, FavoriteChannelsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1, position == itemCount - 1)
    }

    class ViewHolder(
        private val binding: ItemFavoriteChannelBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FavoriteChannelRow, rank: Int, isLast: Boolean) {
            binding.rankText.text = binding.root.context.getString(R.string.stats_rank_format, rank)
            binding.channelName.text = item.channelName
            binding.watchTimeText.text = formatDurationShort(item.totalSecondsWatched)
            binding.loyaltyBadge.text = binding.root.context.getString(R.string.stats_loyalty_badge, item.loyaltyScore)
            binding.sessionCountText.text = binding.root.context.resources.getQuantityString(
                R.plurals.stats_sessions,
                item.sessionCount,
                item.sessionCount,
            )
            binding.watchTimeProgress.max = 100
            binding.watchTimeProgress.progress = (item.watchTimeProgress * 100).toInt()
            binding.divider.isVisible = !isLast
        }

        private fun formatDurationShort(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return if (hours > 0L) {
                String.format("%dh %02dm", hours, minutes)
            } else {
                String.format("%dm", minutes)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<FavoriteChannelRow>() {
        override fun areItemsTheSame(oldItem: FavoriteChannelRow, newItem: FavoriteChannelRow): Boolean {
            return oldItem.channelId == newItem.channelId
        }

        override fun areContentsTheSame(oldItem: FavoriteChannelRow, newItem: FavoriteChannelRow): Boolean {
            return oldItem == newItem
        }
    }
}
