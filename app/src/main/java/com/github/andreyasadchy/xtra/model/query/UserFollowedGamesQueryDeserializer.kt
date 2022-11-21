package com.github.andreyasadchy.xtra.model.query

import com.github.andreyasadchy.xtra.model.helix.game.Game
import com.github.andreyasadchy.xtra.model.helix.tag.Tag
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class UserFollowedGamesQueryDeserializer : JsonDeserializer<UserFollowedGamesQueryResponse> {

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): UserFollowedGamesQueryResponse {
        val data = mutableListOf<Game>()
        val dataJson = json.asJsonObject?.getAsJsonObject("data")?.getAsJsonObject("user")?.getAsJsonObject("followedGames")?.getAsJsonArray("nodes")
        dataJson?.forEach { item ->
            item?.asJsonObject?.let { obj ->
                val tags = mutableListOf<Tag>()
                obj.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { tagElement ->
                    tagElement?.takeIf { it.isJsonObject }?.asJsonObject.let { tag ->
                        tags.add(Tag(
                            id = tag?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                            name = tag?.get("localizedName")?.takeIf { !it.isJsonNull }?.asString,
                        ))
                    }
                }
                data.add(Game(
                    id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString,
                    name = obj.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
                    box_art_url = obj.get("boxArtURL")?.takeIf { !it.isJsonNull }?.asString,
                    viewersCount = obj.get("viewersCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0, // returns null if 0
                    broadcastersCount = obj.get("broadcastersCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    tags = tags
                ))
            }
        }
        return UserFollowedGamesQueryResponse(data)
    }
}