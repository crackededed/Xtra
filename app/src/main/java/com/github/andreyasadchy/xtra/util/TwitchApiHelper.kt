package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import org.json.JSONObject
import java.lang.Integer.parseInt
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TwitchApiHelper {

    var checkedValidation = false
    var checkedUpdates = false

    fun getTemplateUrl(url: String?, type: String): String? {
        if (url.isNullOrBlank() || url.startsWith("https://vod-secure.twitch.tv/_404/404_processing")) {
            return when (type) {
                "game" -> "https://static-cdn.jtvnw.net/ttv-static/404_boxart.jpg"
                "video" -> "https://vod-secure.twitch.tv/_404/404_processing_320x180.png"
                else -> null
            }
        }
        val width = when (type) {
            "game" -> "285"
            "video" -> "1280"
            "profileimage" -> "300"
            else -> ""
        }
        val height = when (type) {
            "game" -> "380"
            "video" -> "720"
            "profileimage" -> "300"
            else -> ""
        }
        return when {
            type == "clip" -> url.replace(Regex("-\\d+x\\d+."), ".")
            url.contains("%{width}") -> url.replace("%{width}", width).replace("%{height}", height)
            url.contains("{width}") -> url.replace("{width}", width).replace("{height}", height)
            else -> url.replace(Regex("-\\d+x\\d+."), "-${width}x${height}.")
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

    fun getDuration(duration: String): Long? {
        return duration.toLongOrNull() ?: try {
            val h = duration.substringBefore("h", "0").takeLast(2).filter { it.isDigit() }.toInt()
            val m = duration.substringBefore("m", "0").takeLast(2).filter { it.isDigit() }.toInt()
            val s = duration.substringBefore("s", "0").takeLast(2).filter { it.isDigit() }.toInt()
            ((h * 3600) + (m * 60) + s).toLong()
        } catch (e: Exception) {
            null
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

    fun getUptime(startedAt: String?): String? {
        return if (startedAt != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val createdAt = try {
                    Instant.parse(startedAt)
                } catch (e: DateTimeParseException) {
                    null
                }
                if (createdAt != null) {
                    val diff = Duration.between(createdAt, Instant.now())
                    if (!diff.isNegative) {
                        DateUtils.formatElapsedTime(diff.seconds)
                    } else null
                } else null
            } else {
                val currentTime = Calendar.getInstance().time.time
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                format.timeZone = TimeZone.getTimeZone("UTC")
                val createdAt = try {
                    format.parse(startedAt)?.time
                } catch (e: ParseException) {
                    null
                }
                val diff = if (createdAt != null) ((currentTime - createdAt) / 1000) else null
                if (diff != null && diff >= 0) {
                    DateUtils.formatElapsedTime(diff)
                } else null
            }
        } else null
    }

    fun getVodTimeLeft(context: Context, input: String?, days: Int): String? {
        val time = input?.let { parseIso8601DateUTC(it) }
        return if (time != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = Instant.ofEpochMilli(time).plus(days.toLong(), ChronoUnit.DAYS)
                val diff = Duration.between(Instant.now(), date)
                if (!diff.isNegative) {
                    getDurationFromSeconds(context, diff.seconds.toString(), true)
                } else null
            } else {
                val currentTime = Calendar.getInstance().time.time
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = time
                calendar.add(Calendar.DAY_OF_MONTH, days)
                val diff = ((calendar.time.time - currentTime) / 1000)
                if (diff >= 0) {
                    getDurationFromSeconds(context, diff.toString(), true)
                } else null
            }
        } else null
    }

    fun getMinutesLeft(hour: Int, minute: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentDate = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.systemDefault())
            val date = currentDate.withHour(hour).withMinute(minute).let {
                if (it < currentDate) it.plusDays(1) else it
            }
            return ChronoUnit.MINUTES.between(currentDate, date).toInt()
        } else {
            val currentDate = Calendar.getInstance()
            val date = Calendar.getInstance()
            date.set(Calendar.HOUR_OF_DAY, hour)
            date.set(Calendar.MINUTE, minute)
            if (date < currentDate) {
                date.add(Calendar.DAY_OF_YEAR, 1)
            }
            return ((date.timeInMillis - currentDate.timeInMillis) / 60000).toInt()
        }
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
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(input), ZoneOffset.systemDefault())
                DateTimeFormatter.ofPattern(pattern).format(date)
            } else {
                val format = SimpleDateFormat(pattern, Locale.getDefault())
                format.format(Date(input))
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getClipTime(days: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val date = LocalDateTime.ofInstant(Instant.now().minus(days.toLong(), ChronoUnit.DAYS), ZoneOffset.UTC)
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").format(date)
        } else {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -days)
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.format(calendar.time)
        }
    }

    fun parseIso8601DateUTC(date: String): Long? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Instant.parse(date).toEpochMilli().takeIf { it > 0 }
            } catch (e: DateTimeParseException) {
                null
            }
        } else {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(date)?.time?.takeIf { it > 0 }
            } catch (e: ParseException) {
                try {
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.getDefault())
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    format.parse(date)?.time?.takeIf { it > 0 }
                } catch (e: ParseException) {
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZ", Locale.getDefault())
                        format.timeZone = TimeZone.getTimeZone("UTC")
                        format.parse(date)?.time?.takeIf { it > 0 }
                    } catch (e: ParseException) {
                        null
                    }
                }
            }
        }
    }

    fun formatTimeString(context: Context, iso8601date: String): String? {
        return parseIso8601DateUTC(iso8601date)?.let { formatTime(context, it) }
    }

    fun formatTime(context: Context, date: Long): String {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentYear = Year.now().value
            val year = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneOffset.UTC).year
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val year = Calendar.getInstance().let {
                it.timeInMillis = date
                it.get(Calendar.YEAR)
            }
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        }
        return DateUtils.formatDateTime(context, date, format)
    }

    fun formatViewsCount(context: Context, count: Int, truncate: Boolean): String {
        return if (count > 1000 && truncate) {
            context.getString(R.string.views, formatCountIfMoreThanAThousand(count))
        } else {
            context.resources.getQuantityString(R.plurals.views, count, count)
        }
    }

    fun formatViewersCount(context: Context, count: Int, truncate: Boolean): String {
        return if (count > 1000 && truncate) {
            context.getString(R.string.viewers_count, formatCountIfMoreThanAThousand(count))
        } else {
            context.resources.getQuantityString(R.plurals.viewers, count, count)
        }
    }

    fun formatCount(count: Int, truncate: Boolean): String {
        return if (count > 1000 && truncate) {
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

    fun addTokenPrefixGQL(token: String) = "OAuth $token"
    fun addTokenPrefixHelix(token: String) = "Bearer $token"

    fun getGQLHeaders(context: Context, includeToken: Boolean = false): Map<String, String> {
        return mutableMapOf<String, String>().apply {
            if (context.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                context.tokenPrefs().getString(C.GQL_HEADERS, null)?.let {
                    try {
                        val json = JSONObject(it)
                        json.keys().forEach { key ->
                            put(key, json.optString(key))
                        }
                    } catch (e: Exception) {

                    }
                }
            } else {
                context.prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")?.let {
                    if (it.isNotBlank()) {
                        put(C.HEADER_CLIENT_ID, it)
                    }
                }
                if (includeToken) {
                    context.tokenPrefs().getString(C.GQL_TOKEN2, null)?.let {
                        if (it.isNotBlank()) {
                            put(C.HEADER_TOKEN, addTokenPrefixGQL(it))
                        }
                    }
                }
            }
        }
    }

    fun getHelixHeaders(context: Context): Map<String, String> {
        return mutableMapOf<String, String>().apply {
            context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")?.let {
                if (it.isNotBlank()) {
                    put(C.HEADER_CLIENT_ID, it)
                }
            }
            context.tokenPrefs().getString(C.TOKEN, null)?.let {
                if (it.isNotBlank()) {
                    put(C.HEADER_TOKEN, addTokenPrefixHelix(it))
                }
            }
        }
    }

    fun isIntegrityTokenExpired(context: Context): Boolean {
        return System.currentTimeMillis() >= context.tokenPrefs().getLong(C.INTEGRITY_EXPIRATION, 0)
    }

    fun getVideoUrlMapFromPreview(url: String, type: String?, list: List<String>?): Map<String, String> {
        val qualityList = list ?: listOf("chunked", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "144p30", "high", "medium", "low", "mobile", "audio_only")
        val map = mutableMapOf<String, String>()
        qualityList.forEach { quality ->
            map[if (quality == "chunked") "source" else quality] = url
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

    fun getMessageIdString(msgId: String?): String? {
        val appContext = XtraApp.INSTANCE.applicationContext
        return when (msgId) {
            "highlighted-message" -> ContextCompat.getString(appContext, R.string.irc_msgid_highlighted_message)
            "announcement" -> ContextCompat.getString(appContext, R.string.irc_msgid_announcement)
            else -> null
        }
    }

    fun getNoticeString(context: Context, msgId: String?, message: String?): String? {
        val lang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore("-")
        return if (lang == "ar" || lang == "de" || lang == "es" || lang == "fr" || lang == "in" || lang == "it" || lang == "ja" || lang == "pt" || lang == "ru" || lang == "tr" || lang == "zh") {
            when (msgId) {
                "already_banned" -> ContextCompat.getString(context, R.string.irc_notice_already_banned).format(
                    message?.substringBefore(" is already banned", "") ?: "")
                "already_emote_only_off" -> ContextCompat.getString(context, R.string.irc_notice_already_emote_only_off)
                "already_emote_only_on" -> ContextCompat.getString(context, R.string.irc_notice_already_emote_only_on)
                "already_followers_off" -> ContextCompat.getString(context, R.string.irc_notice_already_followers_off)
                "already_followers_on" -> ContextCompat.getString(context, R.string.irc_notice_already_followers_on).format(
                    message?.substringAfter("is already in ", "")?.substringBefore(" followers-only mode", "") ?: "")
                "already_r9k_off" -> ContextCompat.getString(context, R.string.irc_notice_already_r9k_off)
                "already_r9k_on" -> ContextCompat.getString(context, R.string.irc_notice_already_r9k_on)
                "already_slow_off" -> ContextCompat.getString(context, R.string.irc_notice_already_slow_off)
                "already_slow_on" -> ContextCompat.getString(context, R.string.irc_notice_already_slow_on).format(
                    message?.substringAfter("is already in ", "")?.substringBefore("-second slow", "") ?: "")
                "already_subs_off" -> ContextCompat.getString(context, R.string.irc_notice_already_subs_off)
                "already_subs_on" -> ContextCompat.getString(context, R.string.irc_notice_already_subs_on)
                "autohost_receive" -> ContextCompat.getString(context, R.string.irc_notice_autohost_receive).format(
                    message?.substringBefore(" is now auto hosting", "") ?: "").format(
                    message?.substringAfter("you for up to ", "")?.substringBefore(" viewers", "") ?: "")
                "bad_ban_admin" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_admin).format(
                    message?.substringAfter("cannot ban admin", "")?.substringBefore(". Please email", "") ?: "")
                "bad_ban_anon" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_anon)
                "bad_ban_broadcaster" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_broadcaster)
                "bad_ban_mod" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_mod).format(
                    message?.substringAfter("cannot ban moderator", "")?.substringBefore(" unless you are", "") ?: "")
                "bad_ban_self" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_self)
                "bad_ban_staff" -> ContextCompat.getString(context, R.string.irc_notice_bad_ban_staff).format(
                    message?.substringAfter("cannot ban staff", "")?.substringBefore(". Please email", "") ?: "")
                "bad_commercial_error" -> ContextCompat.getString(context, R.string.irc_notice_bad_commercial_error)
                "bad_delete_message_broadcaster" -> ContextCompat.getString(context, R.string.irc_notice_bad_delete_message_broadcaster)
                "bad_delete_message_mod" -> ContextCompat.getString(context, R.string.irc_notice_bad_delete_message_mod).format(
                    message?.substringAfter("from another moderator ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_host_error" -> ContextCompat.getString(context, R.string.irc_notice_bad_host_error).format(
                    message?.substringAfter("a problem hosting ", "")?.substringBefore(". Please try", "") ?: "")
                "bad_host_hosting" -> ContextCompat.getString(context, R.string.irc_notice_bad_host_hosting).format(
                    message?.substringAfter("is already hosting ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_host_rate_exceeded" -> ContextCompat.getString(context, R.string.irc_notice_bad_host_rate_exceeded).format(
                    message?.substringAfter("changed more than ", "")?.substringBefore(" times every half", "") ?: "")
                "bad_host_rejected" -> ContextCompat.getString(context, R.string.irc_notice_bad_host_rejected)
                "bad_host_self" -> ContextCompat.getString(context, R.string.irc_notice_bad_host_self)
                "bad_mod_banned" -> ContextCompat.getString(context, R.string.irc_notice_bad_mod_banned).format(
                    message?.substringBefore(" is banned", "") ?: "")
                "bad_mod_mod" -> ContextCompat.getString(context, R.string.irc_notice_bad_mod_mod).format(
                    message?.substringBefore(" is already", "") ?: "")
                "bad_slow_duration" -> ContextCompat.getString(context, R.string.irc_notice_bad_slow_duration).format(
                    message?.substringAfter("to more than ", "")?.substringBefore(" seconds.", "") ?: "")
                "bad_timeout_admin" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_admin).format(
                    message?.substringAfter("cannot timeout admin ", "")?.substringBefore(". Please email", "") ?: "")
                "bad_timeout_anon" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_anon)
                "bad_timeout_broadcaster" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_broadcaster)
                "bad_timeout_duration" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_duration).format(
                    message?.substringAfter("for more than ", "")?.substringBeforeLast(".", "") ?: "")
                "bad_timeout_mod" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_mod).format(
                    message?.substringAfter("cannot timeout moderator ", "")?.substringBefore(" unless you are", "") ?: "")
                "bad_timeout_self" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_self)
                "bad_timeout_staff" -> ContextCompat.getString(context, R.string.irc_notice_bad_timeout_staff).format(
                    message?.substringAfter("cannot timeout staff ", "")?.substringBefore(". Please email", "") ?: "")
                "bad_unban_no_ban" -> ContextCompat.getString(context, R.string.irc_notice_bad_unban_no_ban).format(
                    message?.substringBefore(" is not banned", "") ?: "")
                "bad_unhost_error" -> ContextCompat.getString(context, R.string.irc_notice_bad_unhost_error)
                "bad_unmod_mod" -> ContextCompat.getString(context, R.string.irc_notice_bad_unmod_mod).format(
                    message?.substringBefore(" is not a", "") ?: "")
                "bad_vip_grantee_banned" -> ContextCompat.getString(context, R.string.irc_notice_bad_vip_grantee_banned).format(
                    message?.substringBefore(" is banned in", "") ?: "")
                "bad_vip_grantee_already_vip" -> ContextCompat.getString(context, R.string.irc_notice_bad_vip_grantee_already_vip).format(
                    message?.substringBefore(" is already a", "") ?: "")
                "bad_vip_max_vips_reached" -> ContextCompat.getString(context, R.string.irc_notice_bad_vip_max_vips_reached)
                "bad_vip_achievement_incomplete" -> ContextCompat.getString(context, R.string.irc_notice_bad_vip_achievement_incomplete)
                "bad_unvip_grantee_not_vip" -> ContextCompat.getString(context, R.string.irc_notice_bad_unvip_grantee_not_vip).format(
                    message?.substringBefore(" is not a", "") ?: "")
                "ban_success" -> ContextCompat.getString(context, R.string.irc_notice_ban_success).format(
                    message?.substringBefore(" is now banned", "") ?: "")
                "cmds_available" -> ContextCompat.getString(context, R.string.irc_notice_cmds_available).format(
                    message?.substringAfter("details): ", "")?.substringBefore(" More help:", "") ?: "")
                "color_changed" -> ContextCompat.getString(context, R.string.irc_notice_color_changed)
                "commercial_success" -> ContextCompat.getString(context, R.string.irc_notice_commercial_success).format(
                    message?.substringAfter("Initiating ", "")?.substringBefore(" second commercial break.", "") ?: "")
                "delete_message_success" -> ContextCompat.getString(context, R.string.irc_notice_delete_message_success).format(
                    message?.substringAfter("The message from ", "")?.substringBefore(" is now deleted.", "") ?: "")
                "delete_staff_message_success" -> ContextCompat.getString(context, R.string.irc_notice_delete_staff_message_success).format(
                    message?.substringAfter("message from staff ", "")?.substringBefore(". Please email", "") ?: "")
                "emote_only_off" -> ContextCompat.getString(context, R.string.irc_notice_emote_only_off)
                "emote_only_on" -> ContextCompat.getString(context, R.string.irc_notice_emote_only_on)
                "followers_off" -> ContextCompat.getString(context, R.string.irc_notice_followers_off)
                "followers_on" -> ContextCompat.getString(context, R.string.irc_notice_followers_on).format(
                    message?.substringAfter("is now in ", "")?.substringBefore(" followers-only mode", "") ?: "")
                "followers_on_zero" -> ContextCompat.getString(context, R.string.irc_notice_followers_on_zero)
                "host_off" -> ContextCompat.getString(context, R.string.irc_notice_host_off)
                "host_on" -> ContextCompat.getString(context, R.string.irc_notice_host_on).format(
                    message?.substringAfter("Now hosting ", "")?.substringBeforeLast(".", "") ?: "")
                "host_receive" -> ContextCompat.getString(context, R.string.irc_notice_host_receive).format(
                    message?.substringBefore(" is now hosting", "") ?: "").format(
                    message?.substringAfter("you for up to ", "")?.substringBefore(" viewers", "") ?: "")
                "host_receive_no_count" -> ContextCompat.getString(context, R.string.irc_notice_host_receive_no_count).format(
                    message?.substringBefore(" is now hosting", "") ?: "")
                "host_target_went_offline" -> ContextCompat.getString(context, R.string.irc_notice_host_target_went_offline).format(
                    message?.substringBefore(" has gone offline", "") ?: "")
                "hosts_remaining" -> ContextCompat.getString(context, R.string.irc_notice_hosts_remaining).format(
                    message?.substringBefore(" host commands", "") ?: "")
                "invalid_user" -> ContextCompat.getString(context, R.string.irc_notice_invalid_user).format(
                    message?.substringAfter("Invalid username: ", "") ?: "")
                "mod_success" -> ContextCompat.getString(context, R.string.irc_notice_mod_success).format(
                    message?.substringAfter("You have added ", "")?.substringBefore(" as a moderator", "") ?: "")
                "msg_banned" -> ContextCompat.getString(context, R.string.irc_notice_msg_banned).format(
                    message?.substringAfter("from talking in ", "")?.substringBeforeLast(".", "") ?: "")
                "msg_bad_characters" -> ContextCompat.getString(context, R.string.irc_notice_msg_bad_characters)
                "msg_channel_blocked" -> ContextCompat.getString(context, R.string.irc_notice_msg_channel_blocked)
                "msg_channel_suspended" -> ContextCompat.getString(context, R.string.irc_notice_msg_channel_suspended)
                "msg_duplicate" -> ContextCompat.getString(context, R.string.irc_notice_msg_duplicate)
                "msg_emoteonly" -> ContextCompat.getString(context, R.string.irc_notice_msg_emoteonly)
                "msg_followersonly" -> ContextCompat.getString(context, R.string.irc_notice_msg_followersonly).format(
                    message?.substringAfter("This room is in ", "")?.substringBefore(" followers-only mode", "") ?: "").format(
                    message?.substringAfter("Follow ", "")?.substringBefore(" to join", "") ?: "")
                "msg_followersonly_followed" -> ContextCompat.getString(context, R.string.irc_notice_msg_followersonly_followed).format(
                    message?.substringAfter("This room is in ", "")?.substringBefore(" followers-only mode", "") ?: "").format(
                    message?.substringAfter("following for ", "")?.substringBefore(". Continue", "") ?: "")
                "msg_followersonly_zero" -> ContextCompat.getString(context, R.string.irc_notice_msg_followersonly_zero).format(
                    message?.substringAfter(". Follow ", "")?.substringBefore(" to join the", "") ?: "")
                "msg_r9k" -> ContextCompat.getString(context, R.string.irc_notice_msg_r9k)
                "msg_ratelimit" -> ContextCompat.getString(context, R.string.irc_notice_msg_ratelimit)
                "msg_rejected" -> ContextCompat.getString(context, R.string.irc_notice_msg_rejected)
                "msg_rejected_mandatory" -> ContextCompat.getString(context, R.string.irc_notice_msg_rejected_mandatory)
                "msg_slowmode" -> ContextCompat.getString(context, R.string.irc_notice_msg_slowmode).format(
                    message?.substringAfter("talk again in ", "")?.substringBefore(" seconds.", "") ?: "")
                "msg_subsonly" -> ContextCompat.getString(context, R.string.irc_notice_msg_subsonly).format(
                    message?.substringAfter("/products/", "")?.substringBefore("/ticket?ref", "") ?: "")
                "msg_suspended" -> ContextCompat.getString(context, R.string.irc_notice_msg_suspended)
                "msg_timedout" -> ContextCompat.getString(context, R.string.irc_notice_msg_timedout).format(
                    message?.substringAfter("timed out for ", "")?.substringBefore(" more seconds.", "") ?: "")
                "msg_verified_email" -> ContextCompat.getString(context, R.string.irc_notice_msg_verified_email)
                "no_help" -> ContextCompat.getString(context, R.string.irc_notice_no_help)
                "no_mods" -> ContextCompat.getString(context, R.string.irc_notice_no_mods)
                "no_vips" -> ContextCompat.getString(context, R.string.irc_notice_no_vips)
                "not_hosting" -> ContextCompat.getString(context, R.string.irc_notice_not_hosting)
                "no_permission" -> ContextCompat.getString(context, R.string.irc_notice_no_permission)
                "r9k_off" -> ContextCompat.getString(context, R.string.irc_notice_r9k_off)
                "r9k_on" -> ContextCompat.getString(context, R.string.irc_notice_r9k_on)
                "raid_error_already_raiding" -> ContextCompat.getString(context, R.string.irc_notice_raid_error_already_raiding)
                "raid_error_forbidden" -> ContextCompat.getString(context, R.string.irc_notice_raid_error_forbidden)
                "raid_error_self" -> ContextCompat.getString(context, R.string.irc_notice_raid_error_self)
                "raid_error_too_many_viewers" -> ContextCompat.getString(context, R.string.irc_notice_raid_error_too_many_viewers)
                "raid_error_unexpected" -> ContextCompat.getString(context, R.string.irc_notice_raid_error_unexpected).format(
                    message?.substringAfter("a problem raiding ", "")?.substringBefore(". Please try", "") ?: "")
                "raid_notice_mature" -> ContextCompat.getString(context, R.string.irc_notice_raid_notice_mature)
                "raid_notice_restricted_chat" -> ContextCompat.getString(context, R.string.irc_notice_raid_notice_restricted_chat)
                "room_mods" -> ContextCompat.getString(context, R.string.irc_notice_room_mods).format(
                    message?.substringAfter("this channel are: ", "") ?: "")
                "slow_off" -> ContextCompat.getString(context, R.string.irc_notice_slow_off)
                "slow_on" -> ContextCompat.getString(context, R.string.irc_notice_slow_on).format(
                    message?.substringAfter("send messages every ", "")?.substringBefore(" seconds.", "") ?: "")
                "subs_off" -> ContextCompat.getString(context, R.string.irc_notice_subs_off)
                "subs_on" -> ContextCompat.getString(context, R.string.irc_notice_subs_on)
                "timeout_no_timeout" -> ContextCompat.getString(context, R.string.irc_notice_timeout_no_timeout).format(
                    message?.substringBefore(" is not timed", "") ?: "")
                "timeout_success" -> ContextCompat.getString(context, R.string.irc_notice_timeout_success).format(
                    message?.substringBefore(" has been", "") ?: "").format(
                    message?.substringAfter("timed out for ", "")?.substringBeforeLast(".", "") ?: "")
                "tos_ban" -> ContextCompat.getString(context, R.string.irc_notice_tos_ban).format(
                    message?.substringAfter("has closed channel ", "")?.substringBefore(" due to Terms", "") ?: "")
                "turbo_only_color" -> ContextCompat.getString(context, R.string.irc_notice_turbo_only_color).format(
                    message?.substringAfter("following instead: ", "") ?: "")
                "unavailable_command" -> ContextCompat.getString(context, R.string.irc_notice_unavailable_command).format(
                    message?.substringAfter("Sorry, “", "")?.substringBefore("” is not available", "") ?: "")
                "unban_success" -> ContextCompat.getString(context, R.string.irc_notice_unban_success).format(
                    message?.substringBefore(" is no longer", "") ?: "")
                "unmod_success" -> ContextCompat.getString(context, R.string.irc_notice_unmod_success).format(
                    message?.substringAfter("You have removed ", "")?.substringBefore(" as a moderator", "") ?: "")
                "unraid_error_no_active_raid" -> ContextCompat.getString(context, R.string.irc_notice_unraid_error_no_active_raid)
                "unraid_error_unexpected" -> ContextCompat.getString(context, R.string.irc_notice_unraid_error_unexpected)
                "unraid_success" -> ContextCompat.getString(context, R.string.irc_notice_unraid_success)
                "unrecognized_cmd" -> ContextCompat.getString(context, R.string.irc_notice_unrecognized_cmd).format(
                    message?.substringAfter("Unrecognized command: ", "") ?: "")
                "untimeout_banned" -> ContextCompat.getString(context, R.string.irc_notice_untimeout_banned).format(
                    message?.substringBefore(" is permanently banned", "") ?: "")
                "untimeout_success" -> ContextCompat.getString(context, R.string.irc_notice_untimeout_success).format(
                    message?.substringBefore(" is no longer", "") ?: "")
                "unvip_success" -> ContextCompat.getString(context, R.string.irc_notice_unvip_success).format(
                    message?.substringAfter("You have removed ", "")?.substringBefore(" as a VIP", "") ?: "")
                "usage_ban" -> ContextCompat.getString(context, R.string.irc_notice_usage_ban)
                "usage_clear" -> ContextCompat.getString(context, R.string.irc_notice_usage_clear)
                "usage_color" -> ContextCompat.getString(context, R.string.irc_notice_usage_color).format(
                    message?.substringAfter("following: ", "")?.substringBeforeLast(".", "") ?: "")
                "usage_commercial" -> ContextCompat.getString(context, R.string.irc_notice_usage_commercial)
                "usage_disconnect" -> ContextCompat.getString(context, R.string.irc_notice_usage_disconnect)
                "usage_delete" -> ContextCompat.getString(context, R.string.irc_notice_usage_delete)
                "usage_emote_only_off" -> ContextCompat.getString(context, R.string.irc_notice_usage_emote_only_off)
                "usage_emote_only_on" -> ContextCompat.getString(context, R.string.irc_notice_usage_emote_only_on)
                "usage_followers_off" -> ContextCompat.getString(context, R.string.irc_notice_usage_followers_off)
                "usage_followers_on" -> ContextCompat.getString(context, R.string.irc_notice_usage_followers_on)
                "usage_help" -> ContextCompat.getString(context, R.string.irc_notice_usage_help)
                "usage_host" -> ContextCompat.getString(context, R.string.irc_notice_usage_host)
                "usage_marker" -> ContextCompat.getString(context, R.string.irc_notice_usage_marker)
                "usage_me" -> ContextCompat.getString(context, R.string.irc_notice_usage_me)
                "usage_mod" -> ContextCompat.getString(context, R.string.irc_notice_usage_mod)
                "usage_mods" -> ContextCompat.getString(context, R.string.irc_notice_usage_mods)
                "usage_r9k_off" -> ContextCompat.getString(context, R.string.irc_notice_usage_r9k_off)
                "usage_r9k_on" -> ContextCompat.getString(context, R.string.irc_notice_usage_r9k_on)
                "usage_raid" -> ContextCompat.getString(context, R.string.irc_notice_usage_raid)
                "usage_slow_off" -> ContextCompat.getString(context, R.string.irc_notice_usage_slow_off)
                "usage_slow_on" -> ContextCompat.getString(context, R.string.irc_notice_usage_slow_on).format(
                    message?.substringAfter("default=", "")?.substringBefore(")", "") ?: "")
                "usage_subs_off" -> ContextCompat.getString(context, R.string.irc_notice_usage_subs_off)
                "usage_subs_on" -> ContextCompat.getString(context, R.string.irc_notice_usage_subs_on)
                "usage_timeout" -> ContextCompat.getString(context, R.string.irc_notice_usage_timeout)
                "usage_unban" -> ContextCompat.getString(context, R.string.irc_notice_usage_unban)
                "usage_unhost" -> ContextCompat.getString(context, R.string.irc_notice_usage_unhost)
                "usage_unmod" -> ContextCompat.getString(context, R.string.irc_notice_usage_unmod)
                "usage_unraid" -> ContextCompat.getString(context, R.string.irc_notice_usage_unraid)
                "usage_untimeout" -> ContextCompat.getString(context, R.string.irc_notice_usage_untimeout)
                "usage_unvip" -> ContextCompat.getString(context, R.string.irc_notice_usage_unvip)
                "usage_user" -> ContextCompat.getString(context, R.string.irc_notice_usage_user)
                "usage_vip" -> ContextCompat.getString(context, R.string.irc_notice_usage_vip)
                "usage_vips" -> ContextCompat.getString(context, R.string.irc_notice_usage_vips)
                "usage_whisper" -> ContextCompat.getString(context, R.string.irc_notice_usage_whisper)
                "vip_success" -> ContextCompat.getString(context, R.string.irc_notice_vip_success).format(
                    message?.substringAfter("You have added ", "")?.substringBeforeLast(" as a vip", "") ?: "")
                "vips_success" -> ContextCompat.getString(context, R.string.irc_notice_vips_success).format(
                    message?.substringAfter("channel are: ", "")?.substringBeforeLast(".", "") ?: "")
                "whisper_banned" -> ContextCompat.getString(context, R.string.irc_notice_whisper_banned)
                "whisper_banned_recipient" -> ContextCompat.getString(context, R.string.irc_notice_whisper_banned_recipient)
                "whisper_invalid_login" -> ContextCompat.getString(context, R.string.irc_notice_whisper_invalid_login)
                "whisper_invalid_self" -> ContextCompat.getString(context, R.string.irc_notice_whisper_invalid_self)
                "whisper_limit_per_min" -> ContextCompat.getString(context, R.string.irc_notice_whisper_limit_per_min)
                "whisper_limit_per_sec" -> ContextCompat.getString(context, R.string.irc_notice_whisper_limit_per_sec)
                "whisper_restricted" -> ContextCompat.getString(context, R.string.irc_notice_whisper_restricted)
                "whisper_restricted_recipient" -> ContextCompat.getString(context, R.string.irc_notice_whisper_restricted_recipient)
                else -> message
            }
        } else {
            message
        }
    }
}
