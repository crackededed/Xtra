package com.github.andreyasadchy.xtra.ui.view.chat

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.core.widget.TextViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ViewChatBinding
import com.github.andreyasadchy.xtra.model.chat.*
import com.github.andreyasadchy.xtra.ui.common.ChatAdapter
import com.github.andreyasadchy.xtra.ui.view.SlidingLayout
import com.github.andreyasadchy.xtra.util.*
import com.github.andreyasadchy.xtra.util.chat.Raid
import com.github.andreyasadchy.xtra.util.chat.RoomState
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.android.extensions.LayoutContainer
import java.util.regex.Pattern
import kotlin.math.max

class ChatView : ConstraintLayout {

    interface ChatViewCallback {
        fun send(message: CharSequence)
        fun onRaidClicked()
        fun onRaidClose()
    }

    private var _binding: ViewChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter

    private var isChatTouched = false
    private var showFlexbox = false

    private var hasRecentEmotes: Boolean? = null

    private var autoCompleteList = mutableListOf<Any>()
    private var autoCompleteAdapter: AutoCompleteAdapter? = null

    private lateinit var fragment: Fragment
    private var messagingEnabled = false

    private var callback: ChatViewCallback? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            toggleEmoteMenu(false)
        }
    }

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

    fun init(fragment: Fragment, channelId: String?) {
        this.fragment = fragment
        with(binding) {
            adapter = ChatAdapter(
                fragment = fragment,
                emoteSize = context.convertDpToPixels(29.5f),
                badgeSize = context.convertDpToPixels(18.5f),
                randomColor = context.prefs().getBoolean(C.CHAT_RANDOMCOLOR, true),
                isLightTheme = context.isLightTheme,
                useThemeAdaptedUsernameColor = context.prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                boldNames = context.prefs().getBoolean(C.CHAT_BOLDNAMES, false),
                emoteQuality = context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                animateGifs = context.prefs().getBoolean(C.ANIMATED_EMOTES, true),
                enableZeroWidth = context.prefs().getBoolean(C.CHAT_ZEROWIDTH, true),
                enableTimestamps = context.prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                timestampFormat = context.prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                firstMsgVisibility = context.prefs().getString(C.CHAT_FIRSTMSG_VISIBILITY, "0"),
                firstChatMsg = context.getString(R.string.chat_first),
                rewardChatMsg = context.getString(R.string.chat_reward),
                redeemedChatMsg = context.getString(R.string.redeemed),
                redeemedNoMsg = context.getString(R.string.user_redeemed),
                imageLibrary = context.prefs().getString(C.CHAT_IMAGE_LIBRARY, "0"),
                channelId = channelId
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
                        if (showFlexbox && flexbox.isGone) {
                            flexbox.visible()
                            flexbox.postDelayed({ flexbox.gone() }, 5000)
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
        }
    }

    fun submitList(list: MutableList<ChatMessage>?) {
        adapter.messages = list
    }

    fun notifyMessageAdded() {
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
        }
    }

    fun notifyEmotesLoaded() {
        adapter.messages?.size?.let { adapter.notifyItemRangeChanged(it - 40, 40) }
    }

    fun notifyRoomState(roomState: RoomState) {
        with(binding) {
            if (roomState.emote != null) {
                when (roomState.emote) {
                    "0" -> textEmote.gone()
                    "1" -> textEmote.visible()
                }
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
            if (roomState.unique != null) {
                when (roomState.unique) {
                    "0" -> textUnique.gone()
                    "1" -> textUnique.visible()
                }
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
            if (roomState.subs != null) {
                when (roomState.subs) {
                    "0" -> textSubs.gone()
                    "1" -> textSubs.visible()
                }
            }
            if (textEmote.isGone && textFollowers.isGone && textUnique.isGone && textSlow.isGone && textSubs.isGone) {
                showFlexbox = false
                flexbox.gone()
            } else {
                showFlexbox = true
                flexbox.visible()
                flexbox.postDelayed({ flexbox.gone() }, 5000)
            }
        }
    }

    fun notifyRaid(raid: Raid, newId: Boolean) {
        with(binding) {
            if (newId) {
                raidLayout.visible()
                raidLayout.setOnClickListener { callback?.onRaidClicked() }
                raidImage.visible()
                raidImage.loadImage(fragment, raid.targetLogo, circle = true)
                raidText.visible()
                raidClose.visible()
                raidClose.setOnClickListener {
                    callback?.onRaidClose()
                    hideRaid()
                }
            }
            raidText.text = context.getString(R.string.raid_text, raid.targetName, raid.viewerCount)
        }
    }

    fun hideRaid() {
        with(binding) {
            raidLayout.gone()
            raidImage.gone()
            raidText.gone()
            raidClose.gone()
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

    fun setRecentEmotes(list: List<Emote>?) {
        if (!list.isNullOrEmpty()) {
            hasRecentEmotes = true
        }
    }

    fun addGlobalStvEmotes(list: List<Emote>?) {
        adapter.addGlobalStvEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addChannelStvEmotes(list: List<Emote>?) {
        adapter.addChannelStvEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addGlobalBttvEmotes(list: List<Emote>?) {
        adapter.addGlobalBttvEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addChannelBttvEmotes(list: List<Emote>?) {
        adapter.addChannelBttvEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addGlobalFfzEmotes(list: List<Emote>?) {
        adapter.addGlobalFfzEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addChannelFfzEmotes(list: List<Emote>?) {
        adapter.addChannelFfzEmotes(list)
        addToAutoCompleteList(list)
    }

    fun addGlobalBadges(list: List<TwitchBadge>?) {
        adapter.addGlobalBadges(list)
    }

    fun addChannelBadges(list: List<TwitchBadge>?) {
        adapter.addChannelBadges(list)
    }

    fun addCheerEmotes(list: List<CheerEmote>?) {
        adapter.addCheerEmotes(list)
    }

    fun setUsername(username: String?) {
        adapter.setUsername(username)
    }

    fun setCallback(callback: ChatViewCallback) {
        this.callback = callback
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

    @SuppressLint("SetTextI18n")
    fun reply(userName: CharSequence) {
        val text = "@$userName "
        binding.editText.apply {
            setText(text)
            setSelection(text.length)
            requestFocus()
            WindowCompat.getInsetsController(fragment.requireActivity().window, this).show(WindowInsetsCompat.Type.ime())
        }
    }

    fun setMessage(text: CharSequence) {
        binding.editText.setText(text)
    }

    fun enableChatInteraction(enableMessaging: Boolean) {
        with(binding) {
            adapter.setOnClickListener { original, formatted, userId, channelId, fullMsg ->
                editText.hideKeyboard()
                editText.clearFocus()
                MessageClickedDialog.newInstance(enableMessaging, original, formatted, userId, channelId, fullMsg).show(fragment.childFragmentManager, "closeOnPip")
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
                        if (hasRecentEmotes != true && viewPager.currentItem == 0) {
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

    private fun sendMessage(): Boolean {
        with(binding) {
            editText.hideKeyboard()
            editText.clearFocus()
            toggleEmoteMenu(false)
            return callback?.let {
                val text = editText.text.trim()
                editText.text.clear()
                if (text.isNotEmpty()) {
                    it.send(text)
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
            private val emoteQuality: String) : ArrayAdapter<Any>(context, 0, list) {

        private var mFilter: ArrayFilter? = null

        override fun getFilter(): Filter = mFilter ?: ArrayFilter().also { mFilter = it }

        private inner class ArrayFilter : Filter() {
            override fun performFiltering(prefix: CharSequence?): FilterResults {
                val results = FilterResults()
                val list = autoCompleteList.toList()
                val originalValuesField = ArrayAdapter::class.java.getDeclaredField("mOriginalValues")
                originalValuesField.isAccessible = true
                val originalValues = originalValuesField.get(this@AutoCompleteAdapter) as List<*>?
                if (originalValues == null) {
                    originalValuesField.set(this@AutoCompleteAdapter, list)
                }
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
                        findViewById<ImageView>(R.id.image)?.loadImage(fragment, when (emoteQuality) {
                            "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                            "3" -> item.url3x ?: item.url2x ?: item.url1x
                            "2" -> item.url2x ?: item.url1x
                            else -> item.url1x
                        }, diskCacheStrategy = DiskCacheStrategy.DATA)
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
