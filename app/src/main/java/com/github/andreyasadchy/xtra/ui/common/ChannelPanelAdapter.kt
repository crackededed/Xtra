package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
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
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.linkify.LinkifyPlugin

class ChannelPanelAdapter(
    private val fragment: Fragment,
    data: List<ChannelPanel>
) :  RecyclerView.Adapter<ChannelPanelAdapter.ViewHolder>() {

    private val mData: List<ChannelPanel> = data

    private val markwon = Markwon.builder(fragment.requireContext())
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .build()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentChannelPanelListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mData[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int {
        return mData.size
    }

    inner class ViewHolder(
        private val binding: FragmentChannelPanelListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChannelPanel?) {
            with(binding) {
                if (item != null) {
                    fragment.requireContext()
                    if (item.imageURL != null) {
                        imageLayout.visible()
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
                                intent.setData(item.linkURL.toUri())
                                (fragment.activity as MainActivity).startActivity(intent)
                            }
                        }
                    } else {
                        imageLayout.gone()
                    }
                    if (item.title != null) {
                        titleText.visible()
                        titleText.text = item.title
                    } else {
                        titleText.gone()
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