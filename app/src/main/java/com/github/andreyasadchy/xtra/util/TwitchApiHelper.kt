package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.icu.number.Notation
import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.text.CompactDecimalFormat
import android.os.Build
import android.text.format.DateUtils
import com.github.andreyasadchy.xtra.R
import org.json.JSONObject
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TwitchApiHelper {

    private val imageSizeRegex = Regex("-\\d+x\\d+.")
    var checkedValidation = false
    var checkedUpdates = false

    fun getStreamThumbnail(url: String?): String? {
        return when {
            url.isNullOrBlank() -> "https://static-cdn.jtvnw.net/ttv-static/404_preview-440x248.jpg"
            url.contains("{width}x{height}") -> url.replace("{width}", "1280").replace("{height}", "720")
            else -> url.replace(imageSizeRegex, "-1280x720.")
        }
    }

    fun getVideoThumbnail(url: String?): String? {
        return when {
            url.isNullOrBlank() || url.startsWith("https://vod-secure.twitch.tv/_404/404_processing") -> {
                "https://vod-secure.twitch.tv/_404/404_processing_320x180.png"
            }
            url.contains("{width}x{height}") -> url.replace("{width}", "1280").replace("{height}", "720")
            url.contains("%{width}x%{height}") -> url.replace("%{width}", "1280").replace("%{height}", "720")
            else -> url.replace(imageSizeRegex, "-1280x720.")
        }
    }

    fun getClipThumbnail(url: String?): String? {
        return url?.replace(imageSizeRegex, "-1280x720.")
    }

    fun getGameBoxArt(url: String?): String? {
        return when {
            url.isNullOrBlank() -> "https://static-cdn.jtvnw.net/ttv-static/404_boxart.jpg"
            url.contains("{width}x{height}") -> url.replace("{width}", "285").replace("{height}", "380")
            else -> url.replace(imageSizeRegex, "-285x380.")
        }
    }

    fun getProfileImage(url: String?): String? {
        return url?.replace(imageSizeRegex, "-300x300.")
    }

    fun getType(context: Context, type: String?): String? {
        return when (type?.lowercase()) {
            "archive" -> context.getString(R.string.video_type_archive)
            "highlight" -> context.getString(R.string.video_type_highlight)
            "upload" -> context.getString(R.string.video_type_upload)
            else -> null
        }
    }

    fun getDuration(duration: String): Int {
        val h = duration.substringBefore("h", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        val m = duration.substringBefore("m", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        val s = duration.substringBefore("s", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        return (h * 3600) + (m * 60) + s
    }

    fun getDurationFromSeconds(context: Context, input: String?): String? {
        return input?.toIntOrNull()?.let { duration ->
            val days = (duration / 86400)
            val hours = ((duration % 86400) / 3600)
            val minutes = (((duration % 86400) % 3600) / 60)
            val seconds = (duration % 60)
            buildString {
                if (days > 0) {
                    append("$days${context.getString(R.string.days)}")
                }
                if (hours > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$hours${context.getString(R.string.hours)}")
                }
                if (minutes > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$minutes${context.getString(R.string.minutes)}")
                }
                if (seconds > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$seconds${context.getString(R.string.seconds)}")
                }
            }
        }
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

    fun formatDate(context: Context, time: Long): String {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentYear = Year.now().value
            val year = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.UTC).year
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val year = Calendar.getInstance().let {
                it.timeInMillis = time
                it.get(Calendar.YEAR)
            }
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        }
        return DateUtils.formatDateTime(context, time, format)
    }

    fun formatCount(count: Int, compact: Boolean): String {
        return if (compact) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    NumberFormatter.withLocale(Locale.getDefault())
                        .notation(Notation.compactShort())
                        .precision(Precision.maxFraction(1))
                        .roundingMode(RoundingMode.DOWN)
                        .format(count)
                        .toString()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val format = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                    format.maximumFractionDigits = 1
                    format.roundingMode = RoundingMode.DOWN.ordinal
                    format.format(count)
                }
                else -> {
                    if (count > 1000) {
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
                        if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
                    } else {
                        count.toString()
                    }
                }
            }
        } else {
            NumberFormat.getInstance().format(count)
        }
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

    fun getVideoUrlsFromPreview(url: String, type: String?, list: List<String>?): Map<String, String> {
        val qualityList = list ?: listOf("chunked", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "144p30", "high", "medium", "low", "mobile", "audio_only")
        return qualityList.associate { quality ->
            val name = if (quality == "chunked") {
                "source"
            } else {
                quality
            }
            val url = url
                .replace("storyboards", quality)
                .replaceAfterLast("/",
                    if (type?.lowercase() == "highlight") {
                        "highlight-${url.substringAfterLast("/").substringBefore("-")}.m3u8"
                    } else {
                        "index-dvr.m3u8"
                    }
                )
            name to url
        }
    }

    fun getMessageIdString(context: Context, msgId: String?): String? {
        return when (msgId) {
            "highlighted-message" -> context.getString(R.string.irc_msgid_highlighted_message)
            "announcement" -> context.getString(R.string.irc_msgid_announcement)
            else -> null
        }
    }
}
