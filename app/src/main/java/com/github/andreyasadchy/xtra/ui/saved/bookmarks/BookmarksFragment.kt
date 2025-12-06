package com.github.andreyasadchy.xtra.ui.saved.bookmarks

import android.os.Build
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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar

@AndroidEntryPoint
class BookmarksFragment : BaseNetworkFragment(), Scrollable, Sortable, BookmarksSortDialog.OnFilter, IntegrityDialog.CallbackListener {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BookmarksViewModel by viewModels()
    private lateinit var adapter: ListAdapter<Bookmark, out RecyclerView.ViewHolder>
    override var enableNetworkCheck = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null &&
                        it != "done" &&
                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                        requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    ) {
                        IntegrityDialog.show(childFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        adapter = BookmarksAdapter(this, {
            viewModel.updateVideo(
                requireContext().filesDir.path,
                it,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            )
        }, {
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
            viewModel.vodIgnoreUser(it)
        }, {
            val delete = getString(R.string.delete)
            requireActivity().getAlertDialogBuilder()
                .setTitle(delete)
                .setMessage(getString(R.string.are_you_sure))
                .setPositiveButton(delete) { _, _ -> viewModel.delete(it) }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        })
        with(binding) {
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    adapter.unregisterAdapterDataObserver(this)
                    adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            if (positionStart == 0) {
                                recyclerView.smoothScrollToPosition(0)
                            }
                        }
                    })
                }
            })
            recyclerView.adapter = adapter
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerView.updatePadding(bottom = insets.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = viewModel.getSortChannel("bookmarks")
                viewModel.setFilter(
                    sort = sortValues?.videoSort,
                    order = sortValues?.videoType,
                )
                viewModel.sortText.value = requireContext().getString(
                    R.string.sort_and_order,
                    requireContext().getString(
                        when (viewModel.sort) {
                            BookmarksSortDialog.SORT_EXPIRES_AT -> R.string.deletion_date
                            BookmarksSortDialog.SORT_CREATED_AT -> R.string.creation_date
                            BookmarksSortDialog.SORT_SAVED_AT -> R.string.saved_date
                            else -> R.string.saved_date
                        }
                    ),
                    requireContext().getString(
                        when (viewModel.order) {
                            BookmarksSortDialog.ORDER_DESC -> R.string.descending
                            BookmarksSortDialog.ORDER_ASC -> R.string.ascending
                            else -> R.string.descending
                        }
                    )
                )
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { list ->
                    val sorted = if (viewModel.order == BookmarksSortDialog.ORDER_ASC) {
                        when (viewModel.sort) {
                            BookmarksSortDialog.SORT_EXPIRES_AT -> list.sortedWith(compareBy(nullsLast()) {
                                if (it.type?.lowercase() == "archive") {
                                    val userType = it.userType ?: it.userBroadcasterType
                                    if (userType != null && it.createdAt != null) {
                                        val time = TwitchApiHelper.parseIso8601DateUTC(it.createdAt)
                                        val days = when (userType.lowercase()) {
                                            "" -> 14
                                            "affiliate" -> 14
                                            else -> 60
                                        }
                                        if (time != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                val date = Instant.ofEpochMilli(time).plus(days.toLong(), ChronoUnit.DAYS)
                                                val diff = Duration.between(Instant.now(), date)
                                                if (!diff.isNegative) {
                                                    diff.seconds
                                                } else null
                                            } else {
                                                val currentTime = Calendar.getInstance().time.time
                                                val calendar = Calendar.getInstance()
                                                calendar.timeInMillis = time
                                                calendar.add(Calendar.DAY_OF_MONTH, days)
                                                val diff = ((calendar.time.time - currentTime) / 1000)
                                                if (diff >= 0) {
                                                    diff
                                                } else null
                                            }
                                        } else null
                                    } else null
                                } else null
                            })
                            BookmarksSortDialog.SORT_CREATED_AT -> list.sortedWith(compareBy(nullsLast()) {
                                it.createdAt?.let { createdAt -> TwitchApiHelper.parseIso8601DateUTC(createdAt) }
                            })
                            else -> list.sortedWith(compareBy(nullsLast()) { it.id })
                        }
                    } else {
                        when (viewModel.sort) {
                            BookmarksSortDialog.SORT_EXPIRES_AT -> list.sortedWith(compareByDescending(nullsFirst()) {
                                if (it.type?.lowercase() == "archive") {
                                    val userType = it.userType ?: it.userBroadcasterType
                                    if (userType != null && it.createdAt != null) {
                                        val time = TwitchApiHelper.parseIso8601DateUTC(it.createdAt)
                                        val days = when (userType.lowercase()) {
                                            "" -> 14
                                            "affiliate" -> 14
                                            else -> 60
                                        }
                                        if (time != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                val date = Instant.ofEpochMilli(time).plus(days.toLong(), ChronoUnit.DAYS)
                                                val diff = Duration.between(Instant.now(), date)
                                                if (!diff.isNegative) {
                                                    diff.seconds
                                                } else null
                                            } else {
                                                val currentTime = Calendar.getInstance().time.time
                                                val calendar = Calendar.getInstance()
                                                calendar.timeInMillis = time
                                                calendar.add(Calendar.DAY_OF_MONTH, days)
                                                val diff = ((calendar.time.time - currentTime) / 1000)
                                                if (diff >= 0) {
                                                    diff
                                                } else null
                                            }
                                        } else null
                                    } else null
                                } else null
                            })
                            BookmarksSortDialog.SORT_CREATED_AT -> list.sortedWith(compareByDescending(nullsFirst()) {
                                it.createdAt?.let { createdAt -> TwitchApiHelper.parseIso8601DateUTC(createdAt) }
                            })
                            else -> list.sortedWith(compareByDescending(nullsFirst()) { it.id })
                        }
                    }
                    adapter.submitList(sorted)
                    binding.nothingHere.isVisible = sorted.isEmpty()
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
                        (adapter as BookmarksAdapter).setVideoPositions(it)
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.ignoredUsers.collectLatest {
                        (adapter as BookmarksAdapter).setIgnoredUsers(it)
                    }
                }
            }
            viewModel.updateUsers(
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            )
        }
        val helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModel.updateVideos(requireContext().filesDir.path, requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), helixHeaders)
        }
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visible()
        sortBar.root.setOnClickListener {
            BookmarksSortDialog.newInstance(
                sort = viewModel.sort,
                order = viewModel.order,
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

    override fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, changed: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (changed) {
                    adapter.submitList(emptyList())
                    viewModel.setFilter(sort, order)
                    viewModel.sortText.value = requireContext().getString(R.string.sort_and_order, sortText, orderText)
                }
                if (saveDefault) {
                    val item = viewModel.getSortChannel("bookmarks")?.apply {
                        videoSort = sort
                        videoType = order
                    } ?: SortChannel(
                        id = "bookmarks",
                        videoSort = sort,
                        videoType = order
                    )
                    viewModel.saveSortChannel(item)
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    when (callback) {
                        "users" -> viewModel.updateUsers(
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext()),
                            TwitchApiHelper.getHelixHeaders(requireContext()),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
