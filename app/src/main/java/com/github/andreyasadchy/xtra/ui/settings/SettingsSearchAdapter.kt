package com.github.andreyasadchy.xtra.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.SettingsSearchListItemBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem

class SettingsSearchAdapter(
    private val fragment: Fragment,
) : ListAdapter<SettingsSearchItem, SettingsSearchAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<SettingsSearchItem>() {
        override fun areItemsTheSame(oldItem: SettingsSearchItem, newItem: SettingsSearchItem): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: SettingsSearchItem, newItem: SettingsSearchItem): Boolean {
            return true
        }
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SettingsSearchListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: SettingsSearchListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsSearchItem?) {
            with(binding) {
                if (item != null) {
                    root.setOnClickListener {
                        (fragment.requireActivity() as? SettingsActivity)?.searchItem = item.key
                        fragment.findNavController().navigate(item.navDirections)
                    }
                    if (item.location != null) {
                        preferenceLocation.visibility = View.VISIBLE
                        preferenceLocation.text = item.location
                    } else {
                        preferenceLocation.visibility = View.GONE
                    }
                    if (item.title != null) {
                        preferenceTitle.visibility = View.VISIBLE
                        preferenceTitle.text = item.title
                    } else {
                        preferenceTitle.visibility = View.GONE
                    }
                    if (item.summary != null) {
                        preferenceSummary.visibility = View.VISIBLE
                        preferenceSummary.text = item.summary
                    } else {
                        preferenceSummary.visibility = View.GONE
                    }
                    if (item.value != null) {
                        preferenceValue.visibility = View.VISIBLE
                        preferenceValue.text = item.value
                    } else {
                        preferenceValue.visibility = View.GONE
                    }
                }
            }
        }
    }
}