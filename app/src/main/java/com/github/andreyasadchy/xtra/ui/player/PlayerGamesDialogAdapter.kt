package com.github.andreyasadchy.xtra.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class PlayerGamesDialogAdapter(
    private val fragment: Fragment,
) : ListAdapter<Game, PlayerGamesDialogAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.vodPosition == newItem.vodPosition

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentGamesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentGamesListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Game?) {
            with(binding) {
                val context = fragment.requireContext()
                root.setOnClickListener {
                    item?.vodPosition?.let { position ->
                        (fragment.parentFragment as? PlayerFragment)?.seek(position.toLong())
                    }
                    (fragment as? PlayerGamesDialog)?.dismiss()
                }
                if (item?.boxArt != null) {
                    gameImage.visibility = View.VISIBLE
                    Glide.with(fragment)
                        .load(item.boxArt)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(gameImage)
                } else {
                    gameImage.visibility = View.GONE
                }
                if (item?.gameName != null) {
                    gameName.visibility = View.VISIBLE
                    gameName.text = item.gameName
                } else {
                    gameName.visibility = View.GONE
                }
                val position = item?.vodPosition?.div(1000)?.toString()?.let { TwitchApiHelper.getDurationFromSeconds(context, it, true) }
                if (!position.isNullOrBlank()) {
                    viewers.visibility = View.VISIBLE
                    viewers.text = context.getString(R.string.position, position)
                } else {
                    viewers.visibility = View.GONE
                }
                val duration = item?.vodDuration?.div(1000)?.toString()?.let { TwitchApiHelper.getDurationFromSeconds(context, it, true) }
                if (!duration.isNullOrBlank()) {
                    broadcastersCount.visibility = View.VISIBLE
                    broadcastersCount.text = context.getString(R.string.duration, duration)
                } else {
                    broadcastersCount.visibility = View.GONE
                }
            }
        }
    }
}