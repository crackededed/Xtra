package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Reply
import com.github.andreyasadchy.xtra.model.chat.RoomState
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

object ChatUtils {

    const val ACTION = "\u0001ACTION"

    fun parseChatMessage(message: String, userNotice: Boolean): ChatMessage {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        val messageInfo = parts[1] //:<user>!<user>@<user>.tmi.twitch.tv PRIVMSG #<channelName> :<message>
        val userLogin = prefixes["login"] ?: try {
            messageInfo.substring(1, messageInfo.indexOf("!"))
        } catch (e: Exception) {
            null
        }
        val systemMsg = prefixes["system-msg"]?.replace("\\s", " ")
        val msgIndex = messageInfo.indexOf(":", messageInfo.indexOf(":") + 1)
        return if (msgIndex == -1 && userNotice) { // no user message & is user notice
            ChatMessage(
                userId = prefixes["user-id"],
                userLogin = userLogin,
                userName = prefixes["display-name"]?.replace("\\s", " "),
                systemMsg = systemMsg ?: messageInfo,
                timestamp = prefixes["tmi-sent-ts"]?.toLong(),
                fullMsg = message
            )
        } else {
            val userMessage: String
            val isAction: Boolean
            messageInfo.substring(msgIndex + 1).let { //from <message>
                if (!it.startsWith(ACTION)) {
                    userMessage = it
                    isAction = false
                } else {
                    userMessage = it.substring(8, it.lastIndex)
                    isAction = true
                }
            }
            val emotesList = mutableListOf<TwitchEmote>()
            val emotes = prefixes["emotes"]
            if (emotes != null) {
                val entries = splitAndMakeMap(emotes, "/", ":").entries
                entries.forEach { emote ->
                    emote.value?.split(",")?.forEach { indexes ->
                        val index = indexes.split("-")
                        emotesList.add(TwitchEmote(id = emote.key, begin = index[0].toInt(), end = index[1].toInt()))
                    }
                }
            }
            val badgesList = mutableListOf<Badge>()
            val badges = prefixes["badges"]
            if (badges != null) {
                val entries = splitAndMakeMap(badges, ",", "/").entries
                entries.forEach {
                    it.value?.let { value ->
                        badgesList.add(Badge(it.key, value))
                    }
                }
            }
            ChatMessage(
                id = prefixes["id"],
                userId = prefixes["user-id"],
                userLogin = userLogin,
                userName = prefixes["display-name"]?.replace("\\s", " "),
                message = userMessage,
                color = prefixes["color"],
                emotes = emotesList,
                badges = badgesList,
                isAction = isAction,
                isFirst = prefixes["first-msg"] == "1",
                bits = prefixes["bits"]?.toIntOrNull(),
                systemMsg = systemMsg,
                msgId = prefixes["msg-id"],
                reward = prefixes["custom-reward-id"]?.let { ChannelPointReward(id = it) },
                reply = prefixes["reply-thread-parent-msg-id"]?.let {
                    Reply(
                        threadParentId = it,
                        userLogin = prefixes["reply-parent-user-login"],
                        userName = prefixes["reply-parent-display-name"]?.replace("\\s", " "),
                        message = prefixes["reply-parent-msg-body"]?.replace("\\s", " ")
                    )
                },
                timestamp = prefixes["tmi-sent-ts"]?.toLong(),
                fullMsg = message
            )
        }
    }

    fun parseClearMessage(message: String): Pair<ChatMessage, String?> {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        val login = prefixes["login"]
        val messageInfo = parts[1]
        val msgIndex = messageInfo.indexOf(":", messageInfo.indexOf(":") + 1)
        val msg = if (msgIndex != -1) messageInfo.substring(msgIndex + 1) else null
        return Pair(
            ChatMessage(
                userLogin = login,
                message = msg,
                timestamp = prefixes["tmi-sent-ts"]?.toLong(),
                fullMsg = message
            ),
            prefixes["target-msg-id"]
        )
    }

    fun parseClearChat(context: Context, message: String): ChatMessage {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        val duration = prefixes["ban-duration"]
        val messageInfo = parts[1]
        val userIndex = messageInfo.indexOf(":", messageInfo.indexOf(":") + 1)
        val login = if (userIndex != -1) messageInfo.substring(userIndex + 1) else null
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
            userId = prefixes["target-user-id"],
            userLogin = login,
            systemMsg = text,
            timestamp = prefixes["tmi-sent-ts"]?.toLong(),
            fullMsg = message
        )
    }

    fun parseNotice(context: Context, message: String): Pair<ChatMessage, String?> {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        val messageInfo = parts[1]
        val msgId = prefixes["msg-id"]
        val text = messageInfo.substring(messageInfo.indexOf(":", messageInfo.indexOf(":") + 1) + 1)
        return Pair(
            ChatMessage(
                systemMsg = TwitchApiHelper.getNoticeString(context, msgId, text),
                fullMsg = message
            ),
            msgId
        )
    }

    fun parseRoomState(message: String): RoomState {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        return RoomState(
            emote = prefixes["emote-only"],
            followers = prefixes["followers-only"],
            unique = prefixes["r9k"],
            slow = prefixes["slow"],
            subs = prefixes["subs-only"]
        )
    }

    fun parseEmoteSets(message: String): List<String>? {
        val parts = message.substring(1).split(" ".toRegex(), 2)
        val prefixes = splitAndMakeMap(parts[0], ";", "=")
        val sets = prefixes["emote-sets"]
        return sets?.split(",")?.dropLastWhile { it.isEmpty() }
    }

    private fun splitAndMakeMap(string: String, splitRegex: String, mapRegex: String): Map<String, String?> {
        val list = string.split(splitRegex.toRegex()).dropLastWhile { it.isEmpty() }
        val map = LinkedHashMap<String, String?>()
        for (pair in list) {
            val kv = pair.split(mapRegex.toRegex()).dropLastWhile { it.isEmpty() }
            map[kv[0]] = if (kv.size == 2) kv[1] else null
        }
        return map
    }
}
