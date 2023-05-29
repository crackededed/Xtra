package com.github.andreyasadchy.xtra.ui.channel

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChannelBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.Utils
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.*
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class ChannelPagerFragment : BaseNetworkFragment(), Scrollable {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelPagerViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
            toolbar.apply {
                navigationIcon = Utils.getNavigationIcon(activity)
                setNavigationOnClickListener { activity.popFragment() }
            }
            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
            if ((setting == 0 && account.id != args.channelId || account.login != args.channelLogin) || setting == 1) {
                followButton.visible()
                var initialized = false
                viewModel.follow.observe(viewLifecycleOwner) { pair ->
                    val following = pair.first
                    val errorMessage = pair.second
                    if (initialized) {
                        if (!errorMessage.isNullOrBlank()) {
                            requireContext().shortToast(errorMessage)
                        } else {
                            requireContext().shortToast(requireContext().getString(if (following) R.string.now_following else R.string.unfollowed, args.channelName))
                        }
                    } else {
                        initialized = true
                    }
                    if (errorMessage.isNullOrBlank()) {
                        followButton.setOnClickListener {
                            if (!following) {
                                viewModel.saveFollowChannel(requireContext(), args.channelId, args.channelLogin, args.channelName, args.channelLogo)
                            } else {
                                FragmentUtils.showUnfollowDialog(requireContext(), args.channelName) {
                                    viewModel.deleteFollowChannel(requireContext(), args.channelId)
                                }
                            }
                        }
                        followButton.setImageResource(if (following) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
                    }
                }
            }
            search.setOnClickListener { findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment()) }
            menu.setOnClickListener { it ->
                PopupMenu(activity, it).apply {
                    inflate(R.menu.top_menu)
                    menu.findItem(R.id.login).title = if (account !is NotLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
                    setOnMenuItemClickListener {
                        when(it.itemId) {
                            R.id.settings -> { activity.startActivityFromFragment(this@ChannelPagerFragment, Intent(activity, SettingsActivity::class.java), 3) }
                            R.id.login -> {
                                if (account is NotLoggedIn) {
                                    activity.startActivityForResult(Intent(activity, LoginActivity::class.java), 1)
                                } else {
                                    AlertDialog.Builder(activity).apply {
                                        setTitle(getString(R.string.logout_title))
                                        account.login?.nullIfEmpty()?.let { user -> setMessage(getString(R.string.logout_msg, user)) }
                                        setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
                                        setPositiveButton(getString(R.string.yes)) { _, _ -> activity.startActivityForResult(Intent(activity, LoginActivity::class.java), 2) }
                                    }.show()
                                }
                            }
                            else -> menu.close()
                        }
                        true
                    }
                    show()
                }
            }
        }
        with(binding.pagerLayout) {
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private val layoutParams = binding.collapsingToolbar.layoutParams as AppBarLayout.LayoutParams
                private val originalScrollFlags = layoutParams.scrollFlags

                override fun onPageSelected(position: Int) {
                    layoutParams.scrollFlags = if (position != 2) {
                        originalScrollFlags
                    } else {
                        binding.appBar.setExpanded(false, isResumed)
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
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
        }
    }

    val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.pagerLayout.viewPager.currentItem}")

    override fun initialize() {
        viewModel.loadStream(requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(requireContext()).helixToken, requireContext().prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"))
        viewModel.stream.observe(viewLifecycleOwner) { stream ->
            updateStreamLayout(stream)
            if (stream?.user != null) {
                updateUserLayout(stream.user)
            } else {
                viewModel.loadUser(requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(requireContext()).helixToken)
            }
        }
        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user != null) {
                updateUserLayout(user)
            }
        }
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if ((setting == 0 && account.id != args.channelId || account.login != args.channelLogin) || setting == 1) {
            viewModel.isFollowingChannel(requireContext(), args.channelId, args.channelLogin)
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
            stream?.title.let {
                if (!it.isNullOrBlank()) {
                    streamLayout.visible()
                    title.visible()
                    title.text = it.trim()
                } else {
                    title.gone()
                }
            }
            stream?.gameName.let { streamGameName ->
                if (!streamGameName.isNullOrBlank()) {
                    streamLayout.visible()
                    gameName.visible()
                    gameName.text = streamGameName
                    stream?.gameId?.let { gameId ->
                        gameName.setOnClickListener {
                            findNavController().navigate(
                                if (requireContext().prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameId = gameId,
                                        gameName = streamGameName
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameId = gameId,
                                        gameName = streamGameName
                                    )
                                }
                            )
                        }
                    }
                } else {
                    gameName.gone()
                }
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
                viewModel.updateLocalUser(requireContext(), user)
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.retry(requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(requireContext()).helixToken, requireContext().prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.appBar.setExpanded(false, false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == Activity.RESULT_OK) {
            requireActivity().recreate()
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