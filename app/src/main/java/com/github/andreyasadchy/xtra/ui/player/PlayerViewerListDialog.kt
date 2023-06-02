package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentViewerListBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.ui.common.ExpandingBottomSheetDialogFragment
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject


@AndroidEntryPoint
class PlayerViewerListDialog @Inject constructor(private val repository: ApiRepository) : ExpandingBottomSheetDialogFragment() {

    companion object {

        private const val LOGIN = "login"

        fun newInstance(login: String, repository: ApiRepository): PlayerViewerListDialog {
            return PlayerViewerListDialog(repository).apply {
                arguments = bundleOf(LOGIN to login)
            }
        }
    }

    private var _binding: FragmentViewerListBinding? = null
    private val binding get() = _binding!!

    private val moderatorsListItems = mutableListOf<String>()
    private var moderatorsListOffset = 0
    private val vipsListItems = mutableListOf<String>()
    private var vipsListOffset = 0
    private val viewerListItems = mutableListOf<String>()
    private var viewerListOffset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentViewerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            loadViewerList()
            viewerList.observe(viewLifecycleOwner) { fullList ->
                if (fullList != null) {
                    if (fullList.broadcasters.isNotEmpty()) {
                        broadcasterText.visible()
                        broadcasterList.visible()
                        broadcasterList.adapter = Adapter(context, fullList.broadcasters)
                    } else {
                        broadcasterText.gone()
                        broadcasterList.gone()
                    }
                    if (fullList.moderators.isNotEmpty()) {
                        moderatorsText.visible()
                        moderatorsList.apply {
                            visible()
                            adapter = Adapter(context, moderatorsListItems)
                        }
                        loadItems(fullList, moderatorsList)
                    } else {
                        moderatorsText.gone()
                        moderatorsList.gone()
                    }
                    if (fullList.vips.isNotEmpty()) {
                        vipsText.visible()
                        vipsList.apply {
                            visible()
                            adapter = Adapter(context, vipsListItems)
                        }
                        if (fullList.moderators.size <= 100) {
                            loadItems(fullList, vipsList)
                        }
                    } else {
                        vipsText.gone()
                        vipsList.gone()
                    }
                    if (fullList.viewers.isNotEmpty()) {
                        viewersText.visible()
                        viewersList.apply {
                            visible()
                            adapter = Adapter(context, viewerListItems)
                        }
                        if ((fullList.moderators.size + fullList.vips.size) <= 100) {
                            loadItems(fullList, viewersList)
                        }
                    } else {
                        viewersText.gone()
                        viewersList.gone()
                    }
                    if (fullList.count != null) {
                        userCount.visible()
                        userCount.text = requireContext().getString(R.string.user_count, TwitchApiHelper.formatCount(requireContext(), fullList.count))
                    } else {
                        userCount.gone()
                    }
                    scrollView.viewTreeObserver.addOnScrollChangedListener {
                        if (!scrollView.canScrollVertically(1)) {
                            when {
                                moderatorsListOffset != fullList.moderators.size -> loadItems(fullList, moderatorsList)
                                vipsListOffset != fullList.vips.size -> loadItems(fullList, vipsList)
                                viewerListOffset != fullList.viewers.size -> loadItems(fullList, viewersList)
                            }
                        }
                    }
                }
            }
        }
    }

    private val viewerList = MutableLiveData<ChannelViewerList?>()
    private var isLoading = false

    private fun loadViewerList(): MutableLiveData<ChannelViewerList?> {
        if (!isLoading) {
            isLoading = true
            viewerList.value = null
            lifecycleScope.launch {
                try {
                    val get = repository.loadChannelViewerList(TwitchApiHelper.getGQLHeaders(requireContext()), requireArguments().getString(LOGIN))
                    viewerList.postValue(get)
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
        return viewerList
    }

    private fun loadItems(fullList: ChannelViewerList, recyclerView: RecyclerView) {
        with(binding) {
            when (recyclerView) {
                moderatorsList -> {
                    val remaining = fullList.moderators.size - moderatorsListOffset
                    val add = if (remaining > 100) { 100 } else { remaining }
                    moderatorsListItems.addAll(fullList.moderators.subList(moderatorsListOffset, moderatorsListOffset + add))
                    moderatorsListOffset += add
                    moderatorsList.adapter?.let { it.notifyItemRangeChanged(it.itemCount - add, add) }
                }
                vipsList -> {
                    val remaining = fullList.vips.size - vipsListOffset
                    val add = if (remaining > 100) { 100 } else { remaining }
                    vipsListItems.addAll(fullList.vips.subList(vipsListOffset, vipsListOffset + add))
                    vipsListOffset += add
                    vipsList.adapter?.let { it.notifyItemRangeChanged(it.itemCount - add, add) }
                }
                viewersList -> {
                    val remaining = fullList.viewers.size - viewerListOffset
                    val add = if (remaining > 100) { 100 } else { remaining }
                    viewerListItems.addAll(fullList.viewers.subList(viewerListOffset, viewerListOffset + add))
                    viewerListOffset += add
                    viewersList.adapter?.let { it.notifyItemRangeChanged(it.itemCount - add, add) }
                }
                else -> {}
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class Adapter internal constructor(context: Context?, data: List<String>) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        private val mData: List<String> = data
        private val mInflater: LayoutInflater = LayoutInflater.from(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = mInflater.inflate(R.layout.fragment_viewer_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = mData[position]
            holder.textView.text = item
        }

        override fun getItemCount(): Int {
            return mData.size
        }

        inner class ViewHolder internal constructor(itemView: View) :
            RecyclerView.ViewHolder(itemView),
            View.OnClickListener {
            var textView: TextView = itemView.findViewById(R.id.userName)
            override fun onClick(view: View) {

            }

            init {
                itemView.setOnClickListener(this)
            }
        }
    }
}
