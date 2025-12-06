package com.github.andreyasadchy.xtra.ui.search.videos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.VideosAdapter
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.search.RecentSearchAdapter
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VideoSearchFragment : PagedListFragment(), Searchable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoSearchViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Video, out RecyclerView.ViewHolder>
    private var recentSearchAdapter = RecentSearchAdapter({ (parentFragment as? SearchPagerFragment)?.setQuery(it.query) }, { viewModel.deleteRecentSearch(it) })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = VideosAdapter(this, {
            DownloadDialog.newInstance(
                id = it.id,
                title = it.title,
                uploadDate = it.uploadDate,
                duration = it.duration,
                videoType = it.type,
                animatedPreviewUrl = it.animatedPreviewURL,
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
                channelLogo = it.channelLogo,
                thumbnail = it.thumbnail,
                gameId = it.gameId,
                gameSlug = it.gameSlug,
                gameName = it.gameName,
            ).show(childFragmentManager, null)
        }, {
            viewModel.saveBookmark(
                requireContext().filesDir.path,
                it,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        })
        setAdapter(binding.recyclerView, pagingAdapter)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.flow.collectLatest { pagingData ->
                        pagingAdapter.submitData(pagingData)
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        progressBar.isVisible = loadState.refresh is LoadState.Loading && pagingAdapter.itemCount == 0
                        nothingHere.isVisible = loadState.refresh !is LoadState.Loading && pagingAdapter.itemCount == 0 && viewModel.query.value.isNotBlank()
                        if (viewModel.query.value.isBlank() && requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
                            recyclerView.adapter = recentSearchAdapter
                        } else {
                            if (recyclerView.adapter is RecentSearchAdapter) {
                                recyclerView.adapter = pagingAdapter
                            }
                        }
                        if ((loadState.refresh as? LoadState.Error ?:
                            loadState.append as? LoadState.Error ?:
                            loadState.prepend as? LoadState.Error)?.error?.message == "failed integrity check" &&
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                            requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                        ) {
                            IntegrityDialog.show(childFragmentManager, "refresh")
                        }
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.recentSearches.collectLatest {
                        recentSearchAdapter.submitList(it)
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
            requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true) &&
            TwitchApiHelper.isIntegrityTokenExpired(requireContext())
        ) {
            IntegrityDialog.show(childFragmentManager, "refresh")
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.positions.collectLatest {
                        (pagingAdapter as VideosAdapter).setVideoPositions(it)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookmarks.collectLatest {
                    (pagingAdapter as VideosAdapter).setBookmarksList(it)
                }
            }
        }
    }

    override fun search(query: String) {
        viewModel.setQuery(query)
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewModel.saveRecentSearch(query)
        }
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