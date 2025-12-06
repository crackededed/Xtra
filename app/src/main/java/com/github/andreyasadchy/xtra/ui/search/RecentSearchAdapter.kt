package com.github.andreyasadchy.xtra.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.FragmentRecentSearchListItemBinding
import com.github.andreyasadchy.xtra.model.ui.RecentSearch

class RecentSearchAdapter(
    private val select: (RecentSearch) -> Unit,
    private val delete: (RecentSearch) -> Unit,
) : ListAdapter<RecentSearch, RecentSearchAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<RecentSearch>() {
        override fun areItemsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean {
            return oldItem.query == newItem.query
        }

        override fun areContentsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean {
            return true
        }
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentRecentSearchListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentRecentSearchListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentSearch?) {
            with(binding) {
                if (item != null) {
                    root.setOnClickListener {
                        select(item)
                    }
                    text.text = item.query
                    delete.setOnClickListener {
                        delete(item)
                    }
                }
            }
        }
    }
}