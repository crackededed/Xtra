package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChannelPanelListItemBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
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
                        title.visibility = View.VISIBLE
                        title.text = item.title
                    } else {
                        title.visibility = View.GONE
                    }
                    if (item.imageUrl != null) {
                        imageLayout.visibility = View.VISIBLE
                        Glide.with(fragment)
                            .load(item.imageUrl)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(imageView)
                        if (item.linkUrl != null) {
                            imageView.setOnClickListener {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, item.linkUrl.toUri()).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                    }
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        imageLayout.visibility = View.GONE
                    }
                    if (item.description != null) {
                        description.visibility = View.VISIBLE
                        markwon.setMarkdown(description, item.description)
                    } else {
                        description.visibility = View.GONE
                    }
                }
            }
        }
    }
}