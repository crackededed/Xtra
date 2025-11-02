package com.github.andreyasadchy.xtra.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.RecentSearch

class RecentSearchAdapter(
    private val onRecentSearchItemSelected: (String) -> Unit,
    private val onRecentSearchItemInserted: (String) -> Unit,
    private val onRecentSearchItemLongClick: (RecentSearch) -> Unit,
) : ListAdapter<RecentSearch, RecentSearchAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<RecentSearch>() {
        override fun areItemsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean = oldItem.query == newItem.query
        override fun areContentsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean = oldItem.query == newItem.query
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.fragment_recent_search_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.recentSearchText)
        private val recentSearchIcon : LinearLayout = itemView.findViewById(R.id.recentSearchSelect)
        private val recentSearchInsert : LinearLayout = itemView.findViewById(R.id.recentSearchInsert)

        fun bind(item: RecentSearch) {
            textView.text = item.query
            recentSearchIcon.setOnClickListener {
                onRecentSearchItemSelected(item.query)
            }
            recentSearchIcon.setOnLongClickListener {
                onRecentSearchItemLongClick(item)
                true
            }
            recentSearchInsert.setOnClickListener {
                onRecentSearchItemInserted(item.query)
            }
        }
    }
}