package com.github.andreyasadchy.xtra.ui.team

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentTeamBinding
import com.github.andreyasadchy.xtra.model.ui.Team
import com.github.andreyasadchy.xtra.model.ui.TeamMember
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInLandscapeOrientation
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TeamFragment : BaseNetworkFragment(), Scrollable {
    private var _binding: FragmentTeamBinding? = null
    private val binding get() = _binding!!
    private val args: TeamFragmentArgs by navArgs()
    private val viewModel: TeamViewModel by viewModels()

    private lateinit var pagingAdapter: PagingDataAdapter<TeamMember, out RecyclerView.ViewHolder>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentTeamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = TeamMembersAdapter(this)
        binding.recyclerView.apply {
            visible()
            adapter = pagingAdapter
        }
        with(binding) {
            val activity = requireActivity() as MainActivity
            if (activity.isInLandscapeOrientation) {
                appBar.setExpanded(false, false)
            }
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.share -> {
                        requireContext().startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/team/${args.teamName}")
                            type = "text/plain"
                        }, null))
                        true
                    }
                    else -> false
                }
            }
            if (!requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                appBar.setLiftable(false)
                appBar.background = null
                collapsingToolbar.setContentScrimColor(MaterialColors.getColor(collapsingToolbar, com.google.android.material.R.attr.colorSurface))
            }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                collapsingToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }

    }

    override fun initialize() {
        viewModel.loadTeam(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.team.collectLatest { team ->
                    if (team != null) {
                        updateTeamLayout(team)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { members ->
                    pagingAdapter.submitData(members)
                }
            }
        }
    }

    private fun updateTeamLayout(team: Team?) {
        with(binding) {
            team?.displayName.let {
                userLayout.visible()
                teamName.visible()
                teamName.text = it
            }
            team?.description.let {
                if (!it.isNullOrBlank()) {
                    teamDescription.visible()
                    teamDescription.text = it
                    val markwon = Markwon.builder(this@TeamFragment.requireContext())
                        .usePlugin(SoftBreakAddsNewLinePlugin.create())
                        .usePlugin(LinkifyPlugin.create())
                        .build()

                    markwon.setMarkdown(teamDescription, it)
                    teamDescription.setOnClickListener { _ ->
                        if (teamDescription.maxLines == MAX_LINES_COLLAPSED) {
                            teamDescription.maxLines = Int.MAX_VALUE
                        } else {
                            teamDescription.maxLines = MAX_LINES_COLLAPSED
                        }
                    }
                } else {
                    teamDescription.gone()
                }
            }
            team?.logoURL.let {
                if (it != null) {
                    userLayout.visible()
                    logoImage.visible()
                    this@TeamFragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(this@TeamFragment.requireContext()).apply {
                            data(it)
                            if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(logoImage)
                        }.build()
                    )
                } else {
                    logoImage.gone()
                }
            }
            team?.bannerURL.let {
                if (it != null) {
                    userLayout.visible()
                    bannerImage.visible()
                    this@TeamFragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(this@TeamFragment.requireContext()).apply {
                            data(it)
                            crossfade(true)
                            target(bannerImage)
                        }.build()
                    )
                } else {
                    bannerImage.gone()
                }
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.retry(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.appBar.setExpanded(false, false)
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val MAX_LINES_COLLAPSED = 3
    }
}