package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.text.getSpans
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.LiveChatMessage
import com.github.andreyasadchy.xtra.model.chat.PubSubPointReward
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.chat.CenteredImageSpan
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import java.util.Random
import kotlin.collections.set

class ChatAdapter(
    private val fragment: Fragment,
    private val emoteSize: Int,
    private val badgeSize: Int,
    private val randomColor: Boolean,
    private val boldNames: Boolean,
    private val emoteQuality: String,
    private val animateGifs: Boolean,
    private val enableZeroWidth: Boolean,
    private val enableTimestamps: Boolean,
    private val timestampFormat: String?,
    private val firstMsgVisibility: String?,
    private val firstChatMsg: String,
    private val rewardChatMsg: String,
    private val redeemedChatMsg: String,
    private val redeemedNoMsg: String,
    private val imageLibrary: String?,
    private val channelId: String?) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    var messages: MutableList<ChatMessage>? = null
        set(value) {
            val oldSize = field?.size ?: 0
            if (oldSize > 0) {
                notifyItemRangeRemoved(0, oldSize)
            }
            field = value
        }
    private val twitchColors = intArrayOf(-65536, -16776961, -16744448, -5103070, -32944, -6632142, -47872, -13726889, -2448096, -2987746, -10510688, -14774017, -38476, -7722014, -16711809)
    private val noColor = -10066329
    private val random = Random()
    private val userColors = HashMap<String, Int>()
    private val savedColors = HashMap<String, Int>()
    private var globalStvEmotes: List<Emote>? = null
    private var channelStvEmotes: List<Emote>? = null
    private var globalBttvEmotes: List<Emote>? = null
    private var channelBttvEmotes: List<Emote>? = null
    private var globalFfzEmotes: List<Emote>? = null
    private var channelFfzEmotes: List<Emote>? = null
    private var globalBadges: List<TwitchBadge>? = null
    private var channelBadges: List<TwitchBadge>? = null
    private var cheerEmotes: List<CheerEmote>? = null
    private var loggedInUser: String? = null
    private val scaledEmoteSize = (emoteSize * 0.78f).toInt()

    private var messageClickListener: ((CharSequence, CharSequence, String?, String?, String?) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = messages?.get(position) ?: return
        val liveMessage = chatMessage as? LiveChatMessage
        val pointReward = chatMessage as? PubSubPointReward ?: liveMessage?.pointReward
        val builder = SpannableStringBuilder()
        val images = ArrayList<Image>()
        var imageIndex = 0
        var badgesCount = 0
        val systemMsg = liveMessage?.systemMsg
        if (systemMsg != null) {
            builder.append("$systemMsg\n")
            imageIndex += systemMsg.length + 1
        } else {
            val msgId = liveMessage?.msgId?.let { TwitchApiHelper.getMessageIdString(it) ?: it }
            if (msgId != null) {
                builder.append("$msgId\n")
                imageIndex += msgId.length + 1
            }
        }
        if (liveMessage?.isFirst == true && firstMsgVisibility == "0") {
            builder.append("$firstChatMsg\n")
            imageIndex += firstChatMsg.length + 1
        }
        if (liveMessage?.rewardId != null && pointReward == null && firstMsgVisibility == "0") {
            builder.append("$rewardChatMsg\n")
            imageIndex += rewardChatMsg.length + 1
        }
        if (!pointReward?.message.isNullOrBlank()) {
            val string = redeemedChatMsg.format(pointReward?.rewardTitle)
            builder.append("$string ")
            imageIndex += string.length + 1
            builder.append("  ")
            images.add(Image(
                url1x = pointReward?.rewardImage?.url1,
                url2x = pointReward?.rewardImage?.url2,
                url3x = pointReward?.rewardImage?.url4,
                url4x = pointReward?.rewardImage?.url4,
                start = imageIndex++,
                end = imageIndex++,
                isEmote = false
            ))
            badgesCount++
            builder.append("${pointReward?.rewardCost}\n")
            imageIndex += (pointReward?.rewardCost?.toString()?.length ?: 0) + 1
        }
        val timestamp = liveMessage?.timestamp?.let { TwitchApiHelper.getTimestamp(it, timestampFormat) } ?: pointReward?.timestamp?.let { TwitchApiHelper.getTimestamp(it, timestampFormat) }
        if (enableTimestamps && timestamp != null) {
            builder.append("$timestamp ")
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#999999")), imageIndex, imageIndex + timestamp.length, SPAN_INCLUSIVE_INCLUSIVE)
            imageIndex += timestamp.length + 1
        }
        chatMessage.badges?.forEach { chatBadge ->
            val badge = channelBadges?.find { it.setId == chatBadge.setId && it.version == chatBadge.version } ?: globalBadges?.find { it.setId == chatBadge.setId && it.version == chatBadge.version }
            badge?.let {
                builder.append("  ")
                images.add(Image(
                    url1x = it.url1x,
                    url2x = it.url2x,
                    url3x = it.url3x,
                    url4x = it.url4x,
                    start = imageIndex++,
                    end = imageIndex++,
                    isEmote = false
                ))
                badgesCount++
            }
        }
        val fullMsg = chatMessage.fullMsg
        val userId = chatMessage.userId
        val userName = chatMessage.userName
        val userNameLength = userName?.length ?: 0
        val userNameEndIndex = imageIndex + userNameLength
        val originalMessage: String
        val userNameWithPostfixLength: Int
        if (chatMessage !is PubSubPointReward && !userName.isNullOrBlank()) {
            builder.append(userName)
            if (!chatMessage.isAction) {
                builder.append(": ")
                originalMessage = "$userName: ${chatMessage.message}"
                userNameWithPostfixLength = userNameLength + 2
            } else {
                builder.append(" ")
                originalMessage = "$userName ${chatMessage.message}"
                userNameWithPostfixLength = userNameLength + 1
            }
        } else {
            if (chatMessage is PubSubPointReward && pointReward?.message.isNullOrBlank()) {
                val string = redeemedNoMsg.format(userName, pointReward?.rewardTitle)
                builder.append("$string ")
                imageIndex += string.length + 1
                builder.append("  ")
                images.add(Image(
                    url1x = pointReward?.rewardImage?.url1,
                    url2x = pointReward?.rewardImage?.url2,
                    url3x = pointReward?.rewardImage?.url4,
                    url4x = pointReward?.rewardImage?.url4,
                    type = null,
                    isAnimated = false,
                    isZeroWidth = false,
                    start = imageIndex++,
                    end = imageIndex++,
                    isEmote = false
                ))
                badgesCount++
                builder.append("${pointReward?.rewardCost}")
                imageIndex += pointReward?.rewardCost?.toString()?.length ?: 0
                originalMessage = "$userName: ${chatMessage.message}"
                userNameWithPostfixLength = imageIndex
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#999999")), 0, imageIndex, SPAN_INCLUSIVE_INCLUSIVE)
            } else {
                originalMessage = "${chatMessage.message}"
                userNameWithPostfixLength = 0
            }
        }
        builder.append(chatMessage.message)
        val color = if (chatMessage is PubSubPointReward) null else
            chatMessage.color.let { userColor ->
                if (userColor == null) {
                    userColors[userName] ?: getRandomColor().also { if (userName != null) userColors[userName] = it }
                } else {
                    savedColors[userColor] ?: Color.parseColor(userColor).also { savedColors[userColor] = it }
                }
            }
        if (color != null && userName != null) {
            builder.setSpan(ForegroundColorSpan(color), imageIndex, userNameEndIndex, SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(if (boldNames) Typeface.BOLD else Typeface.NORMAL), imageIndex, userNameEndIndex, SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        try {
            chatMessage.emotes?.let { emotes ->
                val copy = emotes.map {
                    val realBegin = chatMessage.message?.offsetByCodePoints(0, it.begin) ?: 0
                    val realEnd = if (it.begin == realBegin) {
                        it.end
                    } else {
                        it.end + realBegin - it.begin
                    }
                    TwitchEmote(id = it.id, begin = realBegin, end = realEnd)
                }
                imageIndex += userNameWithPostfixLength
                for (e in copy) {
                    val begin = imageIndex + e.begin
                    builder.replace(begin, imageIndex + e.end + 1, ".")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), begin, begin + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    val length = e.end - e.begin
                    for (e1 in copy) {
                        if (e.begin < e1.begin) {
                            e1.begin -= length
                            e1.end -= length
                        }
                    }
                    e.end -= length
                }
                copy.forEach { images.add(Image(
                    url1x = it.url1x,
                    url2x = it.url2x,
                    url3x = it.url3x,
                    url4x = it.url4x,
                    type = it.type,
                    isAnimated = it.isAnimated,
                    isZeroWidth = it.isZeroWidth,
                    start = imageIndex + it.begin,
                    end = imageIndex + it.end + 1,
                    isEmote = true
                )) }
            }
            val split = builder.substring(userNameWithPostfixLength).split(" ")
            var builderIndex = userNameWithPostfixLength
            var emotesFound = 0
            var wasMentioned = false
            for (value in split) {
                val length = value.length
                val endIndex = builderIndex + length
                var emote =
                    channelStvEmotes?.find { it.name == value } ?:
                    channelBttvEmotes?.find { it.name == value } ?:
                    channelFfzEmotes?.find { it.name == value } ?:
                    globalStvEmotes?.find { it.name == value } ?:
                    globalBttvEmotes?.find { it.name == value } ?:
                    globalFfzEmotes?.find { it.name == value }
                val bitsCount = value.takeLastWhile { it.isDigit() }
                val bitsName = value.substringBeforeLast(bitsCount)
                if (bitsCount.isNotEmpty()) {
                    val cheerEmote = if (liveMessage == null || liveMessage.bits != null) {
                        cheerEmotes?.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
                    } else null
                    if (cheerEmote != null) {
                        emote = cheerEmote
                        if (emote.color != null) {
                            builder.setSpan(ForegroundColorSpan(Color.parseColor(emote.color)), builderIndex + bitsName.length, endIndex, SPAN_INCLUSIVE_INCLUSIVE)
                        }
                    }
                }
                if (emote == null) {
                    if (!Patterns.WEB_URL.matcher(value).matches()) {
                        if (value.startsWith('@')) {
                            builder.setSpan(StyleSpan(if (boldNames) Typeface.BOLD else Typeface.NORMAL), builderIndex, endIndex, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        loggedInUser?.let {
                            if (!wasMentioned && value.contains(it, true) && chatMessage.userLogin != it) {
                                wasMentioned = true
                            }
                        }
                    } else {
                        val url = if (value.startsWith("http")) value else "https://$value"
                        builder.setSpan(URLSpan(url), builderIndex, endIndex, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    builderIndex += length + 1
                } else {
                    for (j in images.lastIndex - emotesFound downTo badgesCount) {
                        val e = images[j]
                        if (e.start > builderIndex) {
                            val remove = if (emote is CheerEmote) {
                                length - 1 - bitsCount.length
                            } else {
                                length - 1
                            }
                            e.start -= remove
                            e.end -= remove
                        }
                    }
                    if (emote is CheerEmote) {
                        builder.replace(builderIndex, builderIndex + bitsName.length, ".")
                    } else {
                        builder.replace(builderIndex, endIndex, ".")
                    }
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    images.add(Image(
                        url1x = emote.url1x,
                        url2x = emote.url2x,
                        url3x = emote.url3x,
                        url4x = emote.url4x,
                        type = emote.type,
                        isAnimated = emote.isAnimated,
                        isZeroWidth = emote.isZeroWidth,
                        start = builderIndex,
                        end = builderIndex + 1,
                        isEmote = true
                    ))
                    emotesFound++
                    builderIndex += 2
                    if (emote is CheerEmote) {
                        builderIndex += bitsCount.length
                    }
                }
            }
            if (color != null && chatMessage.isAction) {
                builder.setSpan(ForegroundColorSpan(color), if (userName != null) userNameEndIndex + 1 else 0, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when {
                liveMessage?.isFirst == true && (firstMsgVisibility?.toInt() ?: 0) < 2 -> holder.textView.setBackgroundResource(R.color.chatMessageFirst)
                liveMessage?.rewardId != null && (firstMsgVisibility?.toInt() ?: 0) < 2 -> holder.textView.setBackgroundResource(R.color.chatMessageReward)
                liveMessage?.systemMsg != null || liveMessage?.msgId != null -> holder.textView.setBackgroundResource(R.color.chatMessageNotice)
                wasMentioned && userId != null -> holder.textView.setBackgroundResource(R.color.chatMessageMention)
                else -> holder.textView.background = null
            }
        } catch (e: Exception) {
//            Crashlytics.logException(e)
        }
        holder.bind(originalMessage, builder, userId, channelId, fullMsg)
        loadImages(holder, images, originalMessage, builder, userId, channelId, fullMsg)
    }

    override fun getItemCount(): Int = messages?.size ?: 0

    private fun loadImages(holder: ViewHolder, images: List<Image>, originalMessage: CharSequence, builder: SpannableStringBuilder, userId: String?, channelId: String?, fullMsg: String?) {
        images.forEach {
            when (imageLibrary) {
                "0" -> loadCoil(holder, it, originalMessage, builder, userId, channelId, fullMsg)
                "1" -> {
                    when {
                        it.type.equals("webp", true) -> loadGlide(holder, it, originalMessage, builder, userId, channelId, fullMsg)
                        else -> loadCoil(holder, it, originalMessage, builder, userId, channelId, fullMsg)
                    }
                }
                else -> loadGlide(holder, it, originalMessage, builder, userId, channelId, fullMsg)
            }
        }
    }

    private fun loadCoil(holder: ViewHolder, image: Image, originalMessage: CharSequence, builder: SpannableStringBuilder, userId: String?, channelId: String?, fullMsg: String?) {
        val request = ImageRequest.Builder(fragment.requireContext())
            .data(when (emoteQuality) {
                "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                "3" -> image.url3x ?: image.url2x ?: image.url1x
                "2" -> image.url2x ?: image.url1x
                else -> image.url1x
            })
            .target(
                onSuccess = { result ->
                    val size = if (image.isEmote) {
                        calculateEmoteSize(result)
                    } else {
                        Pair(badgeSize, badgeSize)
                    }
                    if (image.isZeroWidth && enableZeroWidth) {
                        result.setBounds(-90, 0, size.first - 90, size.second)
                    } else {
                        result.setBounds(0, 0, size.first, size.second)
                    }
                    if (result is Animatable && image.isAnimated != false && animateGifs) {
                        result.callback = object : Drawable.Callback {
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                holder.textView.removeCallbacks(what)
                            }

                            override fun invalidateDrawable(who: Drawable) {
                                holder.textView.invalidate()
                            }

                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                holder.textView.postDelayed(what, `when`)
                            }
                        }
                        (result as Animatable).start()
                    }
                    try {
                        builder.setSpan(CenteredImageSpan(result), image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    } catch (e: IndexOutOfBoundsException) {
                    }
                    holder.bind(originalMessage, builder, userId, channelId, fullMsg)
                },
            )
            .build()
        fragment.requireContext().imageLoader.enqueue(request)
    }

    private fun loadGlide(holder: ViewHolder, image: Image, originalMessage: CharSequence, builder: SpannableStringBuilder, userId: String?, channelId: String?, fullMsg: String?) {
        Glide.with(fragment)
            .load(when (emoteQuality) {
                "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                "3" -> image.url3x ?: image.url2x ?: image.url1x
                "2" -> image.url2x ?: image.url1x
                else -> image.url1x
            })
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    val size = if (image.isEmote) {
                        calculateEmoteSize(resource)
                    } else {
                        Pair(badgeSize, badgeSize)
                    }
                    if (image.isZeroWidth && enableZeroWidth) {
                        resource.setBounds(-90, 0, size.first - 90, size.second)
                    } else {
                        resource.setBounds(0, 0, size.first, size.second)
                    }
                    if (resource is Animatable && image.isAnimated != false && animateGifs) {
                        resource.callback = object : Drawable.Callback {
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                holder.textView.removeCallbacks(what)
                            }

                            override fun invalidateDrawable(who: Drawable) {
                                holder.textView.invalidate()
                            }

                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                holder.textView.postDelayed(what, `when`)
                            }
                        }
                        (resource as Animatable).start()
                    }
                    try {
                        builder.setSpan(CenteredImageSpan(resource), image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    } catch (e: IndexOutOfBoundsException) {
                    }
                    holder.bind(originalMessage, builder, userId, channelId, fullMsg)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
    }

    fun addGlobalStvEmotes(list: List<Emote>?) {
        globalStvEmotes = list
    }

    fun addChannelStvEmotes(list: List<Emote>?) {
        channelStvEmotes = list
    }

    fun addGlobalBttvEmotes(list: List<Emote>?) {
        globalBttvEmotes = list
    }

    fun addChannelBttvEmotes(list: List<Emote>?) {
        channelBttvEmotes = list
    }

    fun addGlobalFfzEmotes(list: List<Emote>?) {
        globalFfzEmotes = list
    }

    fun addChannelFfzEmotes(list: List<Emote>?) {
        channelFfzEmotes = list
    }

    fun addGlobalBadges(list: List<TwitchBadge>?) {
        globalBadges = list
    }

    fun addChannelBadges(list: List<TwitchBadge>?) {
        channelBadges = list
    }

    fun addCheerEmotes(list: List<CheerEmote>?) {
        cheerEmotes = list
    }

    fun setUsername(username: String?) {
        loggedInUser = username
    }

    fun setOnClickListener(listener: (CharSequence, CharSequence, String?, String?, String?) -> Unit) {
        messageClickListener = listener
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.getSpans<ImageSpan>()?.forEach {
                (it.drawable as? Animatable)?.start()
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.getSpans<ImageSpan>()?.forEach {
                (it.drawable as? Animatable)?.stop()
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        val childCount = recyclerView.childCount
        if (animateGifs) {
            for (i in 0 until childCount) {
                ((recyclerView.getChildAt(i) as TextView).text as? Spannable)?.getSpans<ImageSpan>()?.forEach {
                    (it.drawable as? Animatable)?.stop()
                }
            }
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun getRandomColor(): Int =
        if (randomColor) {
            twitchColors[random.nextInt(twitchColors.size)]
        } else {
            noColor
        }

    private fun calculateEmoteSize(resource: Drawable): Pair<Int, Int> {
        val widthRatio = resource.intrinsicWidth.toFloat() / resource.intrinsicHeight.toFloat()
        val width: Int
        val height: Int
        when {
            widthRatio == 1f -> {
                width = emoteSize
                height = emoteSize
            }
            widthRatio <= 1.2f -> {
                width = (emoteSize * widthRatio).toInt()
                height = emoteSize
            }
            else -> {
                width = (scaledEmoteSize * widthRatio).toInt()
                height = scaledEmoteSize
            }
        }
        return width to height
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView = itemView as TextView

        fun bind(originalMessage: CharSequence, formattedMessage: SpannableStringBuilder, userId: String?, channelId: String?, fullMsg: String?) {
            textView.apply {
                text = formattedMessage
                movementMethod = LinkMovementMethod.getInstance()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tooltipText = originalMessage
                } else {
                    TooltipCompat.setTooltipText(this, originalMessage);
                }
                setOnClickListener { messageClickListener?.invoke(originalMessage, formattedMessage, userId, channelId, fullMsg) }
            }
        }
    }
}
