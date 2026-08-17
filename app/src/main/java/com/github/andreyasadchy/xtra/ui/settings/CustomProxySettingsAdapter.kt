package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentCustomProxySettingsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.CustomProxy

class CustomProxySettingsAdapter(
    private val fragment: Fragment,
) : ListAdapter<CustomProxy, CustomProxySettingsAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<CustomProxy>() {
        override fun areItemsTheSame(oldItem: CustomProxy, newItem: CustomProxy): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CustomProxy, newItem: CustomProxy): Boolean {
            return oldItem.url == newItem.url &&
                    oldItem.position == newItem.position &&
                    oldItem.enabled == newItem.enabled
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var checkListener: ((CustomProxy) -> Unit)? = null
    var editListener: ((CustomProxy) -> Unit)? = null
    var deleteListener: ((CustomProxy) -> Unit)? = null
    var statusMap: Map<String, Boolean>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentCustomProxySettingsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateStatus(binding: FragmentCustomProxySettingsListItemBinding, context: Context, item: CustomProxy) {
        with(binding) {
            if (!item.url.isNullOrBlank()) {
                status.visibility = View.VISIBLE
                val online = statusMap?.get(item.url)
                if (online != null) {
                    if (online) {
                        status.text = context.getString(R.string.online)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            status.setTextColor(context.resources.getColor(R.color.online, context.theme))
                        } else {
                            @Suppress("DEPRECATION")
                            status.setTextColor(context.resources.getColor(R.color.online))
                        }
                    } else {
                        status.text = context.getString(R.string.offline)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            status.setTextColor(context.resources.getColor(R.color.offline, context.theme))
                        } else {
                            @Suppress("DEPRECATION")
                            status.setTextColor(context.resources.getColor(R.color.offline))
                        }
                    }
                } else {
                    status.text = context.getString(R.string.loading)
                    val id = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).use {
                        it.getResourceId(0, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        status.setTextColor(context.resources.getColor(id, context.theme))
                    } else {
                        @Suppress("DEPRECATION")
                        status.setTextColor(context.resources.getColor(id))
                    }
                }
            } else {
                status.visibility = View.GONE
            }
        }
    }

    inner class ViewHolder(
        private val binding: FragmentCustomProxySettingsListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: CustomProxy) {
            with(binding) {
                val context = fragment.requireContext()
                image.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@ViewHolder)
                    }
                    false
                }
                text.text = item.url?.takeIf { it.isNotBlank() }?.let {
                    it.toUri().host ?: "https://$it".toUri().host
                }
                updateStatus(binding, context, item)
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