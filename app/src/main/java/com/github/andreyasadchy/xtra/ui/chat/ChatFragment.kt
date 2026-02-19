package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.use
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
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.tokenPrefs
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
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()
    private var adapter: ChatAdapter? = null

    private var isChatTouched = false
    private var showChatStatus = false
    private var hasRecentEmotes = false
    private var messagingEnabled = false

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
                    val enableMessaging = isLive && isLoggedIn
                    val sizeModifier = (requireContext().prefs().getInt(C.CHAT_SIZE_MODIFIER, 100).toFloat() / 100f)
                    adapter = ChatAdapter(
                        messages = viewModel.chatMessages,
                        localTwitchEmotes = viewModel.localTwitchEmotes,
                        thirdPartyEmotes = viewModel.thirdPartyEmotes,
                        globalBadges = viewModel.globalBadges,
                        channelBadges = viewModel.channelBadges,
                        cheerEmotes = viewModel.cheerEmotes,
                        namePaints = viewModel.namePaints,
                        stvBadges = viewModel.stvBadges,
                        personalEmoteSets = viewModel.personalEmoteSets,
                        stvUsers = viewModel.stvUsers,
                        enableTimestamps = requireContext().prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                        timestampFormat = requireContext().prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                        firstMsgVisibility = requireContext().prefs().getString(C.CHAT_FIRSTMSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                        firstChatMsg = getString(R.string.chat_first),
                        redeemedChatMsg = getString(R.string.redeemed),
                        redeemedNoMsg = getString(R.string.user_redeemed),
                        rewardChatMsg = getString(R.string.chat_reward),
                        replyMessage = getString(R.string.replying_to_message),
                        useRandomColors = requireContext().prefs().getBoolean(C.CHAT_RANDOMCOLOR, true),
                        useReadableColors = requireContext().prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                        isLightTheme = requireContext().obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                            it.getBoolean(0, false)
                        },
                        nameDisplay = requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0"),
                        useBoldNames = requireContext().prefs().getBoolean(C.CHAT_BOLDNAMES, false),
                        showNamePaints = requireContext().prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                        showStvBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                        showPersonalEmotes = requireContext().prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
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
                        emoteSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        badgeSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_BADGE_SIZE, "18.5")?.toFloatOrNull() ?: 18.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        emoteQuality = requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                        animateGifs = requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true),
                        enableOverlayEmotes = requireContext().prefs().getBoolean(C.CHAT_ZEROWIDTH, true),
                        translateMessage = this@ChatFragment::onTranslateMessageClicked,
                        showLanguageDownloadDialog = this@ChatFragment::showLanguageDownloadDialog,
                        channelId = channelId,
                        loggedInUser = if (enableMessaging) accountLogin else null,
                        messageClickListener = { channelId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            MessageClickedDialog.newInstance(enableMessaging, channelId).show(this@ChatFragment.childFragmentManager, "messageDialog")
                        },
                        replyClickListener = {
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ReplyClickedDialog.newInstance(enableMessaging).show(this@ChatFragment.childFragmentManager, "replyDialog")
                        },
                        imageClickListener = { url, name, format, isAnimated, source, thirdParty, emoteId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ImageClickedDialog.newInstance(url, name, format, isAnimated, source, thirdParty, emoteId).show(this@ChatFragment.childFragmentManager, "imageDialog")
                        },
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
                                    chatStatus.visibility = View.VISIBLE
                                    chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
                                }
                            }
                        })
                    }
                    btnDown.setOnClickListener {
                        view.post {
                            val lastIndex = synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.lastIndex
                            }
                            recyclerView.scrollToPosition(lastIndex)
                            it.visibility = View.GONE
                        }
                    }
                    if (enableMessaging) {
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
                        autoCompleteAdapter = AutoCompleteAdapter(
                            requireContext(),
                            R.layout.auto_complete_emotes_list_item,
                            R.id.name,
                            viewModel.autoCompleteList,
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
                                send.visibility = View.VISIBLE
                                clear.visibility = View.VISIBLE
                            } else {
                                send.visibility = View.GONE
                                clear.visibility = View.GONE
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
                        replyView.visibility = View.GONE
                        send.setOnClickListener { sendMessage() }
                        if ((view.parent?.parent?.parent?.parent as? View)?.id == R.id.slidingLayout && !requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            messageView.visibility = View.GONE
                        } else {
                            messageView.visibility = View.VISIBLE
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
                                0 -> getString(R.string.recent_emotes)
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
                            viewModel.roomState.collectLatest { roomState ->
                                if (roomState != null) {
                                    when (roomState.emote) {
                                        "0" -> textEmote.visibility = View.GONE
                                        "1" -> textEmote.visibility = View.VISIBLE
                                    }
                                    if (roomState.followers != null) {
                                        when (roomState.followers) {
                                            "-1" -> textFollowers.visibility = View.GONE
                                            "0" -> {
                                                textFollowers.text = getString(R.string.room_followers)
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                            else -> {
                                                textFollowers.text = getString(
                                                    R.string.room_followers_min,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), (roomState.followers.toInt() * 60).toString())
                                                )
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.unique) {
                                        "0" -> textUnique.visibility = View.GONE
                                        "1" -> textUnique.visibility = View.VISIBLE
                                    }
                                    if (roomState.slow != null) {
                                        when (roomState.slow) {
                                            "0" -> textSlow.visibility = View.GONE
                                            else -> {
                                                textSlow.text = getString(
                                                    R.string.room_slow,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), roomState.slow)
                                                )
                                                textSlow.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.subs) {
                                        "0" -> textSubs.visibility = View.GONE
                                        "1" -> textSubs.visibility = View.VISIBLE
                                    }
                                    if (textEmote.isGone && textFollowers.isGone && textUnique.isGone && textSlow.isGone && textSubs.isGone) {
                                        showChatStatus = false
                                        chatStatus.visibility = View.GONE
                                    } else {
                                        showChatStatus = true
                                        chatStatus.visibility = View.VISIBLE
                                        chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
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
                                    adapter?.let { adapter ->
                                        val size = synchronized(viewModel.chatMessages) {
                                            viewModel.chatMessages.size
                                        }
                                        adapter.notifyItemRangeChanged(0, size)
                                    }
                                    messageDialog?.adapter?.let { adapter ->
                                        val size = synchronized(adapter.messages) {
                                            adapter.messages.size
                                        }
                                        adapter.notifyItemRangeChanged(0, size)
                                    }
                                    replyDialog?.adapter?.let { adapter ->
                                        val size = synchronized(adapter.messages) {
                                            adapter.messages.size
                                        }
                                        adapter.notifyItemRangeChanged(0, size)
                                    }
                                    viewModel.reloadMessages.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hideRaid.collectLatest {
                                if (it) {
                                    raidLayout.visibility = View.GONE
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
                                            raidLayout.visibility = View.GONE
                                            viewModel.raidClosed = true
                                        } else {
                                            raidLayout.visibility = View.VISIBLE
                                            raidLayout.setOnClickListener { viewModel.raidClicked.value = raid }
                                            requireContext().imageLoader.enqueue(
                                                ImageRequest.Builder(requireContext()).apply {
                                                    data(raid.targetLogo)
                                                    if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                                        transformations(CircleCropTransformation())
                                                    }
                                                    crossfade(true)
                                                    target(raidImage)
                                                }.build()
                                            )
                                            raidClose.setOnClickListener {
                                                raidLayout.visibility = View.GONE
                                                viewModel.raidClosed = true
                                            }
                                            raidText.text = getString(
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
                                    pollLayout.visibility = View.GONE
                                    viewModel.pollSecondsLeft.value = null
                                    viewModel.pollTimer?.cancel()
                                    viewModel.pollClosed = true
                                    viewModel.hidePoll.value = false
                                }
                            }
                        }
                    }
                    pollClose.setOnClickListener {
                        pollLayout.visibility = View.GONE
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
                                                pollLayout.visibility = View.VISIBLE
                                                pollTitle.text = getString(R.string.poll_title, poll.title)
                                                pollChoices.text = poll.choices?.joinToString("\n") {
                                                    getString(
                                                        R.string.poll_choice,
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                pollStatus.visibility = View.VISIBLE
                                            }
                                            "COMPLETED", "TERMINATED" -> {
                                                pollLayout.visibility = View.VISIBLE
                                                pollTitle.text = getString(R.string.poll_title, poll.title)
                                                val winningTotal = poll.choices?.maxOfOrNull { it.totalVotes ?: 0 } ?: 0
                                                pollChoices.text = poll.choices?.joinToString("\n") {
                                                    getString(
                                                        if (winningTotal == it.totalVotes) {
                                                            R.string.poll_choice_winner
                                                        } else {
                                                            R.string.poll_choice
                                                        },
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                pollStatus.visibility = View.GONE
                                                viewModel.pollSecondsLeft.value = null
                                                viewModel.pollTimer?.cancel()
                                                viewModel.startPollTimeout { pollLayout.visibility = View.GONE }
                                            }
                                            else -> {
                                                pollLayout.visibility = View.GONE
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
                                    pollStatus.text = getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
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
                                    predictionLayout.visibility = View.GONE
                                    viewModel.predictionSecondsLeft.value = null
                                    viewModel.predictionTimer?.cancel()
                                    viewModel.predictionClosed = true
                                    viewModel.hidePrediction.value = false
                                }
                            }
                        }
                    }
                    predictionClose.setOnClickListener {
                        predictionLayout.visibility = View.GONE
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
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                predictionStatus.visibility = View.VISIBLE
                                            }
                                            "LOCKED" -> {
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.visibility = View.GONE }
                                                predictionStatus.visibility = View.VISIBLE
                                                predictionStatus.text = getString(R.string.prediction_locked)
                                            }
                                            "CANCELED", "CANCEL_PENDING", "RESOLVED", "RESOLVE_PENDING" -> {
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val resolved = prediction.status == "RESOLVED" || prediction.status == "RESOLVE_PENDING"
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        if (resolved && prediction.winningOutcomeId != null && prediction.winningOutcomeId == it.id) {
                                                            R.string.prediction_outcome_winner
                                                        } else {
                                                            R.string.prediction_outcome
                                                        },
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.visibility = View.GONE }
                                                if (resolved) {
                                                    predictionStatus.visibility = View.GONE
                                                } else {
                                                    predictionStatus.visibility = View.VISIBLE
                                                    predictionStatus.text = getString(R.string.prediction_refunded)
                                                }
                                            }
                                            else -> {
                                                predictionLayout.visibility = View.GONE
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
                                    predictionStatus.text = getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
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
                            viewModel.newMessage.collect { result ->
                                val message = result.first
                                val lastIndex = result.second
                                val removeCount = result.third
                                adapter?.let { adapter ->
                                    adapter.notifyItemInserted(lastIndex)
                                    if (removeCount > 0) {
                                        synchronized(viewModel.chatMessages) {
                                            repeat(removeCount) {
                                                viewModel.chatMessages.removeAt(0)
                                            }
                                        }
                                        adapter.notifyItemRangeRemoved(0, removeCount)
                                    }
                                    if (!isChatTouched && binding.btnDown.isGone) {
                                        binding.recyclerView.scrollToPosition(lastIndex - removeCount)
                                    }
                                }
                                messageDialog?.newMessage(message)
                                replyDialog?.newMessage(message)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.addMessages.collect { result ->
                                val messages = result.first
                                val lastIndex = result.second
                                adapter?.let { adapter ->
                                    adapter.notifyItemRangeInserted(0, messages.size)
                                    if (!isChatTouched && binding.btnDown.isGone) {
                                        binding.recyclerView.scrollToPosition(lastIndex)
                                    }
                                }
                                messageDialog?.addMessages(messages)
                                replyDialog?.addMessages(messages)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.removeMessages.collect { size ->
                                adapter?.notifyItemRangeRemoved(0, size)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.updateUserMessages.collectLatest { userId ->
                                adapter?.let { adapter ->
                                    synchronized(viewModel.chatMessages) {
                                        viewModel.chatMessages.mapIndexedNotNull { index, message ->
                                            if (message.userId != null && message.userId == userId) {
                                                index
                                            } else null
                                        }
                                    }.forEach {
                                        adapter.notifyItemChanged(it)
                                    }
                                }
                                messageDialog?.updateUserMessages(userId)
                                replyDialog?.updateUserMessages(userId)
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && channelId != null && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.translateAllMessages.collectLatest {
                                    if (it != null) {
                                        adapter?.translateAllMessages = it
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
                    chatReplayUnavailable.visibility = View.VISIBLE
                }
            }
            if ((view.parent?.parent?.parent?.parent as? View)?.id != R.id.slidingLayout) {
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
        return viewModel.translateAllMessages.value == true
    }

    fun toggleTranslateAllMessages(enable: Boolean) {
        viewModel.translateAllMessages.value = enable
    }

    fun emoteMenuIsVisible() = binding.emoteMenu.isVisible

    fun toggleEmoteMenu(enable: Boolean) {
        if (enable) {
            binding.emoteMenu.visibility = View.VISIBLE
        } else {
            binding.emoteMenu.visibility = View.GONE
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
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
            editText.clearFocus()
            toggleEmoteMenu(false)
            replyView.visibility = View.GONE
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
                val lastIndex = synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.lastIndex
                }
                recyclerView.scrollToPosition(lastIndex)
                true
            } else {
                false
            }
        }
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter? {
        return adapter?.createMessageClickedChatAdapter()
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter? {
        return adapter?.createReplyClickedChatAdapter()
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        with(binding) {
            if (!replyId.isNullOrBlank()) {
                messageDialog?.dismiss()
                replyView.visibility = View.VISIBLE
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
                    getString(R.string.replying_to_message, name, message)
                }
                replyClose.setOnClickListener {
                    replyView.visibility = View.GONE
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
                        chatMessage.translatedMessage = getString(R.string.translate_failed_id)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = null
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
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
                    translators[sourceLanguage] = it
                }
                translator.translate(message)
                    .addOnSuccessListener { text ->
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                        chatMessage.translationFailed = false
                        chatMessage.messageLanguage = null
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
                    .addOnFailureListener {
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed, languageName)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = sourceLanguage
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        } else {
            val previousTranslation = chatMessage.translatedMessage
            chatMessage.translatedMessage = getString(R.string.translate_failed_id)
            chatMessage.translationFailed = true
            chatMessage.messageLanguage = null
            adapter?.let { adapter ->
                synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                }?.let {
                    (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                        adapter.updateTranslation(chatMessage, it, previousTranslation)
                    } ?: adapter.notifyItemChanged(it)
                }
            }
            messageDialog?.updateTranslation(chatMessage, previousTranslation)
            replyDialog?.updateTranslation(chatMessage, previousTranslation)
        }
    }

    private fun showLanguageDownloadDialog(chatMessage: ChatMessage, sourceLanguage: String) {
        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
        requireContext().getAlertDialogBuilder()
            .setMessage(getString(R.string.download_language_model_message, languageName))
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
                    translators[sourceLanguage] = it
                }
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val message = chatMessage.message ?: chatMessage.systemMsg
                        if (message != null) {
                            translator.translate(message)
                                .addOnSuccessListener { text ->
                                    val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                                    val previousTranslation = chatMessage.translatedMessage
                                    chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                                    chatMessage.translationFailed = false
                                    chatMessage.messageLanguage = null
                                    adapter?.let { adapter ->
                                        synchronized(viewModel.chatMessages) {
                                            viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                                        }?.let {
                                            (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                                adapter.updateTranslation(chatMessage, it, previousTranslation)
                                            } ?: adapter.notifyItemChanged(it)
                                        }
                                    }
                                    messageDialog?.updateTranslation(chatMessage, previousTranslation)
                                    replyDialog?.updateTranslation(chatMessage, previousTranslation)
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