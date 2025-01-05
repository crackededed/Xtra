package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.widget.TextViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ViewChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChannelPoll
import com.github.andreyasadchy.xtra.model.chat.ChannelPrediction
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.chat.RoomState
import com.github.andreyasadchy.xtra.model.chat.StvBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.SlidingLayout
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.hideKeyboard
import com.github.andreyasadchy.xtra.util.isLightTheme
import com.github.andreyasadchy.xtra.util.loadImage
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.toggleVisibility
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.android.extensions.LayoutContainer
import java.util.regex.Pattern
import kotlin.math.max

private const val HIDE_POLL_TIMEOUT_MS = 2 * 60 * 1000L
private const val HIDE_PREDICTION_TIMEOUT_MS = 2 * 10 * 1000L

private const val USER_SYMBOL = "\uD83E\uDDCD"
private const val WINNING_SYMBOL = "\uD83C\uDFC6"
private const val TOKEN_SYMBOL = "\uD83E\uDE99"

class ChatView : ConstraintLayout {

    interface ChatViewCallback {
        fun send(message: CharSequence, replyId: String?)
        fun onRaidClicked(raid: Raid)
        fun onRaidClose()
        fun onPollClose(isManual: Boolean)
        fun onPredictionClose(isManual: Boolean)
    }

    private var _binding: ViewChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter

    private var isChatTouched = false
    private var showChatStatus = false

    private var hasRecentEmotes = false

    private var autoCompleteList = mutableListOf<Any>()
    private var autoCompleteAdapter: AutoCompleteAdapter? = null

    private lateinit var fragment: Fragment
    private var messagingEnabled = false

    private var callback: ChatViewCallback? = null

    private var hidePollRunnable: Runnable? = null
    private var hidePredictionRunnable: Runnable? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            toggleEmoteMenu(false)
        }
    }

    private val messageDialog: MessageClickedDialog?
        get() = fragment.childFragmentManager.findFragmentByTag("messageDialog") as? MessageClickedDialog

    private val replyDialog: ReplyClickedDialog?
        get() = fragment.childFragmentManager.findFragmentByTag("replyDialog") as? ReplyClickedDialog

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        _binding = ViewChatBinding.inflate(LayoutInflater.from(context), this, true)
    }

    fun init(fragment: Fragment, channelId: String?, namePaints: List<NamePaint>? = null, paintUsers: Map<String, String>? = null, stvBadges: List<StvBadge>? = null, stvBadgeUsers: Map<String, String>? = null, personalEmoteSets: Map<String, List<Emote>>? = null, personalEmoteSetUsers: Map<String, String>? = null, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)? = null, chatUrl: String? = null) {
        this.fragment = fragment
        with(binding) {
            adapter = ChatAdapter(
                enableTimestamps = context.prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                timestampFormat = context.prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                firstMsgVisibility = context.prefs().getString(C.CHAT_FIRSTMSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                firstChatMsg = context.getString(R.string.chat_first),
                redeemedChatMsg = context.getString(R.string.redeemed),
                redeemedNoMsg = context.getString(R.string.user_redeemed),
                rewardChatMsg = context.getString(R.string.chat_reward),
                replyMessage = context.getString(R.string.replying_to_message),
                useRandomColors = context.prefs().getBoolean(C.CHAT_RANDOMCOLOR, true),
                useReadableColors = context.prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                isLightTheme = context.isLightTheme,
                nameDisplay = context.prefs().getString(C.UI_NAME_DISPLAY, "0"),
                useBoldNames = context.prefs().getBoolean(C.CHAT_BOLDNAMES, false),
                showNamePaints = context.prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                namePaintsList = namePaints,
                paintUsersMap = paintUsers,
                showStvBadges = context.prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                stvBadgesList = stvBadges,
                stvBadgeUsersMap = stvBadgeUsers,
                showPersonalEmotes = context.prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                personalEmoteSetsMap = personalEmoteSets,
                personalEmoteSetUsersMap = personalEmoteSetUsers,
                showSystemMessageEmotes = context.prefs().getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                chatUrl = chatUrl,
                getEmoteBytes = getEmoteBytes,
                fragment = fragment,
                backgroundColor = MaterialColors.getColor(this@ChatView, com.google.android.material.R.attr.colorSurface),
                dialogBackgroundColor = MaterialColors.getColor(this@ChatView,
                    if (context.prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
                        com.google.android.material.R.attr.colorSurfaceContainerLow
                    } else {
                        com.google.android.material.R.attr.colorSurface
                    }
                ),
                imageLibrary = context.prefs().getString(C.CHAT_IMAGE_LIBRARY, "0"),
                emoteSize = context.convertDpToPixels(29.5f),
                badgeSize = context.convertDpToPixels(18.5f),
                emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                animateGifs = context.prefs().getBoolean(C.ANIMATED_EMOTES, true),
                enableZeroWidth = context.prefs().getBoolean(C.CHAT_ZEROWIDTH, true),
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
                        btnDown.isVisible = shouldShowButton()
                        if (showChatStatus && chatStatus.isGone) {
                            chatStatus.visible()
                            chatStatus.postDelayed({ chatStatus.gone() }, 5000)
                        }
                    }
                })
            }
            btnDown.setOnClickListener {
                post {
                    scrollToLastPosition()
                    it.toggleVisibility()
                }
            }
            pollClose.setOnClickListener {
                hidePoll(true)
            }
            predictionClose.setOnClickListener {
                hidePrediction(true)
            }
        }
    }

    fun submitList(list: MutableList<ChatMessage>?) {
        adapter.messages = list
        messageDialog?.adapter?.let { adapter ->
            if (!adapter.userId.isNullOrBlank() || !adapter.userLogin.isNullOrBlank()) {
                adapter.messages = list?.filter {
                    (!adapter.userId.isNullOrBlank() && it.userId == adapter.userId) || (!adapter.userLogin.isNullOrBlank() && it.userLogin == adapter.userLogin)
                }?.toMutableList()
            }
        }
        replyDialog?.adapter?.let { adapter ->
            if (!adapter.threadParentId.isNullOrBlank()) {
                adapter.messages = list?.filter {
                    it.reply?.threadParentId == adapter.threadParentId || it.id == adapter.threadParentId
                }?.toMutableList()
            }
        }
    }

    fun notifyMessageAdded(newMessage: ChatMessage) {
        with(binding) {
            adapter.messages?.apply {
                adapter.notifyItemInserted(lastIndex)
                val messageLimit = context.prefs().getInt(C.CHAT_LIMIT, 600)
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
                        val messageLimit = context.prefs().getInt(C.CHAT_LIMIT, 600)
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
                        val messageLimit = context.prefs().getInt(C.CHAT_LIMIT, 600)
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
        }
    }

    fun notifyEmotesLoaded() {
        adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) }
        messageDialog?.adapter?.let { adapter -> adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) } }
        replyDialog?.adapter?.let { adapter -> adapter.messages?.let { adapter.notifyItemRangeChanged(0, it.size) } }
    }

    fun notifyRoomState(roomState: RoomState) {
        with(binding) {
            when (roomState.emote) {
                "0" -> textEmote.gone()
                "1" -> textEmote.visible()
            }
            if (roomState.followers != null) {
                when (roomState.followers) {
                    "-1" -> textFollowers.gone()
                    "0" -> {
                        textFollowers.text = context.getString(R.string.room_followers)
                        textFollowers.visible()
                    }
                    else -> {
                        textFollowers.text = context.getString(R.string.room_followers_min, TwitchApiHelper.getDurationFromSeconds(context, (roomState.followers.toInt() * 60).toString()))
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
                        textSlow.text = context.getString(R.string.room_slow, TwitchApiHelper.getDurationFromSeconds(context, roomState.slow))
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
        }
    }

    fun notifyRaid(raid: Raid) {
        with(binding) {
            raidLayout.visible()
            raidLayout.setOnClickListener { callback?.onRaidClicked(raid) }
            raidImage.visible()
            raidImage.loadImage(
                fragment,
                raid.targetLogo,
                circle = context.prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)
            )
            raidText.visible()
            raidClose.visible()
            raidClose.setOnClickListener {
                hideRaid()
            }
            raidText.text = context.getString(
                R.string.raid_text,
                if (raid.targetLogin != null && !raid.targetLogin.equals(raid.targetName, true)) {
                    when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
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

    fun hideRaid() {
        with(binding) {
            raidLayout.gone()
            raidImage.gone()
            raidText.gone()
            raidClose.gone()
        }
        callback?.onRaidClose()
    }

    fun notifyPoll(poll: ChannelPoll) {
        with(binding) {
            if (poll.status == ChannelPoll.Status.ACTIVE || poll.status == ChannelPoll.Status.COMPLETED) {
                pollLayout.visible()
                val remainingDurationMilliseconds = poll.remainingDurationMilliseconds ?: 0
                if (remainingDurationMilliseconds > 0) {
                    removeHidePollCallback()
                    pollLeftTimeText.base = SystemClock.elapsedRealtime() + remainingDurationMilliseconds
                    pollLeftTimeText.start()
                    pollLeftTimeText.visible()
                } else {
                    finishPoll()
                }
                pollLeftTimeText.setOnChronometerTickListener {
                    if (pollLeftTimeText.base < SystemClock.elapsedRealtime()) {
                        finishPoll()
                    }
                }
                val winningTotal = poll.choices?.maxOfOrNull { it.votes?.total ?: 0 }
                val total = poll.votes?.total ?: 0
                pollText.text = context.getString(
                    R.string.poll_text,
                    poll.title,
                    poll.choices?.joinToString("") {
                        "\n%s%d%% (%d): %s".format(
                            if (poll.status == ChannelPoll.Status.COMPLETED && winningTotal == it.votes?.total) WINNING_SYMBOL else "",
                            it.votes?.total?.times(100)?.div(max(total, 1)) ?: 0,
                            it.votes?.total ?: 0,
                            it.title,
                        )
                    }
                )
            } else {
                hidePoll()
            }
        }
    }

    private fun hidePoll(isManual: Boolean = false) {
        with(binding) {
            pollLayout.gone()
            pollLeftTimeText.stop()
            removeHidePollCallback()
        }
        callback?.onPollClose(isManual)
    }

    private fun finishPoll() {
        with(binding) {
            pollLeftTimeText.gone()
            pollLeftTimeText.stop()
            removeHidePollCallback()
            hidePollRunnable = postDelayed(HIDE_POLL_TIMEOUT_MS) { hidePoll() }
        }
    }

    private fun removeHidePollCallback() {
        hidePollRunnable?.let {
            removeCallbacks(it)
            hidePollRunnable = null
        }
    }

    fun notifyPrediction(prediction: ChannelPrediction) {
        with(binding) {
            predictionLayout.visible()
            if (prediction.status == ChannelPrediction.Status.ACTIVE) {
                removeHidePredictionCallback()
                val remainingMs = (prediction.createdAt?.plus(prediction.predictionWindowSeconds?.times(1000) ?: 0) ?: 0) - System.currentTimeMillis()
                predictionLeftTimeText.base = SystemClock.elapsedRealtime() + remainingMs
                predictionLeftTimeText.start()
                predictionLeftTimeText.visible()
                predictionFinishedText.gone()
            } else {
                finishPrediction()
            }
            predictionLeftTimeText.setOnChronometerTickListener {
                if (predictionLeftTimeText.base < SystemClock.elapsedRealtime()) {
                    finishPrediction()
                }
            }
            predictionFinishedText.text = context.getString(when (prediction.status) {
                ChannelPrediction.Status.CANCELED, ChannelPrediction.Status.CANCEL_PENDING -> R.string.prediction_canceled_text
                ChannelPrediction.Status.RESOLVED, ChannelPrediction.Status.RESOLVE_PENDING -> R.string.prediction_finished_text
                else -> R.string.prediction_closed_text
            })
            val totalPoints = prediction.outcomes?.sumOf { it.totalPoints ?: 0 } ?: 0
            predictionText.text = context.getString(
                R.string.prediction_text,
                prediction.title,
                prediction.outcomes?.joinToString("") {
                    "\n%s%d%% (%d$TOKEN_SYMBOL, %d$USER_SYMBOL): %s".format(
                        if (prediction.status == ChannelPrediction.Status.RESOLVED && prediction.winningOutcomeId == it.id) WINNING_SYMBOL else "",
                        it.totalPoints?.times(100)?.div(max(totalPoints, 1)) ?: 0,
                        it.totalPoints ?: 0,
                        it.totalUsers ?: 0,
                        it.title,
                    )
                }
            )
        }
    }

    private fun hidePrediction(isManual: Boolean = false) {
        with(binding) {
            predictionLayout.gone()
            predictionLeftTimeText.stop()
            removeHidePredictionCallback()
        }
        callback?.onPredictionClose(isManual)
    }

    private fun finishPrediction() {
        with(binding) {
            predictionLeftTimeText.gone()
            predictionLeftTimeText.stop()
            predictionFinishedText.visible()
            removeHidePredictionCallback()
            hidePredictionRunnable = postDelayed(HIDE_PREDICTION_TIMEOUT_MS) { hidePrediction() }
        }
    }

    private fun removeHidePredictionCallback() {
        hidePredictionRunnable?.let {
            removeCallbacks(it)
            hidePredictionRunnable = null
        }
    }

    fun scrollToLastPosition() {
        adapter.messages?.let { binding.recyclerView.scrollToPosition(it.lastIndex) }
    }

    fun addToAutoCompleteList(list: Collection<Any>?) {
        if (!list.isNullOrEmpty()) {
            if (messagingEnabled) {
                val newItems = list.filter { it !in autoCompleteList }
                autoCompleteAdapter?.addAll(newItems) ?: autoCompleteList.addAll(newItems)
            }
        }
    }

    fun setRecentEmotes() {
        hasRecentEmotes = true
    }

    fun addLocalTwitchEmotes(list: List<TwitchEmote>?) {
        adapter.localTwitchEmotes = list
        messageDialog?.adapter?.localTwitchEmotes = list
        replyDialog?.adapter?.localTwitchEmotes = list
    }

    fun addGlobalStvEmotes(list: List<Emote>?) {
        adapter.globalStvEmotes = list
        messageDialog?.adapter?.globalStvEmotes = list
        replyDialog?.adapter?.globalStvEmotes = list
        addToAutoCompleteList(list)
    }

    fun addChannelStvEmotes(list: List<Emote>?) {
        adapter.channelStvEmotes = list
        messageDialog?.adapter?.channelStvEmotes = list
        replyDialog?.adapter?.channelStvEmotes = list
        addToAutoCompleteList(list)
    }

    fun addGlobalBttvEmotes(list: List<Emote>?) {
        adapter.globalBttvEmotes = list
        messageDialog?.adapter?.globalBttvEmotes = list
        replyDialog?.adapter?.globalBttvEmotes = list
        addToAutoCompleteList(list)
    }

    fun addChannelBttvEmotes(list: List<Emote>?) {
        adapter.channelBttvEmotes = list
        messageDialog?.adapter?.channelBttvEmotes = list
        replyDialog?.adapter?.channelBttvEmotes = list
        addToAutoCompleteList(list)
    }

    fun addGlobalFfzEmotes(list: List<Emote>?) {
        adapter.globalFfzEmotes = list
        messageDialog?.adapter?.globalFfzEmotes = list
        replyDialog?.adapter?.globalFfzEmotes = list
        addToAutoCompleteList(list)
    }

    fun addChannelFfzEmotes(list: List<Emote>?) {
        adapter.channelFfzEmotes = list
        messageDialog?.adapter?.channelFfzEmotes = list
        replyDialog?.adapter?.channelFfzEmotes = list
        addToAutoCompleteList(list)
    }

    fun addGlobalBadges(list: List<TwitchBadge>?) {
        adapter.globalBadges = list
        messageDialog?.adapter?.globalBadges = list
        replyDialog?.adapter?.globalBadges = list
    }

    fun addChannelBadges(list: List<TwitchBadge>?) {
        adapter.channelBadges = list
        messageDialog?.adapter?.channelBadges = list
        replyDialog?.adapter?.channelBadges = list
    }

    fun addCheerEmotes(list: List<CheerEmote>?) {
        adapter.cheerEmotes = list
        messageDialog?.adapter?.cheerEmotes = list
        replyDialog?.adapter?.cheerEmotes = list
    }

    fun addPaint(paint: NamePaint) {
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
    }

    fun addPaintUser(pair: Pair<String, String>) {
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
    }

    fun addStvBadge(badge: StvBadge) {
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
    }

    fun addStvBadgeUser(pair: Pair<String, String>) {
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
    }

    fun addPersonalEmoteSet(pair: Pair<String, List<Emote>>) {
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
    }

    fun addPersonalEmoteSetUser(pair: Pair<String, String>) {
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
    }

    private fun updateUserMessages(userId: String) {
        adapter.messages?.toList()?.let { messages ->
            messages.filter { it.userId == userId }.forEach { message ->
                messages.indexOf(message).takeIf { it != -1 }?.let {
                    adapter.notifyItemChanged(it)
                }
            }
        }
        messageDialog?.updateUserMessages(userId)
        replyDialog?.updateUserMessages(userId)
    }

    fun setUsername(username: String?) {
        adapter.loggedInUser = username
        messageDialog?.adapter?.loggedInUser = username
        replyDialog?.adapter?.loggedInUser = username
    }

    fun setCallback(callback: ChatViewCallback) {
        this.callback = callback
    }

    fun createMessageClickedChatAdapter(messages: List<ChatMessage>): MessageClickedChatAdapter {
        return adapter.createMessageClickedChatAdapter(messages)
    }

    fun createReplyClickedChatAdapter(messages: List<ChatMessage>): ReplyClickedChatAdapter {
        return adapter.createReplyClickedChatAdapter(messages)
    }

    fun emoteMenuIsVisible(): Boolean = binding.emoteMenu.isVisible

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
            fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment, backPressedCallback)
        } else {
            backPressedCallback.remove()
        }
    }

    fun appendEmote(emote: Emote) {
        binding.editText.text.append(emote.name).append(' ')
    }

    fun reply(replyId: String?, replyMessage: String?) {
        with(binding) {
            if (!replyId.isNullOrBlank()) {
                messageDialog?.dismiss()
                replyView.visible()
                replyText.text = replyMessage
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
                WindowCompat.getInsetsController(fragment.requireActivity().window, this).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    fun setMessage(text: CharSequence) {
        binding.editText.setText(text)
    }

    fun enableChatInteraction(enableMessaging: Boolean) {
        with(binding) {
            adapter.messageClickListener = { channelId ->
                editText.hideKeyboard()
                editText.clearFocus()
                MessageClickedDialog.newInstance(enableMessaging, channelId).show(fragment.childFragmentManager, "messageDialog")
            }
            adapter.replyClickListener = {
                editText.hideKeyboard()
                editText.clearFocus()
                ReplyClickedDialog.newInstance(enableMessaging).show(fragment.childFragmentManager, "replyDialog")
            }
            adapter.imageClickListener = { url, name, source, format, isAnimated, emoteId ->
                editText.hideKeyboard()
                editText.clearFocus()
                ImageClickedDialog.newInstance(url, name, source, format, isAnimated, emoteId).show(fragment.childFragmentManager, "imageDialog")
            }
            if (enableMessaging) {
                autoCompleteAdapter = AutoCompleteAdapter(context, fragment, autoCompleteList, context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4").apply {
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
                if (parent != null && parent.parent is SlidingLayout && !context.prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                    messageView.gone()
                } else {
                    messageView.visible()
                }
                viewPager.adapter = object : FragmentStateAdapter(fragment) {
                    override fun getItemCount(): Int = 3

                    override fun createFragment(position: Int): Fragment {
                        return EmotesFragment.newInstance(position)
                    }
                }
                viewPager.offscreenPageLimit = 2
                viewPager.reduceDragSensitivity()
                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = when (position) {
                        0 -> context.getString(R.string.recent_emotes)
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
        }
    }

    override fun onDetachedFromWindow() {
        binding.recyclerView.adapter = null
        super.onDetachedFromWindow()
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
            return callback?.let {
                val text = editText.text.trim()
                editText.text.clear()
                if (text.isNotEmpty()) {
                    it.send(text, replyId)
                    scrollToLastPosition()
                    true
                } else {
                    false
                }
            } == true
        }
    }

    private fun shouldShowButton(): Boolean {
        with(binding) {
            val offset = recyclerView.computeVerticalScrollOffset()
            if (offset < 0) {
                return false
            }
            val extent = recyclerView.computeVerticalScrollExtent()
            val range = recyclerView.computeVerticalScrollRange()
            val percentage = (100f * offset / (range - extent).toFloat())
            return percentage < 100f
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

    inner class AutoCompleteAdapter(
        context: Context,
        private val fragment: Fragment,
        list: List<Any>,
        private val emoteQuality: String,
    ) : ArrayAdapter<Any>(context, 0, list) {

        private var mFilter: ArrayFilter? = null

        override fun getFilter(): Filter = mFilter ?: ArrayFilter().also { mFilter = it }

        private inner class ArrayFilter : Filter() {
            override fun performFiltering(prefix: CharSequence?): FilterResults {
                val results = FilterResults()
                val originalValuesField = ArrayAdapter::class.java.getDeclaredField("mOriginalValues")
                originalValuesField.isAccessible = true
                val originalValues = originalValuesField.get(this@AutoCompleteAdapter) as List<*>?
                if (originalValues == null) {
                    originalValuesField.set(this@AutoCompleteAdapter, autoCompleteList.toList())
                }
                val list = originalValues ?: autoCompleteList.toList()
                if (prefix.isNullOrEmpty()) {
                    results.values = list
                    results.count = list.size
                } else {
                    var regexString = ""
                    prefix.toString().lowercase().forEach {
                        regexString += "${Pattern.quote(it.toString())}\\S*?"
                    }
                    val regex = Regex(regexString)
                    val newList = list.filter {
                        regex.matches(it.toString().lowercase())
                    }
                    results.values = newList
                    results.count = newList.size
                }
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                val objectsField = ArrayAdapter::class.java.getDeclaredField("mObjects")
                objectsField.isAccessible = true
                objectsField.set(this@AutoCompleteAdapter, results.values as? List<*> ?: mutableListOf<Any>())
                if (results.count > 0) {
                    notifyDataSetChanged()
                } else {
                    notifyDataSetInvalidated()
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val viewHolder: ViewHolder

            val item = getItem(position)!!
            return when (getItemViewType(position)) {
                TYPE_EMOTE -> {
                    if (convertView == null) {
                        val view = LayoutInflater.from(context).inflate(R.layout.auto_complete_emotes_list_item, parent, false)
                        viewHolder = ViewHolder(view).also { view.tag = it }
                    } else {
                        viewHolder = convertView.tag as ViewHolder
                    }
                    viewHolder.containerView.apply {
                        item as Emote
                        findViewById<ImageView>(R.id.image)?.loadImage(
                            fragment,
                            when (emoteQuality) {
                                "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                "3" -> item.url3x ?: item.url2x ?: item.url1x
                                "2" -> item.url2x ?: item.url1x
                                else -> item.url1x
                            },
                            diskCacheStrategy = DiskCacheStrategy.DATA
                        )
                        findViewById<TextView>(R.id.name)?.text = item.name
                    }
                }
                else -> {
                    if (convertView == null) {
                        val view = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
                        viewHolder = ViewHolder(view).also { view.tag = it }
                    } else {
                        viewHolder = convertView.tag as ViewHolder
                    }
                    (viewHolder.containerView as TextView).apply {
                        text = (item as Chatter).name
                        context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                            TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                        }
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (getItem(position) is Emote) TYPE_EMOTE else TYPE_USERNAME
        }

        override fun getViewTypeCount(): Int = 2

        inner class ViewHolder(override val containerView: View) : LayoutContainer
    }

    private companion object {
        const val TYPE_EMOTE = 0
        const val TYPE_USERNAME = 1
    }
}
