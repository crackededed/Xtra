package com.github.andreyasadchy.xtra.util.chat

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.Patterns
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.StvBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.chat.ImageClickedDialog
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintSpan
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import java.util.Random
import kotlin.math.floor
import kotlin.math.pow

object ChatAdapterUtils {

    private val twitchColors = intArrayOf(-65536, -16776961, -16744448, -5103070, -32944, -6632142, -47872, -13726889, -2448096, -2987746, -10510688, -14774017, -38476, -7722014, -16711809)
    private const val RED_HUE_DEGREES = 0f
    private const val GREEN_HUE_DEGREES = 120f
    private const val BLUE_HUE_DEGREES = 240f
    private const val PI_DEGREES = 180f
    private const val TWO_PI_DEGREES = 360f

    fun prepareChatMessage(chatMessage: ChatMessage, itemView: View, enableTimestamps: Boolean, timestampFormat: String?, firstMsgVisibility: Int, firstChatMsg: String, redeemedChatMsg: String, redeemedNoMsg: String, rewardChatMsg: String, replyMessage: String, imageClick: ((String?, String?, String?, String?, Boolean?, String?) -> Unit)?, useRandomColors: Boolean, random: Random, useReadableColors: Boolean, isLightTheme: Boolean, nameDisplay: String?, useBoldNames: Boolean, showNamePaints: Boolean, namePaints: List<NamePaint>?, paintUsers: Map<String, String>?, showStvBadges: Boolean, stvBadges: List<StvBadge>?, stvBadgeUsers: Map<String, String>?, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>?, personalEmoteSetUsers: Map<String, String>?, enableOverlayEmotes: Boolean, showSystemMessageEmotes: Boolean, loggedInUser: String?, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?, userColors: HashMap<String, Int>, savedColors: HashMap<String, Int>, localTwitchEmotes: List<TwitchEmote>?, globalStvEmotes: List<Emote>?, channelStvEmotes: List<Emote>?, globalBttvEmotes: List<Emote>?, channelBttvEmotes: List<Emote>?, globalFfzEmotes: List<Emote>?, channelFfzEmotes: List<Emote>?, globalBadges: List<TwitchBadge>?, channelBadges: List<TwitchBadge>?, cheerEmotes: List<CheerEmote>?, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalBadges: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>): Triple<SpannableStringBuilder, ArrayList<Image>, Triple<NamePaint, String, Int>?> {
        val builder = SpannableStringBuilder()
        val images = ArrayList<Image>()
        var imagePaint: Triple<NamePaint, String, Int>? = null
        var builderIndex = 0
        when {
            chatMessage.isReply -> {
                val userName = if (chatMessage.reply?.userName != null && chatMessage.reply.userLogin != null && !chatMessage.reply.userLogin.equals(chatMessage.reply.userName, true)) {
                    when (nameDisplay) {
                        "0" -> "${chatMessage.reply.userName}(${chatMessage.reply.userLogin})"
                        "1" -> chatMessage.reply.userName
                        else -> chatMessage.reply.userLogin
                    }
                } else {
                    chatMessage.reply?.userName ?: chatMessage.reply?.userLogin
                }
                val string = replyMessage.format(userName, "")
                builder.append(string)
                builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), 0, string.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                builderIndex += string.length
                val message = chatMessage.reply?.message
                if (message != null) {
                    builder.append(message)
                    builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + message.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    prepareEmotes(chatMessage, message, builder, builderIndex, images, null, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                }
                itemView.setBackgroundResource(0)
            }
            chatMessage.message.isNullOrBlank() && (chatMessage.systemMsg != null || chatMessage.reward?.title != null) -> {
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), 0, timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                if (chatMessage.systemMsg != null) {
                    builder.append(chatMessage.systemMsg)
                    builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + chatMessage.systemMsg.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (showSystemMessageEmotes) {
                        prepareEmotes(chatMessage, chatMessage.systemMsg, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                    }
                    builderIndex = builder.length
                } else {
                    if (chatMessage.reward?.title != null) {
                        val userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                            when (nameDisplay) {
                                "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                                "1" -> chatMessage.userName
                                else -> chatMessage.userLogin
                            }
                        } else {
                            chatMessage.userName
                        }
                        val string = redeemedNoMsg.format(userName, chatMessage.reward.title)
                        builder.append("$string ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + string.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (showSystemMessageEmotes) {
                            prepareEmotes(chatMessage, string, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                        }
                        builderIndex = builder.length
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        images.add(Image(
                            url1x = chatMessage.reward.url1x,
                            url2x = chatMessage.reward.url2x,
                            url3x = chatMessage.reward.url4x,
                            url4x = chatMessage.reward.url4x,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                        if (chatMessage.reward.cost != null) {
                            builder.append("${chatMessage.reward.cost}")
                            builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + chatMessage.reward.cost.toString().length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            builderIndex += chatMessage.reward.cost.toString().length
                        }
                    }
                }
                itemView.setBackgroundResource(0)
            }
            else -> {
                if (chatMessage.systemMsg != null) {
                    builder.append("${chatMessage.systemMsg}\n")
                    builderIndex += chatMessage.systemMsg.length + 1
                } else {
                    if (chatMessage.msgId != null) {
                        val msgId = TwitchApiHelper.getMessageIdString(chatMessage.msgId) ?: chatMessage.msgId
                        builder.append("$msgId\n")
                        builderIndex += msgId.length + 1
                    }
                }
                if (chatMessage.isFirst && firstMsgVisibility == 0) {
                    builder.append("$firstChatMsg\n")
                    builderIndex += firstChatMsg.length + 1
                }
                if (chatMessage.reward?.title != null) {
                    val string = redeemedChatMsg.format(chatMessage.reward.title)
                    builder.append("$string ")
                    builderIndex += string.length + 1
                    builder.append(". ")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    images.add(Image(
                        url1x = chatMessage.reward.url1x,
                        url2x = chatMessage.reward.url2x,
                        url3x = chatMessage.reward.url4x,
                        url4x = chatMessage.reward.url4x,
                        start = builderIndex++,
                        end = builderIndex++
                    ))
                    if (chatMessage.reward.cost != null) {
                        builder.append("${chatMessage.reward.cost}")
                        builderIndex += chatMessage.reward.cost.toString().length
                    }
                    builder.append("\n")
                    builderIndex += 1
                } else {
                    if (chatMessage.reward?.id != null && firstMsgVisibility == 0) {
                        builder.append("$rewardChatMsg\n")
                        builderIndex += rewardChatMsg.length + 1
                    }
                }
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                chatMessage.badges?.forEach { chatBadge ->
                    val badge = channelBadges?.find { it.setId == chatBadge.setId && it.version == chatBadge.version } ?: globalBadges?.find { it.setId == chatBadge.setId && it.version == chatBadge.version }
                    if (badge != null) {
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.title, null, null, null, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        images.add(Image(
                            localData = badge.localData?.let { getLocalBadgeData(badge.setId + badge.version, it, savedLocalBadges, chatUrl, getEmoteBytes) },
                            url1x = badge.url1x,
                            url2x = badge.url2x,
                            url3x = badge.url3x,
                            url4x = badge.url4x,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                    }
                }
                if (showStvBadges && !chatMessage.userId.isNullOrBlank()) {
                    stvBadgeUsers?.get(chatMessage.userId)?.let { badgeId -> stvBadges?.find { it.id == badgeId } }?.let { badge ->
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.name, null, badge.format, true, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        images.add(Image(
                            url1x = badge.url1x,
                            url2x = badge.url2x,
                            url3x = badge.url3x,
                            url4x = badge.url4x,
                            format = badge.format,
                            isAnimated = true,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                    }
                }
                val color = if (chatMessage.color != null) {
                    getSavedColor(chatMessage.color, savedColors, useReadableColors, isLightTheme)
                } else {
                    userColors[chatMessage.userName] ?: if (useRandomColors) {
                        twitchColors[random.nextInt(twitchColors.size)]
                    } else {
                        -10066329
                    }.let { newColor ->
                        if (useReadableColors) {
                            adaptUsernameColor(newColor, isLightTheme)
                        } else {
                            newColor
                        }.also { if (chatMessage.userName != null) userColors[chatMessage.userName] = it }
                    }
                }
                if (!chatMessage.userName.isNullOrBlank()) {
                    val userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                        when (nameDisplay) {
                            "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                            "1" -> chatMessage.userName
                            else -> chatMessage.userLogin
                        }
                    } else {
                        chatMessage.userName
                    }
                    builder.append(userName)
                    builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (useBoldNames) {
                        builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (showNamePaints && !chatMessage.userId.isNullOrBlank()) {
                        paintUsers?.get(chatMessage.userId)?.let { paintId -> namePaints?.find { it.id == paintId } }?.let { paint ->
                            when (paint.type) {
                                "LINEAR_GRADIENT", "RADIAL_GRADIENT" -> {
                                    if (paint.colors != null && paint.colorPositions != null) {
                                        builder.setSpan(
                                            NamePaintSpan(
                                                userName,
                                                paint.type,
                                                paint.colors,
                                                paint.colorPositions,
                                                paint.angle,
                                                paint.repeat,
                                                paint.shadows
                                            ),
                                            builderIndex,
                                            builderIndex + userName.length,
                                            SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                }
                                "URL" -> {
                                    if (!paint.imageUrl.isNullOrBlank()) {
                                        imagePaint = Triple(paint, userName, builderIndex)
                                    }
                                }
                            }
                        }
                    }
                    builderIndex += userName.length
                    if (!chatMessage.isAction) {
                        builder.append(": ")
                        builderIndex += 2
                    } else {
                        builder.append(" ")
                        builderIndex += 1
                    }
                }
                val wasMentioned = if (chatMessage.message != null) {
                    builder.append(chatMessage.message)
                    if (chatMessage.isAction) {
                        builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + chatMessage.message.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    prepareEmotes(chatMessage, chatMessage.message, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, personalEmoteSetUsers, globalStvEmotes, channelStvEmotes, globalBttvEmotes, channelBttvEmotes, globalFfzEmotes, channelFfzEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes) == true
                } else false
                when {
                    chatMessage.isFirst && firstMsgVisibility < 2 -> itemView.setBackgroundResource(R.color.chatMessageFirst)
                    chatMessage.reward?.id != null && firstMsgVisibility < 2 -> itemView.setBackgroundResource(R.color.chatMessageReward)
                    chatMessage.systemMsg != null || chatMessage.msgId != null -> itemView.setBackgroundResource(R.color.chatMessageNotice)
                    wasMentioned -> itemView.setBackgroundResource(R.color.chatMessageMention)
                    else -> itemView.setBackgroundResource(0)
                }
            }
        }
        return Triple(builder, images, imagePaint)
    }

    private fun getSavedColor(color: String, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean): Int {
        return savedColors[color] ?: Color.parseColor(color).let { newColor ->
            if (useReadableColors) {
                adaptUsernameColor(newColor, isLightTheme)
            } else {
                newColor
            }.also { savedColors[color] = it }
        }
    }

    private fun adaptUsernameColor(color: Int, isLightTheme: Boolean): Int {
        val colorArray = FloatArray(3)
        ColorUtils.colorToHSL(color, colorArray)
        if (isLightTheme) {
            val luminanceMax = 0.75f -
                    maxOf(1f - ((colorArray[0] - GREEN_HUE_DEGREES) / 100f).pow(2f), RED_HUE_DEGREES) * 0.4f
            colorArray[2] = minOf(colorArray[2], luminanceMax)
        } else {
            val distToRed = RED_HUE_DEGREES - colorArray[0]
            val distToBlue = BLUE_HUE_DEGREES - colorArray[0]
            val normDistanceToRed = distToRed - TWO_PI_DEGREES * floor((distToRed + PI_DEGREES) / TWO_PI_DEGREES)
            val normDistanceToBlue = distToBlue - TWO_PI_DEGREES * floor((distToBlue + PI_DEGREES) / TWO_PI_DEGREES)

            val luminanceMin = 0.3f +
                    maxOf((1f - (normDistanceToBlue / 40f).pow(2f)) * 0.35f, RED_HUE_DEGREES) +
                    maxOf((1f - (normDistanceToRed / 40f).pow(2f)) * 0.1f, RED_HUE_DEGREES)
            colorArray[2] = maxOf(colorArray[2], luminanceMin)
        }

        return ColorUtils.HSLToColor(colorArray)
    }

    private fun prepareEmotes(chatMessage: ChatMessage, message: String, builder: SpannableStringBuilder, startIndex: Int, images: ArrayList<Image>, imageClick: ((String?, String?, String?, String?, Boolean?, String?) -> Unit)?, useReadableColors: Boolean, isLightTheme: Boolean, enableOverlayEmotes: Boolean, useBoldNames: Boolean, loggedInUser: String?, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?, savedColors: HashMap<String, Int>, localTwitchEmotes: List<TwitchEmote>?, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>?, personalEmoteSetUsers: Map<String, String>?, globalStvEmotes: List<Emote>?, channelStvEmotes: List<Emote>?, globalBttvEmotes: List<Emote>?, channelBttvEmotes: List<Emote>?, globalFfzEmotes: List<Emote>?, channelFfzEmotes: List<Emote>?, cheerEmotes: List<CheerEmote>?, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>): Boolean {
        var wasMentioned = false
        try {
            var builderIndex = startIndex
            val split = builder.substring(builderIndex).split(" ")
            var previousImage: Image? = null
            val twitchEmotes = chatMessage.emotes?.map {
                val realBegin = message.offsetByCodePoints(0, it.begin)
                val realEnd = if (it.begin == realBegin) {
                    it.end
                } else {
                    it.end + realBegin - it.begin
                }
                localTwitchEmotes?.find { emote -> emote.id == it.id }?.let { emote ->
                    TwitchEmote(
                        id = emote.id,
                        name = emote.name,
                        localData = emote.localData,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        begin = realBegin,
                        end = realEnd,
                        setId = emote.setId,
                        ownerId = emote.ownerId
                    )
                } ?: TwitchEmote(id = it.id, begin = realBegin, end = realEnd)
            }?.sortedBy { it.begin }?.toMutableList()
            val personalEmotes = if (showPersonalEmotes && !chatMessage.userId.isNullOrBlank()) {
                personalEmoteSetUsers?.get(chatMessage.userId)?.let { setId -> personalEmoteSets?.entries?.find { it.key == setId } }?.value
            } else null
            for (value in split) {
                if (chatMessage.bits != null) {
                    val bitsCount = value.takeLastWhile { it.isDigit() }
                    val bitsName = value.substringBeforeLast(bitsCount)
                    if (bitsCount.isNotEmpty()) {
                        val emote = cheerEmotes?.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
                        if (emote != null) {
                            builder.replace(builderIndex, builderIndex + bitsName.length, ".")
                            builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (imageClick != null) {
                                builder.setSpan(object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, null, emote.format, emote.isAnimated, null)
                                    }

                                    override fun updateDrawState(ds: TextPaint) {}
                                }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            images.add(Image(
                                localData = emote.localData?.let { getLocalCheerEmoteData(emote.name + emote.minBits, it, savedLocalCheerEmotes, chatUrl, getEmoteBytes) },
                                url1x = emote.url1x,
                                url2x = emote.url2x,
                                url3x = emote.url3x,
                                url4x = emote.url4x,
                                format = emote.format,
                                isAnimated = emote.isAnimated,
                                isEmote = true,
                                start = builderIndex,
                                end = builderIndex + 1
                            ))
                            builderIndex += 1
                            if (!emote.color.isNullOrBlank()) {
                                builder.setSpan(ForegroundColorSpan(getSavedColor(emote.color, savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + bitsCount.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (!twitchEmotes.isNullOrEmpty()) {
                                val removed = bitsName.length - 1
                                twitchEmotes.forEach {
                                    it.begin -= removed
                                    it.end -= removed
                                }
                            }
                            previousImage = null
                            builderIndex += bitsCount.length + 1
                            continue
                        }
                    }
                }
                val foundEmote = personalEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.PERSONAL_STV } ?:
                channelStvEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.CHANNEL_STV } ?:
                channelBttvEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.CHANNEL_BTTV } ?:
                channelFfzEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.CHANNEL_FFZ } ?:
                globalStvEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.GLOBAL_STV } ?:
                globalBttvEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.GLOBAL_BTTV } ?:
                globalFfzEmotes?.find { it.name == value }?.let { it to ImageClickedDialog.GLOBAL_FFZ }
                if (foundEmote != null) {
                    val emote = foundEmote.first
                    val source = foundEmote.second
                    if (emote.isOverlayEmote && enableOverlayEmotes && previousImage != null) {
                        builder.replace(builderIndex - 1, builderIndex + value.length, "")
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl, getEmoteBytes) },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            isEmote = true,
                            start = previousImage.start,
                            end = previousImage.end
                        )
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length + 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage.overlayEmote = image
                        previousImage = image
                        continue
                    } else {
                        builder.replace(builderIndex, builderIndex + value.length, ".")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, emote.name, source, emote.format, emote.isAnimated, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl, getEmoteBytes) },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            isEmote = true,
                            start = builderIndex,
                            end = builderIndex + 1
                        )
                        images.add(image)
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length - 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage = image
                        builderIndex += 2
                        continue
                    }
                }
                val twitchEmote = twitchEmotes?.firstOrNull()?.let { first ->
                    val messageIndex = builderIndex - startIndex
                    when {
                        first.begin == messageIndex -> first
                        first.begin < messageIndex -> {
                            twitchEmotes.remove(first)
                            twitchEmotes.firstOrNull()?.takeIf { it.begin == messageIndex }
                        }
                        else -> null
                    }
                }
                if (twitchEmote != null) {
                    twitchEmotes.remove(twitchEmote)
                    builder.replace(builderIndex, builderIndex + value.length, ".")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    val emote = localTwitchEmotes?.find { emote -> emote.id == twitchEmote.id }?.let { emote ->
                        TwitchEmote(
                            id = emote.id,
                            name = emote.name,
                            localData = emote.localData,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            begin = builderIndex,
                            end = builderIndex + 1,
                            setId = emote.setId,
                            ownerId = emote.ownerId
                        )
                    } ?: TwitchEmote(id = twitchEmote.id)
                    if (imageClick != null) {
                        builder.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, null, emote.format, emote.isAnimated, emote.id)
                            }

                            override fun updateDrawState(ds: TextPaint) {}
                        }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val image = Image(
                        localData = emote.localData?.let { getLocalTwitchEmoteData(emote.id!!, it, savedLocalTwitchEmotes, chatUrl, getEmoteBytes) },
                        url1x = emote.url1x,
                        url2x = emote.url2x,
                        url3x = emote.url3x,
                        url4x = emote.url4x,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        isEmote = true,
                        start = builderIndex,
                        end = builderIndex + 1
                    )
                    images.add(image)
                    if (twitchEmotes.isNotEmpty()) {
                        val removed = value.length - 1
                        twitchEmotes.forEach {
                            it.begin -= removed
                            it.end -= removed
                        }
                    }
                    previousImage = image
                    builderIndex += 2
                    continue
                }
                if (Patterns.WEB_URL.matcher(value).matches()) {
                    val url = if (value.startsWith("http")) value else "https://$value"
                    builder.setSpan(URLSpan(url), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    previousImage = null
                    builderIndex += value.length + 1
                    continue
                }
                if (value.startsWith('@') && useBoldNames) {
                    builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!wasMentioned &&
                    !loggedInUser.isNullOrBlank() &&
                    value.contains(loggedInUser, true) &&
                    chatMessage.userId != null &&
                    chatMessage.userLogin != loggedInUser
                ) {
                    wasMentioned = true
                }
                previousImage = null
                builderIndex += value.length + 1
            }
        } catch (e: Exception) {

        }
        return wasMentioned
    }

    private fun getLocalTwitchEmoteData(name: String, data: Pair<Long, Int>, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?): ByteArray? {
        return savedLocalTwitchEmotes[name] ?: chatUrl?.let{ url ->
            getEmoteBytes?.let { get ->
                get(url, data)?.also {
                    if (savedLocalTwitchEmotes.size >= 100) {
                        savedLocalTwitchEmotes.remove(savedLocalTwitchEmotes.keys.first())
                    }
                    savedLocalTwitchEmotes[name] = it
                }
            }
        }
    }

    private fun getLocalBadgeData(name: String, data: Pair<Long, Int>, savedLocalBadges: MutableMap<String, ByteArray>, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?): ByteArray? {
        return savedLocalBadges[name] ?: chatUrl?.let{ url ->
            getEmoteBytes?.let { get ->
                get(url, data)?.also {
                    if (savedLocalBadges.size >= 100) {
                        savedLocalBadges.remove(savedLocalBadges.keys.first())
                    }
                    savedLocalBadges[name] = it
                }
            }
        }
    }

    private fun getLocalCheerEmoteData(name: String, data: Pair<Long, Int>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?): ByteArray? {
        return savedLocalCheerEmotes[name] ?: chatUrl?.let{ url ->
            getEmoteBytes?.let { get ->
                get(url, data)?.also {
                    if (savedLocalCheerEmotes.size >= 100) {
                        savedLocalCheerEmotes.remove(savedLocalCheerEmotes.keys.first())
                    }
                    savedLocalCheerEmotes[name] = it
                }
            }
        }
    }

    private fun getLocalEmoteData(name: String, data: Pair<Long, Int>, savedLocalEmotes: MutableMap<String, ByteArray>, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?): ByteArray? {
        return savedLocalEmotes[name] ?: chatUrl?.let{ url ->
            getEmoteBytes?.let { get ->
                get(url, data)?.also {
                    if (savedLocalEmotes.size >= 100) {
                        savedLocalEmotes.remove(savedLocalEmotes.keys.first())
                    }
                    savedLocalEmotes[name] = it
                }
            }
        }
    }

    fun loadImages(fragment: Fragment, itemView: View, bind: (SpannableStringBuilder) -> Unit, images: List<Image>, imagePaint: Triple<NamePaint, String, Int>?, backgroundColor: Int, imageLibrary: String?, builder: SpannableStringBuilder, emoteSize: Int, badgeSize: Int, emoteQuality: String, animateGifs: Boolean, enableOverlayEmotes: Boolean) {
        if (imagePaint != null) {
            val paint = imagePaint.first
            if (imageLibrary == "0") {
                fragment.requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(fragment.requireContext()).apply {
                        data(paint.imageUrl)
                        target(
                            onSuccess = {
                                (it.asDrawable(fragment.resources)).let { result ->
                                    if (result is Animatable && animateGifs) {
                                        result.callback = object : Drawable.Callback {
                                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                                itemView.removeCallbacks(what)
                                            }

                                            override fun invalidateDrawable(who: Drawable) {
                                                itemView.invalidate()
                                            }

                                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                                itemView.postDelayed(what, `when`)
                                            }
                                        }
                                        (result as Animatable).start()
                                    }
                                    try {
                                        builder.setSpan(
                                            NamePaintImageSpan(
                                                imagePaint.second,
                                                paint.shadows,
                                                (itemView.background as? ColorDrawable)?.color,
                                                backgroundColor,
                                                result
                                            ),
                                            imagePaint.third,
                                            imagePaint.third + imagePaint.second.length,
                                            SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    } catch (e: IndexOutOfBoundsException) {
                                    }
                                    bind(builder)
                                }
                            },
                        )
                    }.build()
                )
            } else {
                Glide.with(fragment)
                    .load(paint.imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .into(object : CustomTarget<Drawable>() {
                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            if (resource is Animatable && animateGifs) {
                                resource.callback = object : Drawable.Callback {
                                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                        itemView.removeCallbacks(what)
                                    }

                                    override fun invalidateDrawable(who: Drawable) {
                                        itemView.invalidate()
                                    }

                                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                        itemView.postDelayed(what, `when`)
                                    }
                                }
                                (resource as Animatable).start()
                            }
                            try {
                                builder.setSpan(
                                    NamePaintImageSpan(
                                        imagePaint.second,
                                        paint.shadows,
                                        (itemView.background as? ColorDrawable)?.color,
                                        backgroundColor,
                                        resource
                                    ),
                                    imagePaint.third,
                                    imagePaint.third + imagePaint.second.length,
                                    SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            } catch (e: IndexOutOfBoundsException) {
                            }
                            bind(builder)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                        }
                    })
            }
        }
        images.forEach { image ->
            loadImage(imageLibrary, fragment, image, emoteQuality) { result ->
                val imageSize = if (image.isEmote) {
                    emoteSize
                } else {
                    badgeSize
                }
                val widthRatio = result.intrinsicWidth.toFloat() / result.intrinsicHeight.toFloat()
                val size = if (widthRatio == 1f) {
                    imageSize to imageSize
                } else {
                    (imageSize * widthRatio).toInt() to imageSize
                }
                result.setBounds(0, 0, size.first, size.second)
                if (result is Animatable && image.isAnimated && animateGifs) {
                    result.callback = object : Drawable.Callback {
                        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                            itemView.removeCallbacks(what)
                        }

                        override fun invalidateDrawable(who: Drawable) {
                            itemView.invalidate()
                        }

                        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                            itemView.postDelayed(what, `when`)
                        }
                    }
                    (result as Animatable).start()
                }
                if (image.overlayEmote != null) {
                    val drawables = arrayOf(result)
                    nextOverlayEmote(imageLibrary, fragment, drawables, image.overlayEmote!!, image, itemView, bind, builder, emoteSize, emoteQuality, animateGifs, enableOverlayEmotes)
                } else {
                    builder.setSpan(CenteredImageSpan(result), image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    bind(builder)
                }
            }
        }
    }

    private fun nextOverlayEmote(imageLibrary: String?, fragment: Fragment, drawables: Array<Drawable>, image: Image, bottomImage: Image, itemView: View, bind: (SpannableStringBuilder) -> Unit, builder: SpannableStringBuilder, emoteSize: Int, emoteQuality: String, animateGifs: Boolean, enableOverlayEmotes: Boolean) {
        loadImage(imageLibrary, fragment, image, emoteQuality) { result ->
            val widthRatio = result.intrinsicWidth.toFloat() / result.intrinsicHeight.toFloat()
            val size = if (widthRatio == 1f) {
                emoteSize to emoteSize
            } else {
                (emoteSize * widthRatio).toInt() to emoteSize
            }
            result.setBounds(0, 0, size.first, size.second)
            if (result is Animatable && image.isAnimated && animateGifs) {
                result.callback = object : Drawable.Callback {
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                        itemView.removeCallbacks(what)
                    }

                    override fun invalidateDrawable(who: Drawable) {
                        itemView.invalidate()
                    }

                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                        itemView.postDelayed(what, `when`)
                    }
                }
                (result as Animatable).start()
            }
            val array = drawables.plus(result)
            if (image.overlayEmote != null) {
                nextOverlayEmote(imageLibrary, fragment, array, image.overlayEmote!!, bottomImage, itemView, bind, builder, emoteSize, emoteQuality, animateGifs, enableOverlayEmotes)
            } else {
                val layer = LayerDrawable(array)
                val width = array.maxOf { it.bounds.right }
                val height = array.maxOf { it.bounds.bottom }
                layer.setBounds(0, 0, width, height)
                builder.setSpan(CenteredImageSpan(layer), bottomImage.start, bottomImage.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                bind(builder)
            }
        }
    }

    private fun loadImage(imageLibrary: String?, fragment: Fragment, image: Image, emoteQuality: String, onLoaded: (Drawable) -> Unit) {
        if (imageLibrary == "0" || (imageLibrary == "1" && !image.format.equals("webp", true))) {
            loadCoil(fragment, image, emoteQuality, onLoaded)
        } else {
            loadGlide(fragment, image, emoteQuality, onLoaded)
        }
    }

    private fun loadCoil(fragment: Fragment, image: Image, emoteQuality: String, onLoaded: (Drawable) -> Unit) {
        fragment.requireContext().imageLoader.enqueue(
            ImageRequest.Builder(fragment.requireContext()).apply {
                data(image.localData ?: when (emoteQuality) {
                    "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                    "3" -> image.url3x ?: image.url2x ?: image.url1x
                    "2" -> image.url2x ?: image.url1x
                    else -> image.url1x
                })
                target(
                    onSuccess = {
                        onLoaded((it.asDrawable(fragment.resources)))
                    },
                )
            }.build()
        )
    }

    private fun loadGlide(fragment: Fragment, image: Image, emoteQuality: String, onLoaded: (Drawable) -> Unit) {
        Glide.with(fragment)
            .load(image.localData ?: when (emoteQuality) {
                "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                "3" -> image.url3x ?: image.url2x ?: image.url1x
                "2" -> image.url2x ?: image.url1x
                else -> image.url1x
            })
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    onLoaded(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
    }
}