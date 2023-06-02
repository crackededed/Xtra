package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.util.Pair
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.util.chat.ChatCallback
import com.github.andreyasadchy.xtra.util.chat.ChatListenerImpl
import com.github.andreyasadchy.xtra.util.chat.ChatReadIRC
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatWriteIRC
import com.github.andreyasadchy.xtra.util.chat.ChatWriteWebSocket
import com.github.andreyasadchy.xtra.util.chat.OnChatMessageReceivedListener
import com.github.andreyasadchy.xtra.util.chat.PubSubCallback
import com.github.andreyasadchy.xtra.util.chat.PubSubListenerImpl
import com.github.andreyasadchy.xtra.util.chat.PubSubWebSocket
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.lang.Integer.parseInt
import java.lang.Long.parseLong
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TwitchApiHelper {

    var checkedValidation = false

    fun getTemplateUrl(url: String?, type: String): String? {
        if ((url == null)||(url == "")||(url.startsWith("https://vod-secure.twitch.tv/_404/404_processing")))
            return when (type) {
                "game" -> "https://static-cdn.jtvnw.net/ttv-static/404_boxart.jpg"
                "video" -> "https://vod-secure.twitch.tv/_404/404_processing_320x180.png"
                else -> null
            }
        val width = when (type) {
            "game" -> "285"
            "video" -> "1280"
            "profileimage" -> "300"
            else -> "" }
        val height = when (type) {
            "game" -> "380"
            "video" -> "720"
            "profileimage" -> "300"
            else -> "" }
        val reg1 = """-\d\d\dx\d\d\d""".toRegex()
        val reg2 = """\d\d\d\dx\d\d\d""".toRegex()
        val reg3 = """\d\d\dx\d\d\d""".toRegex()
        val reg4 = """\d\dx\d\d\d""".toRegex()
        val reg5 = """\d\d\dx\d\d""".toRegex()
        val reg6 = """\d\dx\d\d""".toRegex()
        if (type == "clip") return if (reg1.containsMatchIn(url)) reg1.replace(url, "") else url
        return when {
            url.contains("%{width}", true) -> url.replace("%{width}", width).replace("%{height}", height)
            url.contains("{width}", true) -> url.replace("{width}", width).replace("{height}", height)
            reg2.containsMatchIn(url) -> reg2.replace(url, "${width}x${height}")
            reg3.containsMatchIn(url) -> reg3.replace(url, "${width}x${height}")
            reg4.containsMatchIn(url) -> reg4.replace(url, "${width}x${height}")
            reg5.containsMatchIn(url) -> reg5.replace(url, "${width}x${height}")
            reg6.containsMatchIn(url) -> reg6.replace(url, "${width}x${height}")
            else -> url
        }
    }

    fun getType(context: Context, type: String?): String? {
        return when (type?.lowercase()) {
            "archive" -> context.getString(R.string.video_type_archive)
            "highlight" -> context.getString(R.string.video_type_highlight)
            "upload" -> context.getString(R.string.video_type_upload)
            "rerun" -> context.getString(R.string.video_type_rerun)
            else -> null
        }
    }

    fun getUserType(context: Context, type: String?): String? {
        return when (type?.lowercase()) {
            "partner" -> context.getString(R.string.user_partner)
            "affiliate" -> context.getString(R.string.user_affiliate)
            "staff" -> context.getString(R.string.user_staff)
            "admin" -> context.getString(R.string.user_admin)
            "global_mod" -> context.getString(R.string.user_global_mod)
            else -> null
        }
    }

    fun getDuration(duration: String?): Long? {
        return if (duration.isNullOrBlank()) {
            null
        } else {
            try {
                parseLong(duration)
            } catch (e: Exception) {
                try {
                    val h = duration.substringBefore("h", "0").takeLast(2).filter { it.isDigit() }.toInt()
                    val m = duration.substringBefore("m", "0").takeLast(2).filter { it.isDigit() }.toInt()
                    val s = duration.substringBefore("s", "0").takeLast(2).filter { it.isDigit() }.toInt()
                    ((h * 3600) + (m * 60) + s).toLong()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun getDurationFromSeconds(context: Context, input: String?, text: Boolean = true): String? {
        if (input != null) {
            val duration = try {
                parseInt(input)
            } catch (e: NumberFormatException) {
                return null
            }
            val days = (duration / 86400)
            val hours = ((duration % 86400) / 3600)
            val minutes = (((duration % 86400) % 3600) / 60)
            val seconds = (duration % 60)
            return if (text) String.format((if (days != 0) (days.toString() + context.getString(R.string.days) + " ") else "") +
                    (if (hours != 0) (hours.toString() + context.getString(R.string.hours) + " ") else "") +
                    (if (minutes != 0) (minutes.toString() + context.getString(R.string.minutes) + " ") else "") +
                    (if (seconds != 0) (seconds.toString() + context.getString(R.string.seconds) + " ") else "")).trim() else
                String.format((if (days != 0) ("$days:") else "") +
                        (if (hours != 0) (if (hours < 10 && days != 0) "0$hours:" else "$hours:") else (if (days != 0) "00:" else "")) +
                        (if (minutes != 0) (if (minutes < 10 && (hours != 0||days != 0)) "0$minutes:" else "$minutes:") else (if (hours != 0||days != 0) "00:" else "")) +
                        (if (seconds != 0) (if (seconds < 10 && (minutes != 0||hours != 0||days != 0)) "0$seconds" else "$seconds") else (if (minutes != 0||hours != 0||days != 0) "00" else "")))
        } else return null
    }

    fun getUptime(context: Context, input: String?): String? {
        return if (input != null) {
            val currentTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).time.time
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            val createdAt = try {
                format.parse(input)?.time
            } catch (e: ParseException) {
                null
            }
            val diff = if (createdAt != null) ((currentTime - createdAt) / 1000) else null
            return if (diff != null && diff >= 0) {
                getDurationFromSeconds(context, diff.toString(), false)
            } else null
        } else null
    }

    fun getVodTimeLeft(context: Context, input: String?, days: Int): String? {
        val time = input?.let { parseIso8601Date(it) }
        return if (time != null) {
            val currentTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).time.time
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.time = Date(time)
            calendar.add(Calendar.DAY_OF_MONTH, days)
            val diff = ((calendar.time.time - currentTime) / 1000)
            return if (diff >= 0) {
                getDurationFromSeconds(context, diff.toString(), true)
            } else null
        } else null
    }

    fun getTimestamp(input: Long, timestampFormat: String?): String? {
        val pattern = when (timestampFormat) {
            "0" -> "H:mm"
            "1" -> "HH:mm"
            "2" -> "H:mm:ss"
            "3" -> "HH:mm:ss"
            "4" -> "h:mm a"
            "5" -> "hh:mm a"
            "6" -> "h:mm:ss a"
            else -> "hh:mm:ss a"
        }
        val format = SimpleDateFormat(pattern, Locale.getDefault())
        return try {
            format.format(Date(input))
        } catch (e: Exception) {
            null
        }
    }

    fun getClipTime(period: VideoPeriodEnum? = null): String {
        val days = when (period) {
            VideoPeriodEnum.DAY -> -1
            VideoPeriodEnum.WEEK -> -7
            VideoPeriodEnum.MONTH -> -30
            else -> 0 }
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(calendar.time)
    }

    fun parseIso8601Date(date: String): Long? {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).parse(date)?.time
        } catch (e: ParseException) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.getDefault()).parse(date)?.time
            } catch (e: ParseException) {
                null
            }
        }
    }

    fun formatTimeString(context: Context, iso8601date: String): String? {
        return parseIso8601Date(iso8601date)?.let { formatTime(context, it) }
    }

    fun formatTime(context: Context, date: Long): String {
        val year = Calendar.getInstance().let {
            it.timeInMillis = date
            it.get(Calendar.YEAR)
        }
        val format = if (year == Calendar.getInstance().get(Calendar.YEAR)) {
            DateUtils.FORMAT_NO_YEAR
        } else {
            DateUtils.FORMAT_SHOW_DATE
        }
        return DateUtils.formatDateTime(context, date, format)
    }

    fun startChatReadIRC(useSSL: Boolean, loggedIn: Boolean, channelName: String, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, usePubSub: Boolean, newMessageListener: OnChatMessageReceivedListener, callback: ChatCallback): ChatReadIRC {
        return ChatReadIRC(useSSL, loggedIn, channelName, ChatListenerImpl(newMessageListener, callback, showUserNotice, showClearMsg, showClearChat, usePubSub)).apply { start() }
    }

    fun startChatWriteIRC(useSSL: Boolean, userName: String?, userToken: String?, channelName: String, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, usePubSub: Boolean, newMessageListener: OnChatMessageReceivedListener, callback: ChatCallback): ChatWriteIRC {
        return ChatWriteIRC(useSSL, userName, userToken, channelName, ChatListenerImpl(newMessageListener, callback, showUserNotice, showClearMsg, showClearChat, usePubSub)).apply { start() }
    }

    fun startChatReadWebSocket(loggedIn: Boolean, channelName: String, client: OkHttpClient, coroutineScope: CoroutineScope, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, usePubSub: Boolean, newMessageListener: OnChatMessageReceivedListener, callback: ChatCallback): ChatReadWebSocket {
        return ChatReadWebSocket(loggedIn, channelName, client, coroutineScope, ChatListenerImpl(newMessageListener, callback, showUserNotice, showClearMsg, showClearChat, usePubSub)).apply { connect() }
    }

    fun startChatWriteWebSocket(userName: String?, userToken: String?, channelName: String, client: OkHttpClient, coroutineScope: CoroutineScope, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, usePubSub: Boolean, newMessageListener: OnChatMessageReceivedListener, callback: ChatCallback): ChatWriteWebSocket {
        return ChatWriteWebSocket(userName, userToken, channelName, client, coroutineScope, ChatListenerImpl(newMessageListener, callback, showUserNotice, showClearMsg, showClearChat, usePubSub)).apply { connect() }
    }

    fun startPubSub(channelId: String, userId: String?, gqlToken: String?, collectPoints: Boolean, notifyPoints: Boolean, showRaids: Boolean, client: OkHttpClient, coroutineScope: CoroutineScope, newMessageListener: OnChatMessageReceivedListener, callback: PubSubCallback): PubSubWebSocket {
        return PubSubWebSocket(channelId, userId, gqlToken, collectPoints, notifyPoints, showRaids, client, coroutineScope, PubSubListenerImpl(newMessageListener, callback)).apply { connect() }
    }

    fun parseClipOffset(url: String): Double {
        val time = url.substringAfterLast('=').split("\\D".toRegex())
        var offset = 0.0
        var multiplier = 1.0
        for (i in time.lastIndex - 1 downTo 0) {
            offset += time[i].toDouble() * multiplier
            multiplier *= 60
        }
        return offset
    }

    fun addTokenPrefixHelix(token: String) = "Bearer $token"
    fun addTokenPrefixGQL(token: String) = "OAuth $token"

    fun formatViewsCount(context: Context, count: Int): String {
        return if (count > 1000 && context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, false)) {
            context.getString(R.string.views, formatCountIfMoreThanAThousand(count))
        } else {
            context.resources.getQuantityString(R.plurals.views, count, count)
        }
    }

    fun formatViewersCount(context: Context, count: Int): String {
        return if (count > 1000 && context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, false)) {
            context.getString(R.string.viewers_count, formatCountIfMoreThanAThousand(count))
        } else {
            context.resources.getQuantityString(R.plurals.viewers, count, count)
        }
    }

    fun formatCount(context: Context, count: Int): String {
        return if (count > 1000 && context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, false)) {
            formatCountIfMoreThanAThousand(count)
        } else {
            count.toString()
        }
    }

    private fun formatCountIfMoreThanAThousand(count: Int): String {
        val divider: Int
        val suffix = if (count.toString().length < 7) {
            divider = 1000
            "K"
        } else {
            divider = 1_000_000
            "M"
        }
        val truncated = count / (divider / 10)
        val hasDecimal = truncated / 10.0 != (truncated / 10).toDouble()
        return if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
    }

    fun getGQLHeaders(context: Context): Map<String, String> {
        return mutableMapOf<String, String>().apply {
            if (context.prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
                context.prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")?.let {
                    if (it.isNotBlank()) {
                        put("Client-ID", it)
                    }
                }
                context.prefs().getString(C.INTEGRITY_TOKEN, null)?.let {
                    if (it.isNotBlank()) {
                        put("Client-Integrity", it)
                    }
                }
                context.prefs().getString(C.DEVICE_ID, null)?.let {
                    if (it.isNotBlank()) {
                        put("X-Device-Id", it)
                    }
                }
            } else {
                context.prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")?.let {
                    if (it.isNotBlank()) {
                        put("Client-ID", it)
                    }
                }
            }
        }
    }

    fun isIntegrityTokenExpired(context: Context): Boolean {
        return System.currentTimeMillis() >= context.prefs().getLong(C.INTEGRITY_EXPIRATION, 0)
    }

    val gamesApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val streamsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val gameStreamsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val gameVideosApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val gameClipsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.HELIX), Pair(2, C.GQL))
    val channelVideosApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val channelClipsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.HELIX), Pair(2, C.GQL))
    val searchVideosApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL))
    val searchStreamsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.HELIX))
    val searchChannelsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.HELIX), Pair(2, C.GQL))
    val searchGamesApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val followedStreamsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.HELIX), Pair(2, C.GQL))
    val followedVideosApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL))
    val followedChannelsApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL), Pair(2, C.HELIX))
    val followedGamesApiDefaults: ArrayList<Pair<Long?, String?>?> = arrayListOf(Pair(0, C.GQL_QUERY), Pair(1, C.GQL))

    fun listFromPrefs(pref: String?, defaults: ArrayList<Pair<Long?, String?>?>): ArrayList<Pair<Long?, String?>?> {
        return if (!pref.isNullOrBlank()) {
            val list = ArrayList<Pair<Long?, String?>?>()
            val split = splitAndMakeMap(pref)
            for (i in split.sortedBy { it?.first }) {
                val item = defaults.find { it?.second == i?.second }
                if (item != null) {
                    list.add(i)
                }
            }
            for (i in defaults.sortedBy { it?.first }) {
                val item = list.find { it?.second == i?.second }
                if (item == null) {
                    i?.first?.toInt()?.let { list.add(it, i) }
                }
            }
            list
        } else {
            defaults
        }
    }

    private fun splitAndMakeMap(string: String): ArrayList<Pair<Long?, String?>?> {
        val list = string.split(",".toRegex()).dropLastWhile { it.isEmpty() }
        val map = arrayListOf<Pair<Long?, String?>?>()
        for (pair in list) {
            val kv = pair.split(":".toRegex()).dropLastWhile { it.isEmpty() }
            map.add(Pair(kv[0].toLong(), kv[1]))
        }
        return map
    }

    fun getVideoUrlFromPreview(url: String, type: String?, quality: String? = null): String {
        return url
            .replace("storyboards",
                quality ?: if (type?.lowercase() == "upload") {
                    "1080p60"
                } else {
                    "chunked"
                })
            .replaceAfterLast("/",
                if (type?.lowercase() == "highlight") {
                    "highlight-${url.substringAfterLast("/").substringBefore("-")}.m3u8"
                } else {
                    "index-dvr.m3u8"
                }
            )
    }

    fun getVideoUrlMapFromPreview(url: String, type: String?): MutableMap<String, String> {
        val qualityList = listOf("chunked", "720p60", "720p30", "480p30", "360p30", "160p30", "144p30", "high", "medium", "low", "mobile", "audio_only")
        val map = mutableMapOf<String, String>()
        qualityList.forEach { quality ->
            map[quality] = url
                .replace("storyboards", quality)
                .replaceAfterLast("/",
                    if (type?.lowercase() == "highlight") {
                        "highlight-${url.substringAfterLast("/").substringBefore("-")}.m3u8"
                    } else {
                        "index-dvr.m3u8"
                    }
                )
        }
        return map
    }

    fun getClipUrlMapFromPreview(url: String): Map<String, String> {
        val appContext = XtraApp.INSTANCE.applicationContext
        return mapOf(kotlin.Pair(appContext.getString(R.string.source), url.substringBefore("-preview") + ".mp4"))
    }

    fun getMessageIdString(msgId: String?): String? {
        val appContext = XtraApp.INSTANCE.applicationContext
        return when (msgId) {
            "highlighted-message" -> appContext.getString(R.string.irc_msgid_highlighted_message)
            "announcement" -> appContext.getString(R.string.irc_msgid_announcement)
            else -> null
        }
    }

    fun getNoticeString(context: Context, msgId: String?, message: String?): String? {
        val lang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore("-")
        return if (lang == "ar" || lang == "de" || lang == "es" || lang == "fr" || lang == "in" || lang == "ja" || lang == "pt" || lang == "ru" || lang == "tr") {
            when (msgId) {
                "already_banned" -> context.getString(R.string.irc_notice_already_banned,
                    message?.substringBefore(" is already banned", "") ?: "")
                "already_emote_only_off" -> context.getString(R.string.irc_notice_already_emote_only_off)
                "already_emote_only_on" -> context.getString(R.string.irc_notice_already_emote_only_on)
                "already_followers_off" -> context.getString(R.string.irc_notice_already_followers_off)
                "already_followers_on" -> context.getString(R.string.irc_notice_already_followers_on,
                    message?.substringAfter("is already in ", "")?.substringBefore(" followers-only mode", "") ?: "")
                "already_r9k_off" -> context.getString(R.string.irc_notice_already_r9k_off)
                "already_r9k_on" -> context.getString(R.string.irc_notice_already_r9k_on)
                "already_slow_off" -> context.getString(R.string.irc_notice_already_slow_off)
                "already_slow_on" -> context.getString(R.string.irc_notice_already_slow_on,
                    message?.substringAfter("is already in ", "")?.substringBefore("-second slow", "") ?: "")
                "already_subs_off" -> context.getString(R.string.irc_notice_already_subs_off)
                "already_subs_on" -> context.getString(R.string.irc_notice_already_subs_on)
                "autohost_receive" -> context.getString(R.string.irc_notice_autohost_receive,
                    message?.substringBefore(" is now auto hosting", "") ?: "",
                    message?.substringAfter("you for up to ", "")?.substringBefore(" viewers", "") ?: "")
                "bad_ban_admin" -> context.getString(R.string.irc_notice_bad_ban_admin,
                    message?.substringAfter("cannot ban admin", "")?.substringBefore(". Please email", "") ?: "")
                "bad_ban_anon" -> context.getString(R.string.irc_notice_bad_ban_anon)
                "bad_ban_broadcaster" -> context.getString(R.string.irc_notice_bad_ban_broadcaster)
                "bad_ban_mod" -> context.getString(R.string.irc_notice_bad_ban_mod,
                    message?.substringAfter("cannot ban moderator", "")?.substringBefore(" unless you are", "") ?: "")
                "bad_ban_self" -> context.getString(R.string.irc_notice_bad_ban_self)
                "bad_ban_staff" -> context.getString(R.string.irc_notice_bad_ban_staff,
                    message?.substringAfter("cannot ban staff", "")?.substringBefore(". Please email", "") ?: "")
                "bad_commercial_error" -> context.getString(R.string.irc_notice_bad_commercial_error)
                "bad_delete_message_broadcaster" -> context.getString(R.string.irc_notice_bad_delete_message_broadcaster)
                "bad_delete_message_mod" -> context.getString(R.string.irc_notice_bad_delete_message_mod,
                    message?.substringAfter("from another moderator ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_host_error" -> context.getString(R.string.irc_notice_bad_host_error,
                    message?.substringAfter("a problem hosting ", "")?.substringBefore(". Please try", "") ?: "")
                "bad_host_hosting" -> context.getString(R.string.irc_notice_bad_host_hosting,
                    message?.substringAfter("is already hosting ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_host_rate_exceeded" -> context.getString(R.string.irc_notice_bad_host_rate_exceeded,
                    message?.substringAfter("changed more than ", "")?.substringBefore(" times every half", "") ?: "")
                "bad_host_rejected" -> context.getString(R.string.irc_notice_bad_host_rejected)
                "bad_host_self" -> context.getString(R.string.irc_notice_bad_host_self)
                "bad_mod_banned" -> context.getString(R.string.irc_notice_bad_mod_banned,
                    message?.substringBefore(" is banned", "") ?: "")
                "bad_mod_mod" -> context.getString(R.string.irc_notice_bad_mod_mod,
                    message?.substringBefore(" is already", "") ?: "")
                "bad_slow_duration" -> context.getString(R.string.irc_notice_bad_slow_duration,
                    message?.substringAfter("to more than ", "")?.substringBefore(" seconds.", "") ?: "")
                "bad_timeout_admin" -> context.getString(R.string.irc_notice_bad_timeout_admin,
                    message?.substringAfter("cannot timeout admin ", "")?.substringBefore(". Please email", "") ?: "")
                "bad_timeout_anon" -> context.getString(R.string.irc_notice_bad_timeout_anon)
                "bad_timeout_broadcaster" -> context.getString(R.string.irc_notice_bad_timeout_broadcaster)
                "bad_timeout_duration" -> context.getString(R.string.irc_notice_bad_timeout_duration,
                    message?.substringAfter("for more than ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_timeout_mod" -> context.getString(R.string.irc_notice_bad_timeout_mod,
                    message?.substringAfter("cannot timeout moderator ", "")?.substringBefore(" unless you are", "") ?: "")
                "bad_timeout_self" -> context.getString(R.string.irc_notice_bad_timeout_self)
                "bad_timeout_staff" -> context.getString(R.string.irc_notice_bad_timeout_staff,
                    message?.substringAfter("cannot timeout staff ", "")?.substringBefore(". Please email", "") ?: "")
                "bad_unban_no_ban" -> context.getString(R.string.irc_notice_bad_unban_no_ban,
                    message?.substringBefore(" is not banned", "") ?: "")
                "bad_unhost_error" -> context.getString(R.string.irc_notice_bad_unhost_error)
                "bad_unmod_mod" -> context.getString(R.string.irc_notice_bad_unmod_mod,
                    message?.substringBefore(" is not a", "") ?: "")
                "bad_vip_grantee_banned" -> context.getString(R.string.irc_notice_bad_vip_grantee_banned,
                    message?.substringBefore(" is banned in", "") ?: "")
                "bad_vip_grantee_already_vip" -> context.getString(R.string.irc_notice_bad_vip_grantee_already_vip,
                    message?.substringBefore(" is already a", "") ?: "")
                "bad_vip_max_vips_reached" -> context.getString(R.string.irc_notice_bad_vip_max_vips_reached)
                "bad_vip_achievement_incomplete" -> context.getString(R.string.irc_notice_bad_vip_achievement_incomplete)
                "bad_unvip_grantee_not_vip" -> context.getString(R.string.irc_notice_bad_unvip_grantee_not_vip,
                    message?.substringBefore(" is not a", "") ?: "")
                "ban_success" -> context.getString(R.string.irc_notice_ban_success,
                    message?.substringBefore(" is now banned", "") ?: "")
                "cmds_available" -> context.getString(R.string.irc_notice_cmds_available,
                    message?.substringAfter("details): ", "")?.substringBefore(" More help:", "") ?: "")
                "color_changed" -> context.getString(R.string.irc_notice_color_changed)
                "commercial_success" -> context.getString(R.string.irc_notice_commercial_success,
                    message?.substringAfter("Initiating ", "")?.substringBefore(" second commercial break.", "") ?: "")
                "delete_message_success" -> context.getString(R.string.irc_notice_delete_message_success,
                    message?.substringAfter("The message from ", "")?.substringBefore(" is now deleted.", "") ?: "")
                "delete_staff_message_success" -> context.getString(R.string.irc_notice_delete_staff_message_success,
                    message?.substringAfter("message from staff ", "")?.substringBefore(". Please email", "") ?: "")
                "emote_only_off" -> context.getString(R.string.irc_notice_emote_only_off)
                "emote_only_on" -> context.getString(R.string.irc_notice_emote_only_on)
                "followers_off" -> context.getString(R.string.irc_notice_followers_off)
                "followers_on" -> context.getString(R.string.irc_notice_followers_on,
                    message?.substringAfter("is now in ", "")?.substringBefore(" followers-only mode", "") ?: "")
                "followers_on_zero" -> context.getString(R.string.irc_notice_followers_on_zero)
                "host_off" -> context.getString(R.string.irc_notice_host_off)
                "host_on" -> context.getString(R.string.irc_notice_host_on,
                    message?.substringAfter("Now hosting ", "")?.substringBeforeLast(".", "") ?: "")
                "host_receive" -> context.getString(R.string.irc_notice_host_receive,
                    message?.substringBefore(" is now hosting", "") ?: "",
                    message?.substringAfter("you for up to ", "")?.substringBefore(" viewers", "") ?: "")
                "host_receive_no_count" -> context.getString(R.string.irc_notice_host_receive_no_count,
                    message?.substringBefore(" is now hosting", "") ?: "")
                "host_target_went_offline" -> context.getString(R.string.irc_notice_host_target_went_offline,
                    message?.substringBefore(" has gone offline", "") ?: "")
                "hosts_remaining" -> context.getString(R.string.irc_notice_hosts_remaining,
                    message?.substringBefore(" host commands", "") ?: "")
                "invalid_user" -> context.getString(R.string.irc_notice_invalid_user,
                    message?.substringAfter("Invalid username: ", "") ?: "")
                "mod_success" -> context.getString(R.string.irc_notice_mod_success,
                    message?.substringAfter("You have added ", "")?.substringBefore(" as a moderator", "") ?: "")
                "msg_banned" -> context.getString(R.string.irc_notice_msg_banned,
                    message?.substringAfter("from talking in ", "")?.substringBeforeLast(".", "") ?: "")
                "msg_bad_characters" -> context.getString(R.string.irc_notice_msg_bad_characters)
                "msg_channel_blocked" -> context.getString(R.string.irc_notice_msg_channel_blocked)
                "msg_channel_suspended" -> context.getString(R.string.irc_notice_msg_channel_suspended)
                "msg_duplicate" -> context.getString(R.string.irc_notice_msg_duplicate)
                "msg_emoteonly" -> context.getString(R.string.irc_notice_msg_emoteonly)
                "msg_followersonly" -> context.getString(R.string.irc_notice_msg_followersonly,
                    message?.substringAfter("This room is in ", "")?.substringBefore(" followers-only mode", "") ?: "",
                    message?.substringAfter("Follow ", "")?.substringBefore(" to join", "") ?: "")
                "msg_followersonly_followed" -> context.getString(R.string.irc_notice_msg_followersonly_followed,
                    message?.substringAfter("This room is in ", "")?.substringBefore(" followers-only mode", "") ?: "",
                    message?.substringAfter("following for ", "")?.substringBefore(". Continue", "") ?: "")
                "msg_followersonly_zero" -> context.getString(R.string.irc_notice_msg_followersonly_zero,
                    message?.substringAfter(". Follow ", "")?.substringBefore(" to join the", "") ?: "")
                "msg_r9k" -> context.getString(R.string.irc_notice_msg_r9k)
                "msg_ratelimit" -> context.getString(R.string.irc_notice_msg_ratelimit)
                "msg_rejected" -> context.getString(R.string.irc_notice_msg_rejected)
                "msg_rejected_mandatory" -> context.getString(R.string.irc_notice_msg_rejected_mandatory)
                "msg_slowmode" -> context.getString(R.string.irc_notice_msg_slowmode,
                    message?.substringAfter("talk again in ", "")?.substringBefore(" seconds.", "") ?: "")
                "msg_subsonly" -> context.getString(R.string.irc_notice_msg_subsonly,
                    message?.substringAfter("/products/", "")?.substringBefore("/ticket?ref", "") ?: "")
                "msg_suspended" -> context.getString(R.string.irc_notice_msg_suspended)
                "msg_timedout" -> context.getString(R.string.irc_notice_msg_timedout,
                    message?.substringAfter("timed out for ", "")?.substringBefore(" more seconds.", "") ?: "")
                "msg_verified_email" -> context.getString(R.string.irc_notice_msg_verified_email)
                "no_help" -> context.getString(R.string.irc_notice_no_help)
                "no_mods" -> context.getString(R.string.irc_notice_no_mods)
                "no_vips" -> context.getString(R.string.irc_notice_no_vips)
                "not_hosting" -> context.getString(R.string.irc_notice_not_hosting)
                "no_permission" -> context.getString(R.string.irc_notice_no_permission)
                "r9k_off" -> context.getString(R.string.irc_notice_r9k_off)
                "r9k_on" -> context.getString(R.string.irc_notice_r9k_on)
                "raid_error_already_raiding" -> context.getString(R.string.irc_notice_raid_error_already_raiding)
                "raid_error_forbidden" -> context.getString(R.string.irc_notice_raid_error_forbidden)
                "raid_error_self" -> context.getString(R.string.irc_notice_raid_error_self)
                "raid_error_too_many_viewers" -> context.getString(R.string.irc_notice_raid_error_too_many_viewers)
                "raid_error_unexpected" -> context.getString(R.string.irc_notice_raid_error_unexpected,
                    message?.substringAfter("a problem raiding ", "")?.substringBefore(". Please try", "") ?: "")
                "raid_notice_mature" -> context.getString(R.string.irc_notice_raid_notice_mature)
                "raid_notice_restricted_chat" -> context.getString(R.string.irc_notice_raid_notice_restricted_chat)
                "room_mods" -> context.getString(R.string.irc_notice_room_mods,
                    message?.substringAfter("this channel are: ", "") ?: "")
                "slow_off" -> context.getString(R.string.irc_notice_slow_off)
                "slow_on" -> context.getString(R.string.irc_notice_slow_on,
                    message?.substringAfter("send messages every ", "")?.substringBefore(" seconds.", "") ?: "")
                "subs_off" -> context.getString(R.string.irc_notice_subs_off)
                "subs_on" -> context.getString(R.string.irc_notice_subs_on)
                "timeout_no_timeout" -> context.getString(R.string.irc_notice_timeout_no_timeout,
                    message?.substringBefore(" is not timed", "") ?: "")
                "timeout_success" -> context.getString(R.string.irc_notice_timeout_success,
                    message?.substringBefore(" has been", "") ?: "",
                    message?.substringAfter("timed out for ", "")?.substringBeforeLast(".", "") ?: "")
                "tos_ban" -> context.getString(R.string.irc_notice_tos_ban,
                    message?.substringAfter("has closed channel ", "")?.substringBefore(" due to Terms", "") ?: "")
                "turbo_only_color" -> context.getString(R.string.irc_notice_turbo_only_color,
                    message?.substringAfter("following instead: ", "") ?: "")
                "unavailable_command" -> context.getString(R.string.irc_notice_unavailable_command,
                    message?.substringAfter("Sorry, “", "")?.substringBefore("” is not available", "") ?: "")
                "unban_success" -> context.getString(R.string.irc_notice_unban_success,
                    message?.substringBefore(" is no longer", "") ?: "")
                "unmod_success" -> context.getString(R.string.irc_notice_unmod_success,
                    message?.substringAfter("You have removed ", "")?.substringBefore(" as a moderator", "") ?: "")
                "unraid_error_no_active_raid" -> context.getString(R.string.irc_notice_unraid_error_no_active_raid)
                "unraid_error_unexpected" -> context.getString(R.string.irc_notice_unraid_error_unexpected)
                "unraid_success" -> context.getString(R.string.irc_notice_unraid_success)
                "unrecognized_cmd" -> context.getString(R.string.irc_notice_unrecognized_cmd,
                    message?.substringAfter("Unrecognized command: ", "") ?: "")
                "untimeout_banned" -> context.getString(R.string.irc_notice_untimeout_banned,
                    message?.substringBefore(" is permanently banned", "") ?: "")
                "untimeout_success" -> context.getString(R.string.irc_notice_untimeout_success,
                    message?.substringBefore(" is no longer", "") ?: "")
                "unvip_success" -> context.getString(R.string.irc_notice_unvip_success,
                    message?.substringAfter("You have removed ", "")?.substringBefore(" as a VIP", "") ?: "")
                "usage_ban" -> context.getString(R.string.irc_notice_usage_ban)
                "usage_clear" -> context.getString(R.string.irc_notice_usage_clear)
                "usage_color" -> context.getString(R.string.irc_notice_usage_color,
                    message?.substringAfter("following: ", "")?.substringBeforeLast(".", "") ?: "")
                "usage_commercial" -> context.getString(R.string.irc_notice_usage_commercial)
                "usage_disconnect" -> context.getString(R.string.irc_notice_usage_disconnect)
                "usage_delete" -> context.getString(R.string.irc_notice_usage_delete)
                "usage_emote_only_off" -> context.getString(R.string.irc_notice_usage_emote_only_off)
                "usage_emote_only_on" -> context.getString(R.string.irc_notice_usage_emote_only_on)
                "usage_followers_off" -> context.getString(R.string.irc_notice_usage_followers_off)
                "usage_followers_on" -> context.getString(R.string.irc_notice_usage_followers_on)
                "usage_help" -> context.getString(R.string.irc_notice_usage_help)
                "usage_host" -> context.getString(R.string.irc_notice_usage_host)
                "usage_marker" -> context.getString(R.string.irc_notice_usage_marker)
                "usage_me" -> context.getString(R.string.irc_notice_usage_me)
                "usage_mod" -> context.getString(R.string.irc_notice_usage_mod)
                "usage_mods" -> context.getString(R.string.irc_notice_usage_mods)
                "usage_r9k_off" -> context.getString(R.string.irc_notice_usage_r9k_off)
                "usage_r9k_on" -> context.getString(R.string.irc_notice_usage_r9k_on)
                "usage_raid" -> context.getString(R.string.irc_notice_usage_raid)
                "usage_slow_off" -> context.getString(R.string.irc_notice_usage_slow_off)
                "usage_slow_on" -> context.getString(R.string.irc_notice_usage_slow_on,
                    message?.substringAfter("default=", "")?.substringBefore(")", "") ?: "")
                "usage_subs_off" -> context.getString(R.string.irc_notice_usage_subs_off)
                "usage_subs_on" -> context.getString(R.string.irc_notice_usage_subs_on)
                "usage_timeout" -> context.getString(R.string.irc_notice_usage_timeout)
                "usage_unban" -> context.getString(R.string.irc_notice_usage_unban)
                "usage_unhost" -> context.getString(R.string.irc_notice_usage_unhost)
                "usage_unmod" -> context.getString(R.string.irc_notice_usage_unmod)
                "usage_unraid" -> context.getString(R.string.irc_notice_usage_unraid)
                "usage_untimeout" -> context.getString(R.string.irc_notice_usage_untimeout)
                "usage_unvip" -> context.getString(R.string.irc_notice_usage_unvip)
                "usage_user" -> context.getString(R.string.irc_notice_usage_user)
                "usage_vip" -> context.getString(R.string.irc_notice_usage_vip)
                "usage_vips" -> context.getString(R.string.irc_notice_usage_vips)
                "usage_whisper" -> context.getString(R.string.irc_notice_usage_whisper)
                "vip_success" -> context.getString(R.string.irc_notice_vip_success,
                    message?.substringAfter("You have added ", "")?.substringBeforeLast(" as a vip", "") ?: "")
                "vips_success" -> context.getString(R.string.irc_notice_vips_success,
                    message?.substringAfter("channel are: ", "")?.substringBeforeLast(".", "") ?: "")
                "whisper_banned" -> context.getString(R.string.irc_notice_whisper_banned)
                "whisper_banned_recipient" -> context.getString(R.string.irc_notice_whisper_banned_recipient)
                "whisper_invalid_login" -> context.getString(R.string.irc_notice_whisper_invalid_login)
                "whisper_invalid_self" -> context.getString(R.string.irc_notice_whisper_invalid_self)
                "whisper_limit_per_min" -> context.getString(R.string.irc_notice_whisper_limit_per_min)
                "whisper_limit_per_sec" -> context.getString(R.string.irc_notice_whisper_limit_per_sec)
                "whisper_restricted" -> context.getString(R.string.irc_notice_whisper_restricted)
                "whisper_restricted_recipient" -> context.getString(R.string.irc_notice_whisper_restricted_recipient)
                else -> message
            }
        } else {
            message
        }
    }
}
