package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.FragmentSearchChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.visible

class SearchTagsAdapter(
    private val selectTag: (Tag) -> Unit,
) : PagingDataAdapter<Tag, SearchTagsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Tag>() {
        override fun areItemsTheSame(oldItem: Tag, newItem: Tag): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: Tag, newItem: Tag): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentSearchChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentSearchChannelsListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Tag?) {
            with(binding) {
                if (item != null) {
                    if (item.name != null) {
                        userName.visible()
                        userName.text = item.name
                    } else {
                        userName.gone()
                    }
                    root.setOnClickListener {
                        selectTag(item)
                    }
                }
            }
        }
    }
}