package com.github.andreyasadchy.xtra.ui.player.stream

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerStreamBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.ui.player.PlayerSettingsDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.FragmentUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.BroadcastSettings
import com.github.andreyasadchy.xtra.util.disable
import com.github.andreyasadchy.xtra.util.enable
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

@AndroidEntryPoint
class StreamPlayerFragment : BasePlayerFragment() {

    private var _binding: FragmentPlayerStreamBinding? = null
    private val binding get() = _binding!!
    override val viewModel: StreamPlayerViewModel by viewModels()
    lateinit var chatFragment: ChatFragment
    private lateinit var stream: Stream

    override val controllerAutoShow: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stream = requireArguments().getParcelable(KEY_STREAM)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerStreamBinding.inflate(inflater, container, false).also {
            (it.slidingLayout as LinearLayout).orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = requireView().findViewById<ImageButton>(R.id.playerSettings)
        val mode = requireView().findViewById<ImageButton>(R.id.playerMode)
        viewModel.loaded.observe(viewLifecycleOwner) {
            if (it) {
                settings?.enable()
                mode?.enable()
                (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.let { setQualityText() }
            } else {
                settings?.disable()
                mode?.disable()
            }
        }
        viewModel.stream.observe(viewLifecycleOwner) {
            chatFragment.updateStreamId(it?.id)
            if (prefs.getBoolean(C.CHAT_DISABLE, false) || !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) || requireView().findViewById<TextView>(R.id.playerViewersText)?.text.isNullOrBlank()) {
                updateViewerCount(it?.viewerCount)
            }
            if (prefs.getBoolean(C.CHAT_DISABLE, false) || !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                    requireView().findViewById<TextView>(R.id.playerTitle)?.text.isNullOrBlank() ||
                    requireView().findViewById<TextView>(R.id.playerCategory)?.text.isNullOrBlank()) {
                updateTitle(BroadcastSettings(it?.title, it?.gameId, it?.gameName))
            }
        }
        if (prefs.getBoolean(C.PLAYER_MENU, true)) {
            requireView().findViewById<ImageButton>(R.id.playerMenu)?.apply {
                visible()
                setOnClickListener {
                    FragmentUtils.showPlayerSettingsDialog(
                        fragmentManager = childFragmentManager,
                    )
                }
            }
        }
        if (prefs.getBoolean(C.PLAYER_RESTART, true)) {
            requireView().findViewById<ImageButton>(R.id.playerRestart)?.apply {
                visible()
                setOnClickListener { restartPlayer() }
            }
        }
        if (prefs.getBoolean(C.PLAYER_SEEKLIVE, false)) {
            requireView().findViewById<ImageButton>(R.id.playerSeekLive)?.apply {
                visible()
                setOnClickListener { player?.seekToDefaultPosition() }
            }
        }
        if (prefs.getBoolean(C.PLAYER_VIEWERLIST, false)) {
            requireView().findViewById<LinearLayout>(R.id.playerViewers)?.apply {
                setOnClickListener { openViewerList() }
            }
        }
        if (prefs.getBoolean(C.PLAYER_CHANNEL, true)) {
            requireView().findViewById<TextView>(R.id.playerChannel)?.apply {
                visible()
                text = stream.channelName
                setOnClickListener {
                    findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = stream.channelId,
                        channelLogin = stream.channelLogin,
                        channelName = stream.channelName,
                        channelLogo = stream.channelLogo
                    ))
                    slidingLayout.minimize()
                }
            }
        }
        if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
            stream.startedAt?.let {
                TwitchApiHelper.parseIso8601Date(it)?.let { startedAtMs ->
                    updateUptime(TimeZone.getDefault().getOffset(System.currentTimeMillis()) + startedAtMs)
                }
            }
        }
        updateTitle(BroadcastSettings(stream.title, stream.gameId, stream.gameName))
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != stream.channelId || account.login != stream.channelLogin) || setting == 1)) {
            val followButton = requireView().findViewById<ImageButton>(R.id.playerFollow)
            followButton?.visible()
            var initialized = false
            viewModel.follow.observe(viewLifecycleOwner) { pair ->
                val following = pair.first
                val errorMessage = pair.second
                if (initialized) {
                    if (!errorMessage.isNullOrBlank()) {
                        requireContext().shortToast(errorMessage)
                    } else {
                        requireContext().shortToast(requireContext().getString(if (following) R.string.now_following else R.string.unfollowed, stream.channelName))
                    }
                } else {
                    initialized = true
                }
                if (errorMessage.isNullOrBlank()) {
                    followButton?.setOnClickListener {
                        if (!following) {
                            viewModel.saveFollowChannel(requireContext(), stream.channelId, stream.channelLogin, stream.channelName, stream.channelLogo)
                        } else {
                            FragmentUtils.showUnfollowDialog(requireContext(), stream.channelName) {
                                viewModel.deleteFollowChannel(requireContext(), stream.channelId)
                            }
                        }
                    }
                    followButton?.setImageResource(if (following) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
                }
            }
        }
        chatFragment = childFragmentManager.findFragmentById(R.id.chatFragmentContainer).let {
            if (it != null) {
                it as ChatFragment
            } else {
                val fragment = ChatFragment.newInstance(stream.channelId, stream.channelLogin, stream.channelName, stream.id)
                childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit()
                fragment
            }
        }
    }

    override fun initialize() {
        super.initialize()
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != stream.channelId || account.login != stream.channelLogin) || setting == 1)) {
            viewModel.isFollowingChannel(requireContext(), stream.channelId, stream.channelLogin)
        }
    }

    override fun startPlayer() {
        super.startPlayer()
        viewModel.useProxy = prefs.getString(C.PLAYER_PROXY, "1")?.toIntOrNull() ?: 1
        if (viewModel._stream.value == null) {
            viewModel._stream.value = stream
            loadStream(stream)
            viewModel.loadStream(requireContext(), stream)
        }
    }

    private fun loadStream(stream: Stream) {
        player?.prepare()
        try {
            stream.channelLogin?.let { viewModel.load(
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, false)),
                channelLogin = it,
                proxyUrl = prefs.getString(C.PLAYER_PROXY_URL, "https://api.ttv.lol/playlist/\$channel.m3u8?allow_source=true&allow_audio_only=true&fast_bread=true"),
                randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site"),
                proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, true),
                proxyHost = prefs.getString(C.PROXY_HOST, null),
                proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                proxyUser = prefs.getString(C.PROXY_USER, null),
                proxyPassword = prefs.getString(C.PROXY_PASSWORD, null)
            ) }
            viewModel.result.observe(viewLifecycleOwner) { result ->
                if (result != null) {
                    when (viewModel.useProxy) {
                        0 -> {
                            if (result.second != 0) {
                                requireContext().toast(R.string.proxy_error)
                                viewModel.useProxy = 2
                            }
                        }
                        1 -> {
                            if (result.second != 1) {
                                requireContext().toast(R.string.adblock_not_working)
                                viewModel.useProxy = 2
                            }
                        }
                    }
                    player?.sendCustomCommand(SessionCommand(PlaybackService.START_STREAM, bundleOf(
                        PlaybackService.ITEM to stream,
                        PlaybackService.URI to result.first,
                        PlaybackService.HEADERS to if (result.second == 1) {
                            (hashMapOf("X-Donate-To" to "https://ttv.lol/donate"))
                        } else if (viewModel.useProxy == 3) {
                            (hashMapOf("X-Forwarded-For" to "::1"))
                        } else null,
                        PlaybackService.PLAYLIST_AS_DATA to result.third
                    )), Bundle.EMPTY)
                    player?.prepare()
                }
            }
        } catch (e: Exception) {
            requireContext().toast(R.string.error_stream)
        }
    }

    fun checkAds() {
        player?.sendCustomCommand(SessionCommand(PlaybackService.GET_LAST_TAG, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val tag = result.get().extras.getString(PlaybackService.RESULT)
                    val usingProxy = result.get().extras.getBoolean(PlaybackService.USING_PROXY)
                    val stopProxy = result.get().extras.getBoolean(PlaybackService.STOP_PROXY)
                    val playlist = result.get().extras.getString(PlaybackService.ITEM)
                    val oldValue = viewModel.playingAds
                    viewModel.playingAds = tag == "ads=true"
                    if (viewModel.playingAds) {
                        if (usingProxy) {
                            player?.sendCustomCommand(SessionCommand(PlaybackService.TOGGLE_PROXY, bundleOf(
                                PlaybackService.USING_PROXY to false,
                                PlaybackService.STOP_PROXY to true
                            )), Bundle.EMPTY)
                        } else {
                            if (!oldValue) {
                                if (!stopProxy && !playlist.isNullOrBlank() && prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) && !prefs.getString(C.PROXY_HOST, null).isNullOrBlank() && prefs.getString(C.PROXY_PORT, null)?.toIntOrNull() != null) {
                                    player?.sendCustomCommand(SessionCommand(PlaybackService.TOGGLE_PROXY, bundleOf(PlaybackService.USING_PROXY to true)), Bundle.EMPTY)
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        for (i in 0 until 10) {
                                            delay(10000)
                                            if (!viewModel.checkPlaylist(playlist)) {
                                                break
                                            }
                                        }
                                        player?.sendCustomCommand(SessionCommand(PlaybackService.TOGGLE_PROXY, bundleOf(PlaybackService.USING_PROXY to false)), Bundle.EMPTY)
                                    }
                                } else {
                                    if (prefs.getBoolean(C.PLAYER_HIDE_ADS, false)) {
                                        requireContext().toast(R.string.waiting_ads)
                                    }
                                }
                            }
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onError(error: PlaybackException) {
        Log.e(tag, "Player error", error)
        player?.sendCustomCommand(SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                    if (requireContext().isNetworkAvailable) {
                        when {
                            responseCode == 404 -> {
                                requireContext().toast(R.string.stream_ended)
                            }
                            viewModel.useProxy == 0 && responseCode >= 400 -> {
                                requireContext().toast(R.string.proxy_error)
                                viewModel.useProxy = 2
                                viewLifecycleOwner.lifecycleScope.launch {
                                    delay(1500L)
                                    try {
                                        restartPlayer()
                                    } catch (e: Exception) {}
                                }
                            }
                            viewModel.useProxy == 1 && responseCode >= 400 -> {
                                requireContext().toast(R.string.adblock_not_working)
                                viewModel.useProxy = 2
                                viewLifecycleOwner.lifecycleScope.launch {
                                    delay(1500L)
                                    try {
                                        restartPlayer()
                                    } catch (e: Exception) {}
                                }
                            }
                            else -> {
                                requireContext().shortToast(R.string.player_error)
                                viewLifecycleOwner.lifecycleScope.launch {
                                    delay(1500L)
                                    try {
                                        restartPlayer()
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun updateViewerCount(viewerCount: Int?) {
        val viewers = requireView().findViewById<TextView>(R.id.playerViewersText)
        val viewerIcon = requireView().findViewById<ImageView>(R.id.playerViewersIcon)
        if (viewerCount != null) {
            viewers?.text = TwitchApiHelper.formatCount(requireContext(), viewerCount)
            if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                viewerIcon?.visible()
            }
        } else {
            viewers?.text = null
            viewerIcon?.gone()
        }
    }

    fun updateLive(live: Boolean?, uptimeMs: Long?, channelLogin: String?) {
        if (channelLogin == stream.channelLogin) {
            live?.let {
                if (live) {
                    restartPlayer()
                }
                updateUptime(uptimeMs)
            }
        }
    }

    private fun updateUptime(uptimeMs: Long?) {
        val layout = requireView().findViewById<LinearLayout>(R.id.playerUptime)
        val uptime = requireView().findViewById<Chronometer>(R.id.playerUptimeText)
        uptime?.stop()
        if (uptimeMs != null && prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
            layout?.visible()
            uptime?.apply {
                base = SystemClock.elapsedRealtime() + uptimeMs - System.currentTimeMillis()
                start()
            }
            requireView().findViewById<ImageView>(R.id.playerUptimeIcon)?.apply {
                if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                    visible()
                } else {
                    gone()
                }
            }
        } else {
            layout?.gone()
        }
    }

    fun updateTitle(data: BroadcastSettings?) {
        if (data != null) {
            requireView().findViewById<TextView>(R.id.playerTitle)?.apply {
                if (!data.title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                    text = data.title.trim()
                    visible()
                } else {
                    text = null
                    gone()
                }
            }
            requireView().findViewById<TextView>(R.id.playerCategory)?.apply {
                if (!data.gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                    text = data.gameName
                    visible()
                    setOnClickListener {
                        findNavController().navigate(
                            if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = data.gameId,
                                    gameName = data.gameName
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = data.gameId,
                                    gameName = data.gameName
                                )
                            }
                        )
                        slidingLayout.minimize()
                    }
                } else {
                    text = null
                    gone()
                }
            }
        }
    }

    fun restartPlayer() {
        if (viewModel.playerMode != PlayerMode.DISABLED) {
            loadStream(stream)
        }
    }

    fun openViewerList() {
        stream.channelLogin?.let { login -> FragmentUtils.showPlayerViewerListDialog(childFragmentManager, login, viewModel.repository) }
    }

    fun showPlaylistTags(mediaPlaylist: Boolean) {
        player?.sendCustomCommand(SessionCommand(if (mediaPlaylist) PlaybackService.GET_MEDIA_PLAYLIST else PlaybackService.GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val tags = result.get().extras.getString(PlaybackService.RESULT)
                    if (!tags.isNullOrBlank()) {
                        AlertDialog.Builder(requireContext()).apply {
                            setView(NestedScrollView(context).apply {
                                addView(HorizontalScrollView(context).apply {
                                    addView(TextView(context).apply {
                                        text = tags
                                        textSize = 12F
                                        setTextIsSelectable(true)
                                    })
                                })
                            })
                            setNegativeButton(R.string.copy_clip) { dialog, _ ->
                                val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("label", tags))
                                dialog.dismiss()
                            }
                            setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                        }.show()
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun emoteMenuIsVisible() = chatFragment.emoteMenuIsVisible()

    fun toggleEmoteMenu(enable: Boolean) = chatFragment.toggleEmoteMenu(enable)

    fun toggleBackPressedCallback(enable: Boolean) = chatFragment.toggleBackPressedCallback(enable)

    override fun onMinimize() {
        super.onMinimize()
        chatFragment.hideKeyboard()
    }

//    override fun play(obj: Parcelable) {
//        val stream = obj as Stream
//        if (viewModel.stream != stream) {
//            viewModel.player.playWhenReady = false
//            chatView.adapter.submitList(null)
//        }
//        viewModel.stream = stream
//        draggableView?.maximize()
//    }

    override fun onNetworkRestored() {
        if (isResumed) {
            restartPlayer()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_STREAM = "stream"

        fun newInstance(stream: Stream): StreamPlayerFragment {
            return StreamPlayerFragment().apply { arguments = bundleOf(KEY_STREAM to stream) }
        }
    }
}
