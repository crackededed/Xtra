package com.github.andreyasadchy.xtra.ui.chat

import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.view.AutoCompleteAdapter
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.hideKeyboard
import com.github.andreyasadchy.xtra.util.isLightTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var adapter: ChatAdapter

    private var isChatTouched = false
    private var showChatStatus = false
    private var hasRecentEmotes = false
    private var messagingEnabled = false

    private var autoCompleteList = mutableListOf<Any?>()
    private var autoCompleteAdapter: AutoCompleteAdapter<Any>? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            toggleEmoteMenu(false)
        }
    }

    private val messageDialog: MessageClickedDialog?
        get() = childFragmentManager.findFragmentByTag("messageDialog") as? MessageClickedDialog

    private val replyDialog: ReplyClickedDialog?
        get() = childFragmentManager.findFragmentByTag("replyDialog") as? ReplyClickedDialog

    private var languageIdentifier: LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()

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
                if (isLive || (args.getString(KEY_VIDEO_ID) != null && args.getInt(KEY_START_TIME) != -1) || chatUrl != null) {
                    val sizeModifier = (requireContext().prefs().getInt(C.CHAT_SIZE_MODIFIER, 100).toFloat() / 100f)
                    adapter = ChatAdapter(
                        enableTimestamps = requireContext().prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                        timestampFormat = requireContext().prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                        firstMsgVisibility = requireContext().prefs().getString(C.CHAT_FIRSTMSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                        firstChatMsg = requireContext().getString(R.string.chat_first),
                        redeemedChatMsg = requireContext().getString(R.string.redeemed),
                        redeemedNoMsg = requireContext().getString(R.string.user_redeemed),
                        rewardChatMsg = requireContext().getString(R.string.chat_reward),
                        replyMessage = requireContext().getString(R.string.replying_to_message),
                        useRandomColors = requireContext().prefs().getBoolean(C.CHAT_RANDOMCOLOR, true),
                        useReadableColors = requireContext().prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                        isLightTheme = requireContext().isLightTheme,
                        nameDisplay = requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0"),
                        useBoldNames = requireContext().prefs().getBoolean(C.CHAT_BOLDNAMES, false),
                        showNamePaints = requireContext().prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                        namePaintsList = viewModel.namePaints,
                        paintUsersMap = viewModel.paintUsers,
                        showStvBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                        stvBadgesList = viewModel.stvBadges,
                        stvBadgeUsersMap = viewModel.stvBadgeUsers,
                        showPersonalEmotes = requireContext().prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                        personalEmoteSetsMap = viewModel.personalEmoteSets,
                        personalEmoteSetUsersMap = viewModel.personalEmoteSetUsers,
                        showSystemMessageEmotes = requireContext().prefs().getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                        chatUrl = chatUrl,
                        getEmoteBytes = viewModel::getEmoteBytes,
                        fragment = this@ChatFragment,
                        backgroundColor = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurface),
                        dialogBackgroundColor = MaterialColors.getColor(
                            requireView(),
                            if (requireContext().prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
                                com.google.android.material.R.attr.colorSurfaceContainerLow
                            } else {
                                com.google.android.material.R.attr.colorSurface
                            }
                        ),
                        imageLibrary = requireContext().prefs().getString(C.CHAT_IMAGE_LIBRARY, "0"),
                        messageTextSize = (requireContext().prefs().getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * sizeModifier,
                        emoteSize = requireContext().convertDpToPixels((requireContext().prefs().getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier),
                        badgeSize = requireContext().convertDpToPixels((requireContext().prefs().getString(C.CHAT_BADGE_SIZE, "18.5")?.toFloatOrNull() ?: 18.5f) * sizeModifier),
                        emoteQuality = requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                        animateGifs = requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true),
                        enableOverlayEmotes = requireContext().prefs().getBoolean(C.CHAT_ZEROWIDTH, true),
                        translateMessage = this@ChatFragment::onTranslateMessageClicked,
                        showLanguageDownloadDialog = this@ChatFragment::showLanguageDownloadDialog,
                        channelId = channelId,
                    )
                    recyclerView.let {
                        it.adapter = adapter
                        it.itemAnimator = null
                        it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                isChatTouched = newState == RecyclerView.SCROLL_STATE_DRAGGING
                                val offset = recyclerView.computeVerticalScrollOffset()
                                if (offset < 0) {
                                    btnDown.isVisible = false
                                } else {
                                    val extent = recyclerView.computeVerticalScrollExtent()
                                    val range = recyclerView.computeVerticalScrollRange()
                                    val percentage = (100f * offset / (range - extent).toFloat())
                                    btnDown.isVisible = percentage < 100f
                                }
                                if (showChatStatus && chatStatus.isGone) {
                                    chatStatus.visible()
                                    chatStatus.postDelayed({ chatStatus.gone() }, 5000)
                                }
                            }
                        })
                    }
                    btnDown.setOnClickListener {
                        view.post {
                            adapter.messages?.let { recyclerView.scrollToPosition(it.lastIndex) }
                            it.gone()
                        }
                    }
                    val enableMessaging = isLive && isLoggedIn
                    adapter.messageClickListener = { channelId ->
                        editText.hideKeyboard()
                        editText.clearFocus()
                        MessageClickedDialog.newInstance(enableMessaging, channelId).show(this@ChatFragment.childFragmentManager, "messageDialog")
                    }
                    adapter.replyClickListener = {
                        editText.hideKeyboard()
                        editText.clearFocus()
                        ReplyClickedDialog.newInstance(enableMessaging).show(this@ChatFragment.childFragmentManager, "replyDialog")
                    }
                    adapter.imageClickListener = { url, name, source, format, isAnimated, thirdParty, emoteId ->
                        editText.hideKeyboard()
                        editText.clearFocus()
                        ImageClickedDialog.newInstance(url, name, source, format, isAnimated, thirdParty, emoteId).show(this@ChatFragment.childFragmentManager, "imageDialog")
                    }
                    if (enableMessaging) {
                        adapter.loggedInUser = accountLogin
                        messageDialog?.adapter?.loggedInUser = accountLogin
                        replyDialog?.adapter?.loggedInUser = accountLogin
                        addToAutoCompleteList(viewModel.chatters.values)
                        viewModel.loadRecentEmotes()
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.hasRecentEmotes.collectLatest {
                                    if (it) {
                                        hasRecentEmotes = true
                                    }
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.userEmotes.collectLatest {
                                    addToAutoCompleteList(it)
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.newChatter.collectLatest {
                                    if (it != null) {
                                        addToAutoCompleteList(listOf(it))
                                        viewModel.newChatter.value = null
                                    }
                                }
                            }
                        }
                        autoCompleteAdapter = AutoCompleteAdapter<Any>(
                            requireContext(),
                            R.layout.auto_complete_emotes_list_item,
                            R.id.name,
                            autoCompleteList,
                        ).apply {
                            setNotifyOnChange(false)
                            editText.setAdapter(this)

                            var previousSize = 0
                            editText.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus && count != previousSize) {
                                    previousSize = count
                                    notifyDataSetChanged()
                                }
                                setNotifyOnChange(hasFocus)
                            }
                        }
                        editText.addTextChangedListener(onTextChanged = { text, _, _, _ ->
                            if (text?.isNotBlank() == true) {
                                send.visible()
                                clear.visible()
                            } else {
                                send.gone()
                                clear.gone()
                            }
                        })
                        editText.setTokenizer(SpaceTokenizer())
                        editText.setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                                sendMessage()
                            } else {
                                false
                            }
                        }
                        clear.setOnClickListener {
                            val text = editText.text.toString().trimEnd()
                            editText.setText(text.substring(0, max(text.lastIndexOf(' '), 0)))
                            editText.setSelection(editText.length())
                        }
                        clear.setOnLongClickListener {
                            editText.text.clear()
                            true
                        }
                        replyView.gone()
                        send.setOnClickListener { sendMessage() }
                        if ((view.parent?.parent?.parent as? View)?.id == R.id.slidingLayout && !requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            messageView.gone()
                        } else {
                            messageView.visible()
                        }
                        viewPager.adapter = object : FragmentStateAdapter(this@ChatFragment) {
                            override fun getItemCount(): Int = 3

                            override fun createFragment(position: Int): Fragment {
                                return EmotesFragment.newInstance(position)
                            }
                        }
                        viewPager.offscreenPageLimit = 2
                        viewPager.reduceDragSensitivity()
                        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                            tab.text = when (position) {
                                0 -> requireContext().getString(R.string.recent_emotes)
                                1 -> "Twitch"
                                else -> "7TV/BTTV/FFZ"
                            }
                        }.attach()
                        emotes.setOnClickListener {
                            //TODO add animation
                            if (emoteMenu.isGone) {
                                if (!hasRecentEmotes && viewPager.currentItem == 0) {
                                    viewPager.setCurrentItem(1, false)
                                }
                                toggleEmoteMenu(true)
                            } else {
                                toggleEmoteMenu(false)
                            }
                        }
                        messagingEnabled = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.chatMessages.collect { list ->
                                adapter.messages = list
                                messageDialog?.adapter?.let { adapter ->
                                    if (!adapter.userId.isNullOrBlank() || !adapter.userLogin.isNullOrBlank()) {
                                        adapter.messages = list.filter {
                                            (!adapter.userId.isNullOrBlank() && it.userId == adapter.userId) || (!adapter.userLogin.isNullOrBlank() && it.userLogin == adapter.userLogin)
                                        }.toMutableList()
                                    }
                                }
                                replyDialog?.adapter?.let { adapter ->
                                    if (!adapter.threadParentId.isNullOrBlank()) {
                                        adapter.messages = list.filter {
                                            it.reply?.threadParentId == adapter.threadParentId || it.id == adapter.threadParentId
                                        }.toMutableList()
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newMessage.collect { newMessage ->
                                if (newMessage != null) {
                                    adapter.messages?.apply {
                                        adapter.notifyItemInserted(lastIndex)
                                        val messageLimit = requireContext().prefs().getInt(C.CHAT_LIMIT, 600)
                                        if (size >= (messageLimit + 1)) {
                                            val removeCount = size - messageLimit
                                            repeat(removeCount) {
                                                removeAt(0)
                                            }
                                            adapter.notifyItemRangeRemoved(0, removeCount)
                                        }
                                        if (!isChatTouched && btnDown.isGone) {
                                            recyclerView.scrollToPosition(lastIndex)
                                        }
                                    }
                                    messageDialog?.adapter?.let { adapter ->
                                        if ((!adapter.userId.isNullOrBlank() && newMessage.userId == adapter.userId) || (!adapter.userLogin.isNullOrBlank() && newMessage.userLogin == adapter.userLogin)) {
                                            adapter.messages?.apply {
                                                add(newMessage)
                                                adapter.notifyItemInserted(lastIndex)
                                                val messageLimit = requireContext().prefs().getInt(C.CHAT_LIMIT, 600)
                                                if (size >= (messageLimit + 1)) {
                                                    val removeCount = size - messageLimit
                                                    repeat(removeCount) {
                                                        removeAt(0)
                                                    }
                                                    adapter.notifyItemRangeRemoved(0, removeCount)
                                                }
                                                messageDialog?.scrollToLastPosition()
                                            }
                                        }
                                    }
                                    replyDialog?.adapter?.let { adapter ->
                                        if (!adapter.threadParentId.isNullOrBlank() && newMessage.reply?.threadParentId == adapter.threadParentId) {
                                            adapter.messages?.apply {
                                                add(newMessage)
                                                adapter.notifyItemInserted(lastIndex)
                                                val messageLimit = requireContext().prefs().getInt(C.CHAT_LIMIT, 600)
                                                if (size >= (messageLimit + 1)) {
                                                    val removeCount = size - messageLimit
                                                    repeat(removeCount) {
                                                        removeAt(0)
                                                    }
                                                    adapter.notifyItemRangeRemoved(0, removeCount)
                                                }
                                                replyDialog?.scrollToLastPosition()
                                            }
                                        }
                                    }
                                    viewModel.newMessage.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.localTwitchEmotes.collectLatest {
                                adapter.localTwitchEmotes = it
                                messageDialog?.adapter?.localTwitchEmotes = it
                                replyDialog?.adapter?.localTwitchEmotes = it
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalStvEmotes.collectLatest {
                                adapter.globalStvEmotes = it
                                messageDialog?.adapter?.globalStvEmotes = it
                                replyDialog?.adapter?.globalStvEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelStvEmotes.collectLatest {
                                adapter.channelStvEmotes = it
                                messageDialog?.adapter?.channelStvEmotes = it
                                replyDialog?.adapter?.channelStvEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalBttvEmotes.collectLatest {
                                adapter.globalBttvEmotes = it
                                messageDialog?.adapter?.globalBttvEmotes = it
                                replyDialog?.adapter?.globalBttvEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelBttvEmotes.collectLatest {
                                adapter.channelBttvEmotes = it
                                messageDialog?.adapter?.channelBttvEmotes = it
                                replyDialog?.adapter?.channelBttvEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalFfzEmotes.collectLatest {
                                adapter.globalFfzEmotes = it
                                messageDialog?.adapter?.globalFfzEmotes = it
                                replyDialog?.adapter?.globalFfzEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelFfzEmotes.collectLatest {
                                adapter.channelFfzEmotes = it
                                messageDialog?.adapter?.channelFfzEmotes = it
                                replyDialog?.adapter?.channelFfzEmotes = it
                                addToAutoCompleteList(it)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.globalBadges.collectLatest {
                                adapter.globalBadges = it
                                messageDialog?.adapter?.globalBadges = it
                                replyDialog?.adapter?.globalBadges = it
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.channelBadges.collectLatest {
                                adapter.channelBadges = it
                                messageDialog?.adapter?.channelBadges = it
                                replyDialog?.adapter?.channelBadges = it
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.cheerEmotes.collectLatest {
                                adapter.cheerEmotes = it
                                messageDialog?.adapter?.cheerEmotes = it
                                replyDialog?.adapter?.cheerEmotes = it
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.roomState.collectLatest { roomState ->
                                if (roomState != null) {
                                    when (roomState.emote) {
                                        "0" -> textEmote.gone()
                                        "1" -> textEmote.visible()
                                    }
                                    if (roomState.followers != null) {
                                        when (roomState.followers) {
                                            "-1" -> textFollowers.gone()
                                            "0" -> {
                                                textFollowers.text = requireContext().getString(R.string.room_followers)
                                                textFollowers.visible()
                                            }
                                            else -> {
                                                textFollowers.text = requireContext().getString(
                                                    R.string.room_followers_min,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), (roomState.followers.toInt() * 60).toString())
                                                )
                                                textFollowers.visible()
                                            }
                                        }
                                    }
                                    when (roomState.unique) {
                                        "0" -> textUnique.gone()
                                        "1" -> textUnique.visible()
                                    }
                                    if (roomState.slow != null) {
                                        when (roomState.slow) {
                                            "0" -> textSlow.gone()
                                            else -> {
                                                textSlow.text = requireContext().getString(
                                                    R.string.room_slow,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), roomState.slow)
                                                )
                                                textSlow.visible()
                                            }
                                        }
                                    }
                                    when (roomState.subs) {
                                        "0" -> textSubs.gone()
                                        "1" -> textSubs.visible()
                                    }
                                    if (textEmote.isGone && textFollowers.isGone && textUnique.isGone && textSlow.isGone && textSubs.isGone) {
                                        showChatStatus = false
                                        chatStatus.gone()
                                    } else {
                                        showChatStatus = true
                                        chatStatus.visible()
                                        chatStatus.postDelayed({ chatStatus.gone() }, 5000)
                                    }
                                    viewModel.roomState.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.reloadMessages.collectLatest {
                                if (it) {
                                    adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) }
                                    messageDialog?.adapter?.let { adapter -> adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) } }
                                    replyDialog?.adapter?.let { adapter -> adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) } }
                                    viewModel.reloadMessages.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.scrollDown.collectLatest {
                                if (it) {
                                    adapter.messages?.let { recyclerView.scrollToPosition(it.lastIndex) }
                                    viewModel.scrollDown.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hideRaid.collectLatest {
                                if (it) {
                                    raidLayout.gone()
                                    viewModel.raidClosed = true
                                    viewModel.hideRaid.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raid.collectLatest { raid ->
                                if (raid != null) {
                                    if (!viewModel.raidClosed) {
                                        if (raid.openStream) {
                                            if (requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true) && parentFragment is PlayerFragment) {
                                                (requireActivity() as? MainActivity)?.startStream(
                                                    Stream(
                                                        channelId = raid.targetId,
                                                        channelLogin = raid.targetLogin,
                                                        channelName = raid.targetName,
                                                        profileImageUrl = raid.targetProfileImage,
                                                    )
                                                )
                                            }
                                            raidLayout.gone()
                                            viewModel.raidClosed = true
                                        } else {
                                            raidLayout.visible()
                                            raidLayout.setOnClickListener { viewModel.raidClicked.value = raid }
                                            this@ChatFragment.requireContext().imageLoader.enqueue(
                                                ImageRequest.Builder(this@ChatFragment.requireContext()).apply {
                                                    data(raid.targetLogo)
                                                    if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                                        transformations(CircleCropTransformation())
                                                    }
                                                    crossfade(true)
                                                    target(raidImage)
                                                }.build()
                                            )
                                            raidClose.setOnClickListener {
                                                raidLayout.gone()
                                                viewModel.raidClosed = true
                                            }
                                            raidText.text = requireContext().getString(
                                                R.string.raid_text,
                                                if (raid.targetLogin != null && !raid.targetLogin.equals(raid.targetName, true)) {
                                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                        "0" -> "${raid.targetName}(${raid.targetLogin})"
                                                        "1" -> raid.targetName
                                                        else -> raid.targetLogin
                                                    }
                                                } else {
                                                    raid.targetName
                                                },
                                                raid.viewerCount
                                            )
                                        }
                                    }
                                    viewModel.raid.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raidClicked.collectLatest {
                                if (it != null) {
                                    (requireActivity() as? MainActivity)?.startStream(
                                        Stream(
                                            channelId = it.targetId,
                                            channelLogin = it.targetLogin,
                                            channelName = it.targetName,
                                            profileImageUrl = it.targetProfileImage,
                                        )
                                    )
                                    viewModel.raidClicked.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hidePoll.collectLatest {
                                if (it) {
                                    pollLayout.gone()
                                    viewModel.pollSecondsLeft.value = null
                                    viewModel.pollTimer?.cancel()
                                    viewModel.pollClosed = true
                                    viewModel.hidePoll.value = false
                                }
                            }
                        }
                    }
                    pollClose.setOnClickListener {
                        pollLayout.gone()
                        viewModel.pollSecondsLeft.value = null
                        viewModel.pollTimer?.cancel()
                        viewModel.pollClosed = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.poll.collectLatest { poll ->
                                if (poll != null) {
                                    if (!viewModel.pollClosed) {
                                        when (poll.status) {
                                            "ACTIVE" -> {
                                                pollLayout.visible()
                                                pollTitle.text = requireContext().getString(R.string.poll_title, poll.title)
                                                pollChoices.text = poll.choices?.map {
                                                    requireContext().getString(
                                                        R.string.poll_choice,
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes,
                                                        it.title
                                                    )
                                                }?.joinToString("\n")
                                                pollStatus.visible()
                                            }
                                            "COMPLETED", "TERMINATED" -> {
                                                pollLayout.visible()
                                                pollTitle.text = requireContext().getString(R.string.poll_title, poll.title)
                                                val winningTotal = poll.choices?.maxOfOrNull { it.totalVotes ?: 0 } ?: 0
                                                pollChoices.text = poll.choices?.map {
                                                    requireContext().getString(
                                                        if (winningTotal == it.totalVotes) {
                                                            R.string.poll_choice_winner
                                                        } else {
                                                            R.string.poll_choice
                                                        },
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes,
                                                        it.title
                                                    )
                                                }?.joinToString("\n")
                                                pollStatus.gone()
                                                viewModel.pollSecondsLeft.value = null
                                                viewModel.pollTimer?.cancel()
                                                viewModel.startPollTimeout { pollLayout.gone() }
                                            }
                                            else -> {
                                                pollLayout.gone()
                                                viewModel.pollSecondsLeft.value = null
                                                viewModel.pollTimer?.cancel()
                                                viewModel.pollClosed = true
                                            }
                                        }
                                    }
                                    viewModel.poll.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.pollSecondsLeft.collectLatest {
                                if (it != null) {
                                    pollStatus.text = requireContext().getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
                                    if (it <= 0) {
                                        viewModel.pollSecondsLeft.value = null
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hidePrediction.collectLatest {
                                if (it) {
                                    predictionLayout.gone()
                                    viewModel.predictionSecondsLeft.value = null
                                    viewModel.predictionTimer?.cancel()
                                    viewModel.predictionClosed = true
                                    viewModel.hidePrediction.value = false
                                }
                            }
                        }
                    }
                    predictionClose.setOnClickListener {
                        predictionLayout.gone()
                        viewModel.predictionSecondsLeft.value = null
                        viewModel.predictionTimer?.cancel()
                        viewModel.predictionClosed = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.prediction.collectLatest { prediction ->
                                if (prediction != null) {
                                    if (!viewModel.predictionClosed) {
                                        when (prediction.status) {
                                            "ACTIVE" -> {
                                                predictionLayout.visible()
                                                predictionTitle.text = requireContext().getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.map {
                                                    requireContext().getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints,
                                                        it.totalUsers,
                                                        it.title
                                                    )
                                                }?.joinToString("\n")
                                                predictionStatus.visible()
                                            }
                                            "LOCKED" -> {
                                                predictionLayout.visible()
                                                predictionTitle.text = requireContext().getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.map {
                                                    requireContext().getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints,
                                                        it.totalUsers,
                                                        it.title
                                                    )
                                                }?.joinToString("\n")
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.gone() }
                                                predictionStatus.visible()
                                                predictionStatus.text = requireContext().getString(R.string.prediction_locked)
                                            }
                                            "CANCELED", "CANCEL_PENDING", "RESOLVED", "RESOLVE_PENDING" -> {
                                                predictionLayout.visible()
                                                predictionTitle.text = requireContext().getString(R.string.prediction_title, prediction.title)
                                                val resolved = prediction.status == "RESOLVED" || prediction.status == "RESOLVE_PENDING"
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.map {
                                                    requireContext().getString(
                                                        if (resolved && prediction.winningOutcomeId != null && prediction.winningOutcomeId == it.id) {
                                                            R.string.prediction_outcome_winner
                                                        } else {
                                                            R.string.prediction_outcome
                                                        },
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints,
                                                        it.totalUsers,
                                                        it.title
                                                    )
                                                }?.joinToString("\n")
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.gone() }
                                                if (resolved) {
                                                    predictionStatus.gone()
                                                } else {
                                                    predictionStatus.visible()
                                                    predictionStatus.text = requireContext().getString(R.string.prediction_refunded)
                                                }
                                            }
                                            else -> {
                                                predictionLayout.gone()
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.predictionClosed = true
                                            }
                                        }
                                    }
                                    viewModel.prediction.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.predictionSecondsLeft.collectLatest {
                                if (it != null) {
                                    predictionStatus.text = requireContext().getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
                                    if (it <= 0) {
                                        viewModel.predictionSecondsLeft.value = null
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.playbackMessage.collectLatest {
                                if (it != null) {
                                    if (it.live != null) {
                                        (parentFragment as? PlayerFragment)?.updateLiveStatus(it.live, it.serverTime, channelLogin)
                                    }
                                    (parentFragment as? PlayerFragment)?.updateViewerCount(it.viewers)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.streamInfo.collectLatest {
                                if (it != null) {
                                    (parentFragment as? PlayerFragment)?.updateStreamInfo(it.title, it.gameId, null, it.gameName)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPaint.collectLatest { paint ->
                                if (paint != null) {
                                    adapter.namePaints?.let { namePaints ->
                                        namePaints.find { it.id == paint.id }?.let { namePaints.remove(it) }
                                        namePaints.add(paint)
                                    }
                                    messageDialog?.adapter?.namePaints?.let { namePaints ->
                                        namePaints.find { it.id == paint.id }?.let { namePaints.remove(it) }
                                        namePaints.add(paint)
                                    }
                                    replyDialog?.adapter?.namePaints?.let { namePaints ->
                                        namePaints.find { it.id == paint.id }?.let { namePaints.remove(it) }
                                        namePaints.add(paint)
                                    }
                                    viewModel.newPaint.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPaintUser.collectLatest { pair ->
                                if (pair != null) {
                                    adapter.paintUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    messageDialog?.adapter?.paintUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    replyDialog?.adapter?.paintUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    updateUserMessages(pair.first)
                                    viewModel.newPaintUser.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newStvBadge.collectLatest { badge ->
                                if (badge != null) {
                                    adapter.stvBadges?.let { stvBadges ->
                                        stvBadges.find { it.id == badge.id }?.let { stvBadges.remove(it) }
                                        stvBadges.add(badge)
                                    }
                                    messageDialog?.adapter?.stvBadges?.let { stvBadges ->
                                        stvBadges.find { it.id == badge.id }?.let { stvBadges.remove(it) }
                                        stvBadges.add(badge)
                                    }
                                    replyDialog?.adapter?.stvBadges?.let { stvBadges ->
                                        stvBadges.find { it.id == badge.id }?.let { stvBadges.remove(it) }
                                        stvBadges.add(badge)
                                    }
                                    viewModel.newStvBadge.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newStvBadgeUser.collectLatest { pair ->
                                if (pair != null) {
                                    adapter.stvBadgeUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    messageDialog?.adapter?.stvBadgeUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    replyDialog?.adapter?.stvBadgeUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    updateUserMessages(pair.first)
                                    viewModel.newStvBadgeUser.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPersonalEmoteSet.collectLatest { pair ->
                                if (pair != null) {
                                    adapter.personalEmoteSets?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    messageDialog?.adapter?.personalEmoteSets?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    replyDialog?.adapter?.personalEmoteSets?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    viewModel.newPersonalEmoteSet.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.newPersonalEmoteSetUser.collectLatest { pair ->
                                if (pair != null) {
                                    adapter.personalEmoteSetUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    messageDialog?.adapter?.personalEmoteSetUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    replyDialog?.adapter?.personalEmoteSetUsers?.let {
                                        it.remove(pair.first)
                                        it.put(pair.first, pair.second)
                                    }
                                    updateUserMessages(pair.first)
                                    viewModel.newPersonalEmoteSetUser.value = null
                                }
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && channelId != null && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.translateAllMessages.collectLatest {
                                    if (it != null) {
                                        adapter.translateAllMessages = it
                                        viewModel.translateAllMessages.value = null
                                    }
                                }
                            }
                        }
                        viewModel.checkTranslateAllMessages(channelId)
                    }
                    if (chatUrl != null) {
                        viewModel.startReplay(
                            channelId = channelId,
                            channelLogin = channelLogin,
                            chatUrl = chatUrl,
                            getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                            getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                        )
                    }
                } else {
                    chatReplayUnavailable.visible()
                }
            }
            if ((view.parent?.parent?.parent as? View)?.id != R.id.slidingLayout) {
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                    if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            bottomMargin = insets.bottom
                        }
                    }
                    WindowInsetsCompat.CONSUMED
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
                viewModel.startLive(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), channelId, channelLogin, args.getString(KEY_CHANNEL_NAME), args.getString(KEY_STREAM_ID))
            } else {
                val videoId = args.getString(KEY_VIDEO_ID)
                val startTime = args.getInt(KEY_START_TIME)
                if (videoId != null && startTime != -1) {
                    viewModel.startReplay(
                        channelId = channelId,
                        channelLogin = channelLogin,
                        videoId = videoId,
                        startTime = startTime,
                        getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                        getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
                getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
            )
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
                viewModel.loadRecentMessages(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), channelLogin)
            }
        }
        viewModel.autoReconnect = true
    }

    fun reloadEmotes() {
        viewModel.reloadEmotes(
            requireArguments().getString(KEY_CHANNEL_ID),
            requireArguments().getString(KEY_CHANNEL_LOGIN)
        )
    }

    fun startReplayChatLoad() {
        viewModel.startReplayChatLoad()
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

    fun getTranslateAllMessages(): Boolean {
        return adapter.translateAllMessages
    }

    fun toggleTranslateAllMessages(enable: Boolean) {
        viewModel.translateAllMessages.value = enable
    }

    fun emoteMenuIsVisible() = binding.emoteMenu.isVisible

    fun toggleEmoteMenu(enable: Boolean) {
        if (enable) {
            binding.emoteMenu.visible()
        } else {
            binding.emoteMenu.gone()
        }
        toggleBackPressedCallback(enable)
    }

    fun toggleBackPressedCallback(enable: Boolean) {
        if (enable) {
            requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
        } else {
            backPressedCallback.remove()
        }
    }

    fun appendEmote(emote: Emote) {
        binding.editText.text.append(emote.name).append(' ')
    }

    private fun sendMessage(replyId: String? = null): Boolean {
        with(binding) {
            editText.hideKeyboard()
            editText.clearFocus()
            toggleEmoteMenu(false)
            replyView.gone()
            send.setOnClickListener { sendMessage() }
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendMessage()
                } else {
                    false
                }
            }
            val text = editText.text.trim()
            editText.text.clear()
            return if (text.isNotEmpty()) {
                viewModel.send(
                    message = text,
                    replyId = replyId,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    accountId = requireContext().tokenPrefs().getString(C.USER_ID, null),
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true),
                    useApiChatMessages = requireContext().prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, true),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
                adapter.messages?.let { recyclerView.scrollToPosition(it.lastIndex) }
                true
            } else {
                false
            }
        }
    }

    private fun addToAutoCompleteList(list: Collection<Any>?) {
        if (!list.isNullOrEmpty()) {
            if (messagingEnabled) {
                val newItems = list.filter { it !in autoCompleteList }
                autoCompleteAdapter?.addAll(newItems) ?: autoCompleteList.addAll(newItems)
            }
        }
    }

    private fun updateUserMessages(userId: String) {
        try {
            adapter.messages?.toList()?.let { messages ->
                messages.filter { it.userId != null && it.userId == userId }.forEach { message ->
                    messages.indexOf(message).takeIf { it != -1 }?.let {
                        adapter.notifyItemChanged(it)
                    }
                }
            }
            messageDialog?.updateUserMessages(userId)
            replyDialog?.updateUserMessages(userId)
        } catch (e: NullPointerException) {

        }
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter {
        return adapter.createMessageClickedChatAdapter(adapter.messages?.toList())
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter {
        return adapter.createReplyClickedChatAdapter(adapter.messages?.toList())
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        with(binding) {
            if (!replyId.isNullOrBlank()) {
                messageDialog?.dismiss()
                replyView.visible()
                replyText.text = message?.let {
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
                replyClose.setOnClickListener {
                    replyView.gone()
                    send.setOnClickListener { sendMessage() }
                    editText.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                            sendMessage()
                        } else {
                            false
                        }
                    }
                }
                send.setOnClickListener { sendMessage(replyId) }
                editText.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                        sendMessage(replyId)
                    } else {
                        false
                    }
                }
            }
            editText.apply {
                requestFocus()
                WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, this).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onCopyMessageClicked(message: String) {
        binding.editText.setText(message)
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
        (parentFragment as? PlayerFragment)?.minimize()
    }

    override fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?) {
        val message = chatMessage.message ?: chatMessage.systemMsg
        if (message != null) {
            if (languageTag != null) {
                translateMessage(message, chatMessage, languageTag)
            } else {
                val languageIdentifier = languageIdentifier ?: LanguageIdentification.getClient().also { languageIdentifier = it }
                languageIdentifier.identifyLanguage(message)
                    .addOnSuccessListener { tag ->
                        translateMessage(message, chatMessage, tag)
                    }
                    .addOnFailureListener {
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = requireContext().getString(R.string.translate_failed_id)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = null
                        try {
                            adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                            messageDialog?.updateTranslation(chatMessage, previousTranslation)
                            replyDialog?.updateTranslation(chatMessage, previousTranslation)
                        } catch (e: NullPointerException) {

                        }
                    }
            }
        }
    }

    private fun translateMessage(message: String, chatMessage: ChatMessage, tag: String) {
        val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
        if (tag != "und" && tag != targetLanguage) {
            TranslateLanguage.fromLanguageTag(tag)?.let { sourceLanguage ->
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators.put(sourceLanguage, it)
                }
                translator.translate(message)
                    .addOnSuccessListener { text ->
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = requireContext().getString(R.string.translated_message, languageName, text)
                        chatMessage.translationFailed = false
                        chatMessage.messageLanguage = null
                        try {
                            adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                            messageDialog?.updateTranslation(chatMessage, previousTranslation)
                            replyDialog?.updateTranslation(chatMessage, previousTranslation)
                        } catch (e: NullPointerException) {

                        }
                    }
                    .addOnFailureListener {
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = requireContext().getString(R.string.translate_failed, languageName)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = sourceLanguage
                        try {
                            adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                            messageDialog?.updateTranslation(chatMessage, previousTranslation)
                            replyDialog?.updateTranslation(chatMessage, previousTranslation)
                        } catch (e: NullPointerException) {

                        }
                    }
            }
        } else {
            val previousTranslation = chatMessage.translatedMessage
            chatMessage.translatedMessage = requireContext().getString(R.string.translate_failed_id)
            chatMessage.translationFailed = true
            chatMessage.messageLanguage = null
            try {
                adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                    (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                        adapter.updateTranslation(chatMessage, it, previousTranslation)
                    } ?: adapter.notifyItemChanged(it)
                }
                messageDialog?.updateTranslation(chatMessage, previousTranslation)
                replyDialog?.updateTranslation(chatMessage, previousTranslation)
            } catch (e: NullPointerException) {

            }
        }
    }

    private fun showLanguageDownloadDialog(chatMessage: ChatMessage, sourceLanguage: String) {
        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
        requireContext().getAlertDialogBuilder()
            .setMessage(requireContext().getString(R.string.download_language_model_message, languageName))
            .setNegativeButton(getString(R.string.no), null)
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators.put(sourceLanguage, it)
                }
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val message = chatMessage.message ?: chatMessage.systemMsg
                        if (message != null) {
                            translator.translate(message)
                                .addOnSuccessListener { text ->
                                    val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                                    val previousTranslation = chatMessage.translatedMessage
                                    chatMessage.translatedMessage = requireContext().getString(R.string.translated_message, languageName, text)
                                    chatMessage.translationFailed = false
                                    chatMessage.messageLanguage = null
                                    try {
                                        adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                                            (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                                adapter.updateTranslation(chatMessage, it, previousTranslation)
                                            } ?: adapter.notifyItemChanged(it)
                                        }
                                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                                    } catch (e: NullPointerException) {

                                    }
                                }
                        }
                    }
            }
            .show()
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
                    getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                    getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)) {
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        languageIdentifier?.close()
        translators.forEach {
            it.value.close()
        }
    }

    class SpaceTokenizer : MultiAutoCompleteTextView.Tokenizer {

        override fun findTokenStart(text: CharSequence, cursor: Int): Int {
            var i = cursor

            while (i > 0 && text[i - 1] != ' ') {
                i--
            }
            while (i < cursor && text[i] == ' ') {
                i++
            }

            return i
        }

        override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
            var i = cursor
            val len = text.length

            while (i < len) {
                if (text[i] == ' ') {
                    return i
                } else {
                    i++
                }
            }

            return len
        }

        override fun terminateToken(text: CharSequence): CharSequence {
            return "${if (text.startsWith(':')) text.substring(1) else text} "
        }
    }

    companion object {
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CHAT_URL = "chatUrl"
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
                    putInt(KEY_START_TIME, (startTime ?: -1))
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