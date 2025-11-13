package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.databinding.FragmentChannelPanelListItemBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.visible
import io.noties.markwon.Markwon

class ChannelPanelAdapter(
    private val fragment: Fragment,
) : PagingDataAdapter<ChannelPanel, ChannelPanelAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<ChannelPanel>() {
        override fun areItemsTheSame(oldItem: ChannelPanel, newItem: ChannelPanel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChannelPanel, newItem: ChannelPanel): Boolean =
            oldItem.description == newItem.description &&
                    oldItem.linkURL == newItem.linkURL &&
                    oldItem.title == newItem.title &&
                    oldItem.imageURL == newItem.imageURL
    }) {

    private val markwon = Markwon.create(fragment.requireContext())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentChannelPanelListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentChannelPanelListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChannelPanel?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    if (item.imageURL != null) {
                        imageView.visible()
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.imageURL)
                                crossfade(true)
                                target(imageView)
                            }.build()
                        )
                        if (item.linkURL != null) {
                            imageView.setOnClickListener {
                                val intent = Intent()
                                intent.setAction(Intent.ACTION_VIEW)
                                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                                intent.setData(Uri.parse(item.linkURL))
                                (fragment.activity as MainActivity).startActivity(intent)
                            }
                        }
                    } else {
                        imageView.gone()
                    }
                    if (item.description != null) {
                        descriptionText.visible()
                        markwon.setMarkdown(descriptionText, item.description)
                    } else {
                        descriptionText.gone()
                    }
                }
            }
        }
    }
}