 package com.github.andreyasadchy.xtra.ui.stats
 
 import android.view.LayoutInflater
 import android.view.ViewGroup
 import androidx.recyclerview.widget.DiffUtil
 import androidx.recyclerview.widget.ListAdapter
 import androidx.recyclerview.widget.RecyclerView
 import com.github.andreyasadchy.xtra.databinding.ItemStreamerLoyaltyBinding
 import com.github.andreyasadchy.xtra.model.stats.StreamerLoyalty
 
 class StreamerLoyaltyAdapter : ListAdapter<StreamerLoyalty, StreamerLoyaltyAdapter.ViewHolder>(DIFF_CALLBACK) {
 
     override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
         val binding = ItemStreamerLoyaltyBinding.inflate(
             LayoutInflater.from(parent.context), parent, false
         )
         return ViewHolder(binding)
     }
 
     override fun onBindViewHolder(holder: ViewHolder, position: Int) {
         holder.bind(getItem(position), position + 1)
     }
 
     class ViewHolder(private val binding: ItemStreamerLoyaltyBinding) : RecyclerView.ViewHolder(binding.root) {
         fun bind(loyalty: StreamerLoyalty, rank: Int) {
             binding.rankText.text = "#$rank"
             binding.channelName.text = loyalty.channelName ?: loyalty.channelLogin ?: "Unknown"
             
             val hours = loyalty.totalWatchSeconds / 3600
             val minutes = (loyalty.totalWatchSeconds % 3600) / 60
             binding.watchTime.text = buildString {
                 if (hours > 0) append("${hours}h ")
                 append("${minutes}m watched")
             }
             
             binding.loyaltyScore.text = "${loyalty.loyaltyScore.toInt()}"
             binding.sessionCount.text = "${loyalty.sessionCount} sessions"
             binding.loyaltyProgress.progress = loyalty.loyaltyScore.toInt()
         }
     }
 
     companion object {
         private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StreamerLoyalty>() {
             override fun areItemsTheSame(oldItem: StreamerLoyalty, newItem: StreamerLoyalty) =
                 oldItem.channelId == newItem.channelId
             override fun areContentsTheSame(oldItem: StreamerLoyalty, newItem: StreamerLoyalty) =
                 oldItem == newItem
         }
     }
 }
