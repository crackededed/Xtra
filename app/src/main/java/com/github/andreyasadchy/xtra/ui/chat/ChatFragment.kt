package com.github.andreyasadchy.xtra.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.LiveChatMessage
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.ui.view.chat.MessageClickedDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LifecycleListener
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.Command
import com.github.andreyasadchy.xtra.util.chat.PointsEarned
import com.github.andreyasadchy.xtra.util.chat.Raid
import com.github.andreyasadchy.xtra.util.chat.RoomState
import com.github.andreyasadchy.xtra.util.hideKeyboard
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), LifecycleListener, MessageClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val isLive = args.getBoolean(KEY_IS_LIVE)
            val account = Account.get(requireContext())
            val isLoggedIn = !account.login.isNullOrBlank() && (!account.gqlToken.isNullOrBlank() || !account.helixToken.isNullOrBlank())
            val enableChat = when {
                requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) -> false
                isLive -> {
                    chatView.init(this@ChatFragment, channelId)
                    chatView.setCallback(viewModel)
                    if (isLoggedIn) {
                        chatView.setUsername(account.login)
                        chatView.addToAutoCompleteList(viewModel.chatters)
                        viewModel.recentEmotes.observe(viewLifecycleOwner, Observer(chatView::setRecentEmotes))
                        viewModel.userEmotes.observe(viewLifecycleOwner, Observer(chatView::addToAutoCompleteList))
                        viewModel.newChatter.observe(viewLifecycleOwner) { chatView.addToAutoCompleteList(listOf(it)) }
                    }
                    true
                }
                args.getString(KEY_VIDEO_ID) != null && !args.getBoolean(KEY_START_TIME_EMPTY) -> {
                    chatView.init(this@ChatFragment, channelId)
                    true
                }
                else -> {
                    requireView().findViewById<TextView>(R.id.chatReplayUnavailable)?.visible()
                    false
                }
            }
            if (enableChat) {
                chatView.enableChatInteraction(isLive && isLoggedIn)
                viewModel.chatMessages.observe(viewLifecycleOwner, Observer(chatView::submitList))
                viewModel.newMessage.observe(viewLifecycleOwner) { chatView.notifyMessageAdded() }
                viewModel.globalStvEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalStvEmotes))
                viewModel.channelStvEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelStvEmotes))
                viewModel.globalBttvEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalBttvEmotes))
                viewModel.channelBttvEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelBttvEmotes))
                viewModel.globalFfzEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalFfzEmotes))
                viewModel.channelFfzEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelFfzEmotes))
                viewModel.globalBadges.observe(viewLifecycleOwner, Observer(chatView::addGlobalBadges))
                viewModel.channelBadges.observe(viewLifecycleOwner, Observer(chatView::addChannelBadges))
                viewModel.cheerEmotes.observe(viewLifecycleOwner, Observer(chatView::addCheerEmotes))
                viewModel.roomState.observe(viewLifecycleOwner) { chatView.notifyRoomState(it) }
                viewModel.reloadMessages.observe(viewLifecycleOwner) { chatView.notifyEmotesLoaded() }
                viewModel.scrollDown.observe(viewLifecycleOwner) { chatView.scrollToLastPosition() }
                viewModel.command.observe(viewLifecycleOwner) { postCommand(it) }
                viewModel.pointsEarned.observe(viewLifecycleOwner) { postPointsEarned(it) }
                viewModel.raid.observe(viewLifecycleOwner) { onRaidUpdate(it) }
                viewModel.raidClicked.observe(viewLifecycleOwner) { onRaidClicked() }
                viewModel.viewerCount.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateViewerCount(it) }
                viewModel.title.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateTitle(it) }
            }
        }
    }

    override fun initialize() {
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        val channelName = args.getString(KEY_CHANNEL_NAME)
        val streamId = args.getString(KEY_STREAM_ID)
        val account = Account.get(requireContext())
        val isLoggedIn = !account.login.isNullOrBlank() && (!account.gqlToken.isNullOrBlank() || !account.helixToken.isNullOrBlank())
        val messageLimit = requireContext().prefs().getInt(C.CHAT_LIMIT, 600)
        val useChatWebSocket = requireContext().prefs().getBoolean(C.CHAT_USE_WEBSOCKET, false)
        val useSSL = requireContext().prefs().getBoolean(C.CHAT_USE_SSL, true)
        val usePubSub = requireContext().prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true)
        val helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val gqlClientId = requireContext().prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
        val emoteQuality =  requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs =  requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val showUserNotice = requireContext().prefs().getBoolean(C.CHAT_SHOW_USERNOTICE, true)
        val showClearMsg = requireContext().prefs().getBoolean(C.CHAT_SHOW_CLEARMSG, true)
        val showClearChat = requireContext().prefs().getBoolean(C.CHAT_SHOW_CLEARCHAT, true)
        val collectPoints = requireContext().prefs().getBoolean(C.CHAT_POINTS_COLLECT, true)
        val notifyPoints = requireContext().prefs().getBoolean(C.CHAT_POINTS_NOTIFY, false)
        val showRaids = requireContext().prefs().getBoolean(C.CHAT_RAIDS_SHOW, true)
        val autoSwitchRaids = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
        val enableRecentMsg = requireContext().prefs().getBoolean(C.CHAT_RECENT, true)
        val recentMsgLimit = requireContext().prefs().getInt(C.CHAT_RECENT_LIMIT, 100)
        val enableStv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_STV, true)
        val enableBttv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)
        val enableFfz = requireContext().prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)
        val useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true)
        val disableChat = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        val isLive = args.getBoolean(KEY_IS_LIVE)
        if (!disableChat) {
            if (isLive) {
                viewModel.startLive(useChatWebSocket, useSSL, usePubSub, account, isLoggedIn, helixClientId, gqlClientId, channelId, channelLogin, channelName, streamId, messageLimit, emoteQuality, animateGifs, showUserNotice, showClearMsg, showClearChat, collectPoints, notifyPoints, showRaids, autoSwitchRaids, enableRecentMsg, recentMsgLimit.toString(), enableStv, enableBttv, enableFfz, useApiCommands)
            } else {
                args.getString(KEY_VIDEO_ID).let {
                    if (it != null && !args.getBoolean(KEY_START_TIME_EMPTY)) {
                        val getCurrentPosition = (parentFragment as ChatReplayPlayerFragment)::getCurrentPosition
                        viewModel.startReplay(account, helixClientId, gqlClientId, channelId, channelLogin, it, args.getDouble(KEY_START_TIME), getCurrentPosition, messageLimit, emoteQuality, animateGifs, enableStv, enableBttv, enableFfz)
                    }
                }
            }
        }
    }

    private fun postCommand(command: Command) {
        val message = when (command.type) {
            "join" -> requireContext().getString(R.string.chat_join, command.message)
            "disconnect" -> requireContext().getString(R.string.chat_disconnect, command.message, command.duration)
            "disconnect_command" -> {
                binding.chatView.hideRaid()
                binding.chatView.notifyRoomState(RoomState("0", "-1", "0", "0", "0"))
                requireContext().getString(R.string.disconnected)
            }
            "send_msg_error" -> requireContext().getString(R.string.chat_send_msg_error, command.message)
            "socket_error" -> requireContext().getString(R.string.chat_socket_error, command.message)
            "notice" -> {
                when (command.duration) { // msg-id
                    "unraid_success" -> binding.chatView.hideRaid()
                }
                TwitchApiHelper.getNoticeString(requireContext(), command.duration, command.message)
            }
            "clearmsg" -> requireContext().getString(R.string.chat_clearmsg, command.message, command.duration)
            "clearchat" -> requireContext().getString(R.string.chat_clear)
            "timeout" -> requireContext().getString(R.string.chat_timeout, command.message, TwitchApiHelper.getDurationFromSeconds(requireContext(), command.duration))
            "ban" -> requireContext().getString(R.string.chat_ban, command.message)
            "stream_live" -> requireContext().getString(R.string.stream_live, command.duration)
            "stream_offline" -> requireContext().getString(R.string.stream_offline, command.duration)
            else -> command.message
        }
        viewModel.chat?.onMessage(LiveChatMessage(message = message, color = "#999999", isAction = true, emotes = command.emotes, timestamp = command.timestamp, fullMsg = command.fullMsg))
    }

    private fun postPointsEarned(points: PointsEarned) {
        val message = requireContext().getString(R.string.points_earned, points.pointsGained)
        viewModel.chat?.onMessage(LiveChatMessage(message = message, color = "#999999", isAction = true, timestamp = points.timestamp, fullMsg = points.fullMsg))
    }

    fun isActive(): Boolean? {
        return (viewModel.chat as? ChatViewModel.LiveChatController)?.isActive()
    }

    fun disconnect() {
        (viewModel.chat as? ChatViewModel.LiveChatController)?.disconnect()
    }

    fun reconnect() {
        (viewModel.chat as? ChatViewModel.LiveChatController)?.start()
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        val enableRecentMsg = requireContext().prefs().getBoolean(C.CHAT_RECENT, true)
        val recentMsgLimit = requireContext().prefs().getInt(C.CHAT_RECENT_LIMIT, 100)
        if (channelLogin != null && enableRecentMsg) {
            viewModel.loadRecentMessages(channelLogin, recentMsgLimit.toString())
        }
    }

    fun reloadEmotes() {
        val channelId = requireArguments().getString(KEY_CHANNEL_ID)
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        val helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val helixToken = Account.get(requireContext()).helixToken
        val gqlClientId = requireContext().prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
        val emoteQuality =  requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs =  requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val enableStv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_STV, true)
        val enableBttv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)
        val enableFfz = requireContext().prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)
        viewModel.reloadEmotes(helixClientId, helixToken, gqlClientId, channelId, channelLogin, emoteQuality, animateGifs, enableStv, enableBttv, enableFfz)
    }

    fun updateStreamId(id: String?) {
        viewModel.streamId = id
    }

    private fun onRaidUpdate(raid: Raid) {
        if (viewModel.raidClosed && viewModel.raidNewId) {
            viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
            viewModel.raidClosed = false
        }
        if (raid.openStream) {
            if (!viewModel.raidClosed) {
                if (viewModel.raidAutoSwitch) {
                    if (parentFragment is BasePlayerFragment && (parentFragment as? BasePlayerFragment)?.isSleepTimerActive() != true) {
                        onRaidClicked()
                    }
                } else {
                    viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
                }
                binding.chatView.hideRaid()
            } else {
                viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
                viewModel.raidClosed = false
            }
        } else {
            if (!viewModel.raidClosed) {
                binding.chatView.notifyRaid(raid, viewModel.raidNewId)
            }
        }
    }

    private fun onRaidClicked() {
        viewModel.raid.value?.let {
            (requireActivity() as MainActivity).startStream(Stream(
                channelId = it.targetId,
                channelLogin = it.targetLogin,
                channelName = it.targetName,
                profileImageUrl = it.targetProfileImage,
            ))
        }
    }

    fun hideKeyboard() {
        binding.chatView.hideKeyboard()
        binding.chatView.clearFocus()
    }

    fun emoteMenuIsVisible() = binding.chatView.emoteMenuIsVisible()

    fun toggleEmoteMenu(enable: Boolean) = binding.chatView.toggleEmoteMenu(enable)

    fun toggleBackPressedCallback(enable: Boolean) = binding.chatView.toggleBackPressedCallback(enable)

    fun appendEmote(emote: Emote) {
        binding.chatView.appendEmote(emote)
    }

    override fun onReplyClicked(userName: String) {
        binding.chatView.reply(userName)
    }

    override fun onCopyMessageClicked(message: String) {
        binding.chatView.setMessage(message)
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelLogo: String?) {
        findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
            channelId = id,
            channelLogin = login,
            channelName = name,
            channelLogo = channelLogo
        ))
        (parentFragment as? BasePlayerFragment)?.minimize()
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            viewModel.start()
        }
    }

    override fun onMovedToBackground() {
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false) || requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            viewModel.stop()
        }
    }

    override fun onMovedToForeground() {
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false) || requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            viewModel.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_START_TIME_EMPTY = "startTime_empty"
        private const val KEY_START_TIME = "startTime"

        fun newInstance(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?) = ChatFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_IS_LIVE, true)
                putString(KEY_CHANNEL_ID, channelId)
                putString(KEY_CHANNEL_LOGIN, channelLogin)
                putString(KEY_CHANNEL_NAME, channelName)
                putString(KEY_STREAM_ID, streamId)
            }
        }

        fun newInstance(channelId: String?, channelLogin: String?, videoId: String?, startTime: Double?) = ChatFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_IS_LIVE, false)
                putString(KEY_CHANNEL_ID, channelId)
                putString(KEY_CHANNEL_LOGIN, channelLogin)
                putString(KEY_VIDEO_ID, videoId)
                if (startTime != null) {
                    putBoolean(KEY_START_TIME_EMPTY, false)
                    putDouble(KEY_START_TIME, startTime)
                } else {
                    putBoolean(KEY_START_TIME_EMPTY, true)
                }
            }
        }
    }
}