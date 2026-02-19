package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ImageSpan
import android.util.Patterns
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

class MessageClickedChatAdapter(
    messages: List<ChatMessage>,
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
    private val replyClick: (ChatMessage) -> Unit,
    private val imageClick: (String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit,
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
    private val imageLibrary: String?,
    private val messageTextSize: Float,
    private val emoteSize: Int,
    private val badgeSize: Int,
    private val emoteQuality: String,
    private val animateGifs: Boolean,
    private val enableOverlayEmotes: Boolean,
    private val translateAllMessages: Boolean,
    private val translateMessage: (ChatMessage, String?) -> Unit,
    private val showLanguageDownloadDialog: (ChatMessage, String) -> Unit,
    private val random: Random,
    private val userColors: HashMap<String, Int>,
    private val savedColors: HashMap<String, Int>,
    private val savedLocalTwitchEmotes: MutableMap<String, ByteArray>,
    private val savedLocalBadges: MutableMap<String, ByteArray>,
    private val savedLocalCheerEmotes: MutableMap<String, ByteArray>,
    private val savedLocalEmotes: MutableMap<String, ByteArray>,
    private val loggedInUser: String?,
    var selectedMessage: ChatMessage?,
) : RecyclerView.Adapter<MessageClickedChatAdapter.ViewHolder>() {

    val userId = selectedMessage?.userId
    val userLogin = selectedMessage?.userLogin
    val messages = if (!userId.isNullOrBlank() || !userLogin.isNullOrBlank()) {
        synchronized(messages) {
            messages.filter {
                (!userId.isNullOrBlank() && (it.userId == userId || it.replyParent?.userId == userId)) ||
                        (!userLogin.isNullOrBlank() && (it.userLogin == userLogin || it.replyParent?.userLogin == userLogin))
            }.toMutableList().ifEmpty { null }
        }
    } else {
        null
    } ?: selectedMessage?.let { mutableListOf(it) } ?: mutableListOf()

    var messageClickListener: ((ChatMessage, ChatMessage?) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = synchronized(messages) {
            messages.getOrNull(position)
        } ?: return
        val result = ChatAdapterUtils.prepareChatMessage(
            chatMessage, holder.textView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg,
            rewardChatMsg, replyMessage, { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClick(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, random, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, namePaints, showStvBadges,
            stvBadges, showPersonalEmotes, personalEmoteSets, stvUsers, showSystemMessageEmotes, enableOverlayEmotes, loggedInUser, chatUrl,
            getEmoteBytes, userColors, savedColors, translateAllMessages, translateMessage, showLanguageDownloadDialog, false, localTwitchEmotes,
            thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes
        )
        if (chatMessage == selectedMessage) {
            holder.textView.setBackgroundResource(R.color.chatMessageSelected)
        }
        holder.bind(chatMessage, result.builder)
        ChatAdapterUtils.loadImages(
            fragment, holder.textView, { holder.bind(chatMessage, it) }, result.images, result.imagePaint, result.userName, result.userNameStartIndex,
            backgroundColor, imageLibrary, result.builder, result.translated, emoteSize, badgeSize, emoteQuality, animateGifs, enableOverlayEmotes,
            chatMessage, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, false
        )
    }

    fun updateBackground(chatMessage: ChatMessage, item: TextView) {
        if (chatMessage.message.isNullOrBlank()) {
            item.setBackgroundResource(0)
        } else {
            when {
                chatMessage.isFirst && firstMsgVisibility < 2 -> item.setBackgroundResource(R.color.chatMessageFirst)
                chatMessage.reward?.id != null && firstMsgVisibility < 2 -> item.setBackgroundResource(R.color.chatMessageReward)
                chatMessage.systemMsg != null || chatMessage.msgId != null -> item.setBackgroundResource(R.color.chatMessageNotice)
                loggedInUser?.let { user ->
                    if (chatMessage.userId != null && chatMessage.userLogin != user) {
                        item.text.split(" ").find {
                            !Patterns.WEB_URL.matcher(it).matches() && it.contains(user, true)
                        } != null
                    } else false
                } == true -> item.setBackgroundResource(R.color.chatMessageMention)
                else -> item.setBackgroundResource(0)
            }
        }
        (item.text as? Spannable)?.let { view ->
            view.getSpans<NamePaintImageSpan>().forEach {
                it.backgroundColor = (item.background as? ColorDrawable)?.color
                view.setSpan(it, view.getSpanStart(it), view.getSpanEnd(it), SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
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
            ChatAdapterUtils.addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, false)
            item.text = builder
        }
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
                        chatMessage.replyParent?.let { replyClick(it) }
                    }
                } else {
                    movementMethod = LinkMovementMethod.getInstance()
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    TooltipCompat.setTooltipText(this, chatMessage.message ?: chatMessage.systemMsg)
                    setOnClickListener {
                        if (selectionStart == -1 && selectionEnd == -1 && chatMessage != selectedMessage) {
                            messageClickListener?.invoke(chatMessage, selectedMessage)
                            selectedMessage = chatMessage
                            setBackgroundResource(R.color.chatMessageSelected)
                            (text as? Spannable)?.let { view ->
                                view.getSpans<NamePaintImageSpan>().forEach {
                                    it.backgroundColor = (background as? ColorDrawable)?.color
                                    view.setSpan(it, view.getSpanStart(it), view.getSpanEnd(it), SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
