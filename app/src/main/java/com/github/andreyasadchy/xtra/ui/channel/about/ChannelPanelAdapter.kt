package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChannelPanelListItemBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.linkify.LinkifyPlugin

class ChannelPanelAdapter(
    private val fragment: Fragment,
) : ListAdapter<ChannelPanel, ChannelPanelAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<ChannelPanel>() {
        override fun areItemsTheSame(oldItem: ChannelPanel, newItem: ChannelPanel): Boolean {
            return oldItem.title == newItem.title &&
                    oldItem.imageUrl == newItem.imageUrl &&
                    oldItem.description == newItem.description
        }

        override fun areContentsTheSame(oldItem: ChannelPanel, newItem: ChannelPanel): Boolean {
            return true
        }
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentChannelPanelListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private val markwon = Markwon.builder(fragment.requireContext())
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .build()

    inner class ViewHolder(
        private val binding: FragmentChannelPanelListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChannelPanel?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    if (item.title != null) {
                        title.visible()
                        title.text = item.title
                    } else {
                        title.gone()
                    }
                    if (item.imageUrl != null) {
                        imageLayout.visible()
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context).apply {
                                data(item.imageUrl)
                                crossfade(true)
                                target(imageView)
                            }.build()
                        )
                        if (item.linkUrl != null) {
                            imageView.setOnClickListener {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, item.linkUrl.toUri()).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                    }
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    context.toast(R.string.no_browser_found)
                                }
                            }
                        }
                    } else {
                        imageLayout.gone()
                    }
                    if (item.description != null) {
                        description.visible()
                        markwon.setMarkdown(description, item.description)
                    } else {
                        description.gone()
                    }
                }
            }
        }
    }
}