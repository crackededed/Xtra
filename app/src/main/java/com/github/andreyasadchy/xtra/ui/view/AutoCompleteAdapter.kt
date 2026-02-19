package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.regex.Pattern

class AutoCompleteAdapter<T>(
    context: Context,
    resource: Int,
    textViewResourceId: Int,
    private val originalValues: MutableList<T?>
): ArrayAdapter<T?>(context, resource, textViewResourceId) {

    private var objects = originalValues
    private val imageLibrary = context.prefs().getString(C.CHAT_IMAGE_LIBRARY, "0")
    private val emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val item = getItem(position)
        when (item) {
            is Emote -> {
                view.findViewById<ImageView>(R.id.image)?.let {
                    it.visibility = View.VISIBLE
                    if (imageLibrary == "0" || (imageLibrary == "1" && !item.format.equals("webp", true))) {
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context).apply {
                                data(
                                    when (emoteQuality) {
                                        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                        "3" -> item.url3x ?: item.url2x ?: item.url1x
                                        "2" -> item.url2x ?: item.url1x
                                        else -> item.url1x
                                    }
                                )
                                if (item.thirdParty) {
                                    httpHeaders(NetworkHeaders.Builder().apply {
                                        add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                                    }.build())
                                }
                                crossfade(true)
                                target(it)
                            }.build()
                        )
                    } else {
                        Glide.with(context)
                            .load(
                                when (emoteQuality) {
                                    "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                    "3" -> item.url3x ?: item.url2x ?: item.url1x
                                    "2" -> item.url2x ?: item.url1x
                                    else -> item.url1x
                                }.let {
                                    if (item.thirdParty) {
                                        GlideUrl(it) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
                                    } else it
                                }
                            )
                            .diskCacheStrategy(DiskCacheStrategy.DATA)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(it)
                    }
                }
                view.findViewById<TextView>(R.id.name)?.text = item.name
            }
            is Chatter -> view.findViewById<TextView>(R.id.name)?.text = item.name
        }
        return view
    }

    override fun getFilter(): Filter = filter

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults? {
            return if (constraint.isNullOrBlank()) {
                FilterResults()
            } else {
                val list = synchronized(originalValues) {
                    originalValues
                }
                val regex = constraint.map {
                    "${Pattern.quote(it.lowercase())}\\S*?"
                }.joinToString("").toRegex()
                val results = list.filter {
                    regex.matches(it.toString().lowercase())
                }
                FilterResults().apply {
                    values = results
                    count = results.size
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            objects = (results?.values as? MutableList<T?>) ?: mutableListOf()
            if (results != null && results.count > 0) {
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }
    }

    override fun getCount(): Int = objects.size

    override fun getItem(position: Int): T? = objects[position]
}