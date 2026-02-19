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
import com.github.andreyasadchy.xtra.model.chat.StvUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import java.util.Random

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val localTwitchEmotes: List<TwitchEmote>,
    private val thirdPartyEmotes: List<Emote>,
    private val globalBadges: List<TwitchBadge>,
    private val channelBadges: List<TwitchBadge>,
    private val cheerEmotes: List<CheerEmote>,
    private val namePaints: List<NamePaint>,
    private val stvBadges: List<StvBadge>,
    private val personalEmoteSets: Map<String, List<Emote>>,
    private val stvUsers: List<StvUser>,
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
    private val showStvBadges: Boolean,
    private val showPersonalEmotes: Boolean,
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
    private val loggedInUser: String?,
    private val messageClickListener: ((String?) -> Unit)?,
    private val replyClickListener: (() -> Unit)?,
    private val imageClickListener: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?,
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    var translateAllMessages = false
    private var selectedMessage: ChatMessage? = null
    private val random = Random()
    private val userColors = HashMap<String, Int>()
    private val savedColors = HashMap<String, Int>()
    private val savedLocalTwitchEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalBadges = mutableMapOf<String, ByteArray>()
    private val savedLocalCheerEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalEmotes = mutableMapOf<String, ByteArray>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = synchronized(messages) {
            messages.getOrNull(position)
        } ?: return
        val result = ChatAdapterUtils.prepareChatMessage(
            chatMessage, holder.textView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg,
            rewardChatMsg, replyMessage, null, useRandomColors, random, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints,
            namePaints, showStvBadges, stvBadges, showPersonalEmotes, personalEmoteSets, stvUsers, enableOverlayEmotes, showSystemMessageEmotes,
            loggedInUser, chatUrl, getEmoteBytes, userColors, savedColors, translateAllMessages, translateMessage, showLanguageDownloadDialog,
            true, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, savedLocalTwitchEmotes, savedLocalBadges,
            savedLocalCheerEmotes, savedLocalEmotes
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

    fun createMessageClickedChatAdapter(): MessageClickedChatAdapter {
        return MessageClickedChatAdapter(
            messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { chatMessage -> selectedMessage = chatMessage; replyClickListener?.invoke() },
            { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, showStvBadges, showPersonalEmotes,
            showSystemMessageEmotes, chatUrl, getEmoteBytes, fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize,
            emoteQuality, animateGifs, enableOverlayEmotes, translateAllMessages, translateMessage, showLanguageDownloadDialog, random, userColors,
            savedColors, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes, loggedInUser, selectedMessage
        )
    }

    fun createReplyClickedChatAdapter(): ReplyClickedChatAdapter {
        return ReplyClickedChatAdapter(
            messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, showStvBadges, showPersonalEmotes,
            showSystemMessageEmotes, chatUrl, getEmoteBytes, fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize,
            emoteQuality, animateGifs, enableOverlayEmotes, translateAllMessages, translateMessage, showLanguageDownloadDialog, random, userColors,
            savedColors, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes, loggedInUser, selectedMessage
        )
    }

    override fun getItemCount(): Int = synchronized(messages) {
        messages.size
    }

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
