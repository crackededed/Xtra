package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Animatable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.text.getSpans
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.StvBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import java.util.Random

class ChatAdapter(
    private val enableTimestamps: Boolean,
    private val timestampFormat: String?,
    private val firstMsgVisibility: Int,
    private val firstChatMsg: String,
    private val redeemedChatMsg: String,
    private val redeemedNoMsg: String,
    private val rewardChatMsg: String,
    private val replyMessage: String,
    private val useRandomColors: Boolean,
    private val useReadableColors: Boolean,
    private val isLightTheme: Boolean,
    private val nameDisplay: String?,
    private val useBoldNames: Boolean,
    private val showNamePaints: Boolean,
    namePaintsList: List<NamePaint>?,
    paintUsersMap: Map<String, String>?,
    private val showStvBadges: Boolean,
    stvBadgesList: List<StvBadge>?,
    stvBadgeUsersMap: Map<String, String>?,
    private val showPersonalEmotes: Boolean,
    personalEmoteSetsMap: Map<String, List<Emote>>?,
    personalEmoteSetUsersMap: Map<String, String>?,
    private val showSystemMessageEmotes: Boolean,
    private val chatUrl: String?,
    private val getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?,
    private val fragment: Fragment,
    private val backgroundColor: Int,
    private val dialogBackgroundColor: Int,
    private val imageLibrary: String?,
    private val messageTextSize: Float,
    private val emoteSize: Int,
    private val badgeSize: Int,
    private val emoteQuality: String,
    private val animateGifs: Boolean,
    private val enableOverlayEmotes: Boolean,
    private val translateMessage: (ChatMessage, String?) -> Unit,
    private val showLanguageDownloadDialog: (ChatMessage, String) -> Unit,
    private val channelId: String?,
    private val useHighVisibility: Boolean = false,
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    var messages: MutableList<ChatMessage>? = null
        set(value) {
            val oldSize = field?.size ?: 0
            if (oldSize > 0) {
                notifyItemRangeRemoved(0, oldSize)
            }
            field = value
        }
    private val random = Random()
    private val userColors = HashMap<String, Int>()
    private val savedColors = HashMap<String, Int>()
    var loggedInUser: String? = null
    var localTwitchEmotes: List<TwitchEmote>? = null
    var globalStvEmotes: List<Emote>? = null
    var channelStvEmotes: List<Emote>? = null
    var globalBttvEmotes: List<Emote>? = null
    var channelBttvEmotes: List<Emote>? = null
    var globalFfzEmotes: List<Emote>? = null
    var channelFfzEmotes: List<Emote>? = null
    var globalBadges: List<TwitchBadge>? = null
    var channelBadges: List<TwitchBadge>? = null
    var cheerEmotes: List<CheerEmote>? = null
    val namePaints: MutableList<NamePaint>? = namePaintsList?.toMutableList()
    val paintUsers: MutableMap<String, String>? = paintUsersMap?.toMutableMap()
    val stvBadges: MutableList<StvBadge>? = stvBadgesList?.toMutableList()
    val stvBadgeUsers: MutableMap<String, String>? = stvBadgeUsersMap?.toMutableMap()
    val personalEmoteSets: MutableMap<String, List<Emote>>? = personalEmoteSetsMap?.toMutableMap()
    val personalEmoteSetUsers: MutableMap<String, String>? = personalEmoteSetUsersMap?.toMutableMap()
    var translateAllMessages = false
    private val savedLocalTwitchEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalBadges = mutableMapOf<String, ByteArray>()
    private val savedLocalCheerEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalEmotes = mutableMapOf<String, ByteArray>()

    var messageClickListener: ((String?) -> Unit)? = null
    var replyClickListener: (() -> Unit)? = null
    var imageClickListener: ((String?, String?, String?, String?, Boolean?, Boolean?, String?) -> Unit)? = null
    private var selectedMessage: ChatMessage? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = messages?.get(position) ?: return
        val result = ChatAdapterUtils.prepareChatMessage(
            chatMessage, holder.textView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg,
            rewardChatMsg, replyMessage, null, useRandomColors, random, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints,
            namePaints, paintUsers, showStvBadges, stvBadges, stvBadgeUsers, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, enableOverlayEmotes,
            showSystemMessageEmotes, loggedInUser, chatUrl, getEmoteBytes, userColors, savedColors, translateAllMessages, translateMessage,
            showLanguageDownloadDialog, true, localTwitchEmotes, globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes,
            channelFfzEmotes, globalBadges, channelBadges, cheerEmotes, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes
        )
        holder.bind(chatMessage, result.builder)
        ChatAdapterUtils.loadImages(
            fragment, holder.textView, { holder.bind(chatMessage, it) }, result.images, result.imagePaint, result.userName, result.userNameStartIndex,
            backgroundColor, imageLibrary, result.builder, result.translated, emoteSize, badgeSize, emoteQuality, animateGifs, enableOverlayEmotes,
            chatMessage, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, true
        )
    }

    fun updateTranslation(chatMessage: ChatMessage, item: TextView, previousTranslation: String?) {
        (item.text as? SpannableString)?.let { text ->
            val builder = SpannableStringBuilder()
            builder.append(
                if (previousTranslation != null) {
                    text.dropLast(previousTranslation.length + 1)
                } else {
                    text
                }
            )
            if (!chatMessage.translationFailed) {
                ChatAdapterUtils.addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, true)
            }
            item.text = builder
        }
    }

    fun createMessageClickedChatAdapter(messages: List<ChatMessage>?): MessageClickedChatAdapter {
        return MessageClickedChatAdapter(
            enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { chatMessage -> selectedMessage = chatMessage; replyClickListener?.invoke() },
            { url, name, source, format, isAnimated, thirdParty, emoteId -> imageClickListener?.invoke(url, name, source, format, isAnimated, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, namePaints, paintUsers, showStvBadges,
            stvBadges, stvBadgeUsers, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, showSystemMessageEmotes, chatUrl, getEmoteBytes,
            fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize, emoteQuality, animateGifs, enableOverlayEmotes,
            translateAllMessages, translateMessage, showLanguageDownloadDialog, messages, userColors, savedColors, loggedInUser, localTwitchEmotes,
            globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, globalBadges, channelBadges,
            cheerEmotes, selectedMessage
        )
    }

    fun createReplyClickedChatAdapter(messages: List<ChatMessage>?): ReplyClickedChatAdapter {
        return ReplyClickedChatAdapter(
            enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { url, name, source, format, isAnimated, thirdParty, emoteId -> imageClickListener?.invoke(url, name, source, format, isAnimated, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, namePaints, paintUsers, showStvBadges,
            stvBadges, stvBadgeUsers, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, showSystemMessageEmotes, chatUrl, getEmoteBytes,
            fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize, emoteQuality, animateGifs, enableOverlayEmotes,
            translateAllMessages, translateMessage, showLanguageDownloadDialog, messages, userColors, savedColors, loggedInUser, localTwitchEmotes,
            globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, globalBadges, channelBadges,
            cheerEmotes, selectedMessage
        )
    }

    override fun getItemCount(): Int = messages?.size ?: 0

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.let { view ->
                view.getSpans<ImageSpan>().forEach {
                    (it.drawable as? Animatable)?.start() ?:
                    (it.drawable as? LayerDrawable)?.let {
                        val lastIndex = it.numberOfLayers - 1
                        if (lastIndex > -1) {
                            for (i in 0..lastIndex) {
                                (it.getDrawable(i) as? Animatable)?.start()
                            }
                        }
                    }
                }
                view.getSpans<NamePaintImageSpan>().forEach {
                    (it.drawable as? Animatable)?.start()
                }
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.let { view ->
                view.getSpans<ImageSpan>().forEach {
                    (it.drawable as? Animatable)?.stop() ?:
                    (it.drawable as? LayerDrawable)?.let {
                        val lastIndex = it.numberOfLayers - 1
                        if (lastIndex > -1) {
                            for (i in 0..lastIndex) {
                                (it.getDrawable(i) as? Animatable)?.stop()
                            }
                        }
                    }
                }
                view.getSpans<NamePaintImageSpan>().forEach {
                    (it.drawable as? Animatable)?.stop()
                }
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        val childCount = recyclerView.childCount
        if (animateGifs) {
            for (i in 0 until childCount) {
                ((recyclerView.getChildAt(i) as TextView).text as? Spannable)?.let { view ->
                    view.getSpans<ImageSpan>().forEach {
                        (it.drawable as? Animatable)?.stop() ?:
                        (it.drawable as? LayerDrawable)?.let {
                            val lastIndex = it.numberOfLayers - 1
                            if (lastIndex > -1) {
                                for (i in 0..lastIndex) {
                                    (it.getDrawable(i) as? Animatable)?.stop()
                                }
                            }
                        }
                    }
                    view.getSpans<NamePaintImageSpan>().forEach {
                        (it.drawable as? Animatable)?.stop()
                    }
                }
            }
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView = itemView as TextView

        fun bind(chatMessage: ChatMessage, formattedMessage: SpannableStringBuilder) {
            textView.apply {
                text = formattedMessage
                textSize = messageTextSize
                if (useHighVisibility) {
                    setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    setShadowLayer(0f, 0f, 0f, 0)
                    typeface = android.graphics.Typeface.DEFAULT
                }
                if (chatMessage.isReply) {
                    movementMethod = null
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    TooltipCompat.setTooltipText(this, chatMessage.replyParent?.message ?: chatMessage.replyParent?.systemMsg)
                    setOnClickListener {
                        if (selectionStart == -1 && selectionEnd == -1) {
                            selectedMessage = chatMessage.replyParent
                            messageClickListener?.invoke(channelId)
                        }
                    }
                } else {
                    movementMethod = LinkMovementMethod.getInstance()
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    TooltipCompat.setTooltipText(this, chatMessage.message ?: chatMessage.systemMsg)
                    setOnClickListener {
                        if (selectionStart == -1 && selectionEnd == -1) {
                            selectedMessage = chatMessage
                            messageClickListener?.invoke(channelId)
                        }
                    }
                }
            }
        }
    }
}
