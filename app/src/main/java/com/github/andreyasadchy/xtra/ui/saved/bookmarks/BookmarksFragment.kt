package com.github.andreyasadchy.xtra.ui.saved.bookmarks

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentSavedBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.Bookmark
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BookmarksFragment : BaseNetworkFragment(), Scrollable {

    private var _binding: FragmentSavedBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BookmarksViewModel by viewModels()
    private lateinit var adapter: ListAdapter<Bookmark, out RecyclerView.ViewHolder>
    override var enableNetworkCheck = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            adapter = BookmarksAdapter(this@BookmarksFragment, {
                viewModel.updateVideo(
                    context = requireContext(),
                    helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(requireContext()).helixToken,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    videoId = it
                )
            }, {
                VideoDownloadDialog.newInstance(it).show(childFragmentManager, null)
            }, {
                viewModel.vodIgnoreUser(it)
            }, {
                val delete = getString(R.string.delete)
                AlertDialog.Builder(activity)
                    .setTitle(delete)
                    .setMessage(getString(R.string.are_you_sure))
                    .setPositiveButton(delete) { _, _ -> viewModel.delete(requireContext(), it) }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            })
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
            (recyclerView.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }
    }

    override fun initialize() {
        with(binding) {
            viewModel.bookmarks.observe(viewLifecycleOwner) {
                adapter.submitList(it.reversed())
                nothingHere.isVisible = it.isEmpty()
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                viewModel.positions.observe(viewLifecycleOwner) {
                    (adapter as BookmarksAdapter).setVideoPositions(it)
                }
            }
            if (requireContext().prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true)) {
                viewModel.ignoredUsers.observe(viewLifecycleOwner) {
                    (adapter as BookmarksAdapter).setIgnoredUsers(it)
                }
                viewModel.updateUsers(
                    helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(requireContext()).helixToken,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                )
            }
            if (!Account.get(requireContext()).helixToken.isNullOrBlank()) {
                viewModel.updateVideos(
                    context = requireContext(),
                    helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(requireContext()).helixToken,
                )
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
