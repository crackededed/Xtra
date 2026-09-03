package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentProxySettingsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.VideoSwap

class VideoSwapSettingsAdapter(
    private val fragment: Fragment,
): ListAdapter<VideoSwap, VideoSwapSettingsAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<VideoSwap>() {
        override fun areItemsTheSame(oldItem: VideoSwap, newItem: VideoSwap): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoSwap, newItem: VideoSwap): Boolean {
            return oldItem.platform == newItem.platform &&
                    oldItem.playerType == newItem.playerType &&
                    oldItem.position == newItem.position &&
                    oldItem.enabled == newItem.enabled
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var checkListener: ((VideoSwap) -> Unit)? = null
    var editListener: ((VideoSwap) -> Unit)? = null
    var deleteListener: ((VideoSwap) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentProxySettingsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentProxySettingsListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: VideoSwap) {
            with(binding) {
                val context = fragment.requireContext()
                val string = buildString {
                    if (!item.platform.isNullOrBlank()) {
                        append(item.platform)
                    }
                    if (!item.playerType.isNullOrBlank()) {
                        if (isNotBlank()) {
                            append(", ")
                        }
                        append(item.playerType)
                    }
                }
                if (item.position == -1) {
                    image.visibility = View.INVISIBLE
                    text.text = context.getString(R.string.default_values)
                    status.visibility = View.VISIBLE
                    status.text = string
                    checkBox.visibility = View.GONE
                    delete.visibility = View.GONE
                } else {
                    image.visibility = View.VISIBLE
                    image.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper?.startDrag(this@ViewHolder)
                        }
                        false
                    }
                    text.text = string
                    status.visibility = View.GONE
                    checkBox.visibility = View.VISIBLE
                    checkBox.isChecked = item.enabled
                    checkBox.setOnCheckedChangeListener { _, isChecked ->
                        item.enabled = isChecked
                        checkListener?.invoke(item)
                    }
                    delete.visibility = View.VISIBLE
                    delete.setOnClickListener {
                        deleteListener?.invoke(item)
                    }
                }
                edit.setOnClickListener {
                    editListener?.invoke(item)
                }
            }
        }
    }
}