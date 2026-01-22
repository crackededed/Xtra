package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.Collections
import java.util.regex.Pattern

class AutoCompleteAdapter<T>(
    context: Context,
    resource: Int,
    textViewResourceId: Int,
    objects: MutableList<T?>
): ArrayAdapter<T?>(context, resource, textViewResourceId) {

    private val mLock = Any()
    private var mObjects = objects
    private var mOriginalValues: MutableList<T?>? = null
    private var mNotifyOnChange = true
    private val imageLibrary = context.prefs().getString(C.CHAT_IMAGE_LIBRARY, "0")
    private val emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val item = getItem(position)
        when (item) {
            is Emote -> {
                view.findViewById<ImageView>(R.id.image)?.let {
                    it.visibility = View.VISIBLE
                    Glide.with(context)
                        .load(
                            when (emoteQuality) {
                                "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                "3" -> item.url3x ?: item.url2x ?: item.url1x
                                "2" -> item.url2x ?: item.url1x
                                else -> item.url1x
                            }
                        )
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(it)
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
                val list = synchronized(mLock) {
                    mOriginalValues ?: mObjects.also { mOriginalValues = it }
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
            mObjects = (results?.values as? MutableList<T?>) ?: mutableListOf()
            if (results != null && results.count > 0) {
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }
    }

    override fun add(`object`: T?) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                mOriginalValues!!.add(`object`)
            } else {
                mObjects.add(`object`)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun addAll(collection: Collection<T?>) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                mOriginalValues!!.addAll(collection)
            } else {
                mObjects.addAll(collection)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun addAll(vararg items: T?) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                Collections.addAll(mOriginalValues!!, *items)
            } else {
                Collections.addAll(mObjects, *items)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun insert(`object`: T?, index: Int) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                mOriginalValues!!.add(index, `object`)
            } else {
                mObjects.add(index, `object`)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun remove(`object`: T?) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                mOriginalValues!!.remove(`object`)
            } else {
                mObjects.remove(`object`)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun clear() {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                mOriginalValues!!.clear()
            } else {
                mObjects.clear()
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun sort(comparator: Comparator<in T?>) {
        synchronized(mLock) {
            if (mOriginalValues != null) {
                Collections.sort(mOriginalValues!!, comparator)
            } else {
                Collections.sort(mObjects, comparator)
            }
        }
        if (mNotifyOnChange) notifyDataSetChanged()
    }

    override fun notifyDataSetChanged() {
        super.notifyDataSetChanged()
        mNotifyOnChange = true
    }

    override fun setNotifyOnChange(notifyOnChange: Boolean) {
        mNotifyOnChange = notifyOnChange
    }

    override fun getCount(): Int = mObjects.size

    override fun getItem(position: Int): T? = mObjects[position]
}