package com.github.andreyasadchy.xtra.ui.clips.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentClipsBinding
import com.github.andreyasadchy.xtra.model.ui.BroadcastTypeEnum
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.model.ui.VideoSortEnum
import com.github.andreyasadchy.xtra.ui.clips.BaseClipsFragment
import com.github.andreyasadchy.xtra.ui.clips.ClipsAdapter
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.videos.VideosSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClipsFragment : BaseClipsFragment(), Scrollable, VideosSortDialog.OnFilter {

    private var _binding: FragmentClipsBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: ClipsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Clip, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClipsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val showDialog: (Clip) -> Unit = {
            lastSelectedItem = it
            showDownloadDialog()
        }
        pagingAdapter = if (args.channelId != null || args.channelLogin != null) {
            ChannelClipsAdapter(this, showDialog)
        } else {
            ClipsAdapter(this, showDialog, args.gameId != null || args.gameName != null)
        }
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter, viewModel.flow)
        with(binding) {
            sortBar.root.visible()
            sortBar.root.setOnClickListener {
                VideosSortDialog.newInstance(
                    period = viewModel.period,
                    languageIndex = viewModel.languageIndex,
                    clipChannel = args.channelId != null,
                    saveSort = viewModel.saveSort,
                    saveDefault = if (pagingAdapter is ClipsAdapter) requireContext().prefs().getBoolean(C.SORT_DEFAULT_GAME_CLIPS, false) else requireContext().prefs().getBoolean(C.SORT_DEFAULT_CHANNEL_CLIPS, false)
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
                period = period,
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
