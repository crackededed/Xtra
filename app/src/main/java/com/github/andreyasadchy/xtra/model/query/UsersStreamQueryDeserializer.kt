package com.github.andreyasadchy.xtra.model.query

import com.github.andreyasadchy.xtra.model.helix.stream.Stream
import com.github.andreyasadchy.xtra.model.helix.tag.Tag
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class UsersStreamQueryDeserializer : JsonDeserializer<UsersStreamQueryResponse> {

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): UsersStreamQueryResponse {
        val data = mutableListOf<Stream>()
        val dataJson = json.asJsonObject?.getAsJsonObject("data")?.getAsJsonArray("users")
        dataJson?.forEach { item ->
            item?.asJsonObject?.let { obj ->
                if (obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("viewersCount")?.isJsonNull == false) {
                    val tags = mutableListOf<Tag>()
                    obj.get("stream")?.takeIf { !it.isJsonNull }?.asJsonObject?.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { tagElement ->
                        tagElement?.takeIf { it.isJsonObject }?.asJsonObject.let { tag ->
                            tags.add(Tag(
                                id = tag?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                                name = tag?.get("localizedName")?.takeIf { !it.isJsonNull }?.asString,
                            ))
                        }
                    }
                    data.add(Stream(
                        id = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                        user_id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString,
                        user_login = obj.get("login")?.takeIf { !it.isJsonNull }?.asString,
                        user_name = obj.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
                        game_id = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("game")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                        game_name = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("game")?.takeIf { it.isJsonObject }?.asJsonObject?.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
                        type = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("type")?.takeIf { !it.isJsonNull }?.asString,
                        title = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("broadcaster")?.takeIf { it.isJsonObject }?.asJsonObject?.get("broadcastSettings")?.takeIf { it.isJsonObject }?.asJsonObject?.get("title")?.takeIf { !it.isJsonNull }?.asString,
                        viewer_count = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("viewersCount")?.takeIf { !it.isJsonNull }?.asInt,
                        started_at = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("createdAt")?.takeIf { !it.isJsonNull }?.asString,
                        thumbnail_url = obj.get("stream")?.takeIf { it.isJsonObject }?.asJsonObject?.get("previewImageURL")?.takeIf { !it.isJsonNull }?.asString,
                        profileImageURL = obj.get("profileImageURL")?.takeIf { !it.isJsonNull }?.asString,
                        tags = tags
                    ))
                }
            }
        }
        return UsersStreamQueryResponse(data)
    }
}