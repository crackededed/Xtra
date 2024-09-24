package com.github.andreyasadchy.xtra.ui.channel

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
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
import com.github.andreyasadchy.xtra.databinding.FragmentChannelBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.FragmentUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInLandscapeOrientation
import com.github.andreyasadchy.xtra.util.loadImage
import com.github.andreyasadchy.xtra.util.nullIfEmpty
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


@AndroidEntryPoint
class ChannelPagerFragment : BaseNetworkFragment(), Scrollable, FragmentHost, IntegrityDialog.CallbackListener {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelPagerViewModel by viewModels()

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null && it != "done" && requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)) {
                        IntegrityDialog.show(childFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        with(binding) {
            val activity = requireActivity() as MainActivity
            val account = Account.get(activity)
            if (activity.isInLandscapeOrientation) {
                appBar.setExpanded(false, false)
            }
            if (viewModel.stream.value == null) {
                watchLive.setOnClickListener { activity.startStream(Stream(
                    id = args.streamId,
                    channelId = args.channelId,
                    channelLogin = args.channelLogin,
                    channelName = args.channelName,
                    profileImageUrl = args.channelLogo))
                }
            }
            args.channelName.let {
                if (it != null) {
                    userLayout.visible()
                    userName.visible()
                    userName.text = it
                } else {
                    userName.gone()
                }
            }
            args.channelLogo.let {
                if (it != null) {
                    userLayout.visible()
                    userImage.visible()
                    userImage.loadImage(this@ChannelPagerFragment, it, circle = true)
                } else {
                    userImage.gone()
                }
            }
            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (account !is NotLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.followButton -> {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                FragmentUtils.showUnfollowDialog(requireContext(), args.channelName) {
                                    viewModel.deleteFollowChannel(TwitchApiHelper.getGQLHeaders(requireContext(), true), setting, args.channelId)
                                }
                            } else {
                                viewModel.saveFollowChannel(requireContext().filesDir.path, TwitchApiHelper.getGQLHeaders(requireContext(), true), setting, args.channelId, args.channelLogin, args.channelName, args.channelLogo)
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
                        if (account is NotLoggedIn) {
                            activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                        } else {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                account.login?.nullIfEmpty()?.let { user -> setMessage(getString(R.string.logout_msg, user)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java)) }
                            }.show()
                        }
                        true
                    }
                    R.id.share -> {
                        context?.let { FragmentUtils.shareLink(it, "https://twitch.tv/${args.channelLogin}", args.channelName) }
                        true
                    }
                    R.id.download -> {
                        viewModel.stream.value?.let { DownloadDialog.newInstance(it).show(childFragmentManager, null) }
                        true
                    }
                    else -> false
                }
            }
            if ((setting == 0 && account.id != args.channelId || account.login != args.channelLogin) || setting == 1) {
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
                                        requireContext().shortToast(requireContext().getString(R.string.now_following, args.channelName))
                                    } else {
                                        requireContext().shortToast(requireContext().getString(R.string.unfollowed, args.channelName))
                                    }
                                }
                                viewModel.follow.value = null
                            }
                        }
                    }
                }
            }
            if (!requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                appBar.setLiftable(false)
                appBar.background = null
                collapsingToolbar.setContentScrimColor(MaterialColors.getColor(collapsingToolbar, com.google.android.material.R.attr.colorSurface))
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private val layoutParams = collapsingToolbar.layoutParams as AppBarLayout.LayoutParams
                private val originalScrollFlags = layoutParams.scrollFlags

                override fun onPageSelected(position: Int) {
                    layoutParams.scrollFlags = if (position != 2) {
                        originalScrollFlags
                    } else {
                        appBar.setExpanded(false, isResumed)
                        appBar.background = null
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                    }
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}").let { fragment ->
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
            val adapter = ChannelPagerAdapter(this@ChannelPagerFragment, args)
            viewPager.adapter = adapter
            viewPager.offscreenPageLimit = adapter.itemCount
            viewPager.reduceDragSensitivity()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (position) {
                    0 -> getString(R.string.videos)
                    1 -> getString(R.string.clips)
                    else -> getString(R.string.chat)
                }
            }.attach()
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                collapsingToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    override fun initialize() {
        viewModel.loadStream(TwitchApiHelper.getHelixHeaders(requireContext()), TwitchApiHelper.getGQLHeaders(requireContext()), requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true))
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stream.collectLatest { stream ->
                    if (stream != null) {
                        updateStreamLayout(stream)
                        if (stream.user != null) {
                            updateUserLayout(stream.user)
                        } else {
                            viewModel.loadUser(TwitchApiHelper.getHelixHeaders(requireContext()))
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.user.collectLatest { user ->
                    if (user != null) {
                        updateUserLayout(user)
                    }
                }
            }
        }
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        if ((setting == 0 && account.id != args.channelId || account.login != args.channelLogin) || setting == 1) {
            viewModel.isFollowingChannel(TwitchApiHelper.getHelixHeaders(requireContext()), account, TwitchApiHelper.getGQLHeaders(requireContext(), true), setting, args.channelId, args.channelLogin)
        }
    }

    private fun updateStreamLayout(stream: Stream?) {
        with(binding) {
            val activity = requireActivity() as MainActivity
            if (stream?.type?.lowercase() == "rerun") {
                watchLive.text = getString(R.string.watch_rerun)
                watchLive.setOnClickListener { activity.startStream(stream) }
            } else {
                if (stream?.viewerCount != null) {
                    watchLive.text = getString(R.string.watch_live)
                    watchLive.setOnClickListener { activity.startStream(stream) }
                } else {
                    if (stream?.user?.lastBroadcast != null) {
                        TwitchApiHelper.formatTimeString(requireContext(), stream.user.lastBroadcast!!).let {
                            if (it != null)  {
                                lastBroadcast.visible()
                                lastBroadcast.text = requireContext().getString(R.string.last_broadcast_date, it)
                            } else {
                                lastBroadcast.gone()
                            }
                        }
                    }
                }
            }
            stream?.channelLogo.let {
                if (it != null) {
                    userLayout.visible()
                    userImage.visible()
                    userImage.loadImage(this@ChannelPagerFragment, it, circle = true)
                    requireArguments().putString(C.CHANNEL_PROFILEIMAGE, it)
                } else {
                    userImage.gone()
                }
            }
            stream?.channelName.let {
                if (it != null && it != args.channelName) {
                    userLayout.visible()
                    userName.visible()
                    userName.text = it
                    requireArguments().putString(C.CHANNEL_DISPLAYNAME, it)
                }
            }
            stream?.channelLogin.let {
                if (it != null && it != args.channelLogin) {
                    requireArguments().putString(C.CHANNEL_LOGIN, it)
                }
            }
            stream?.id.let {
                if (it != null && it != args.streamId) {
                    requireArguments().putString(C.STREAM_ID, it)
                }
            }
            if (!stream?.title.isNullOrBlank()) {
                streamLayout.visible()
                title.visible()
                title.text = stream?.title?.trim()
            } else {
                title.gone()
            }
            if (!stream?.gameName.isNullOrBlank()) {
                streamLayout.visible()
                gameName.visible()
                gameName.text = stream?.gameName
                gameName.setOnClickListener {
                    findNavController().navigate(
                        if (requireContext().prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = stream?.gameId,
                                gameSlug = stream?.gameSlug,
                                gameName = stream?.gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = stream?.gameId,
                                gameSlug = stream?.gameSlug,
                                gameName = stream?.gameName
                            )
                        }
                    )
                }
            } else {
                gameName.gone()
            }
            if (stream?.viewerCount != null) {
                streamLayout.visible()
                viewers.visible()
                viewers.text = TwitchApiHelper.formatViewersCount(requireContext(), stream.viewerCount ?: 0)
            } else {
                viewers.gone()
            }
            if (requireContext().prefs().getBoolean(C.UI_UPTIME, true)) {
                if (stream?.startedAt != null) {
                    TwitchApiHelper.getUptime(requireContext(), stream.startedAt).let {
                        if (it != null)  {
                            streamLayout.visible()
                            uptime.visible()
                            uptime.text = requireContext().getString(R.string.uptime, it)
                        } else {
                            uptime.gone()
                        }
                    }
                }
            }
        }
    }

    private fun updateUserLayout(user: User) {
        with(binding) {
            if (!userImage.isVisible && user.channelLogo != null) {
                userLayout.visible()
                userImage.visible()
                userImage.loadImage(this@ChannelPagerFragment, user.channelLogo, circle = true)
                requireArguments().putString(C.CHANNEL_PROFILEIMAGE, user.channelLogo)
            }
            if (user.bannerImageURL != null) {
                bannerImage.visible()
                bannerImage.loadImage(this@ChannelPagerFragment, user.bannerImageURL)
                if (userName.isVisible) {
                    userName.setTextColor(Color.WHITE)
                    userName.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                bannerImage.gone()
            }
            if (user.createdAt != null) {
                userCreated.visible()
                userCreated.text = requireContext().getString(R.string.created_at, TwitchApiHelper.formatTimeString(requireContext(), user.createdAt))
                if (user.bannerImageURL != null) {
                    userCreated.setTextColor(Color.LTGRAY)
                    userCreated.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userCreated.gone()
            }
            if (user.followersCount != null) {
                userFollowers.visible()
                userFollowers.text = requireContext().getString(R.string.followers, TwitchApiHelper.formatCount(requireContext(), user.followersCount))
                if (user.bannerImageURL != null) {
                    userFollowers.setTextColor(Color.LTGRAY)
                    userFollowers.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userFollowers.gone()
            }
            val broadcasterType = if (user.broadcasterType != null) { TwitchApiHelper.getUserType(requireContext(), user.broadcasterType) } else null
            val type = if (user.type != null) { TwitchApiHelper.getUserType(requireContext(), user.type) } else null
            val typeString = if (broadcasterType != null && type != null) "$broadcasterType, $type" else broadcasterType ?: type
            if (typeString != null) {
                userType.visible()
                userType.text = typeString
                if (user.bannerImageURL != null) {
                    userType.setTextColor(Color.LTGRAY)
                    userType.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userType.gone()
            }
            if (args.updateLocal) {
                viewModel.updateLocalUser(requireContext().filesDir.path, user)
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.retry(TwitchApiHelper.getHelixHeaders(requireContext()), TwitchApiHelper.getGQLHeaders(requireContext()), requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true))
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    when (callback) {
                        "refresh" -> {
                            viewModel.retry(TwitchApiHelper.getHelixHeaders(requireContext()), TwitchApiHelper.getGQLHeaders(requireContext()), requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true))
                            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                            val account = Account.get(requireContext())
                            if ((setting == 0 && account.id != args.channelId || account.login != args.channelLogin) || setting == 1) {
                                viewModel.isFollowingChannel(TwitchApiHelper.getHelixHeaders(requireContext()), account, TwitchApiHelper.getGQLHeaders(requireContext(), true), setting, args.channelId, args.channelLogin)
                            }
                        }
                        "follow" -> viewModel.saveFollowChannel(requireContext().filesDir.path, TwitchApiHelper.getGQLHeaders(requireContext(), true), requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0, args.channelId, args.channelLogin, args.channelName, args.channelLogo)
                        "unfollow" -> viewModel.deleteFollowChannel(TwitchApiHelper.getGQLHeaders(requireContext(), true), requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0, args.channelId)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.appBar.setExpanded(false, false)
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}