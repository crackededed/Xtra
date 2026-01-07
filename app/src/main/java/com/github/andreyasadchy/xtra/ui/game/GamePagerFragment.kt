package com.github.andreyasadchy.xtra.ui.game

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamePagerBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInLandscapeOrientation
import com.github.andreyasadchy.xtra.util.loadImage
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GamePagerFragment : BaseNetworkFragment(), Scrollable, FragmentHost, IntegrityDialog.CallbackListener {

    private var _binding: FragmentGamePagerBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: GamePagerViewModel by viewModels()
    private var firstLaunch = true

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGamePagerBinding.inflate(inflater, container, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            binding.sortBar.root.visible()
        }
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
        with(binding) {
            val activity = requireActivity() as MainActivity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isInLandscapeOrientation) {
                    appBar.setExpanded(false, false)
                }
            } else {
                if (activity.orientation == 2) {
                    appBar.setExpanded(false, false)
                }
            }
            if (args.gameName != null) {
                gameLayout.visible()
                gameName.visible()
                gameName.text = args.gameName
            } else {
                gameName.gone()
            }
            if (args.boxArt != null) {
                gameLayout.visible()
                gameImage.visible()
                gameImage.loadImage(
                    this@GamePagerFragment,
                    args.boxArt,
                )
            } else {
                gameImage.gone()
            }
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.followButton -> {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                requireContext().getAlertDialogBuilder()
                                    .setMessage(requireContext().getString(R.string.unfollow_channel, args.gameName))
                                    .setNegativeButton(getString(R.string.no), null)
                                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                        viewModel.deleteFollowGame(
                                            args.gameId,
                                            setting,
                                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                        )
                                    }
                                    .show()
                            } else {
                                viewModel.saveFollowGame(
                                    args.gameId,
                                    args.gameSlug,
                                    args.gameName,
                                    setting,
                                    requireContext().filesDir.path,
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                    TwitchApiHelper.getHelixHeaders(requireContext()),
                                    requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        true
                    }
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
                    else -> false
                }
            }
            if (setting < 2) {
                val followButton = toolbar.menu.findItem(R.id.followButton)
                followButton?.isVisible = true
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.isFollowing.collectLatest {
                            if (it != null) {
                                followButton?.apply {
                                    if (it) {
                                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_favorite_black_24)
                                        title = requireContext().getString(R.string.unfollow)
                                    } else {
                                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_favorite_border_black_24)
                                        title = requireContext().getString(R.string.follow)
                                    }
                                }
                            }
                        }
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.follow.collectLatest { pair ->
                            if (pair != null) {
                                val following = pair.first
                                val errorMessage = pair.second
                                if (!errorMessage.isNullOrBlank()) {
                                    requireContext().shortToast(errorMessage)
                                } else {
                                    if (following) {
                                        requireContext().shortToast(requireContext().getString(R.string.now_following, args.gameName))
                                    } else {
                                        requireContext().shortToast(requireContext().getString(R.string.unfollowed, args.gameName))
                                    }
                                }
                                viewModel.follow.value = null
                            }
                        }
                    }
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_GAME_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_GAME_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled) {
                    key
                } else {
                    null
                }
            }
            if (tabs.size <= 1) {
                tabLayout.gone()
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = GamePagerAdapter(this@GamePagerFragment, tabs)
            viewPager.adapter = adapter
            if (!requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                appBar.setLiftable(false)
                appBar.background = null
                collapsingToolbar.setContentScrimColor(MaterialColors.getColor(collapsingToolbar, com.google.android.material.R.attr.colorSurface))
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}")?.let { fragment ->
                            if (fragment is Sortable) {
                                fragment.setupSortBar(sortBar)
                                sortBar.root.doOnLayout {
                                    toolbarContainer.layoutParams = (toolbarContainer.layoutParams as CollapsingToolbarLayout.LayoutParams).apply { bottomMargin = toolbarContainer2.height }
                                    val toolbarHeight = toolbarContainer.marginTop + toolbarContainer.marginBottom
                                    toolbar.layoutParams = toolbar.layoutParams.apply { height = toolbarHeight }
                                    collapsingToolbar.scrimVisibleHeightTrigger = toolbarHeight + 1
                                }
                            } else {
                                sortBar.root.gone()
                                toolbarContainer2.doOnLayout {
                                    toolbarContainer.layoutParams = (toolbarContainer.layoutParams as CollapsingToolbarLayout.LayoutParams).apply { bottomMargin = toolbarContainer2.height }
                                    val toolbarHeight = toolbarContainer.marginTop + toolbarContainer.marginBottom
                                    toolbar.layoutParams = toolbar.layoutParams.apply { height = toolbarHeight }
                                    collapsingToolbar.scrimVisibleHeightTrigger = toolbarHeight + 1
                                }
                            }
                        }
                    }
                }
            })
            if (firstLaunch) {
                val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.offscreenPageLimit = adapter.itemCount
            viewPager.reduceDragSensitivity()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.live)
                    "2" -> getString(R.string.clips)
                    else -> getString(R.string.live)
                }
            }.attach()
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
        viewModel.loadGame(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.game.collectLatest { game ->
                    if (game != null) {
                        updateGameLayout(game)
                    }
                }
            }
        }
        val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        if (setting < 2) {
            viewModel.isFollowingGame(
                args.gameId,
                args.gameName,
                setting,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext(), true),
            )
        }
        if (args.updateLocal) {
            viewModel.updateLocalGame(
                requireContext().filesDir.path,
                args.gameId,
                args.gameName,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        }
    }

    private fun updateGameLayout(game: Game?) {
        with(binding) {
            if (!gameImage.isVisible && game?.boxArt != null) {
                gameLayout.visible()
                gameImage.visible()
                gameImage.loadImage(
                    this@GamePagerFragment,
                    game.boxArt,
                )
            }
            if (game?.gameName != null && game.gameName != args.gameName) {
                gameLayout.visible()
                gameName.visible()
                gameName.text = game.gameName
            }
            if (game?.viewersCount != null) {
                viewers.visible()
                val count = game.viewersCount ?: 0
                viewers.text = requireContext().resources.getQuantityString(
                    R.plurals.viewers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
            } else {
                viewers.gone()
            }
            if (game?.broadcastersCount != null && requireContext().prefs().getBoolean(C.UI_BROADCASTERSCOUNT, true)) {
                broadcastersCount.visible()
                val count = game.broadcastersCount ?: 0
                broadcastersCount.text = requireContext().resources.getQuantityString(
                    R.plurals.broadcasters,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
            } else {
                broadcastersCount.gone()
            }
            if (game?.followersCount != null) {
                followers.visible()
                val count = game.followersCount
                followers.text = requireContext().resources.getQuantityString(
                    R.plurals.followers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
            } else {
                followers.gone()
            }
            if (!game?.tags.isNullOrEmpty() && requireContext().prefs().getBoolean(C.UI_TAGS, true)) {
                tagsLayout.removeAllViews()
                tagsLayout.visible()
                val tagsFlowLayout = Flow(requireContext()).apply {
                    layoutParams = ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topToTop = tagsLayout.id
                        bottomToBottom = tagsLayout.id
                        startToStart = tagsLayout.id
                        endToEnd = tagsLayout.id
                    }
                    setWrapMode(Flow.WRAP_CHAIN)
                }
                tagsLayout.addView(tagsFlowLayout)
                val ids = mutableListOf<Int>()
                for (tag in game.tags) {
                    val text = TextView(requireContext())
                    val id = ViewCompat.generateViewId()
                    text.id = id
                    ids.add(id)
                    text.text = tag.name
                    requireContext().obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                        TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                    }
                    if (tag.id != null) {
                        text.setOnClickListener {
                            findNavController().navigate(
                                GamesFragmentDirections.actionGlobalGamesFragment(
                                    tags = arrayOf(tag)
                                )
                            )
                        }
                    }
                    val padding = requireContext().convertDpToPixels(5f)
                    text.setPadding(padding, 0, padding, 0)
                    tagsLayout.addView(text)
                }
                tagsFlowLayout.referencedIds = ids.toIntArray()
            } else {
                tagsLayout.gone()
            }
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onNetworkRestored() {
        viewModel.loadGame(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    when (callback) {
                        "refresh" -> {
                            viewModel.loadGame(
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext()),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                            if (setting < 2) {
                                viewModel.isFollowingGame(
                                    args.gameId,
                                    args.gameName,
                                    setting,
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                )
                            }
                        }
                        "follow" -> viewModel.saveFollowGame(
                            args.gameId,
                            args.gameSlug,
                            args.gameName,
                            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().filesDir.path,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            TwitchApiHelper.getHelixHeaders(requireContext()),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                        "unfollow" -> viewModel.deleteFollowGame(
                            args.gameId,
                            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
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
