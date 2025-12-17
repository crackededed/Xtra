package com.github.andreyasadchy.xtra.ui.team

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
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
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Team
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInLandscapeOrientation
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TeamFragment : PagedListFragment(), Scrollable, IntegrityDialog.CallbackListener {

    private var _binding: FragmentTeamBinding? = null
    private val binding get() = _binding!!
    private val args: TeamFragmentArgs by navArgs()
    private val viewModel: TeamViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTeamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
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
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                        }
                        true
                    }
                    R.id.share -> {
                        requireContext().startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/team/${args.teamName}")
                            args.teamName?.let {
                                putExtra(Intent.EXTRA_TITLE, it)
                            }
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
                if (activity.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerViewLayout.recyclerView.updatePadding(bottom = systemBars.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
        pagingAdapter = TeamMembersAdapter(this) {
            findNavController().navigate(
                TopStreamsFragmentDirections.actionGlobalTopFragment(
                    tags = arrayOf(it)
                )
            )
        }
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        viewModel.loadTeamInfo(
            teamName = args.teamName,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
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
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter)
        if (requireContext().prefs().getBoolean(C.UI_SCROLLTOP, true)) {
            binding.recyclerViewLayout.scrollTop.setOnClickListener {
                scrollToTop()
                it.gone()
            }
        }
    }

    private fun updateTeamLayout(team: Team) {
        with(binding) {
            if (!team.displayName.isNullOrBlank()) {
                teamName.visible()
                teamName.text = team.displayName
                if (team.bannerUrl != null) {
                    teamName.setTextColor(Color.LTGRAY)
                    teamName.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                teamName.gone()
            }
            if (team.memberCount != null) {
                teamMembers.visible()
                val count = team.memberCount
                teamMembers.text = requireContext().resources.getQuantityString(
                    R.plurals.members,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
                if (team.bannerUrl != null) {
                    teamMembers.setTextColor(Color.LTGRAY)
                    teamMembers.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                teamMembers.gone()
            }
            if (!team.ownerName.isNullOrBlank() || !team.ownerLogin.isNullOrBlank()) {
                teamOwner.visible()
                teamOwner.text = requireContext().getString(
                    R.string.owner,
                    if (team.ownerLogin != null && !team.ownerLogin.equals(team.ownerName, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${team.ownerName}(${team.ownerLogin})"
                            "1" -> team.ownerName
                            else -> team.ownerLogin
                        }
                    } else {
                        team.ownerName
                    }
                )
                if (team.bannerUrl != null) {
                    teamOwner.setTextColor(Color.LTGRAY)
                    teamOwner.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                teamOwner.gone()
            }
            if (team.logoUrl != null) {
                logoImage.visible()
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(team.logoUrl)
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
            if (team.bannerUrl != null) {
                bannerImage.visible()
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(team.bannerUrl)
                        crossfade(true)
                        target(bannerImage)
                    }.build()
                )
            } else {
                bannerImage.gone()
            }
            if (!team.description.isNullOrBlank()) {
                teamDescription.visible()
                val markwon = Markwon.builder(requireContext())
                    .usePlugin(SoftBreakAddsNewLinePlugin.create())
                    .usePlugin(LinkifyPlugin.create())
                    .build()
                markwon.setMarkdown(teamDescription, team.description)
                teamDescription.setOnClickListener {
                    if (teamDescription.maxLines == 3) {
                        teamDescription.maxLines = Int.MAX_VALUE
                    } else {
                        teamDescription.maxLines = 3
                    }
                }
            } else {
                teamDescription.gone()
            }
        }
    }

    override fun scrollToTop() {
        with(binding) {
            appBar.setExpanded(true, true)
            recyclerViewLayout.recyclerView.scrollToPosition(0)
        }
    }

    override fun onNetworkRestored() {
        viewModel.loadTeamInfo(
            teamName = args.teamName,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
        pagingAdapter.retry()
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.loadTeamInfo(
                        teamName = args.teamName,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
            pagingAdapter.refresh()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.appBar.setExpanded(false, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}