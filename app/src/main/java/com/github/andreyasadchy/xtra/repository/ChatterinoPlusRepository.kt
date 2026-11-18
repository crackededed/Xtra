package com.github.andreyasadchy.xtra.repository

import android.os.SystemClock
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.math.roundToInt

class ChatterinoPlusRepository(baseClient: Lazy<OkHttpClient>) {
    private val httpClient by lazy {
        baseClient.value.newBuilder().apply { interceptors().clear() }.followRedirects(false).build()
    }
    private val mutex = Mutex()
    private val roster = MutableStateFlow(Roster.EMPTY)
    val cosmetics = roster.asStateFlow()
    private var nextRefresh = 0L
    private var etag: String? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (SystemClock.elapsedRealtime() < nextRefresh) return@withLock
            nextRefresh = SystemClock.elapsedRealtime() + 60_000L
            try {
                val request = Request.Builder().url(ROSTER_URL).apply {
                    etag?.let { header("If-None-Match", it) }
                }.build()
                httpClient.newCall(request).executeAsync().use { response ->
                    when {
                        response.code == 304 -> Unit
                        response.isSuccessful -> {
                            roster.value = parseRoster(JSONObject(response.body.string()))
                            etag = response.header("ETag")
                        }
                        else -> return@withLock
                    }
                }
                nextRefresh = SystemClock.elapsedRealtime() + 30 * 60_000L
            } catch (e: CancellationException) {
                nextRefresh = 0L
                throw e
            } catch (_: Exception) {
                // Keep the last successful snapshot when the provider is unavailable.
            }
        }
    }

    data class Cosmetics(val paint: NamePaint?, val badge: Badge?)

    data class Badge(val title: String, val englishTitle: String?, val dark: Urls, val light: Urls?)

    data class Urls(val one: String?, val two: String?, val four: String?)

    class Roster internal constructor(
        private val byId: Map<String, Cosmetics>,
        private val byLogin: Map<String, Cosmetics>,
    ) {
        fun find(id: String?, login: String?): Cosmetics? =
            if (id.isNullOrBlank()) byLogin[login?.lowercase()] else byId[id]

        companion object {
            val EMPTY = Roster(emptyMap(), emptyMap())
        }
    }

    companion object {
        private const val ROSTER_URL = "https://chat.eblo.id/api/v1/plus/roster"

        internal fun parseRoster(json: JSONObject): Roster {
            val users = json.getJSONArray("users")
            val paintData = json.optJSONObject("paints") ?: JSONObject()
            val paints = paintData.keys().asSequence().mapNotNull { id ->
                paintData.optJSONObject(id)?.optJSONObject("spec")?.let { paint(id, it) }?.let { id to it }
            }.toMap()
            val badges = json.optJSONObject("badges")
            val badgeSet = badges?.optJSONObject("set") ?: JSONObject()
            val parsedBadges = badgeSet.keys().asSequence().mapNotNull { id ->
                badgeSet.optJSONObject(id)?.let(::badge)?.let { id to it }
            }.toMap()
            val defaultBadge = parsedBadges["m1"] ?: badges?.optJSONObject("plus")?.let(::badge)
            val byId = mutableMapOf<String, Cosmetics>()
            val byLogin = mutableMapOf<String, Cosmetics>()
            for (index in 0 until users.length()) {
                val user = users.optJSONObject(index) ?: continue
                val id = user.optString("id")
                if (id.isEmpty() || !id.all(Char::isDigit)) continue
                val paint = paints[user.optString("paint")]
                val badge = if (user.optBoolean("badge")) parsedBadges[user.optString("badge_id")] ?: defaultBadge else null
                if (paint == null && badge == null) continue
                val cosmetics = Cosmetics(paint, badge)
                byId[id] = cosmetics
                user.optString("login").takeIf { it.isNotBlank() }?.lowercase()?.let { byLogin[it] = cosmetics }
            }
            return Roster(byId, byLogin)
        }

        private fun paint(id: String, spec: JSONObject): NamePaint? {
            val shadowData = spec.optJSONArray("shadows")
            val shadows = (0 until minOf(shadowData?.length() ?: 0, 3)).mapNotNull { index ->
                val shadow = shadowData?.optJSONArray(index) ?: return@mapNotNull null
                val color = color(shadow.optString(3)) ?: return@mapNotNull null
                NamePaint.Shadow(
                    shadow.optDouble(0, 0.0).finiteFloat().coerceIn(-4f, 4f),
                    shadow.optDouble(1, 0.0).finiteFloat().coerceIn(-4f, 4f),
                    shadow.optDouble(2, 0.0).finiteFloat().coerceIn(0f, 8f),
                    color,
                )
            }
            val type = spec.optString("type")
            if (type == "image") {
                val url = imageUrl(spec.optString("img")) ?: return null
                return NamePaint(id, "URL", imageUrl = url, shadows = shadows, imageFit = spec.optString("fit"))
            }
            if (type != "linear" && type != "radial") return null
            val stopData = spec.optJSONArray("stops") ?: return null
            val stops = (0 until minOf(stopData.length(), 16)).mapNotNull { index ->
                val stop = stopData.optJSONArray(index) ?: return@mapNotNull null
                val position = stop.optDouble(0).toFloat().takeIf { it.isFinite() } ?: return@mapNotNull null
                val color = color(stop.optString(1)) ?: return@mapNotNull null
                position.coerceIn(0f, 1f) to color
            }.sortedBy { it.first }
            if (stops.size < 2) return null
            val motion = spec.optJSONObject("anim")
            val animation = motion?.optString("kind")?.takeIf { it in setOf("slide", "spin", "pulse") }?.let {
                val duration = motion.optDouble("speed", 6.0).takeIf { speed -> speed.isFinite() && speed > 0.5 } ?: 6.0
                NamePaint.Animation(it, (duration.coerceAtMost(600.0) * 1000).roundToInt())
            }
            return NamePaint(
                id = id,
                type = if (type == "linear") "LINEAR_GRADIENT" else "RADIAL_GRADIENT",
                colors = stops.map { it.second }.toIntArray(),
                colorPositions = stops.map { it.first }.toFloatArray(),
                angle = spec.optInt("angle"),
                shadows = shadows,
                tiles = spec.optInt("tiles", if (spec.optInt("repeat") != 0) 3 else 1).coerceIn(1, 6),
                animation = animation,
            )
        }

        private fun Double.finiteFloat(): Float = toFloat().takeIf { it.isFinite() } ?: 0f

        private fun color(value: String): Int? {
            if (!value.startsWith("#")) return null
            val hex = when (value.length) {
                7 -> "ff" + value.substring(1)
                9 -> value.takeLast(2) + value.substring(1, 7)
                else -> return null
            }
            return hex.toUIntOrNull(16)?.toInt()
        }

        private fun badge(data: JSONObject): Badge? {
            val dark = urls(data.optJSONObject("urls")) ?: return null
            return Badge(
                data.optString("title", "Chatterino+ PLUS"),
                data.optString("title_en").takeIf { it.isNotBlank() },
                dark,
                urls(data.optJSONObject("light")),
            )
        }

        private fun urls(data: JSONObject?): Urls? = data?.let {
            Urls(imageUrl(it.optString("1x")), imageUrl(it.optString("2x")), imageUrl(it.optString("4x")))
                .takeIf { urls -> urls.one != null || urls.two != null || urls.four != null }
        }

        private fun imageUrl(value: String): String? = value.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == "chat.eblo.id" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() }
            ?.takeIf { it.encodedPath.startsWith("/chatterino/media/") || it.encodedPath.startsWith("/chat/static/") }
            ?.toString()
    }
}
