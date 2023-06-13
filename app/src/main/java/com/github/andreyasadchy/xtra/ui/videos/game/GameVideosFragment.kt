package com.github.andreyasadchy.xtra.ui.videos.game

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentVideosBinding
import com.github.andreyasadchy.xtra.model.ui.BroadcastTypeEnum
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.model.ui.VideoSortEnum
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.videos.BaseVideosAdapter
import com.github.andreyasadchy.xtra.ui.videos.BaseVideosFragment
import com.github.andreyasadchy.xtra.ui.videos.VideosAdapter
import com.github.andreyasadchy.xtra.ui.videos.VideosSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GameVideosFragment : BaseVideosFragment(), Scrollable, VideosSortDialog.OnFilter {

    private var _binding: FragmentVideosBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GameVideosViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Video, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = VideosAdapter(this, {
            lastSelectedItem = it
            showDownloadDialog()
        }, {
            lastSelectedItem = it
            viewModel.saveBookmark(requireContext(), it)
        }, true)
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter, viewModel.flow)
        initializeVideoAdapter(viewModel, pagingAdapter as BaseVideosAdapter)
        if (requireContext().prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            viewModel.positions.observe(viewLifecycleOwner) {
                (pagingAdapter as BaseVideosAdapter).setVideoPositions(it)
            }
        }
        viewModel.bookmarks.observe(viewLifecycleOwner) {
            (pagingAdapter as BaseVideosAdapter).setBookmarksList(it)
        }
        with(binding) {
            sortBar.root.visible()
            sortBar.root.setOnClickListener {
                VideosSortDialog.newInstance(
                    sort = viewModel.sort,
                    period = viewModel.period,
                    type = viewModel.type,
                    languageIndex = viewModel.languageIndex,
                    saveSort = viewModel.saveSort,
                    saveDefault = requireContext().prefs().getBoolean(C.SORT_DEFAULT_GAME_VIDEOS, false)
                ).show(childFragmentManager, null)
            }
            viewModel.sortText.observe(viewLifecycleOwner) {
                sortBar.sortText.text = it
            }
        }
    }

    override fun onChange(sort: VideoSortEnum, sortText: CharSequence, period: VideoPeriodEnum, periodText: CharSequence, type: BroadcastTypeEnum, languageIndex: Int, saveSort: Boolean, saveDefault: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.recyclerViewLayout.scrollTop.gone()
            pagingAdapter.submitData(PagingData.empty())
            viewModel.filter(
                context = requireContext(),
                sort = sort,
                period = period,
                type = type,
                languageIndex = languageIndex,
                text = getString(R.string.sort_and_period, sortText, periodText),
                saveSort = saveSort,
                saveDefault = saveDefault
            )
        }
    }

    override fun scrollToTop() {
        binding.recyclerViewLayout.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
