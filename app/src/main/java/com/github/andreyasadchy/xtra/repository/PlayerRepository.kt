package com.github.andreyasadchy.xtra.repository

import android.net.Uri
import android.net.http.HttpEngine
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Base64
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.writeObject
import com.apollographql.apollo.api.parseResponse
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.StreamPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.db.RecentEmotesDao
import com.github.andreyasadchy.xtra.db.VideoPositionsDao
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.gql.playlist.PlaybackAccessTokenResponse
import com.github.andreyasadchy.xtra.model.misc.BttvResponse
import com.github.andreyasadchy.xtra.model.misc.FfzChannelResponse
import com.github.andreyasadchy.xtra.model.misc.FfzGlobalResponse
import com.github.andreyasadchy.xtra.model.misc.FfzResponse
import com.github.andreyasadchy.xtra.model.misc.RecentMessagesResponse
import com.github.andreyasadchy.xtra.model.misc.StvChannelResponse
import com.github.andreyasadchy.xtra.model.misc.StvGlobalResponse
import com.github.andreyasadchy.xtra.model.misc.StvResponse
import com.github.andreyasadchy.xtra.type.BadgeImageSize
import com.github.andreyasadchy.xtra.type.EmoteType
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UploadDataProviders
import org.chromium.net.apihelpers.UrlRequestCallbacks
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlin.random.Random

@Singleton
class PlayerRepository @Inject constructor(
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val recentEmotes: RecentEmotesDao,
    private val videoPositions: VideoPositionsDao,
) {

    suspend fun loadStreamPlaylistUrl(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean): String = withContext(Dispatchers.IO) {
        val accessToken = loadStreamPlaybackAccessToken(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity).let { token ->
            if (token.second?.contains("\"forbidden\":true") == true && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                loadStreamPlaybackAccessToken(networkLibrary, gqlHeaders.filterNot { it.key == C.HEADER_TOKEN }, channelLogin, randomDeviceId, xDeviceId, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, enableIntegrity)
            } else token
        }
        val query = mutableMapOf<String, String>().apply {
            put("allow_source", "true")
            put("allow_audio_only", "true")
            put("fast_bread", "true") //low latency
            put("p", Random.nextInt(9999999).toString())
            if (supportedCodecs?.contains("av1", true) == true) {
                put("platform", "web")
            }
            accessToken.first?.let { put("sig", it) }
            supportedCodecs?.let { put("supported_codecs", it) }
            accessToken.second?.let { put("token", it) }
        }.map { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}" }.joinToString("&", "?")
        "https://usher.ttvnw.net/api/v2/channel/hls/${channelLogin}.m3u8${query}"
    }

    suspend fun loadStreamPlaylist(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean): String? = withContext(Dispatchers.IO) {
        val url = loadStreamPlaylistUrl(networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, false, null, null, null, null, enableIntegrity)
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    String(response.second)
                } else null
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        response.responseBody as String
                    } else null
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body.string()
                    } else null
                }
            }
        }
    }

    private suspend fun loadStreamPlaybackAccessToken(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, enableIntegrity: Boolean): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders, randomDeviceId, xDeviceId, enableIntegrity)
        try {
            val response = if (proxyPlaybackAccessToken && !proxyHost.isNullOrBlank() && proxyPort != null) {
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
                    accessTokenHeaders.filterKeys { it == C.HEADER_CLIENT_ID || it == "X-Device-Id" }.forEach {
                        addHeader(it.key, it.value)
                    }
                    header("Content-Type", "application/json")
                    post(graphQLRepository.getPlaybackAccessTokenRequestBody(channelLogin, "", playerType).toRequestBody())
                }.build()).execute().use { response ->
                    json.decodeFromString<PlaybackAccessTokenResponse>(response.body.string())
                }
            } else {
                graphQLRepository.loadPlaybackAccessToken(
                    networkLibrary = networkLibrary,
                    headers = accessTokenHeaders,
                    login = channelLogin,
                    playerType = playerType
                )
            }
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.streamPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            val response = if (proxyPlaybackAccessToken && !proxyHost.isNullOrBlank() && proxyPort != null) {
                val query = StreamPlaybackAccessTokenQuery(channelLogin, "web", playerType ?: "")
                val body = buildJsonString {
                    query.apply {
                        writeObject {
                            name("variables")
                            writeObject {
                                serializeVariables(this, CustomScalarAdapters.Empty, false)
                            }
                            name("query")
                            value(document().replaceFirst(name(), "null"))
                        }
                    }
                }
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
                    accessTokenHeaders.filterKeys { it == C.HEADER_CLIENT_ID || it == "X-Device-Id" }.forEach {
                        addHeader(it.key, it.value)
                    }
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    response.body.byteStream().source().buffer().jsonReader().use {
                        query.parseResponse(it)
                    }
                }
            } else {
                graphQLRepository.loadQueryStreamPlaybackAccessToken(
                    networkLibrary = networkLibrary,
                    headers = accessTokenHeaders,
                    login = channelLogin,
                    platform = "web",
                    playerType = playerType ?: ""
                )
            }
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.streamPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        }
    }

    suspend fun loadVideoPlaylistUrl(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders = gqlHeaders, randomDeviceId = true, enableIntegrity = enableIntegrity)
        val accessToken = try {
            val response = graphQLRepository.loadPlaybackAccessToken(
                networkLibrary = networkLibrary,
                headers = accessTokenHeaders,
                vodId = videoId,
                playerType = playerType
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.videoPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            val response = graphQLRepository.loadQueryVideoPlaybackAccessToken(
                networkLibrary = networkLibrary,
                headers = accessTokenHeaders,
                videoId = videoId!!,
                platform = "web",
                playerType = playerType ?: ""
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.videoPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        }
        val backupQualities = mutableListOf<String>()
        accessToken.second?.let { value ->
            val json = try {
                JSONObject(value)
            } catch (e: JSONException) {
                null
            }
            val array = json?.optJSONObject("chansub")?.optJSONArray("restricted_bitrates")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val quality = array.optString(i)
                    if (!quality.isNullOrBlank()) {
                        backupQualities.add(quality)
                    }
                }
            }
        }
        val query = mutableMapOf<String, String>().apply {
            put("allow_source", "true")
            put("allow_audio_only", "true")
            put("include_unavailable", "true")
            put("p", Random.nextInt(9999999).toString())
            if (supportedCodecs?.contains("av1", true) == true) {
                put("platform", "web")
            }
            accessToken.first?.let { put("sig", it) }
            supportedCodecs?.let { put("supported_codecs", it) }
            accessToken.second?.let { put("token", it) }
        }.map { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}" }.joinToString("&", "?")
        "https://usher.ttvnw.net/vod/v2/${videoId}.m3u8${query}" to backupQualities
    }

    suspend fun loadVideoPlaylist(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?, enableIntegrity: Boolean): Pair<String?, List<String>> = withContext(Dispatchers.IO) {
        val result = loadVideoPlaylistUrl(networkLibrary, gqlHeaders, videoId, playerType, supportedCodecs, enableIntegrity)
        val url = result.first
        val backupQualities = result.second
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    String(response.second)
                } else null
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        response.responseBody as String
                    } else null
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        String(response.second)
                    } else null
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body.string()
                    } else null
                }
            }
        } to backupQualities
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

    suspend fun loadClipUrls(networkLibrary: String?, gqlHeaders: Map<String, String>, clipId: String?, enableIntegrity: Boolean): Map<Pair<String, String?>, String>? = withContext(Dispatchers.IO) {
        try {
            val response = graphQLRepository.loadClipUrls(networkLibrary, gqlHeaders, clipId)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            val accessToken = response.data?.clip?.playbackAccessToken
            response.data!!.clip.assets.let { assets ->
                (assets.find { it.portraitMetadata?.portraitClipLayout.isNullOrBlank() } ?: assets.firstOrNull())?.videoQualities?.mapIndexedNotNull { index, quality ->
                    if (quality.sourceURL.isNotBlank()) {
                        val name = if (!quality.quality.isNullOrBlank()) {
                            val frameRate = quality.frameRate?.roundToInt() ?: 0
                            if (frameRate < 60) {
                                "${quality.quality}p"
                            } else {
                                "${quality.quality}p${frameRate}"
                            }
                        } else {
                            index.toString()
                        }
                        val url = "${quality.sourceURL}?sig=${Uri.encode(accessToken?.signature)}&token=${Uri.encode(accessToken?.value)}"
                        Pair(name, quality.codecs) to url
                    } else null
                }?.toMap()
            }
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            val response = graphQLRepository.loadQueryClipUrls(networkLibrary, gqlHeaders, clipId!!)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            val accessToken = response.data?.clip?.playbackAccessToken
            response.data?.clip?.assets?.let { assets ->
                (assets.find { it?.portraitMetadata?.portraitClipLayout.isNullOrBlank() } ?: assets.firstOrNull())?.videoQualities?.mapIndexedNotNull { index, quality ->
                    if (!quality?.sourceURL.isNullOrBlank()) {
                        val name = if (!quality.quality.isNullOrBlank()) {
                            val frameRate = quality.frameRate?.roundToInt() ?: 0
                            if (frameRate < 60) {
                                "${quality.quality}p"
                            } else {
                                "${quality.quality}p${frameRate}"
                            }
                        } else {
                            index.toString()
                        }
                        val url = "${quality.sourceURL}?sig=${Uri.encode(accessToken?.signature)}&token=${Uri.encode(accessToken?.value)}"
                        Pair(name, quality.codecs) to url
                    } else null
                }?.toMap()
            }
        }
    }

    suspend fun sendMinuteWatched(networkLibrary: String?, userId: String?, streamId: String?, channelId: String?, channelLogin: String?) = withContext(Dispatchers.IO) {
        val pageResponse = channelLogin?.let {
            when {
                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                    val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                        httpEngine.get().newUrlRequestBuilder("https://www.twitch.tv/${channelLogin}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                    }
                    String(response.second)
                }
                networkLibrary == "Cronet" && cronetEngine != null -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                        cronetEngine.get().newUrlRequestBuilder("https://www.twitch.tv/${channelLogin}", request.callback, cronetExecutor).build().start()
                        request.future.get().responseBody as String
                    } else {
                        val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                            cronetEngine.get().newUrlRequestBuilder("https://www.twitch.tv/${channelLogin}", getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                        }
                        String(response.second)
                    }
                }
                else -> {
                    okHttpClient.newCall(Request.Builder().url("https://www.twitch.tv/${channelLogin}").build()).execute().use { response ->
                        response.body.string()
                    }
                }
            }
        }
        if (!pageResponse.isNullOrBlank()) {
            val settingsRegex = Regex("(https://assets.twitch.tv/config/settings.*?.js|https://static.twitchcdn.net/config/settings.*?js)")
            val settingsUrl = settingsRegex.find(pageResponse)?.value
            val settingsResponse = settingsUrl?.let {
                when {
                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                            httpEngine.get().newUrlRequestBuilder(settingsUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                        }
                        String(response.second)
                    }
                    networkLibrary == "Cronet" && cronetEngine != null -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                            cronetEngine.get().newUrlRequestBuilder(settingsUrl, request.callback, cronetExecutor).build().start()
                            request.future.get().responseBody as String
                        } else {
                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                cronetEngine.get().newUrlRequestBuilder(settingsUrl, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                            }
                            String(response.second)
                        }
                    }
                    else -> {
                        okHttpClient.newCall(Request.Builder().url(settingsUrl).build()).execute().use { response ->
                            response.body.string()
                        }
                    }
                }
            }
            if (!settingsResponse.isNullOrBlank()) {
                val spadeRegex = Regex("\"spade_url\":\"(.*?)\"")
                val spadeUrl = spadeRegex.find(settingsResponse)?.groups?.get(1)?.value
                if (!spadeUrl.isNullOrBlank()) {
                    val body = buildJsonObject {
                        put("event", "minute-watched")
                        putJsonObject("properties") {
                            put("channel_id", channelId)
                            put("broadcast_id", streamId)
                            put("player", "site")
                            put("user_id", userId?.toLong())
                        }
                    }.toString()
                    val spadeRequest = "data=" + Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
                    when {
                        networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                            suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                httpEngine.get().newUrlRequestBuilder(spadeUrl, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                                    addHeader("Content-Type", "application/x-www-form-urlencoded")
                                    setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(spadeRequest.toByteArray()), cronetExecutor)
                                }.build().start()
                            }
                        }
                        networkLibrary == "Cronet" && cronetEngine != null -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                                cronetEngine.get().newUrlRequestBuilder(spadeUrl, request.callback, cronetExecutor).apply {
                                    addHeader("Content-Type", "application/x-www-form-urlencoded")
                                    setUploadDataProvider(UploadDataProviders.create(spadeRequest.toByteArray()), cronetExecutor)
                                }.build().start()
                                request.future.get().responseBody as String
                            } else {
                                suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                    cronetEngine.get().newUrlRequestBuilder(spadeUrl, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                                        setUploadDataProvider(UploadDataProviders.create(spadeRequest.toByteArray()), cronetExecutor)
                                    }.build().start()
                                }
                            }
                        }
                        else -> {
                            okHttpClient.newCall(Request.Builder().apply {
                                url(spadeUrl)
                                header("Content-Type", "application/x-www-form-urlencoded")
                                post(spadeRequest.toRequestBody())
                            }.build()).execute()
                        }
                    }
                }
            }
        }
    }

    suspend fun loadRecentMessages(networkLibrary: String?, channelLogin: String, limit: String): RecentMessagesResponse = withContext(Dispatchers.IO) {
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://recent-messages.robotty.de/api/v2/recent-messages/${channelLogin}?limit=${limit}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<RecentMessagesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://recent-messages.robotty.de/api/v2/recent-messages/${channelLogin}?limit=${limit}", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<RecentMessagesResponse>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://recent-messages.robotty.de/api/v2/recent-messages/${channelLogin}?limit=${limit}", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<RecentMessagesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://recent-messages.robotty.de/api/v2/recent-messages/${channelLogin}?limit=${limit}")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<RecentMessagesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun loadGlobalStvEmotes(networkLibrary: String?, useWebp: Boolean): List<Emote> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://7tv.io/v3/emote-sets/global", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<StvGlobalResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/emote-sets/global", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<StvGlobalResponse>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/emote-sets/global", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<StvGlobalResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://7tv.io/v3/emote-sets/global")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<StvGlobalResponse>(response.body.string())
                }
            }
        }
        parseStvEmotes(response.emotes, useWebp)
    }

    suspend fun loadStvEmotes(networkLibrary: String?, channelId: String, useWebp: Boolean): Pair<String?, List<Emote>> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${channelId}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<StvChannelResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${channelId}", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<StvChannelResponse>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${channelId}", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<StvChannelResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://7tv.io/v3/users/twitch/${channelId}")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<StvChannelResponse>(response.body.string())
                }
            }
        }
        val set = response.emoteSet
        Pair(set.id, parseStvEmotes(set.emotes, useWebp))
    }

    private fun parseStvEmotes(response: List<StvResponse>, useWebp: Boolean): List<Emote> {
        return response.mapNotNull { emote ->
            emote.name?.takeIf { it.isNotBlank() }?.let { name ->
                emote.data?.let { data ->
                    data.host?.let { host ->
                        host.url?.takeIf { it.isNotBlank() }?.let { template ->
                            val urls = host.files?.mapNotNull { file ->
                                file.name?.takeIf { it.isNotBlank() &&
                                        if (useWebp) {
                                            file.format == "WEBP"
                                        } else {
                                            file.format == "GIF" || file.format == "PNG"
                                        }
                                }?.let { name ->
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
                                isAnimated = data.animated != false,
                                isOverlayEmote = emote.flags == 1,
                                thirdParty = true,
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun getStvUser(networkLibrary: String?, userId: String): String? = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${userId}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                String(response.second)
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${userId}", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    request.future.get().responseBody as String
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/twitch/${userId}", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    String(response.second)
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://7tv.io/v3/users/twitch/${userId}")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    response.body.string()
                }
            }
        }
        JSONObject(response).optJSONObject("user")?.optString("id")
    }

    suspend fun sendStvPresence(networkLibrary: String?, stvUserId: String, channelId: String, sessionId: String?, self: Boolean) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("kind", 1)
            put("passive", self)
            put("session_id", if (self) sessionId else "undefined")
            putJsonObject("data") {
                put("platform", "TWITCH")
                put("id", channelId)
            }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/${stvUserId}/presences", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("Content-Type", "application/json")
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/${stvUserId}/presences", request.callback, cronetExecutor).apply {
                        addHeader("Content-Type", "application/json")
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    request.future.get().responseBody as String
                } else {
                    suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://7tv.io/v3/users/${stvUserId}/presences", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("Content-Type", "application/json")
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://7tv.io/v3/users/${stvUserId}/presences")
                    header("Content-Type", "application/json")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    post(body.toRequestBody())
                }.build()).execute()
            }
        }
    }

    suspend fun loadGlobalBttvEmotes(networkLibrary: String?, useWebp: Boolean): List<Emote> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/emotes/global", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<List<BttvResponse>>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/emotes/global", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<List<BttvResponse>>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/emotes/global", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<List<BttvResponse>>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://api.betterttv.net/3/cached/emotes/global")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<List<BttvResponse>>(response.body.string())
                }
            }
        }
        parseBttvEmotes(response, useWebp)
    }

    suspend fun loadBttvEmotes(networkLibrary: String?, channelId: String, useWebp: Boolean): List<Emote> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/users/twitch/${channelId}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<Map<String, JsonElement>>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/users/twitch/${channelId}", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<Map<String, JsonElement>>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://api.betterttv.net/3/cached/users/twitch/${channelId}", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<Map<String, JsonElement>>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://api.betterttv.net/3/cached/users/twitch/${channelId}")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<Map<String, JsonElement>>(response.body.string())
                }
            }
        }
        parseBttvEmotes(
            response.entries.filter { it.key != "bots" && it.value is JsonArray }.map { entry ->
                (entry.value as JsonArray).map { json.decodeFromJsonElement<BttvResponse>(it) }
            }.flatten(),
            useWebp
        )
    }

    private fun parseBttvEmotes(response: List<BttvResponse>, useWebp: Boolean): List<Emote> {
        val list = listOf("IceCold", "SoSnowy", "SantaHat", "TopHat", "CandyCane", "ReinDeer", "cvHazmat", "cvMask")
        return response.mapNotNull { emote ->
            emote.code?.takeIf { it.isNotBlank() }?.let { name ->
                emote.id?.takeIf { it.isNotBlank() }?.let { id ->
                    Emote(
                        name = name,
                        url1x = if (useWebp) "https://cdn.betterttv.net/emote/$id/1x.webp" else "https://cdn.betterttv.net/emote/$id/1x",
                        url2x = if (useWebp) "https://cdn.betterttv.net/emote/$id/2x.webp" else "https://cdn.betterttv.net/emote/$id/2x",
                        url3x = if (useWebp) "https://cdn.betterttv.net/emote/$id/2x.webp" else "https://cdn.betterttv.net/emote/$id/2x",
                        url4x = if (useWebp) "https://cdn.betterttv.net/emote/$id/3x.webp" else "https://cdn.betterttv.net/emote/$id/3x",
                        format = if (useWebp) "webp" else null,
                        isAnimated = emote.animated != false,
                        isOverlayEmote = list.contains(name),
                        thirdParty = true,
                    )
                }
            }
        }
    }

    suspend fun loadGlobalFfzEmotes(networkLibrary: String?, useWebp: Boolean): List<Emote> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/set/global", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<FfzGlobalResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/set/global", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<FfzGlobalResponse>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/set/global", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<FfzGlobalResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://api.frankerfacez.com/v1/set/global")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<FfzGlobalResponse>(response.body.string())
                }
            }
        }
        response.sets.entries.filter { it.key.toIntOrNull()?.let { set -> response.globalSets.contains(set) } == true }.flatMap {
            it.value.emoticons?.let { emotes -> parseFfzEmotes(emotes, useWebp) } ?: emptyList()
        }
    }

    suspend fun loadFfzEmotes(networkLibrary: String?, channelId: String, useWebp: Boolean): List<Emote> = withContext(Dispatchers.IO) {
        val response = when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/room/id/${channelId}", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                }
                json.decodeFromString<FfzChannelResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/room/id/${channelId}", request.callback, cronetExecutor).apply {
                        addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<FfzChannelResponse>(response)
                } else {
                    val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://api.frankerfacez.com/v1/room/id/${channelId}", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build().start()
                    }
                    json.decodeFromString<FfzChannelResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://api.frankerfacez.com/v1/room/id/${channelId}")
                    header("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                }.build()).execute().use { response ->
                    json.decodeFromString<FfzChannelResponse>(response.body.string())
                }
            }
        }
        response.sets.entries.flatMap {
            it.value.emoticons?.let { emotes -> parseFfzEmotes(emotes, useWebp) } ?: emptyList()
        }
    }

    private fun parseFfzEmotes(response: List<FfzResponse.Emote>, useWebp: Boolean): List<Emote> {
        return response.mapNotNull { emote ->
            emote.name?.takeIf { it.isNotBlank() }?.let { name ->
                val isAnimated = emote.animated != null
                if (isAnimated) {
                    if (useWebp) {
                        emote.animated
                    } else {
                        FfzResponse.Urls(
                            url1x = emote.animated.url1x + ".gif",
                            url2x = emote.animated.url2x + ".gif",
                            url4x = emote.animated.url4x + ".gif",
                        )
                    }
                } else {
                    emote.urls
                }?.let { urls ->
                    Emote(
                        name = name,
                        url1x = urls.url1x,
                        url2x = urls.url2x,
                        url3x = urls.url2x,
                        url4x = urls.url4x,
                        format = if (isAnimated && useWebp) "webp" else null,
                        isAnimated = isAnimated,
                        thirdParty = true,
                    )
                }
            }
        }
    }

    suspend fun loadGlobalBadges(networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, emoteQuality: String, enableIntegrity: Boolean): List<TwitchBadge> = withContext(Dispatchers.IO) {
        try {
            val response = graphQLRepository.loadQueryBadges(networkLibrary, gqlHeaders,
                when (emoteQuality) {
                    "4" -> BadgeImageSize.QUADRUPLE
                    "3" -> BadgeImageSize.QUADRUPLE
                    "2" -> BadgeImageSize.DOUBLE
                    else -> BadgeImageSize.NORMAL
                }
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.badges?.mapNotNull {
                it?.setID?.let { setId ->
                    it.version?.let { version ->
                        it.imageURL?.let { url ->
                            TwitchBadge(
                                setId = setId,
                                version = version,
                                url1x = url,
                                url2x = url,
                                url3x = url,
                                url4x = url,
                                title = it.title
                            )
                        }
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            try {
                val response = graphQLRepository.loadChatBadges(networkLibrary, gqlHeaders, "")
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                }
                response.data!!.badges?.mapNotNull {
                    it.setID?.let { setId ->
                        it.version?.let { version ->
                            TwitchBadge(
                                setId = setId,
                                version = version,
                                url1x = it.image1x,
                                url2x = it.image2x,
                                url3x = it.image4x,
                                url4x = it.image4x,
                                title = it.title,
                            )
                        }
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                if (e.message == "failed integrity check") throw e
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                helixRepository.getGlobalBadges(networkLibrary, helixHeaders).data.mapNotNull { set ->
                    set.setId?.let { setId ->
                        set.versions?.mapNotNull {
                            it.id?.let { version ->
                                TwitchBadge(
                                    setId = setId,
                                    version = version,
                                    url1x = it.url1x,
                                    url2x = it.url2x,
                                    url3x = it.url4x,
                                    url4x = it.url4x
                                )
                            }
                        }
                    }
                }.flatten()
            }
        }
    }

    suspend fun loadChannelBadges(networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, channelId: String?, channelLogin: String?, emoteQuality: String, enableIntegrity: Boolean): List<TwitchBadge> = withContext(Dispatchers.IO) {
        try {
            val response = graphQLRepository.loadQueryUserBadges(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() },
                when (emoteQuality) {
                    "4" -> BadgeImageSize.QUADRUPLE
                    "3" -> BadgeImageSize.QUADRUPLE
                    "2" -> BadgeImageSize.DOUBLE
                    else -> BadgeImageSize.NORMAL
                }
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.user?.broadcastBadges?.mapNotNull {
                it?.setID?.let { setId ->
                    it.version?.let { version ->
                        it.imageURL?.let { url ->
                            TwitchBadge(
                                setId = setId,
                                version = version,
                                url1x = url,
                                url2x = url,
                                url3x = url,
                                url4x = url,
                                title = it.title
                            )
                        }
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            try {
                val response = graphQLRepository.loadChatBadges(networkLibrary, gqlHeaders, channelLogin)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                }
                response.data!!.badges?.mapNotNull {
                    it.setID?.let { setId ->
                        it.version?.let { version ->
                            TwitchBadge(
                                setId = setId,
                                version = version,
                                url1x = it.image1x,
                                url2x = it.image2x,
                                url3x = it.image4x,
                                url4x = it.image4x,
                                title = it.title,
                            )
                        }
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                if (e.message == "failed integrity check") throw e
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                helixRepository.getChannelBadges(networkLibrary, helixHeaders, channelId).data.mapNotNull { set ->
                    set.setId?.let { setId ->
                        set.versions?.mapNotNull {
                            it.id?.let { version ->
                                TwitchBadge(
                                    setId = setId,
                                    version = version,
                                    url1x = it.url1x,
                                    url2x = it.url2x,
                                    url3x = it.url4x,
                                    url4x = it.url4x
                                )
                            }
                        }
                    }
                }.flatten()
            }
        }
    }

    suspend fun loadCheerEmotes(networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, channelId: String?, channelLogin: String?, animateGifs: Boolean, enableIntegrity: Boolean): List<CheerEmote> = withContext(Dispatchers.IO) {
        try {
            val emotes = mutableListOf<CheerEmote>()
            val response = graphQLRepository.loadQueryUserCheerEmotes(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
            }
            response.data!!.cheerConfig?.displayConfig?.let { config ->
                val background = config.backgrounds?.find { it == "dark" } ?: config.backgrounds?.lastOrNull() ?: ""
                val format = if (animateGifs) {
                    config.types?.find { it.animation == "animated" } ?: config.types?.find { it.animation == "static" }
                } else {
                    config.types?.find { it.animation == "static" }
                } ?: config.types?.lastOrNull()
                val scale1x = config.scales?.find { it.startsWith("1") } ?: config.scales?.lastOrNull() ?: ""
                val scale2x = config.scales?.find { it.startsWith("2") } ?: scale1x
                val scale3x = config.scales?.find { it.startsWith("3") } ?: scale2x
                val scale4x = config.scales?.find { it.startsWith("4") } ?: scale3x
                response.data!!.cheerConfig?.groups?.mapNotNull { group ->
                    group.nodes?.mapNotNull { emote ->
                        emote.tiers?.mapNotNull { tier ->
                            config.colors?.find { it.bits == tier?.bits }?.let { item ->
                                val url = group.templateURL!!
                                    .replaceFirst("PREFIX", emote.prefix!!.lowercase())
                                    .replaceFirst("TIER", item.bits!!.toString())
                                    .replaceFirst("BACKGROUND", background)
                                    .replaceFirst("ANIMATION", format?.animation ?: "")
                                    .replaceFirst("EXTENSION", format?.extension ?: "")
                                CheerEmote(
                                    name = emote.prefix,
                                    url1x = url.replaceFirst("SCALE", scale1x),
                                    url2x = url.replaceFirst("SCALE", scale2x),
                                    url3x = url.replaceFirst("SCALE", scale3x),
                                    url4x = url.replaceFirst("SCALE", scale4x),
                                    format = if (format?.animation == "animated") "gif" else null,
                                    isAnimated = format?.animation == "animated",
                                    minBits = item.bits,
                                    color = item.color
                                )
                            }
                        }
                    }?.flatten()
                }?.flatten()?.let { emotes.addAll(it) }
                response.data!!.user?.cheer?.cheerGroups?.mapNotNull { group ->
                    group.nodes?.mapNotNull { emote ->
                        emote.tiers?.mapNotNull { tier ->
                            config.colors?.find { it.bits == tier?.bits }?.let { item ->
                                val url = group.templateURL!!
                                    .replaceFirst("PREFIX", emote.prefix!!.lowercase())
                                    .replaceFirst("TIER", item.bits!!.toString())
                                    .replaceFirst("BACKGROUND", background)
                                    .replaceFirst("ANIMATION", format?.animation ?: "")
                                    .replaceFirst("EXTENSION", format?.extension ?: "")
                                CheerEmote(
                                    name = emote.prefix,
                                    url1x = url.replaceFirst("SCALE", scale1x),
                                    url2x = url.replaceFirst("SCALE", scale2x),
                                    url3x = url.replaceFirst("SCALE", scale3x),
                                    url4x = url.replaceFirst("SCALE", scale4x),
                                    format = if (format?.animation == "animated") "gif" else null,
                                    isAnimated = format?.animation == "animated",
                                    minBits = item.bits,
                                    color = item.color
                                )
                            }
                        }
                    }?.flatten()
                }?.flatten()?.let { emotes.addAll(it) }
            }
            emotes
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            try {
                val emotes = mutableListOf<CheerEmote>()
                val response = graphQLRepository.loadGlobalCheerEmotes(networkLibrary, gqlHeaders)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                }
                response.data!!.cheerConfig.displayConfig.let { config ->
                    val background = config.backgrounds?.find { it == "dark" } ?: config.backgrounds?.lastOrNull() ?: ""
                    val format = if (animateGifs) {
                        config.types?.find { it.animation == "animated" } ?: config.types?.find { it.animation == "static" }
                    } else {
                        config.types?.find { it.animation == "static" }
                    } ?: config.types?.lastOrNull()
                    val scale1x = config.scales?.find { it.startsWith("1") } ?: config.scales?.lastOrNull() ?: ""
                    val scale2x = config.scales?.find { it.startsWith("2") } ?: scale1x
                    val scale3x = config.scales?.find { it.startsWith("3") } ?: scale2x
                    val scale4x = config.scales?.find { it.startsWith("4") } ?: scale3x
                    response.data.cheerConfig.groups.map { group ->
                        group.nodes.map { emote ->
                            emote.tiers.mapNotNull { tier ->
                                config.colors.find { it.bits == tier.bits }?.let { item ->
                                    val url = group.templateURL
                                        .replaceFirst("PREFIX", emote.prefix.lowercase())
                                        .replaceFirst("TIER", item.bits.toString())
                                        .replaceFirst("BACKGROUND", background)
                                        .replaceFirst("ANIMATION", format?.animation ?: "")
                                        .replaceFirst("EXTENSION", format?.extension ?: "")
                                    CheerEmote(
                                        name = emote.prefix,
                                        url1x = url.replaceFirst("SCALE", scale1x),
                                        url2x = url.replaceFirst("SCALE", scale2x),
                                        url3x = url.replaceFirst("SCALE", scale3x),
                                        url4x = url.replaceFirst("SCALE", scale4x),
                                        format = if (format?.animation == "animated") "gif" else null,
                                        isAnimated = format?.animation == "animated",
                                        minBits = item.bits,
                                        color = item.color,
                                    )
                                }
                            }
                        }.flatten()
                    }.flatten().let { emotes.addAll(it) }
                    graphQLRepository.loadChannelCheerEmotes(networkLibrary, gqlHeaders, channelLogin).data?.channel?.cheer?.cheerGroups?.map { group ->
                        group.nodes.map { emote ->
                            emote.tiers.mapNotNull { tier ->
                                config.colors.find { it.bits == tier.bits }?.let { item ->
                                    val url = group.templateURL
                                        .replaceFirst("PREFIX", emote.prefix.lowercase())
                                        .replaceFirst("TIER", item.bits.toString())
                                        .replaceFirst("BACKGROUND", background)
                                        .replaceFirst("ANIMATION", format?.animation ?: "")
                                        .replaceFirst("EXTENSION", format?.extension ?: "")
                                    CheerEmote(
                                        name = emote.prefix,
                                        url1x = url.replaceFirst("SCALE", scale1x),
                                        url2x = url.replaceFirst("SCALE", scale2x),
                                        url3x = url.replaceFirst("SCALE", scale3x),
                                        url4x = url.replaceFirst("SCALE", scale4x),
                                        format = if (format?.animation == "animated") "gif" else null,
                                        isAnimated = format?.animation == "animated",
                                        minBits = item.bits,
                                        color = item.color,
                                    )
                                }
                            }
                        }.flatten()
                    }?.flatten()?.let { emotes.addAll(it) }
                }
                emotes
            } catch (e: Exception) {
                if (e.message == "failed integrity check") throw e
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                helixRepository.getCheerEmotes(networkLibrary, helixHeaders, channelId).data.map { set ->
                    set.tiers.mapNotNull { tier ->
                        tier.images.let { it.dark ?: it.light }?.let { formats ->
                            if (animateGifs) {
                                formats.animated ?: formats.static
                            } else {
                                formats.static
                            }?.let { urls ->
                                CheerEmote(
                                    name = set.prefix,
                                    url1x = urls.url1x,
                                    url2x = urls.url2x,
                                    url3x = urls.url3x,
                                    url4x = urls.url4x,
                                    format = if (urls == formats.animated) "gif" else null,
                                    isAnimated = urls == formats.animated,
                                    minBits = tier.minBits,
                                    color = tier.color
                                )
                            }
                        }
                    }
                }.flatten()
            }
        }
    }

    suspend fun loadUserEmotes(networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, channelId: String?, userId: String?, animateGifs: Boolean, enableIntegrity: Boolean): List<TwitchEmote> = withContext(Dispatchers.IO) {
        try {
            if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
            val emotes = mutableListOf<TwitchEmote>()
            var offset: String? = null
            do {
                val response = graphQLRepository.loadUserEmotes(networkLibrary, gqlHeaders, channelId, offset)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                }
                val sets = response.data!!.channel.self.availableEmoteSetsPaginated
                val items = sets.edges
                items.map { item ->
                    item.node.let { set ->
                        set.emotes.mapNotNull { emote ->
                            emote.token?.let { token ->
                                TwitchEmote(
                                    id = emote.id,
                                    name = if (emote.type == "SMILIES") {
                                        token.replace("\\", "").replace("?", "")
                                            .replace("&lt;", "<").replace("&gt;", ">")
                                            .replace(Regex("\\((.)\\|.\\)")) { it.groups[1]?.value ?: "" }
                                            .replace(Regex("\\[(.).*?]")) { it.groups[1]?.value ?: "" }
                                    } else token,
                                    setId = emote.setID,
                                    ownerId = set.owner?.id
                                )
                            }
                        }
                    }
                }.flatten().let { emotes.addAll(it) }
                offset = items.lastOrNull()?.cursor
            } while (!items.lastOrNull()?.cursor.isNullOrBlank() && sets.pageInfo?.hasNextPage == true)
            emotes
        } catch (e: Exception) {
            if (e.message == "failed integrity check") throw e
            try {
                if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                val response = graphQLRepository.loadQueryUserEmotes(networkLibrary, gqlHeaders)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                }
                response.data!!.user?.emoteSets?.mapNotNull { set ->
                    set.emotes?.mapNotNull { emote ->
                        if (emote?.token != null && (!emote.type?.toString().equals("follower", true) || (emote.owner?.id == null || emote.owner.id == channelId))) {
                            TwitchEmote(
                                id = emote.id,
                                name = if (emote.type == EmoteType.SMILIES) {
                                    emote.token.replace("\\", "").replace("?", "")
                                        .replace("&lt;", "<").replace("&gt;", ">")
                                        .replace(Regex("\\((.)\\|.\\)")) { it.groups[1]?.value ?: "" }
                                        .replace(Regex("\\[(.).*?]")) { it.groups[1]?.value ?: "" }
                                } else emote.token,
                                setId = emote.setID,
                                ownerId = emote.owner?.id
                            )
                        } else null
                    }
                }?.flatten() ?: emptyList()
            } catch (e: Exception) {
                if (e.message == "failed integrity check") throw e
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                val emotes = mutableListOf<TwitchEmote>()
                var offset: String? = null
                do {
                    val response = helixRepository.getUserEmotes(networkLibrary, helixHeaders, userId, channelId, offset)
                    response.data.mapNotNull { emote ->
                        emote.name?.let { name ->
                            emote.id?.let { id ->
                                val format = if (animateGifs) {
                                    emote.format?.find { it == "animated" } ?: emote.format?.find { it == "static" }
                                } else {
                                    emote.format?.find { it == "static" }
                                } ?: emote.format?.firstOrNull() ?: ""
                                val theme = emote.theme?.find { it == "dark" } ?: emote.theme?.lastOrNull() ?: ""
                                val scale1x = emote.scale?.find { it.startsWith("1") } ?: emote.scale?.lastOrNull() ?: ""
                                val scale2x = emote.scale?.find { it.startsWith("2") } ?: scale1x
                                val scale3x = emote.scale?.find { it.startsWith("3") } ?: scale2x
                                val url = response.template
                                    .replaceFirst("{{id}}", id)
                                    .replaceFirst("{{format}}", format)
                                    .replaceFirst("{{theme_mode}}", theme)
                                TwitchEmote(
                                    name = if (emote.type == "smilies") {
                                        name.replace("\\", "").replace("?", "")
                                            .replace("&lt;", "<").replace("&gt;", ">")
                                            .replace(Regex("\\((.)\\|.\\)")) { it.groups[1]?.value ?: "" }
                                            .replace(Regex("\\[(.).*?]")) { it.groups[1]?.value ?: "" }
                                    } else name,
                                    url1x = url.replaceFirst("{{scale}}", scale1x),
                                    url2x = url.replaceFirst("{{scale}}", scale2x),
                                    url3x = url.replaceFirst("{{scale}}", scale3x),
                                    url4x = url.replaceFirst("{{scale}}", scale3x),
                                    format = if (format == "animated") "gif" else null,
                                    setId = emote.setId,
                                    ownerId = emote.ownerId
                                )
                            }
                        }
                    }.let { emotes.addAll(it) }
                    offset = response.pagination?.cursor
                } while (!response.pagination?.cursor.isNullOrBlank())
                emotes
            }
        }
    }

    suspend fun loadEmotesFromSet(networkLibrary: String?, helixHeaders: Map<String, String>, setIds: List<String>, animateGifs: Boolean): List<TwitchEmote> = withContext(Dispatchers.IO) {
        val response = helixRepository.getEmotesFromSet(networkLibrary, helixHeaders, setIds)
        response.data.mapNotNull { emote ->
            emote.name?.let { name ->
                emote.id?.let { id ->
                    val format = if (animateGifs) {
                        emote.format?.find { it == "animated" } ?: emote.format?.find { it == "static" }
                    } else {
                        emote.format?.find { it == "static" }
                    } ?: emote.format?.firstOrNull() ?: ""
                    val theme = emote.theme?.find { it == "dark" } ?: emote.theme?.lastOrNull() ?: ""
                    val scale1x = emote.scale?.find { it.startsWith("1") } ?: emote.scale?.lastOrNull() ?: ""
                    val scale2x = emote.scale?.find { it.startsWith("2") } ?: scale1x
                    val scale3x = emote.scale?.find { it.startsWith("3") } ?: scale2x
                    val url = response.template
                        .replaceFirst("{{id}}", id)
                        .replaceFirst("{{format}}", format)
                        .replaceFirst("{{theme_mode}}", theme)
                    TwitchEmote(
                        name = if (emote.type == "smilies") {
                            name.replace("\\", "").replace("?", "")
                                .replace("&lt;", "<").replace("&gt;", ">")
                                .replace(Regex("\\((.)\\|.\\)")) { it.groups[1]?.value ?: "" }
                                .replace(Regex("\\[(.).*?]")) { it.groups[1]?.value ?: "" }
                        } else name,
                        url1x = url.replaceFirst("{{scale}}", scale1x),
                        url2x = url.replaceFirst("{{scale}}", scale2x),
                        url3x = url.replaceFirst("{{scale}}", scale3x),
                        url4x = url.replaceFirst("{{scale}}", scale3x),
                        format = if (format == "animated") "gif" else null,
                        setId = emote.setId,
                        ownerId = emote.ownerId
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

    suspend fun getVideoPosition(id: Long) = withContext(Dispatchers.IO) {
        videoPositions.getById(id)
    }

    suspend fun saveVideoPosition(position: VideoPosition) = withContext(Dispatchers.IO) {
        videoPositions.insert(position)
    }

    suspend fun deleteVideoPositions() = withContext(Dispatchers.IO) {
        videoPositions.deleteAll()
    }
}