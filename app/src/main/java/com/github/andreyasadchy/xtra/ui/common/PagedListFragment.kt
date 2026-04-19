package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class PagedListFragment : BaseNetworkFragment(), IntegrityDialog.Listener {

    fun <T : Any, VH : RecyclerView.ViewHolder> setAdapter(recyclerView: RecyclerView, adapter: PagingDataAdapter<T, VH>) {
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                adapter.unregisterAdapterDataObserver(this)
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        try {
                            if (positionStart == 0) {
                                recyclerView.scrollToPosition(0)
                            }
                        } catch (e: Exception) {

                        }
                    }
                })
            }
        })
        recyclerView.adapter = adapter
    }

    fun shouldShowButton(recyclerView: RecyclerView): Boolean {
        val offset = recyclerView.computeVerticalScrollOffset()
        if (offset < 0) {
            return false
        }
        val extent = recyclerView.computeVerticalScrollExtent()
        val range = recyclerView.computeVerticalScrollRange()
        val percentage = (100f * offset / (range - extent).toFloat())
        return percentage > 3f
    }

    fun <T : Any, VH : RecyclerView.ViewHolder> initializeAdapter(binding: CommonRecyclerViewLayoutBinding, pagingAdapter: PagingDataAdapter<T, VH>, enableSwipeRefresh: Boolean = true, enableScrollTopButton: Boolean = true) {
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        progressBar.isVisible = loadState.refresh is LoadState.Loading && pagingAdapter.itemCount == 0
                        if (enableSwipeRefresh) {
                            swipeRefresh.isRefreshing = loadState.refresh is LoadState.Loading && pagingAdapter.itemCount != 0
                        }
                        nothingHere.isVisible = loadState.refresh !is LoadState.Loading && pagingAdapter.itemCount == 0
                        if ((loadState.refresh as? LoadState.Error ?:
                            loadState.append as? LoadState.Error ?:
                            loadState.prepend as? LoadState.Error)?.error?.message == C.FAILED_INTEGRITY_CHECK
                        ) {
                            (requireActivity() as? MainActivity)?.getNewIntegrityToken("refresh", childFragmentManager)
                        }
                    }
                }
            }
            if (enableSwipeRefresh) {
                swipeRefresh.isEnabled = true
                swipeRefresh.setOnRefreshListener { pagingAdapter.refresh() }
            }
            if (enableScrollTopButton && requireContext().prefs().getBoolean(C.UI_SCROLLTOP, true)) {
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        scrollTop.isVisible = shouldShowButton(recyclerView)
                    }
                })
                scrollTop.setOnClickListener {
                    (parentFragment as? Scrollable)?.scrollToTop()
                    it.visibility = View.GONE
                }
            }
        }
    }
}