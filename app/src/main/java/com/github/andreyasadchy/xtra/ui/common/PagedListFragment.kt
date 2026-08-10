package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class PagedListFragment : BaseNetworkFragment(), IntegrityDialog.Listener {

    private var pageErrorSnackbar: Snackbar? = null
    private var pageError: LoadState.Error? = null
    private var pageErrorState: PagedListErrorState? = null

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
            setupPagingControls(binding, pagingAdapter, enableSwipeRefresh)
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateTopInsetGuard(binding)
            }
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    updateTopInsetGuard(binding)
                }
            })
            root.post { updateTopInsetGuard(binding) }
            ViewCompat.requestApplyInsets(root)
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        updatePagingState(binding, pagingAdapter, loadState, enableSwipeRefresh = enableSwipeRefresh)
                    }
                }
            }
            if (enableScrollTopButton && requireContext().prefs().getBoolean(C.UI_SCROLL_TOP, true)) {
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

    private fun updateTopInsetGuard(binding: CommonRecyclerViewLayoutBinding) {
        if (!binding.root.isAttachedToWindow) return
        val topInset = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top ?: 0
        val location = IntArray(2)
        binding.root.getLocationInWindow(location)
        val guardHeight = (topInset - location[1]).coerceAtLeast(0)
        if (binding.topInsetGuard.layoutParams.height != guardHeight) {
            binding.topInsetGuard.updateLayoutParams<ViewGroup.LayoutParams> {
                height = guardHeight
            }
        }
        val shouldShowGuard = guardHeight > 0
        if (binding.topInsetGuard.isVisible != shouldShowGuard) {
            binding.topInsetGuard.isVisible = shouldShowGuard
        }
    }

    protected fun <T : Any, VH : RecyclerView.ViewHolder> setupPagingControls(
        binding: CommonRecyclerViewLayoutBinding,
        pagingAdapter: PagingDataAdapter<T, VH>,
        enableSwipeRefresh: Boolean = true,
    ) {
        binding.retryButton.setOnClickListener { pagingAdapter.retry() }
        binding.swipeRefresh.isEnabled = enableSwipeRefresh
        if (enableSwipeRefresh) {
            binding.swipeRefresh.setOnRefreshListener { pagingAdapter.refresh() }
        }
    }

    protected fun <T : Any, VH : RecyclerView.ViewHolder> updatePagingState(
        binding: CommonRecyclerViewLayoutBinding,
        pagingAdapter: PagingDataAdapter<T, VH>,
        loadState: CombinedLoadStates,
        showEmpty: Boolean = true,
        enableSwipeRefresh: Boolean = true,
    ) {
        val contentState = pagedListContentState(loadState.refresh, pagingAdapter.itemCount)
        val refreshError = loadState.refresh as? LoadState.Error
        val appendError = loadState.append as? LoadState.Error
        val prependError = loadState.prepend as? LoadState.Error
        val errorState = pagedListErrorState(loadState.refresh, loadState.append, loadState.prepend)
        val pageError = when (errorState) {
            PagedListErrorState.Refresh -> refreshError
            PagedListErrorState.Page -> appendError ?: prependError
            null -> null
        }

        binding.progressBar.isVisible = contentState == PagedListContentState.Loading
        binding.nothingHere.isVisible = showEmpty && contentState == PagedListContentState.Empty
        binding.errorContainer.isVisible = showEmpty && contentState == PagedListContentState.Error
        binding.retryButton.isVisible = refreshError != null
        if (showEmpty && refreshError != null) {
            binding.errorMessage.setText(R.string.list_load_error)
        }

        if (enableSwipeRefresh) {
            binding.swipeRefresh.isRefreshing = contentState == PagedListContentState.Content && loadState.refresh is LoadState.Loading
        }

        if (pageError != null && pagingAdapter.itemCount > 0) {
            if (this.pageError !== pageError || this.pageErrorState != errorState) {
                pageErrorSnackbar?.dismiss()
                this.pageError = pageError
                this.pageErrorState = errorState
                pageErrorSnackbar = Snackbar.make(
                    binding.recyclerView,
                    if (errorState == PagedListErrorState.Refresh) {
                        R.string.list_refresh_error
                    } else {
                        R.string.list_load_more_error
                    },
                    Snackbar.LENGTH_INDEFINITE,
                ).setAction(R.string.retry) { pagingAdapter.retry() }
                pageErrorSnackbar?.show()
            }
        } else {
            pageErrorSnackbar?.dismiss()
            pageErrorSnackbar = null
            this.pageError = null
            this.pageErrorState = null
        }

        if ((refreshError ?: appendError ?: prependError)?.error?.message == C.FAILED_INTEGRITY_CHECK) {
            (requireActivity() as? MainActivity)?.getNewIntegrityToken("refresh", childFragmentManager)
        }
    }

    override fun onDestroyView() {
        pageErrorSnackbar?.dismiss()
        pageErrorSnackbar = null
        pageError = null
        pageErrorState = null
        super.onDestroyView()
    }
}
