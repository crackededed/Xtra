package com.github.andreyasadchy.xtra.util.chat

import android.graphics.Color
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.StvBadge
import org.json.JSONObject

object StvEventApiUtils {

    class EmoteSetUpdate(
        val channelSet: Boolean,
        val setId: String,
        val added: List<Emote>,
        val removed: List<Emote>,
        val updated: List<Pair<Emote, Emote>>,
    )

    fun parseEmoteSetUpdate(body: JSONObject, useWebp: Boolean, channelStvEmoteSetId: String?): EmoteSetUpdate? {
        val id = if (!body.isNull("id")) body.optString("id").takeIf { it.isNotBlank() } else null
        if (id != null) {
            val channelSet = id == channelStvEmoteSetId
            val added = mutableListOf<Emote>()
            val removed = mutableListOf<Emote>()
            val updated = mutableListOf<Pair<Emote, Emote>>()
            val pushedArray = body.optJSONArray("pushed")
            if (pushedArray != null) {
                for (i in 0 until pushedArray.length()) {
                    val pushedObject = pushedArray.get(i) as? JSONObject
                    if (pushedObject?.optString("key") == "emotes") {
                        parseEmote(pushedObject.optJSONObject("value"), useWebp, channelSet)?.let {
                            added.add(it)
                        }
                    }
                }
            }
            val pulledArray = body.optJSONArray("pulled")
            if (pulledArray != null) {
                for (i in 0 until pulledArray.length()) {
                    val pulledObject = pulledArray.get(i) as? JSONObject
                    if (pulledObject?.optString("key") == "emotes") {
                        parseEmote(pulledObject.optJSONObject("old_value"), useWebp, channelSet)?.let {
                            removed.add(it)
                        }
                    }
                }
            }
            val updatedArray = body.optJSONArray("updated")
            if (updatedArray != null) {
                for (i in 0 until updatedArray.length()) {
                    val updatedObject = updatedArray.get(i) as? JSONObject
                    if (updatedObject?.optString("key") == "emotes") {
                        val old = parseEmote(updatedObject.optJSONObject("old_value"), useWebp, channelSet)
                        val new = parseEmote(updatedObject.optJSONObject("value"), useWebp, channelSet)
                        if (old != null && new != null) {
                            updated.add(Pair(old, new))
                        }
                    }
                }
            }
            return EmoteSetUpdate(channelSet, id, added, removed, updated)
        }
        return null
    }

    private fun parseEmote(value: JSONObject?, useWebp: Boolean, channelSet: Boolean): Emote? {
        val objectData = value?.optJSONObject("data")
        return if (objectData != null) {
            val name = if (!objectData.isNull("name")) objectData.optString("name").takeIf { it.isNotBlank() } else null
            val host = objectData.optJSONObject("host")
            if (name != null && host != null) {
                val template = if (!host.isNull("url")) host.optString("url").takeIf { it.isNotBlank() } else null
                if (template != null) {
                    val urls = mutableListOf<String>()
                    val files = host.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val fileObject = files.get(i) as? JSONObject
                            val fileName = if (fileObject?.isNull("name") == false) fileObject.optString("name").takeIf { it.isNotBlank() } else null
                            val fileFormat = if (fileObject?.isNull("format") == false) fileObject.optString("format").takeIf { it.isNotBlank() } else null
                            if (fileName != null &&
                                if (useWebp) {
                                    fileFormat == "WEBP"
                                } else {
                                    fileFormat == "GIF" || fileFormat == "PNG"
                                }
                            ) {
                                urls.add("https:${template}/${fileName}")
                            }
                        }
                    }
                    Emote(
                        name = name,
                        url1x = urls.getOrNull(0) ?: "https:${template}/1x.webp",
                        url2x = urls.getOrNull(1) ?: if (urls.isEmpty()) "https:${template}/2x.webp" else null,
                        url3x = urls.getOrNull(2) ?: if (urls.isEmpty()) "https:${template}/3x.webp" else null,
                        url4x = urls.getOrNull(3) ?: if (urls.isEmpty()) "https:${template}/4x.webp" else null,
                        format = urls.getOrNull(0)?.substringAfterLast(".") ?: "webp",
                        isAnimated = if (!objectData.isNull("animated")) objectData.optBoolean("animated") else true,
                        isOverlayEmote = objectData.optInt("flags") == 1,
                        source = if (channelSet) Emote.CHANNEL_STV else Emote.PERSONAL_STV,
                    )
                } else null
            } else null
        } else null
    }

    sealed class Cosmetic {
        class Paint(val paint: NamePaint) : Cosmetic()
        class Badge(val badge: StvBadge) : Cosmetic()
    }

    fun parseCosmetic(body: JSONObject, useWebp: Boolean): Cosmetic? {
        val obj = body.optJSONObject("object")
        val kind = obj?.optString("kind")
        val objectData = obj?.optJSONObject("data")
        if (kind != null && objectData != null) {
            when (kind) {
                "PAINT" -> {
                    val id = if (!objectData.isNull("id")) objectData.optString("id").takeIf { it.isNotBlank() } else null
                    if (id != null) {
                        val function = objectData.optString("function")
                        val shadows = mutableListOf<NamePaint.Shadow>()
                        val shadowsArray = objectData.optJSONArray("shadows")
                        if (shadowsArray != null) {
                            for (i in 0 until shadowsArray.length()) {
                                val shadowObject = shadowsArray.get(i) as? JSONObject
                                val xOffset = shadowObject?.optDouble("x_offset")?.toFloat()
                                val yOffset = shadowObject?.optDouble("y_offset")?.toFloat()
                                val radius = shadowObject?.optDouble("radius")?.toFloat()
                                val color = shadowObject?.optInt("color")
                                if (xOffset != null && yOffset != null && radius != null && color != null) {
                                    shadows.add(NamePaint.Shadow(xOffset, yOffset, radius, parseRGBAColor(color)))
                                }
                            }
                        }
                        when (function) {
                            "LINEAR_GRADIENT", "RADIAL_GRADIENT" -> {
                                val colors = mutableListOf<Int>()
                                val positions = mutableListOf<Float>()
                                val stopsArray = objectData.optJSONArray("stops")
                                if (stopsArray != null) {
                                    for (i in 0 until stopsArray.length()) {
                                        val stopObject = stopsArray.get(i) as? JSONObject
                                        val position = stopObject?.optDouble("at")?.toFloat()
                                        val color = stopObject?.optInt("color")
                                        if (color != null && position != null) {
                                            colors.add(parseRGBAColor(color))
                                            positions.add(position)
                                        }
                                    }
                                }
                                return Cosmetic.Paint(NamePaint(
                                    id = id,
                                    type = function,
                                    colors = colors.toIntArray(),
                                    colorPositions = positions.toFloatArray(),
                                    angle = objectData.optInt("angle"),
                                    repeat = objectData.optBoolean("repeat"),
                                    shadows = shadows,
                                ))
                            }
                            "URL" -> {
                                val imageUrl = if (!objectData.isNull("image_url")) objectData.optString("image_url").takeIf { it.isNotBlank() } else null
                                if (imageUrl != null) {
                                    return Cosmetic.Paint(NamePaint(
                                        id = id,
                                        type = function,
                                        imageUrl = imageUrl,
                                        shadows = shadows,
                                    ))
                                }
                            }
                        }
                    }
                }
                "BADGE" -> {
                    val id = if (!objectData.isNull("id")) objectData.optString("id").takeIf { it.isNotBlank() } else null
                    val host = objectData.optJSONObject("host")
                    if (id != null && host != null) {
                        val template = if (!host.isNull("url")) host.optString("url").takeIf { it.isNotBlank() } else null
                        if (template != null) {
                            val urls = mutableListOf<String>()
                            val files = host.optJSONArray("files")
                            if (files != null) {
                                for (i in 0 until files.length()) {
                                    val fileObject = files.get(i) as? JSONObject
                                    val fileName = if (fileObject?.isNull("name") == false) fileObject.optString("name").takeIf { it.isNotBlank() } else null
                                    val fileFormat = if (fileObject?.isNull("format") == false) fileObject.optString("format").takeIf { it.isNotBlank() } else null
                                    if (fileName != null &&
                                        if (useWebp) {
                                            fileFormat == "WEBP"
                                        } else {
                                            fileFormat == "GIF" || fileFormat == "PNG"
                                        }
                                    ) {
                                        urls.add("https:${template}/${fileName}")
                                    }
                                }
                            }
                            return Cosmetic.Badge(StvBadge(
                                id = id,
                                url1x = urls.getOrNull(0) ?: "https:${template}/1x.webp",
                                url2x = urls.getOrNull(1) ?: if (urls.isEmpty()) "https:${template}/2x.webp" else null,
                                url3x = urls.getOrNull(2) ?: if (urls.isEmpty()) "https:${template}/3x.webp" else null,
                                url4x = urls.getOrNull(3) ?: if (urls.isEmpty()) "https:${template}/4x.webp" else null,
                                name = objectData.optString("tooltip"),
                                format = urls.getOrNull(0)?.substringAfterLast(".") ?: "webp",
                            ))
                        }
                    }
                }
            }
        }
        return null
    }

    private fun parseRGBAColor(value: Int): Int {
        return Color.argb(value and 0xFF, value shr 24 and 0xFF, value shr 16 and 0xFF, value shr 8 and 0xFF)
    }

    sealed class Entitlement {
        class Paint(val userId: String, val paintId: String) : Entitlement()
        class Badge(val userId: String, val badgeId: String) : Entitlement()
        class EmoteSet(val userId: String, val setId: String) : Entitlement()
    }

    fun parseEntitlement(body: JSONObject): Entitlement? {
        val obj = body.optJSONObject("object")
        val kind = obj?.optString("kind")
        val user = obj?.optJSONObject("user")
        if (kind != null && user != null) {
            var userId: String? = null
            val connections = user.optJSONArray("connections")
            if (connections != null) {
                for (i in 0 until connections.length()) {
                    val connection = connections.get(i) as? JSONObject
                    if (connection?.optString("platform") == "TWITCH") {
                        userId = if (!connection.isNull("id")) connection.optString("id").takeIf { it.isNotBlank() } else null
                        break
                    }
                }
            }
            if (userId != null) {
                when (kind) {
                    "PAINT" -> {
                        val style = user.optJSONObject("style")
                        val paintId = if (style?.isNull("paint_id") == false) style.optString("paint_id").takeIf { it.isNotBlank() } else null
                        if (paintId != null) {
                            return Entitlement.Paint(userId, paintId)
                        }
                    }
                    "BADGE" -> {
                        val style = user.optJSONObject("style")
                        val badgeId = if (style?.isNull("badge_id") == false) style.optString("badge_id").takeIf { it.isNotBlank() } else null
                        if (badgeId != null) {
                            return Entitlement.Badge(userId, badgeId)
                        }
                    }
                    "EMOTE_SET" -> {
                        val refId = if (!obj.isNull("ref_id")) obj.optString("ref_id").takeIf { it.isNotBlank() } else null
                        if (refId != null) {
                            return Entitlement.EmoteSet(userId, refId)
                        }
                    }
                }
            }
        }
        return null
    }
}