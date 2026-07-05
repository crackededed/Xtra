package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Reply
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

object ChatUtils {

    const val ACTION = "\u0001ACTION"

    class IRCMessage(
        val tags: Map<String, String>,
        val prefix: String?,
        val command: String?,
        val params: List<String>,
        val fullMessage: String,
    )

    fun parseIRCMessage(message: String): IRCMessage {
        var index = 0
        val tags = if (message.startsWith('@')) {
            index += 1
            val next = message.indexOf(' ', index)
            if (next != -1) {
                val start = index
                index = next
                buildMap {
                    message.substring(start, index).split(';').forEach { tag ->
                        val split = tag.split('=')
                        val key = split.getOrNull(0)
                        val value = split.getOrNull(1)
                        if (key != null && !value.isNullOrEmpty()) {
                            put(key, value.replace("\\:", ";").replace("\\s", " "))
                        }
                    }
                }
            } else emptyMap()
        } else emptyMap()
        while (message.getOrNull(index) == ' ') {
            index += 1
        }
        val prefix = if (message.getOrNull(index) == ':') {
            index += 1
            val next = message.indexOf(' ', index)
            if (next != -1) {
                val start = index
                index = next
                message.substring(start, index)
            } else null
        } else null
        while (message.getOrNull(index) == ' ') {
            index += 1
        }
        val next = message.indexOf(' ', index)
        val command = if (next != -1) {
            val start = index
            index = next
            message.substring(start, index)
        } else null
        val params = mutableListOf<String>()
        var nextChar = message.getOrNull(index)
        while (nextChar != null) {
            when (nextChar) {
                ' ' -> index += 1
                ':' -> {
                    index += 1
                    params.add(message.substring(index))
                    break
                }
                else -> {
                    val next = message.indexOf(' ', index)
                    if (next != -1) {
                        val start = index
                        index = next
                        params.add(message.substring(start, index))
                    } else {
                        // Recent messages api removes ":" prefix from some messages
                        params.add(message.substring(index))
                        break
                    }
                }
            }
            nextChar = message.getOrNull(index)
        }
        return IRCMessage(tags, prefix, command, params, message)
    }

    fun parseChatMessage(message: IRCMessage): ChatMessage {
        val userLogin = message.tags["login"] ?: message.prefix?.substringBefore('!', "")
        val emotes = message.tags["emotes"]?.let { value ->
            buildList {
                value.split('/').forEach { emote ->
                    val split = emote.split(':')
                    val id = split.getOrNull(0)
                    val positions = split.getOrNull(1)
                    if (id != null && positions != null) {
                        positions.split(',').forEach { range ->
                            val split = range.split("-")
                            val start = split.getOrNull(0)?.toIntOrNull()
                            val end = split.getOrNull(1)?.toIntOrNull()
                            if (start != null && end != null) {
                                add(TwitchEmote(id = id, begin = start, end = end))
                            }
                        }
                    }
                }
            }
        }
        val badges = message.tags["badges"]?.let { value ->
            value.split(',').mapNotNull { badge ->
                val split = badge.split("/")
                val id = split.getOrNull(0)
                val version = split.getOrNull(1)
                if (id != null && version != null) {
                    Badge(id, version)
                } else null
            }
        }
        var userMessage = if (message.params.size >= 2) {
            message.params.lastOrNull()
        } else null
        val isAction = userMessage?.startsWith(ACTION) == true
        if (isAction) {
            userMessage = userMessage.substring(8, userMessage.lastIndex)
        }
        return ChatMessage(
            type = ChatMessage.USER_MESSAGE,
            id = message.tags["id"],
            userId = message.tags["user-id"],
            userLogin = userLogin,
            userName = message.tags["display-name"],
            message = userMessage,
            color = message.tags["color"],
            emotes = emotes,
            badges = badges,
            isAction = isAction,
            isFirst = message.tags["first-msg"] == "1",
            bits = message.tags["bits"]?.toIntOrNull(),
            systemMsg = message.tags["system-msg"],
            msgId = message.tags["msg-id"],
            reward = message.tags["custom-reward-id"]?.let {
                ChannelPointReward(id = it)
            },
            reply = message.tags["reply-thread-parent-msg-id"]?.let {
                Reply(
                    threadParentId = it,
                    userLogin = message.tags["reply-parent-user-login"],
                    userName = message.tags["reply-parent-display-name"],
                    message = message.tags["reply-parent-msg-body"]
                )
            },
            timestamp = message.tags["tmi-sent-ts"]?.toLongOrNull(),
            fullMsg = message.fullMessage
        )
    }

    fun parseClearMessage(message: IRCMessage): ChatMessage {
        val userMessage = if (message.params.size >= 2) {
            message.params.lastOrNull()
        } else null
        return ChatMessage(
            type = ChatMessage.USER_MESSAGE,
            userLogin = message.tags["login"],
            message = userMessage,
            targetMsgId = message.tags["target-msg-id"],
            timestamp = message.tags["tmi-sent-ts"]?.toLongOrNull(),
            fullMsg = message.fullMessage
        )
    }

    fun parseClearChat(context: Context, message: IRCMessage): ChatMessage {
        val duration = message.tags["ban-duration"]
        val login = if (message.params.size >= 2) {
            message.params.lastOrNull()
        } else null
        val text = if (login != null) {
            if (duration != null) {
                ContextCompat.getString(context, R.string.chat_timeout).format(login, TwitchApiHelper.getDurationFromSeconds(context, duration))
            } else {
                ContextCompat.getString(context, R.string.chat_ban).format(login)
            }
        } else {
            ContextCompat.getString(context, R.string.chat_clear)
        }
        return ChatMessage(
            type = if (login != null) {
                ChatMessage.USER_MESSAGE
            } else {
                ChatMessage.NOTICE_MESSAGE
            },
            userId = message.tags["target-user-id"],
            userLogin = login,
            systemMsg = text,
            timestamp = message.tags["tmi-sent-ts"]?.toLongOrNull(),
            fullMsg = message.fullMessage
        )
    }

    fun parseNotice(message: IRCMessage): ChatMessage {
        val text = if (message.params.size >= 2) {
            message.params.lastOrNull()
        } else null
        return ChatMessage(
            type = ChatMessage.NOTICE_MESSAGE,
            systemMsg = text,
            fullMsg = message.fullMessage
        )
    }
}
