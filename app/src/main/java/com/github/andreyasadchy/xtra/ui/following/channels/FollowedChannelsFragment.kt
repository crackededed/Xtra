package com.github.andreyasadchy.xtra.ui.following.channels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FollowedChannelsFragment : PagedListFragment(), Scrollable, Sortable, FollowedChannelsSortDialog.OnFilter {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowedChannelsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<User, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = FollowedChannelsAdapter(this)
        setAdapter(binding.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = viewModel.getSortChannel("followed_channels")
                viewModel.setFilter(
                    sort = sortValues?.videoSort,
                    order = sortValues?.videoType,
                )
                viewModel.sortText.value = requireContext().getString(
                    R.string.sort_and_period,
                    requireContext().getString(
                        when (viewModel.sort) {
                            FollowedChannelsSortDialog.SORT_FOLLOWED_AT -> R.string.time_followed
                            FollowedChannelsSortDialog.SORT_ALPHABETICALLY -> R.string.alphabetically
                            FollowedChannelsSortDialog.SORT_LAST_BROADCAST -> R.string.last_broadcast
                            else -> R.string.last_broadcast
                        }
                    ),
                    requireContext().getString(
                        when (viewModel.order) {
                            FollowedChannelsSortDialog.ORDER_DESC -> R.string.descending
                            FollowedChannelsSortDialog.ORDER_ASC -> R.string.ascending
                            else -> R.string.descending
                        }
                    )
                )
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableScrollTopButton = false)
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visible()
        sortBar.root.setOnClickListener {
            FollowedChannelsSortDialog.newInstance(
                sort = viewModel.sort,
                order = viewModel.order,
                saveDefault = requireContext().prefs().getBoolean(C.SORT_DEFAULT_FOLLOWED_CHANNELS, false)
            ).show(childFragmentManager, null)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sortText.collectLatest {
                    sortBar.sortText.text = it
                }
            }
        }
    }

    override fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(sort, order)
                viewModel.sortText.value = requireContext().getString(R.string.sort_and_period, sortText, orderText)
                if (saveDefault) {
                    val sortDefaults = viewModel.getSortChannel("followed_channels")
                    if (sortDefaults != null) {
                        sortDefaults.apply {
                            videoSort = sort
                            videoType = order
                        }
                    } else {
                        SortChannel(
                            id = "followed_channels",
                            videoSort = sort,
                            videoType = order
                        )
                    }.let { viewModel.saveSortChannel(it) }
                }
                if (saveDefault != requireContext().prefs().getBoolean(C.SORT_DEFAULT_FOLLOWED_CHANNELS, false)) {
                    requireContext().prefs().edit { putBoolean(C.SORT_DEFAULT_FOLLOWED_CHANNELS, saveDefault) }
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            pagingAdapter.refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}