package com.github.andreyasadchy.xtra.ui.channel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChannelBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationWorker
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInLandscapeOrientation
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class ChannelPagerFragment : BaseNetworkFragment(), Scrollable, FragmentHost, IntegrityDialog.CallbackListener {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelPagerViewModel by viewModels()
    private var firstLaunch = true

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
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
            if (activity.isInLandscapeOrientation) {
                appBar.setExpanded(false, false)
            }
            if (viewModel.stream.value == null) {
                watchLive.setOnClickListener {
                    activity.startStream(
                        Stream(
                            id = args.streamId,
                            channelId = args.channelId,
                            channelLogin = args.channelLogin,
                            channelName = args.channelName,
                            profileImageUrl = args.channelLogo
                        )
                    )
                }
            }
            args.channelName.let {
                if (it != null) {
                    userLayout.visible()
                    userName.visible()
                    userName.text = if (args.channelLogin != null && !args.channelLogin.equals(it, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${it}(${args.channelLogin})"
                            "1" -> it
                            else -> args.channelLogin
                        }
                    } else {
                        it
                    }
                } else {
                    userName.gone()
                }
            }
            args.channelLogo.let {
                if (it != null) {
                    userLayout.visible()
                    userImage.visible()
                    this@ChannelPagerFragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(this@ChannelPagerFragment.requireContext()).apply {
                            data(it)
                            if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                } else {
                    userImage.gone()
                }
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
                    R.id.toggleNotifications -> {
                        viewModel.notificationsEnabled.value?.let {
                            if (it) {
                                args.channelId?.let {
                                    viewModel.disableNotifications(requireContext().tokenPrefs().getString(C.USER_ID, null), it, setting, requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), TwitchApiHelper.getGQLHeaders(requireContext(), true), requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false))
                                }
                            } else {
                                args.channelId?.let {
                                    val notificationsEnabled = requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
                                    viewModel.enableNotifications(requireContext().tokenPrefs().getString(C.USER_ID, null), it, setting, notificationsEnabled, requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), TwitchApiHelper.getGQLHeaders(requireContext(), true), requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false))
                                    if (!notificationsEnabled) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                                        }
                                        viewModel.updateNotifications(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), TwitchApiHelper.getGQLHeaders(requireContext(), true), TwitchApiHelper.getHelixHeaders(requireContext()))
                                        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
                                            "live_notifications",
                                            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                                            PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                                                .setInitialDelay(1, TimeUnit.MINUTES)
                                                .setConstraints(
                                                    Constraints.Builder()
                                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                                        .build()
                                                )
                                                .build()
                                        )
                                        requireContext().prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, true) }
                                    }
                                }
                            }
                        }
                        true
                    }
                    R.id.followButton -> {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                requireContext().getAlertDialogBuilder()
                                    .setMessage(requireContext().getString(R.string.unfollow_channel,
                                        if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                            when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                "0" -> "${args.channelName}(${args.channelLogin})"
                                                "1" -> args.channelName
                                                else -> args.channelLogin
                                            }
                                        } else {
                                            args.channelName
                                        }
                                    ))
                                    .setNegativeButton(getString(R.string.no), null)
                                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                        viewModel.deleteFollowChannel(
                                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                                            args.channelId,
                                            setting,
                                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                        )
                                    }
                                    .show()
                            } else {
                                viewModel.saveFollowChannel(
                                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                                    args.channelId,
                                    args.channelLogin,
                                    args.channelName,
                                    setting,
                                    requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
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
                    R.id.share -> {
                        requireContext().startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${args.channelLogin}")
                            args.channelName?.let {
                                putExtra(Intent.EXTRA_TITLE, it)
                            }
                            type = "text/plain"
                        }, null))
                        true
                    }
                    R.id.download -> {
                        viewModel.stream.value?.let {
                            DownloadDialog.newInstance(
                                id = it.id,
                                title = it.title,
                                startedAt = it.startedAt,
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
                        true
                    }
                    else -> false
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.notificationsEnabled.collectLatest {
                        if (it != null) {
                            toolbar.menu.findItem(R.id.toggleNotifications)?.apply {
                                if (it) {
                                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_notifications_black_24)
                                    title = requireContext().getString(R.string.disable_notifications)
                                } else {
                                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_notifications_none_black_24)
                                    title = requireContext().getString(R.string.enable_notifications)
                                }
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.notifications.collectLatest { pair ->
                        if (pair != null) {
                            val enabled = pair.first
                            val errorMessage = pair.second
                            if (!errorMessage.isNullOrBlank()) {
                                requireContext().shortToast(errorMessage)
                            } else {
                                if (enabled) {
                                    requireContext().shortToast(requireContext().getString(R.string.enabled_notifications))
                                } else {
                                    requireContext().shortToast(requireContext().getString(R.string.disabled_notifications))
                                }
                            }
                            viewModel.notifications.value = null
                        }
                    }
                }
            }
            if (setting == 0 || setting == 1) {
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
                                        requireContext().shortToast(requireContext().getString(R.string.now_following,
                                            if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                                when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                    "0" -> "${args.channelName}(${args.channelLogin})"
                                                    "1" -> args.channelName
                                                    else -> args.channelLogin
                                                }
                                            } else {
                                                args.channelName
                                            }
                                        ))
                                    } else {
                                        requireContext().shortToast(requireContext().getString(R.string.unfollowed,
                                            if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                                when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                    "0" -> "${args.channelName}(${args.channelLogin})"
                                                    "1" -> args.channelName
                                                    else -> args.channelLogin
                                                }
                                            } else {
                                                args.channelName
                                            }
                                        ))
                                    }
                                }
                                viewModel.follow.value = null
                            }
                        }
                    }
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_CHANNEL_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_CHANNEL_TABS.split(',')
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
            val adapter = ChannelPagerAdapter(this@ChannelPagerFragment, args, tabs)
            viewPager.adapter = adapter
            if (!requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                appBar.setLiftable(false)
                appBar.background = null
                collapsingToolbar.setContentScrimColor(MaterialColors.getColor(collapsingToolbar, com.google.android.material.R.attr.colorSurface))
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private val layoutParams = collapsingToolbar.layoutParams as AppBarLayout.LayoutParams
                private val originalScrollFlags = layoutParams.scrollFlags

                override fun onPageSelected(position: Int) {
                    layoutParams.scrollFlags = if (tabs.getOrNull(position) != "3") {
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
                    "0" -> getString(R.string.suggested)
                    "1" -> getString(R.string.videos)
                    "2" -> getString(R.string.clips)
                    "3" -> getString(R.string.chat)
                    "4" -> getString(R.string.about)
                    else -> getString(R.string.videos)
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
        viewModel.loadStream(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stream.collectLatest { stream ->
                    if (stream != null) {
                        updateStreamLayout(stream)
                        if (stream.user != null) {
                            updateUserLayout(stream.user)
                        } else {
                            viewModel.loadUser(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), TwitchApiHelper.getHelixHeaders(requireContext()))
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
        viewModel.isFollowingChannel(
            requireContext().tokenPrefs().getString(C.USER_ID, null),
            args.channelId,
            args.channelLogin,
            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            TwitchApiHelper.getGQLHeaders(requireContext(), true),
            TwitchApiHelper.getHelixHeaders(requireContext()),
        )
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
                    this@ChannelPagerFragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(this@ChannelPagerFragment.requireContext()).apply {
                            data(it)
                            if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                    requireArguments().putString(C.CHANNEL_PROFILEIMAGE, it)
                } else {
                    userImage.gone()
                }
            }
            stream?.channelName.let {
                if (it != null && it != args.channelName) {
                    userLayout.visible()
                    userName.visible()
                    userName.text = if (stream?.channelLogin != null && !stream.channelLogin.equals(it, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${it}(${stream.channelLogin})"
                            "1" -> it
                            else -> stream.channelLogin
                        }
                    } else {
                        it
                    }
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
                title.text = stream.title?.trim()
            } else {
                title.gone()
            }
            if (!stream?.gameName.isNullOrBlank()) {
                streamLayout.visible()
                gameName.visible()
                gameName.text = stream.gameName
                gameName.setOnClickListener {
                    findNavController().navigate(
                        if (requireContext().prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = stream.gameId,
                                gameSlug = stream.gameSlug,
                                gameName = stream.gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = stream.gameId,
                                gameSlug = stream.gameSlug,
                                gameName = stream.gameName
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
                val count = stream.viewerCount ?: 0
                viewers.text = requireContext().resources.getQuantityString(
                    R.plurals.viewers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
            } else {
                viewers.gone()
            }
            if (requireContext().prefs().getBoolean(C.UI_UPTIME, true)) {
                if (stream?.startedAt != null) {
                    TwitchApiHelper.getUptime(stream.startedAt).let {
                        if (it != null) {
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
                this@ChannelPagerFragment.requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(this@ChannelPagerFragment.requireContext()).apply {
                        data(user.channelLogo)
                        if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                            transformations(CircleCropTransformation())
                        }
                        crossfade(true)
                        target(userImage)
                    }.build()
                )
                requireArguments().putString(C.CHANNEL_PROFILEIMAGE, user.channelLogo)
            }
            if (user.bannerImageURL != null) {
                bannerImage.visible()
                this@ChannelPagerFragment.requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(this@ChannelPagerFragment.requireContext()).apply {
                        data(user.bannerImageURL)
                        crossfade(true)
                        target(bannerImage)
                    }.build()
                )
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
                val count = user.followersCount
                userFollowers.text = requireContext().resources.getQuantityString(
                    R.plurals.followers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                )
                if (user.bannerImageURL != null) {
                    userFollowers.setTextColor(Color.LTGRAY)
                    userFollowers.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userFollowers.gone()
            }
            val broadcasterType = when (user.broadcasterType?.lowercase()) {
                "partner" -> requireContext().getString(R.string.user_partner)
                "affiliate" -> requireContext().getString(R.string.user_affiliate)
                else -> null
            }
            val type = when (user.type?.lowercase()) {
                "staff" -> requireContext().getString(R.string.user_staff)
                else -> null
            }
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
                viewModel.updateLocalUser(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), requireContext().filesDir.path, user)
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.retry(
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
                            viewModel.retry(
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext()),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                args.channelId,
                                args.channelLogin,
                                requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                        }
                        "follow" -> viewModel.saveFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            args.channelId,
                            args.channelLogin,
                            args.channelName,
                            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                        "unfollow" -> viewModel.deleteFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            args.channelId,
                            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                        "enableNotifications" -> args.channelId?.let {
                            viewModel.enableNotifications(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                it,
                                requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                        }
                        "disableNotifications" -> args.channelId?.let {
                            viewModel.disableNotifications(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                it,
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