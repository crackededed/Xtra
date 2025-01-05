package com.github.andreyasadchy.xtra.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChannelPoll
import com.github.andreyasadchy.xtra.model.chat.ChannelPrediction
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.main.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LifecycleListener
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), LifecycleListener, MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
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
            if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
                val args = requireArguments()
                val channelId = args.getString(KEY_CHANNEL_ID)
                val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
                val isLive = args.getBoolean(KEY_IS_LIVE)
                val accountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
                val isLoggedIn = !accountLogin.isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                val chatUrl = args.getString(KEY_CHAT_URL)
                val enableChat: Boolean
                if (isLive) {
                    chatView.init(
                        fragment = this@ChatFragment,
                        channelId = channelId,
                        namePaints = viewModel.namePaints,
                        paintUsers = viewModel.paintUsers,
                        stvBadges = viewModel.stvBadges,
                        stvBadgeUsers = viewModel.stvBadgeUsers,
                        personalEmoteSets = viewModel.personalEmoteSets,
                        personalEmoteSetUsers = viewModel.personalEmoteSetUsers
                    )
                    val helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                    val gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true)
                    val accountId = requireContext().tokenPrefs().getString(C.USER_ID, null)
                    val useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true)
                    val useApiChatMessages = requireContext().prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, false)
                    val checkIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                            requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    chatView.setCallback(object : ChatView.ChatViewCallback {
                        override fun send(message: CharSequence, replyId: String?) {
                            viewModel.send(message, replyId, helixHeaders, gqlHeaders, accountId, channelId, channelLogin, useApiCommands, useApiChatMessages, checkIntegrity)
                        }

                        override fun onRaidClicked(raid: Raid) {
                            viewModel.raidClicked.value = raid
                        }

                        override fun onRaidClose() {
                            viewModel.raidClosed = true
                        }

                        override fun onPollClose(isManual: Boolean) {
                            viewModel.closedPollId = if (isManual) viewModel.poll.value?.pollId else null
                            viewModel.poll.value = null
                        }

                        override fun onPredictionClose(isManual: Boolean) {
                            viewModel.closedPredictionId = if (isManual) viewModel.prediction.value?.id else null
                            viewModel.prediction.value = null
                        }
                    })
                    if (isLoggedIn) {
                        chatView.setUsername(accountLogin)
                        chatView.addToAutoCompleteList(viewModel.chatters.values)
                        viewModel.loadRecentEmotes()
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.hasRecentEmotes.collectLatest {
                                    if (it) {
                                        chatView.setRecentEmotes()
                                    }
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.userEmotes.collectLatest {
                                    chatView.addToAutoCompleteList(it)
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.newChatter.collectLatest {
                                    if (it != null) {
                                        chatView.addToAutoCompleteList(listOf(it))
                                        viewModel.newChatter.value = null
                                    }
                                }
                            }
                        }
                    }
                    enableChat = true
                } else {
                    if (chatUrl != null || (args.getString(KEY_VIDEO_ID) != null && !args.getBoolean(KEY_START_TIME_EMPTY))) {
                        chatView.init(
                            fragment = this@ChatFragment,
                            channelId = channelId,
                            getEmoteBytes = viewModel::getEmoteBytes,
                            chatUrl = chatUrl
                        )
                        enableChat = true
                    } else {
                        requireView().findViewById<TextView>(R.id.chatReplayUnavailable)?.visible()
                        enableChat = false
                    }
                }
                if (enableChat) {
                    chatView.enableChatInteraction(isLive && isLoggedIn)
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.chatMessages.collect {
                                chatView.submitList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newMessage.collect {
                                if (it != null) {
                                    chatView.notifyMessageAdded(it)
                                    viewModel.newMessage.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.localTwitchEmotes.collectLatest {
                                chatView.addLocalTwitchEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalStvEmotes.collectLatest {
                                chatView.addGlobalStvEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelStvEmotes.collectLatest {
                                chatView.addChannelStvEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalBttvEmotes.collectLatest {
                                chatView.addGlobalBttvEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelBttvEmotes.collectLatest {
                                chatView.addChannelBttvEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalFfzEmotes.collectLatest {
                                chatView.addGlobalFfzEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelFfzEmotes.collectLatest {
                                chatView.addChannelFfzEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalBadges.collectLatest {
                                chatView.addGlobalBadges(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelBadges.collectLatest {
                                chatView.addChannelBadges(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.cheerEmotes.collectLatest {
                                chatView.addCheerEmotes(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.roomState.collectLatest {
                                if (it != null) {
                                    chatView.notifyRoomState(it)
                                    viewModel.roomState.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.reloadMessages.collectLatest {
                                if (it) {
                                    chatView.notifyEmotesLoaded()
                                    viewModel.reloadMessages.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.scrollDown.collectLatest {
                                if (it) {
                                    chatView.scrollToLastPosition()
                                    viewModel.scrollDown.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hideRaid.collectLatest {
                                if (it) {
                                    chatView.hideRaid()
                                    viewModel.hideRaid.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raid.collectLatest {
                                if (it != null) {
                                    onRaidUpdate(it)
                                    viewModel.raid.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raidClicked.collectLatest {
                                if (it != null) {
                                    onRaidClicked(it)
                                    viewModel.raidClicked.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.poll.collectLatest {
                                if (it != null) {
                                    onPollUpdate(it)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.prediction.collectLatest {
                                if (it != null) {
                                    onPredictionUpdate(it)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.playbackMessage.collectLatest {
                                if (it != null) {
                                    if (it.live != null) {
                                        (parentFragment as? StreamPlayerFragment)?.updateLiveStatus(it.live, it.serverTime, channelLogin)
                                    }
                                    (parentFragment as? StreamPlayerFragment)?.updateViewerCount(it.viewers)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.streamInfo.collectLatest {
                                if (it != null) {
                                    (parentFragment as? StreamPlayerFragment)?.updateStreamInfo(it.title, it.gameId, null, it.gameName)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPaint.collectLatest {
                                if (it != null) {
                                    chatView.addPaint(it)
                                    viewModel.newPaint.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPaintUser.collectLatest {
                                if (it != null) {
                                    chatView.addPaintUser(it)
                                    viewModel.newPaintUser.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newStvBadge.collectLatest {
                                if (it != null) {
                                    chatView.addStvBadge(it)
                                    viewModel.newStvBadge.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newStvBadgeUser.collectLatest {
                                if (it != null) {
                                    chatView.addStvBadgeUser(it)
                                    viewModel.newStvBadgeUser.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPersonalEmoteSet.collectLatest {
                                if (it != null) {
                                    chatView.addPersonalEmoteSet(it)
                                    viewModel.newPersonalEmoteSet.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPersonalEmoteSetUser.collectLatest {
                                if (it != null) {
                                    chatView.addPersonalEmoteSetUser(it)
                                    viewModel.newPersonalEmoteSetUser.value = null
                                }
                            }
                        }
                    }
                    if (chatUrl != null) {
                        viewModel.startReplay(
                            channelId = channelId,
                            channelLogin = channelLogin,
                            chatUrl = chatUrl,
                            getCurrentPosition = (parentFragment as BasePlayerFragment)::getCurrentPosition,
                            getCurrentSpeed = (parentFragment as BasePlayerFragment)::getCurrentSpeed
                        )
                    }
                }
            }
        }
    }

    override fun initialize() {
        if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.startLive(channelId, channelLogin, args.getString(KEY_CHANNEL_NAME), args.getString(KEY_STREAM_ID))
            } else {
                val videoId = args.getString(KEY_VIDEO_ID)
                if (videoId != null && !args.getBoolean(KEY_START_TIME_EMPTY)) {
                    viewModel.startReplay(
                        channelId = channelId,
                        channelLogin = channelLogin,
                        videoId = videoId,
                        startTime = args.getInt(KEY_START_TIME),
                        getCurrentPosition = (parentFragment as BasePlayerFragment)::getCurrentPosition,
                        getCurrentSpeed = (parentFragment as BasePlayerFragment)::getCurrentSpeed
                    )
                }
            }
        }
    }

    fun isActive(): Boolean? {
        return viewModel.isActive()
    }

    fun disconnect() {
        viewModel.disconnect()
    }

    fun reconnect() {
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        if (channelLogin != null) {
            viewModel.startLiveChat(requireArguments().getString(KEY_CHANNEL_ID), channelLogin)
            if (requireContext().prefs().getBoolean(C.CHAT_RECENT, true)) {
                viewModel.loadRecentMessages(channelLogin)
            }
        }
    }

    fun reloadEmotes() {
        viewModel.reloadEmotes(
            requireArguments().getString(KEY_CHANNEL_ID),
            requireArguments().getString(KEY_CHANNEL_LOGIN)
        )
    }

    fun updatePosition(position: Long) {
        viewModel.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        viewModel.updateSpeed(speed)
    }

    fun updateStreamId(id: String?) {
        viewModel.streamId = id
    }

    private fun onRaidUpdate(raid: Raid) {
        if (!viewModel.raidClosed) {
            if (raid.openStream) {
                if (requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true) && parentFragment is BasePlayerFragment) {
                    onRaidClicked(raid)
                }
                binding.chatView.hideRaid()
            } else {
                binding.chatView.notifyRaid(raid)
            }
        }
    }

    private fun onPollUpdate(poll: ChannelPoll) {
        if (viewModel.closedPollId != poll.pollId) {
            binding.chatView.notifyPoll(poll)
        }
    }

    private fun onPredictionUpdate(prediction: ChannelPrediction) {
        if (viewModel.closedPredictionId != prediction.id) {
            binding.chatView.notifyPrediction(prediction)
        }
    }

    private fun onRaidClicked(raid: Raid) {
        (requireActivity() as MainActivity).startStream(
            Stream(
                channelId = raid.targetId,
                channelLogin = raid.targetLogin,
                channelName = raid.targetName,
                profileImageUrl = raid.targetProfileImage,
            )
        )
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter {
        return binding.chatView.createMessageClickedChatAdapter(viewModel.chatMessages.value.toList())
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter {
        return binding.chatView.createReplyClickedChatAdapter(viewModel.chatMessages.value.toList())
    }

    fun emoteMenuIsVisible() = binding.chatView.emoteMenuIsVisible()

    fun toggleEmoteMenu(enable: Boolean) = binding.chatView.toggleEmoteMenu(enable)

    fun toggleBackPressedCallback(enable: Boolean) = binding.chatView.toggleBackPressedCallback(enable)

    fun appendEmote(emote: Emote) {
        binding.chatView.appendEmote(emote)
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        val replyMessage = message?.let {
            val name = if (userName != null && userLogin != null && !userLogin.equals(userName, true)) {
                when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                    "0" -> "${userName}(${userLogin})"
                    "1" -> userName
                    else -> userLogin
                }
            } else {
                userName ?: userLogin
            }
            requireContext().getString(R.string.replying_to_message, name, message)
        }
        binding.chatView.reply(replyId, replyMessage)
    }

    override fun onCopyMessageClicked(message: String) {
        binding.chatView.setMessage(message)
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelLogo: String?) {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = id,
                channelLogin = login,
                channelName = name,
                channelLogo = channelLogo
            )
        )
        (parentFragment as? BasePlayerFragment)?.minimize()
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.resumeLive(channelId, channelLogin)
            } else {
                viewModel.resumeReplay(
                    channelId = channelId,
                    channelLogin = channelLogin,
                    chatUrl = args.getString(KEY_CHAT_URL),
                    videoId = args.getString(KEY_VIDEO_ID),
                    startTime = args.getInt(KEY_START_TIME),
                    getCurrentPosition = (parentFragment as BasePlayerFragment)::getCurrentPosition,
                    getCurrentSpeed = (parentFragment as BasePlayerFragment)::getCurrentSpeed
                )
            }
        }
    }

    override fun onMovedToBackground() {
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)) {
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
        }
    }

    override fun onMovedToForeground() {
        val args = requireArguments()
        val isLive = args.getBoolean(KEY_IS_LIVE)
        if (!isLive || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)) {
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (isLive) {
                viewModel.resumeLive(channelId, channelLogin)
            } else {
                viewModel.resumeReplay(
                    channelId = channelId,
                    channelLogin = channelLogin,
                    chatUrl = args.getString(KEY_CHAT_URL),
                    videoId = args.getString(KEY_VIDEO_ID),
                    startTime = args.getInt(KEY_START_TIME),
                    getCurrentPosition = (parentFragment as BasePlayerFragment)::getCurrentPosition,
                    getCurrentSpeed = (parentFragment as BasePlayerFragment)::getCurrentSpeed
                )
            }
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
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_START_TIME_EMPTY = "startTime_empty"
        private const val KEY_START_TIME = "startTime"

        fun newInstance(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, true)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CHANNEL_NAME, channelName)
                    putString(KEY_STREAM_ID, streamId)
                }
            }
        }

        fun newInstance(channelId: String?, channelLogin: String?, videoId: String?, startTime: Int?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, false)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_VIDEO_ID, videoId)
                    if (startTime != null) {
                        putBoolean(KEY_START_TIME_EMPTY, false)
                        putInt(KEY_START_TIME, startTime)
                    } else {
                        putBoolean(KEY_START_TIME_EMPTY, true)
                    }
                }
            }
        }

        fun newLocalInstance(channelId: String?, channelLogin: String?, chatUrl: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CHAT_URL, chatUrl)
                }
            }
        }
    }
}