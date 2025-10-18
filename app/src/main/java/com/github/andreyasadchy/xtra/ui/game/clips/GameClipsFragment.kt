package com.github.andreyasadchy.xtra.ui.game.clips

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.SortGame
import com.github.andreyasadchy.xtra.ui.common.ClipsAdapter
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.VideosSortDialog
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GameClipsFragment : PagedListFragment(), Scrollable, Sortable, VideosSortDialog.OnFilter {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: GameClipsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Clip, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val showDialog: (Clip) -> Unit = {
            DownloadDialog.newInstance(
                clipId = it.id,
                title = it.title,
                uploadDate = it.uploadDate,
                duration = it.duration,
                videoId = it.videoId,
                vodOffset = it.vodOffset,
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
                channelLogo = it.channelLogo,
                thumbnail = it.thumbnail,
                gameId = it.gameId,
                gameSlug = it.gameSlug,
                gameName = it.gameName,
            ).show(childFragmentManager, null)
        }
        pagingAdapter = ClipsAdapter(this, showDialog, showGame = false)
        setAdapter(binding.recyclerView, pagingAdapter)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (requireContext().prefs().getStringSet(C.UI_NAVIGATION_TABS, resources.getStringArray(R.array.pageValues).toSet()).isNullOrEmpty()) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = args.gameId?.let {
                    viewModel.getSortGame(it)?.takeIf { it.saveSort == true }
                } ?: viewModel.getSortGame("default")
                viewModel.setFilter(
                    period = sortValues?.clipPeriod,
                    languageIndex = sortValues?.clipLanguageIndex,
                    saveSort = sortValues?.saveSort,
                )
                viewModel.sortText.value = requireContext().getString(
                    R.string.sort_and_period,
                    requireContext().getString(R.string.view_count),
                    requireContext().getString(
                        when (viewModel.period) {
                            VideosSortDialog.PERIOD_DAY -> R.string.today
                            VideosSortDialog.PERIOD_WEEK -> R.string.this_week
                            VideosSortDialog.PERIOD_MONTH -> R.string.this_month
                            VideosSortDialog.PERIOD_ALL -> R.string.all_time
                            else -> R.string.this_week
                        }
                    )
                )
                viewModel.filtersText.value = if (viewModel.languageIndex > 0) requireContext().getString(R.string.languages_list,
                    resources.getStringArray(R.array.gqlUserLanguageValues).toList().elementAt(viewModel.languageIndex)
                ) else null
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter)
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visible()
        sortBar.filtersText.text = null
        sortBar.root.setOnClickListener {
            VideosSortDialog.newInstance(
                period = viewModel.period,
                languageIndex = viewModel.languageIndex,
                saveSort = viewModel.saveSort,
                saveDefault = requireContext().prefs().getBoolean(C.SORT_DEFAULT_GAME_CLIPS, false)
            ).show(childFragmentManager, null)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sortText.collectLatest {
                    sortBar.sortText.text = it
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filtersText.collectLatest {
                    sortBar.filtersText.text = it
                }
            }
        }
    }

    override fun onChange(sort: String, sortText: CharSequence, period: String, periodText: CharSequence, type: String, typeText: CharSequence, languageIndex: Int, saveSort: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.scrollTop.gone()
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(period, languageIndex, saveSort)
                viewModel.sortText.value = requireContext().getString(R.string.sort_and_period, sortText, periodText)
                viewModel.filtersText.value = if (languageIndex > 0) requireContext().getString(R.string.languages_list,
                    resources.getStringArray(R.array.gqlUserLanguageValues).toList().elementAt(languageIndex)
                ) else null
                if (!args.gameId.isNullOrBlank() || !args.gameName.isNullOrBlank()) {
                    val sortValues = args.gameId?.let { viewModel.getSortGame(it) }
                    if (saveSort) {
                        if (sortValues != null) {
                            sortValues.apply {
                                this.saveSort = true
                                clipPeriod = period
                                clipLanguageIndex = languageIndex
                            }
                        } else {
                            args.gameId?.let {
                                SortGame(
                                    id = it,
                                    saveSort = true,
                                    clipPeriod = period,
                                    clipLanguageIndex = languageIndex
                                )
                            }
                        }
                    } else {
                        sortValues?.apply {
                            this.saveSort = false
                        }
                    }?.let { viewModel.saveSortGame(it) }
                    if (saveDefault) {
                        if (sortValues != null) {
                            sortValues.apply {
                                this.saveSort = saveSort
                            }
                        } else {
                            args.gameId?.let {
                                SortGame(
                                    id = it,
                                    saveSort = saveSort
                                )
                            }
                        }?.let { viewModel.saveSortGame(it) }
                        val sortDefaults = viewModel.getSortGame("default")
                        if (sortDefaults != null) {
                            sortDefaults.apply {
                                clipPeriod = period
                                clipLanguageIndex = languageIndex
                            }
                        } else {
                            SortGame(
                                id = "default",
                                clipPeriod = period,
                                clipLanguageIndex = languageIndex
                            )
                        }.let { viewModel.saveSortGame(it) }
                    }
                    if (saveDefault != requireContext().prefs().getBoolean(C.SORT_DEFAULT_GAME_CLIPS, false)) {
                        requireContext().prefs().edit { putBoolean(C.SORT_DEFAULT_GAME_CLIPS, saveDefault) }
                    }
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
