package com.github.andreyasadchy.xtra.repository

import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelSearchResponse
import com.github.andreyasadchy.xtra.model.helix.chat.BadgesResponse
import com.github.andreyasadchy.xtra.model.helix.chat.ChatUsersResponse
import com.github.andreyasadchy.xtra.model.helix.chat.CheerEmotesResponse
import com.github.andreyasadchy.xtra.model.helix.chat.EmoteSetsResponse
import com.github.andreyasadchy.xtra.model.helix.chat.UserEmotesResponse
import com.github.andreyasadchy.xtra.model.helix.clip.ClipsResponse
import com.github.andreyasadchy.xtra.model.helix.follows.FollowsResponse
import com.github.andreyasadchy.xtra.model.helix.game.GamesResponse
import com.github.andreyasadchy.xtra.model.helix.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.helix.user.UsersResponse
import com.github.andreyasadchy.xtra.model.helix.video.VideosResponse
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UploadDataProviders
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HelixRepository @Inject constructor(
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    suspend fun getGames(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, names: List<String>? = null): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            names?.forEach { appendQueryParameter("name", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<GamesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<GamesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<GamesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getTopGames(networkLibrary: String?, headers: Map<String, String>, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games/top".toUri().buildUpon().apply {
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<GamesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<GamesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<GamesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getStreams(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null, gameId: String? = null, languages: List<String>? = null, limit: Int? = null, offset: String? = null): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("user_id", it) }
            logins?.forEach { appendQueryParameter("user_login", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            languages?.forEach { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<StreamsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<StreamsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<StreamsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<StreamsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getFollowedStreams(networkLibrary: String?, headers: Map<String, String>, userId: String?, limit: Int?, offset: String?): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<StreamsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<StreamsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<StreamsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<StreamsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getClips(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, channelId: String? = null, gameId: String? = null, startedAt: String? = null, endedAt: String? = null, limit: Int? = null, offset: String? = null): ClipsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/clips".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            startedAt?.let { appendQueryParameter("started_at", it) }
            endedAt?.let { appendQueryParameter("ended_at", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<ClipsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<ClipsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<ClipsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<ClipsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getVideos(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, gameId: String? = null, channelId: String? = null, period: String? = null, broadcastType: String? = null, sort: String? = null, language: String? = null, limit: Int? = null, offset: String? = null): VideosResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/videos".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            channelId?.let { appendQueryParameter("user_id", it) }
            period?.let { appendQueryParameter("period", it) }
            broadcastType?.let { appendQueryParameter("type", it) }
            sort?.let { appendQueryParameter("sort", it) }
            language?.let { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<VideosResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<VideosResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<VideosResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<VideosResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUsers(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): UsersResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/users".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            logins?.forEach { appendQueryParameter("login", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<UsersResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<UsersResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<UsersResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<UsersResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchGames(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/categories".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<GamesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<GamesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<GamesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?, live: Boolean? = null): ChannelSearchResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/channels".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
            live?.let { appendQueryParameter("live_only", it.toString()) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<ChannelSearchResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<ChannelSearchResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<ChannelSearchResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<ChannelSearchResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserFollows(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String? = null, limit: Int? = null, offset: String? = null): FollowsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            targetId?.let { appendQueryParameter("broadcaster_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<FollowsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<FollowsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<FollowsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<FollowsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserFollowers(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String? = null, limit: Int? = null, offset: String? = null): FollowsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/followers".toUri().buildUpon().apply {
            targetId?.let { appendQueryParameter("user_id", it) }
            userId?.let { appendQueryParameter("broadcaster_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<FollowsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<FollowsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<FollowsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<FollowsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserEmotes(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, offset: String?): UserEmotesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/emotes/user".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<UserEmotesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<UserEmotesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<UserEmotesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<UserEmotesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getEmotesFromSet(networkLibrary: String?, headers: Map<String, String>, setIds: List<String>): EmoteSetsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/emotes/set".toUri().buildUpon().apply {
            setIds.forEach { appendQueryParameter("emote_set_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<EmoteSetsResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<EmoteSetsResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<EmoteSetsResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<EmoteSetsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getGlobalBadges(networkLibrary: String?, headers: Map<String, String>): BadgesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/badges/global"
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<BadgesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<BadgesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<BadgesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<BadgesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getChannelBadges(networkLibrary: String?, headers: Map<String, String>, userId: String?): BadgesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/badges".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<BadgesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<BadgesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<BadgesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<BadgesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getCheerEmotes(networkLibrary: String?, headers: Map<String, String>, userId: String?): CheerEmotesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/bits/cheermotes".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<CheerEmotesResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<CheerEmotesResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<CheerEmotesResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<CheerEmotesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getChatters(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, limit: Int? = null, offset: String? = null): ChatUsersResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/chatters".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                json.decodeFromString<ChatUsersResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<ChatUsersResponse>(response)
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    json.decodeFromString<ChatUsersResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    json.decodeFromString<ChatUsersResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun createEventSubSubscription(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, type: String?, sessionId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/eventsub/subscriptions"
        val body = buildJsonObject {
            put("type", type)
            put("version", "1")
            putJsonObject("condition") {
                put("broadcaster_user_id", channelId)
                put("user_id", userId)
            }
            putJsonObject("transport") {
                put("method", "websocket")
                put("session_id", sessionId)
            }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun sendMessage(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, message: String?, replyId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/messages"
        val body = buildJsonObject {
            put("broadcaster_id", channelId)
            put("sender_id", userId)
            put("message", message)
            replyId?.let { put("reply_parent_message_id", it) }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun sendAnnouncement(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, message: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/announcements".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            put("message", message)
            color?.let { put("color", it) }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun banUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, targetId: String?, duration: String? = null, reason: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/bans".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                duration?.toIntOrNull()?.let { put("duration", it) }
                put("reason", reason)
                put("user_id", targetId)
            }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun unbanUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/bans".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("DELETE")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun deleteMessages(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, messageId: String? = null): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/chat".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            messageId?.let { appendQueryParameter("message_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("DELETE")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun getChatColor(networkLibrary: String?, headers: Map<String, String>, userId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/color".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        json.decodeFromString<JsonElement>(response.responseBody as String).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        json.decodeFromString<JsonElement>(response.body.string()).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun updateChatColor(networkLibrary: String?, headers: Map<String, String>, userId: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/color".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            color?.let { appendQueryParameter("color", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("PUT")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("PUT")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("PUT")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("PUT", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun startCommercial(networkLibrary: String?, headers: Map<String, String>, channelId: String?, length: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/commercial"
        val body = buildJsonObject {
            put("broadcaster_id", channelId)
            put("length", length?.toIntOrNull())
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        json.decodeFromString<JsonElement>(response.responseBody as String).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        json.decodeFromString<JsonElement>(response.body.string()).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun updateChatSettings(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, emote: Boolean? = null, followers: Boolean? = null, followersDuration: Int? = null, slow: Boolean? = null, slowDuration: Int? = null, subs: Boolean? = null, unique: Boolean? = null): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/settings".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            emote?.let { put("emote_mode", it) }
            followers?.let { put("follower_mode", it) }
            followersDuration?.let { put("follower_mode_duration", it) }
            slow?.let { put("slow_mode", it) }
            slowDuration?.let { put("slow_mode_wait_time", it) }
            subs?.let { put("subscriber_mode", it) }
            unique?.let { put("unique_chat_mode", it) }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                        setHttpMethod("PATCH")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        setHttpMethod("PATCH")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                            setHttpMethod("PATCH")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    method("PATCH", body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun createStreamMarker(networkLibrary: String?, headers: Map<String, String>, channelId: String?, description: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams/markers"
        val body = buildJsonObject {
            put("user_id", channelId)
            description?.let { put("description", it) }
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun addModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/moderators".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun removeModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/moderators".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("DELETE")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun startRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/raids".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("from_broadcaster_id", it) }
            targetId?.let { appendQueryParameter("to_broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun cancelRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/raids".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("DELETE")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun addVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/vips".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun removeVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/vips".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            setHttpMethod("DELETE")
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun sendWhisper(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String?, message: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/whispers".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("from_user_id", it) }
            targetId?.let { appendQueryParameter("to_user_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            put("message", message)
        }.toString()
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder(url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                if (response.first.httpStatusCode in 200..299) {
                    null
                } else {
                    String(response.second)
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder(url, request.callback, cronetExecutor).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode in 200..299) {
                        null
                    } else {
                        response.responseBody as String
                    }
                } else {
                    val response = suspendCancellableCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder(url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            headers.forEach { addHeader(it.key, it.value) }
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode in 200..299) {
                        null
                    } else {
                        String(response.second)
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }
}