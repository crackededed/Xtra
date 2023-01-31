package com.github.andreyasadchy.xtra.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.ui.view.chat.ChatView
import com.github.andreyasadchy.xtra.ui.view.chat.MessageClickedDialog
import com.github.andreyasadchy.xtra.util.*
import com.github.andreyasadchy.xtra.util.chat.Raid
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.view_chat.view.*

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), LifecycleListener, MessageClickedDialog.OnButtonClickListener {

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var chatView: ChatView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false).also { chatView = it as ChatView }
    }

    override fun initialize() {
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        val channelName = args.getString(KEY_CHANNEL_NAME)
        val streamId = args.getString(KEY_STREAM_ID)
        val account = Account.get(requireContext())
        val isLoggedIn = !account.login.isNullOrBlank() && (!account.gqlToken.isNullOrBlank() || !account.helixToken.isNullOrBlank())
        val useSSl = requireContext().prefs().getBoolean(C.CHAT_USE_SSL, true)
        val usePubSub = requireContext().prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true)
        val helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val gqlClientId = requireContext().prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
        val gqlClientId2 = requireContext().prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
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
        val useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, false)
        val disableChat = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        val isLive = args.getBoolean(KEY_IS_LIVE)
        val enableChat = if (disableChat) {
            false
        } else {
            if (isLive) {
                viewModel.startLive(useSSl, usePubSub, account, isLoggedIn, helixClientId, gqlClientId, gqlClientId2, channelId, channelLogin, channelName, streamId, emoteQuality, animateGifs, showUserNotice, showClearMsg, showClearChat, collectPoints, notifyPoints, showRaids, autoSwitchRaids, enableRecentMsg, recentMsgLimit.toString(), useApiCommands)
                chatView.init(this)
                chatView.setCallback(viewModel, (viewModel.chat as? ChatViewModel.LiveChatController))
                chatView.setChannelId(channelId)
                if (isLoggedIn) {
                    account.login?.let { chatView.setUsername(it) }
                    chatView.setChatters(viewModel.chatters)
                    val emotesObserver = Observer(chatView::addEmotes)
                    viewModel.userEmotes.observe(viewLifecycleOwner, emotesObserver)
                    viewModel.recentEmotes.observe(viewLifecycleOwner, emotesObserver)
                    viewModel.newChatter.observe(viewLifecycleOwner, Observer(chatView::addChatter))
                }
                true
            } else {
                args.getString(KEY_VIDEO_ID).let {
                    if (it != null && !args.getBoolean(KEY_START_TIME_EMPTY)) {
                        chatView.init(this)
                        val getCurrentPosition = (parentFragment as ChatReplayPlayerFragment)::getCurrentPosition
                        viewModel.startReplay(account, helixClientId, gqlClientId, channelId, channelLogin, it, args.getDouble(KEY_START_TIME), getCurrentPosition, emoteQuality, animateGifs)
                        chatView.setChannelId(channelId)
                        true
                    } else {
                        chatView.chatReplayUnavailable.visible()
                        false
                    }
                }
            }
        }
        if (enableChat) {
            chatView.enableChatInteraction(isLive && isLoggedIn)
            viewModel.chatMessages.observe(viewLifecycleOwner, Observer(chatView::submitList))
            viewModel.newMessage.observe(viewLifecycleOwner) { chatView.notifyMessageAdded() }
            viewModel.recentMessages.observe(viewLifecycleOwner) { chatView.addRecentMessages(it) }
            viewModel.globalBadges.observe(viewLifecycleOwner, Observer(chatView::addGlobalBadges))
            viewModel.channelBadges.observe(viewLifecycleOwner, Observer(chatView::addChannelBadges))
            viewModel.otherEmotes.observe(viewLifecycleOwner, Observer(chatView::addEmotes))
            viewModel.cheerEmotes.observe(viewLifecycleOwner, Observer(chatView::addCheerEmotes))
            viewModel.reloadMessages.observe(viewLifecycleOwner) { chatView.notifyEmotesLoaded() }
            viewModel.roomState.observe(viewLifecycleOwner) { chatView.notifyRoomState(it) }
            viewModel.command.observe(viewLifecycleOwner) { chatView.notifyCommand(it) }
            viewModel.reward.observe(viewLifecycleOwner) { chatView.notifyReward(it) }
            viewModel.pointsEarned.observe(viewLifecycleOwner) { chatView.notifyPointsEarned(it) }
            viewModel.raid.observe(viewLifecycleOwner) { onRaidUpdate(it) }
            viewModel.raidClicked.observe(viewLifecycleOwner) { onRaidClicked() }
            viewModel.viewerCount.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateViewerCount(it) }
        }
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
        val gqlClientId = requireContext().prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
        val emoteQuality =  requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs =  requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)
        viewModel.reloadEmotes(helixClientId, helixToken, gqlClientId, channelId, channelLogin, emoteQuality, animateGifs)
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
                chatView.hideRaid()
            } else {
                viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
                viewModel.raidClosed = false
            }
        } else {
            if (!viewModel.raidClosed) {
                chatView.notifyRaid(raid, viewModel.raidNewId)
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
        chatView.hideKeyboard()
        chatView.clearFocus()
    }

    fun hideEmotesMenu() = chatView.hideEmotesMenu()

    fun appendEmote(emote: Emote) {
        chatView.appendEmote(emote)
    }

    override fun onReplyClicked(userName: String) {
        chatView.reply(userName)
    }

    override fun onCopyMessageClicked(message: String) {
        chatView.setMessage(message)
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelLogo: String?) {
        (requireActivity() as MainActivity).viewChannel(id, login, name, channelLogo)
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