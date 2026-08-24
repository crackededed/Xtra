package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.FragmentProxySettingsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.StreamProxy

class StreamProxySettingsAdapter: ListAdapter<StreamProxy, StreamProxySettingsAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<StreamProxy>() {
        override fun areItemsTheSame(oldItem: StreamProxy, newItem: StreamProxy): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StreamProxy, newItem: StreamProxy): Boolean {
            return oldItem.host == newItem.host &&
                    oldItem.position == newItem.position &&
                    oldItem.enabled == newItem.enabled
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var checkListener: ((StreamProxy) -> Unit)? = null
    var editListener: ((StreamProxy) -> Unit)? = null
    var deleteListener: ((StreamProxy) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentProxySettingsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentProxySettingsListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: StreamProxy) {
            with(binding) {
                image.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@ViewHolder)
                    }
                    false
                }
                text.text = item.host
                checkBox.isChecked = item.enabled
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    item.enabled = isChecked
                    checkListener?.invoke(item)
                }
                edit.setOnClickListener {
                    editListener?.invoke(item)
                }
                delete.setOnClickListener {
                    deleteListener?.invoke(item)
                }
            }
        }
    }
}