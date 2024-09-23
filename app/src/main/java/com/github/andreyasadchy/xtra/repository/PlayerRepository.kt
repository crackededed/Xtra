package com.github.andreyasadchy.xtra.repository

import android.net.Uri
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.api.MiscApi
import com.github.andreyasadchy.xtra.api.UsherApi
import com.github.andreyasadchy.xtra.db.RecentEmotesDao
import com.github.andreyasadchy.xtra.db.VideoPositionsDao
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.chat.BttvResponse
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FfzResponse
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.RecentMessagesResponse
import com.github.andreyasadchy.xtra.model.chat.StvResponse
import com.github.andreyasadchy.xtra.model.gql.playlist.PlaybackAccessTokenResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.m3u8.MediaPlaylist
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random

@Singleton
class PlayerRepository @Inject constructor(
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    private val usher: UsherApi,
    private val misc: MiscApi,
    private val graphQL: GraphQLRepository,
    private val recentEmotes: RecentEmotesDao,
    private val videoPositions: VideoPositionsDao) {

    suspend fun getMediaPlaylist(url: String): MediaPlaylist = withContext(Dispatchers.IO) {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.byteStream().use {
                PlaylistUtils.parseMediaPlaylist(it)
            }
        }
    }

    suspend fun loadStreamPlaylistUrl(gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean): String = withContext(Dispatchers.IO) {
        var accessToken = loadStreamPlaybackAccessToken(gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity)?.data?.streamPlaybackAccessToken
        // request an anonymous access token if the user is restricted
        if (accessToken?.value?.contains("\"forbidden\":true") == true) {
            accessToken = loadStreamPlaybackAccessToken(gqlHeaders.filterNot { it.key == C.HEADER_TOKEN }, channelLogin, randomDeviceId, xDeviceId, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity)?.data?.streamPlaybackAccessToken
        }
        buildUrl(
            "https://usher.ttvnw.net/api/channel/hls/$channelLogin.m3u8?",
            "allow_source", "true",
            "allow_audio_only", "true",
            "fast_bread", "true", //low latency
            "p", Random.nextInt(9999999).toString(),
            "platform", if (supportedCodecs?.contains("av1", true) == true) "web" else null,
            "sig", accessToken?.signature,
            "supported_codecs", supportedCodecs,
            "token", accessToken?.value
        ).toString()
    }

    suspend fun loadStreamPlaylist(gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean): String? = withContext(Dispatchers.IO) {
        val accessToken = loadStreamPlaybackAccessToken(gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, false, null, null, null, null, enableIntegrity)?.data?.streamPlaybackAccessToken
        val playlistQueryOptions = HashMap<String, String>().apply {
            put("allow_source", "true")
            put("allow_audio_only", "true")
            put("p", Random.nextInt(9999999).toString())
            if (supportedCodecs?.contains("av1", true) == true) {
                put("platform", "web")
            }
            accessToken?.signature?.let { put("sig", it) }
            supportedCodecs?.let { put("supported_codecs", it) }
            accessToken?.value?.let { put("token", it) }
        }
        usher.getStreamPlaylist(channelLogin, playlistQueryOptions).body()?.string()
    }

    suspend fun loadStreamPlaylistResponse(url: String, proxyMultivariantPlaylist: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?): String = withContext(Dispatchers.IO) {
        okHttpClient.newBuilder().apply {
            if (proxyMultivariantPlaylist && !proxyHost.isNullOrBlank() && proxyPort != null) {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                    }
                }
            }
        }.build().newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.string()
        }
    }

    private suspend fun loadStreamPlaybackAccessToken(gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean): PlaybackAccessTokenResponse? = withContext(Dispatchers.IO) {
        val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders, randomDeviceId, xDeviceId, enableIntegrity)
        if (proxyPlaybackAccessToken && !proxyHost.isNullOrBlank() && proxyPort != null) {
            okHttpClient.newBuilder().apply {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder().header(
                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                        ).build()
                    }
                }
            }.build().newCall(Request.Builder().apply {
                url("https://gql.twitch.tv/gql/")
                post(graphQL.getPlaybackAccessTokenRequestBody(channelLogin, "", playerType).toString().toRequestBody())
                accessTokenHeaders.filterKeys { it == C.HEADER_CLIENT_ID || it == "X-Device-Id" }.forEach {
                    addHeader(it.key, it.value)
                }
            }.build()).execute().use { response ->
                val text = response.body.string()
                if (text.isNotBlank()) {
                    json.decodeFromString<PlaybackAccessTokenResponse>(text)
                } else null
            }
        } else {
            graphQL.loadPlaybackAccessToken(
                headers = accessTokenHeaders,
                login = channelLogin,
                playerType = playerType
            )
        }.also { response ->
            if (enableIntegrity) {
                response?.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
        }
    }

    suspend fun loadVideoPlaylistUrl(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean): Uri = withContext(Dispatchers.IO) {
        val accessToken = loadVideoPlaybackAccessToken(gqlHeaders, videoId, playerType, enableIntegrity).data?.videoPlaybackAccessToken
        buildUrl(
            "https://usher.ttvnw.net/vod/$videoId.m3u8?",
            "allow_source", "true",
            "allow_audio_only", "true",
            "p", Random.nextInt(9999999).toString(),
            "platform", if (supportedCodecs?.contains("av1", true) == true) "web" else null,
            "sig", accessToken?.signature,
            "supported_codecs", supportedCodecs,
            "token", accessToken?.value,
        )
    }

    suspend fun loadVideoPlaylist(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, enableIntegrity: Boolean): Response<ResponseBody> = withContext(Dispatchers.IO) {
        val accessToken = loadVideoPlaybackAccessToken(gqlHeaders, videoId, playerType, enableIntegrity).data?.videoPlaybackAccessToken
        val playlistQueryOptions = HashMap<String, String>().apply {
            put("allow_source", "true")
            put("allow_audio_only", "true")
            put("p", Random.nextInt(9999999).toString())
            accessToken?.signature?.let { put("sig", it) }
            accessToken?.value?.let { put("token", it) }
        }
        usher.getVideoPlaylist(videoId, playlistQueryOptions)
    }

    private suspend fun loadVideoPlaybackAccessToken(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, enableIntegrity: Boolean): PlaybackAccessTokenResponse {
        val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders = gqlHeaders, randomDeviceId = true, enableIntegrity = enableIntegrity)
        return graphQL.loadPlaybackAccessToken(
            headers = accessTokenHeaders,
            vodId = videoId,
            playerType = playerType
        ).also { response ->
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
        }
    }

    private fun getPlaybackAccessTokenHeaders(gqlHeaders: Map<String, String>, randomDeviceId: Boolean?, xDeviceId: String? = null, enableIntegrity: Boolean): Map<String, String> {
        return if (enableIntegrity) {
            gqlHeaders
        } else {
            gqlHeaders.toMutableMap().apply {
                if (randomDeviceId != false) {
                    val randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 32) //X-Device-Id or Device-ID removes "commercial break in progress" (length 16 or 32)
                    put("X-Device-Id", randomId)
                } else {
                    xDeviceId?.let { put("X-Device-Id", it) }
                }
            }
        }
    }

    suspend fun loadClipUrls(gqlHeaders: Map<String, String>, clipId: String?): Map<String, String>? = withContext(Dispatchers.IO) {
        val response = graphQL.loadClipUrls(gqlHeaders, clipId)
        response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
        val accessToken = response.data?.clip?.playbackAccessToken
        response.data?.clip?.videoQualities?.withIndex()?.associateBy({
            if (!it.value.quality.isNullOrBlank()) {
                val frameRate = it.value.frameRate?.roundToInt() ?: 0
                if (frameRate < 60) {
                    "${it.value.quality}p"
                } else {
                    "${it.value.quality}p${frameRate}"
                }
            } else {
                it.index.toString()
            }
        }, { "${it.value.sourceURL}?sig=${Uri.encode(accessToken?.signature)}&token=${Uri.encode(accessToken?.value)}" })
    }

    private fun buildUrl(url: String, vararg queryParams: String?): Uri {
        val stringBuilder = StringBuilder(url)
        stringBuilder.append(queryParams[0])
            .append("=")
            .append(URLEncoder.encode(queryParams[1], Charsets.UTF_8.name()))
        for (i in 2 until queryParams.size step 2) {
            val value = queryParams[i + 1]
            if (!value.isNullOrBlank()) {
                stringBuilder.append("&")
                    .append(queryParams[i])
                    .append("=")
                    .append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            }
        }
        return stringBuilder.toString().toUri()
    }

    suspend fun loadRecentMessages(channelLogin: String, limit: String): RecentMessagesResponse = withContext(Dispatchers.IO) {
        misc.getRecentMessages(channelLogin, limit)
    }

    suspend fun loadGlobalStvEmotes(): List<Emote> = withContext(Dispatchers.IO) {
        parseStvEmotes(misc.getGlobalStvEmotes().emotes)
    }

    suspend fun loadStvEmotes(channelId: String): List<Emote> = withContext(Dispatchers.IO) {
        parseStvEmotes(misc.getStvEmotes(channelId).emoteSet.emotes)
    }

    private fun parseStvEmotes(response: List<StvResponse>): List<Emote> {
        return response.mapNotNull { emote ->
            emote.name?.takeIf { it.isNotBlank() }?.let { name ->
                emote.data?.let { data ->
                    data.host?.let { host ->
                        host.url?.takeIf { it.isNotBlank() }?.let { template ->
                            val urls = host.files?.mapNotNull { file ->
                                file.name?.takeIf { it.isNotBlank() && !it.contains("avif", true) }?.let { name ->
                                    "https:${template}/${name}"
                                }
                            }
                            Emote(
                                name = name,
                                url1x = urls?.getOrNull(0) ?: "https:${template}/1x.webp",
                                url2x = urls?.getOrNull(1) ?: if (urls.isNullOrEmpty()) "https:${template}/2x.webp" else null,
                                url3x = urls?.getOrNull(2) ?: if (urls.isNullOrEmpty()) "https:${template}/3x.webp" else null,
                                url4x = urls?.getOrNull(3) ?: if (urls.isNullOrEmpty()) "https:${template}/4x.webp" else null,
                                format = urls?.getOrNull(0)?.substringAfterLast(".") ?: "webp",
                                isAnimated = data.animated ?: true,
                                isZeroWidth = emote.flags == 1
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun loadGlobalBttvEmotes(): List<Emote> = withContext(Dispatchers.IO) {
        parseBttvEmotes(misc.getGlobalBttvEmotes())
    }

    suspend fun loadBttvEmotes(channelId: String): List<Emote> = withContext(Dispatchers.IO) {
        parseBttvEmotes(
            misc.getBttvEmotes(channelId).entries.filter { it.key != "bots" && it.value is JsonArray }.map { entry ->
                (entry.value as JsonArray).map { json.decodeFromJsonElement<BttvResponse>(it) }
            }.flatten()
        )
    }

    private fun parseBttvEmotes(response: List<BttvResponse>): List<Emote> {
        val list = listOf("IceCold", "SoSnowy", "SantaHat", "TopHat", "CandyCane", "ReinDeer", "cvHazmat", "cvMask")
        return response.mapNotNull { emote ->
            emote.code?.takeIf { it.isNotBlank() }?.let { name ->
                emote.id?.takeIf { it.isNotBlank() }?.let { id ->
                    Emote(
                        name = name,
                        url1x = "https://cdn.betterttv.net/emote/$id/1x.webp",
                        url2x = "https://cdn.betterttv.net/emote/$id/2x.webp",
                        url3x = "https://cdn.betterttv.net/emote/$id/2x.webp",
                        url4x = "https://cdn.betterttv.net/emote/$id/3x.webp",
                        format = "webp",
                        isAnimated = emote.animated ?: true,
                        isZeroWidth = list.contains(name)
                    )
                }
            }
        }
    }

    suspend fun loadGlobalFfzEmotes(): List<Emote> = withContext(Dispatchers.IO) {
        val response = misc.getGlobalFfzEmotes()
        response.sets.entries.filter { it.key.toIntOrNull()?.let { set -> response.globalSets.contains(set) } == true }.flatMap {
            it.value.emoticons?.let { emotes -> parseFfzEmotes(emotes) } ?: emptyList()
        }
    }

    suspend fun loadFfzEmotes(channelId: String): List<Emote> = withContext(Dispatchers.IO) {
        misc.getFfzEmotes(channelId).sets.entries.flatMap {
            it.value.emoticons?.let { emotes -> parseFfzEmotes(emotes) } ?: emptyList()
        }
    }

    private fun parseFfzEmotes(response: List<FfzResponse.Emote>): List<Emote> {
        return response.mapNotNull { emote ->
            emote.name?.takeIf { it.isNotBlank() }?.let { name ->
                val isAnimated = emote.animated != null
                if (isAnimated) {
                    emote.animated
                } else {
                    emote.urls
                }?.let { urls ->
                    Emote(
                        name = name,
                        url1x = urls.url1x,
                        url2x = urls.url2x,
                        url3x = urls.url2x,
                        url4x = urls.url4x,
                        format = if (isAnimated) "webp" else null,
                        isAnimated = isAnimated
                    )
                }
            }
        }
    }

    fun loadRecentEmotesFlow() = recentEmotes.getAllFlow()

    suspend fun loadRecentEmotes(): List<RecentEmote> = withContext(Dispatchers.IO) {
        recentEmotes.getAll()
    }

    suspend fun insertRecentEmotes(emotes: Collection<RecentEmote>) = withContext(Dispatchers.IO) {
        val listSize = emotes.size
        val list = if (listSize <= RecentEmote.MAX_SIZE) {
            emotes
        } else {
            emotes.toList().subList(listSize - RecentEmote.MAX_SIZE, listSize)
        }
        recentEmotes.ensureMaxSizeAndInsert(list)
    }

    fun loadVideoPositions() = videoPositions.getAll()

    fun saveVideoPosition(position: VideoPosition) {
        GlobalScope.launch {
            videoPositions.insert(position)
        }
    }

    suspend fun deleteVideoPositions() = withContext(Dispatchers.IO) {
        videoPositions.deleteAll()
    }
}