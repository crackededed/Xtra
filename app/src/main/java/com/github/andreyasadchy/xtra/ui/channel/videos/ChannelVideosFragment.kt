package com.github.andreyasadchy.xtra.ui.channel.videos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.VideosAdapter
import com.github.andreyasadchy.xtra.ui.common.VideosSortDialog
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelVideosFragment : PagedListFragment(), Scrollable, Sortable, VideosSortDialog.OnFilter {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelVideosViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Video, out RecyclerView.ViewHolder>

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
        }, showChannel = false)
        setAdapter(binding.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = args.channelId?.let {
                    viewModel.getSortChannel(it)?.takeIf { it.saveSort == true }
                } ?: viewModel.getSortChannel("default")
                viewModel.setFilter(
                    sort = sortValues?.videoSort,
                    type = sortValues?.videoType,
                    saveSort = sortValues?.saveSort,
                )
                viewModel.sortText.value = requireContext().getString(
                    R.string.sort_and_period,
                    requireContext().getString(
                        when (viewModel.sort) {
                            VideosSortDialog.SORT_TIME -> R.string.upload_date
                            VideosSortDialog.SORT_VIEWS -> R.string.view_count
                            else -> R.string.upload_date
                        }
                    ),
                    requireContext().getString(R.string.all_time)
                )
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter)
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

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visible()
        sortBar.root.setOnClickListener {
            VideosSortDialog.newInstance(
                sort = viewModel.sort,
                period = viewModel.period,
                type = viewModel.type,
                saveSort = viewModel.saveSort,
                saveDefault = requireContext().prefs().getBoolean(C.SORT_DEFAULT_CHANNEL_VIDEOS, false)
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

    override fun onChange(sort: String, sortText: CharSequence, period: String, periodText: CharSequence, type: String, languageIndex: Int, saveSort: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.scrollTop.gone()
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(sort, type, saveSort)
                viewModel.sortText.value = requireContext().getString(R.string.sort_and_period, sortText, periodText)
                val sortValues = args.channelId?.let { viewModel.getSortChannel(it) }
                if (saveSort) {
                    if (sortValues != null) {
                        sortValues.apply {
                            this.saveSort = true
                            videoSort = sort
                            videoType = type
                        }
                    } else {
                        args.channelId?.let {
                            SortChannel(
                                id = it,
                                saveSort = true,
                                videoSort = sort,
                                videoType = type
                            )
                        }
                    }
                } else {
                    sortValues?.apply {
                        this.saveSort = false
                    }
                }?.let { viewModel.saveSortChannel(it) }
                if (saveDefault) {
                    if (sortValues != null) {
                        sortValues.apply {
                            this.saveSort = saveSort
                        }
                    } else {
                        args.channelId?.let {
                            SortChannel(
                                id = it,
                                saveSort = saveSort
                            )
                        }
                    }?.let { viewModel.saveSortChannel(it) }
                    val sortDefaults = viewModel.getSortChannel("default")
                    if (sortDefaults != null) {
                        sortDefaults.apply {
                            videoSort = sort
                            videoType = type
                        }
                    } else {
                        SortChannel(
                            id = "default",
                            videoSort = sort,
                            videoType = type
                        )
                    }.let { viewModel.saveSortChannel(it) }
                }
                if (saveDefault != requireContext().prefs().getBoolean(C.SORT_DEFAULT_CHANNEL_VIDEOS, false)) {
                    requireContext().prefs().edit { putBoolean(C.SORT_DEFAULT_CHANNEL_VIDEOS, saveDefault) }
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
        (parentFragment as? IntegrityDialog.CallbackListener)?.onIntegrityDialogCallback("refresh")
        if (callback == "refresh") {
            pagingAdapter.refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}