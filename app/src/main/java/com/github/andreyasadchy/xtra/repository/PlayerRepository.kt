package com.github.andreyasadchy.xtra.repository

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.api.MiscApi
import com.github.andreyasadchy.xtra.api.TTVLolApi
import com.github.andreyasadchy.xtra.api.UsherApi
import com.github.andreyasadchy.xtra.db.RecentEmotesDao
import com.github.andreyasadchy.xtra.db.VideoPositionsDao
import com.github.andreyasadchy.xtra.model.PlaybackAccessToken
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.chat.BttvChannelResponse
import com.github.andreyasadchy.xtra.model.chat.BttvGlobalResponse
import com.github.andreyasadchy.xtra.model.chat.FfzChannelResponse
import com.github.andreyasadchy.xtra.model.chat.FfzGlobalResponse
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.RecentMessagesResponse
import com.github.andreyasadchy.xtra.model.chat.StvChannelResponse
import com.github.andreyasadchy.xtra.model.chat.StvGlobalResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PlayerRepository @Inject constructor(
    private val usher: UsherApi,
    private val misc: MiscApi,
    private val graphQL: GraphQLRepository,
    private val recentEmotes: RecentEmotesDao,
    private val videoPositions: VideoPositionsDao,
    private val ttvLolApi: TTVLolApi) {

    suspend fun loadStreamPlaylistUrl(gqlHeaders: Map<String, String>, channelLogin: String, useProxy: Int?, proxyUrl: String?, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?): Pair<Uri, Int> = withContext(Dispatchers.IO) {
        when {
            useProxy == 0 && !proxyUrl.isNullOrBlank() -> {
                proxyUrl.replace("\$channel", channelLogin).toUri() to 0
            }
            useProxy == 1 && ttvLolApi.ping().let { it.isSuccessful && it.body()?.string() == "1" } -> {
                buildUrl(
                    "https://api.ttv.lol/playlist/$channelLogin.m3u8%3F", //manually insert "?" everywhere, some problem with encoding, too lazy for a proper solution
                    "allow_source", "true",
                    "allow_audio_only", "true",
                    "fast_bread", "true",
                    "p", Random.nextInt(9999999).toString()
                ) to 1
            }
            else -> {
                val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders, randomDeviceId, xDeviceId)
                val accessToken = graphQL.loadPlaybackAccessToken(
                    headers = accessTokenHeaders,
                    login = channelLogin,
                    playerType = playerType
                ).streamToken
                buildUrl(
                    "https://usher.ttvnw.net/api/channel/hls/$channelLogin.m3u8?",
                    "allow_source", "true",
                    "allow_audio_only", "true",
                    "fast_bread", "true", //low latency
                    "p", Random.nextInt(9999999).toString(),
                    "sig", accessToken?.signature ?: "",
                    "token", accessToken?.token ?: ""
                ) to 2
            }
        }
    }

    suspend fun loadVideoPlaylistUrl(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?): Uri = withContext(Dispatchers.IO) {
        val accessToken = loadVideoPlaybackAccessToken(gqlHeaders, videoId, playerType)
        buildUrl(
            "https://usher.ttvnw.net/vod/$videoId.m3u8?",
            "allow_source", "true",
            "allow_audio_only", "true",
            "p", Random.nextInt(9999999).toString(),
            "sig", accessToken?.signature ?: "",
            "token", accessToken?.token ?: "",
        )
    }

    suspend fun loadVideoPlaylist(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?): Response<ResponseBody> = withContext(Dispatchers.IO) {
        val accessToken = loadVideoPlaybackAccessToken(gqlHeaders, videoId, playerType)
        val playlistQueryOptions = HashMap<String, String>().apply {
            put("allow_source", "true")
            put("allow_audio_only", "true")
            put("p", Random.nextInt(9999999).toString())
            accessToken?.signature?.let { put("sig", it) }
            accessToken?.token?.let { put("token", it) }
        }
        usher.getVideoPlaylist(videoId, playlistQueryOptions)
    }

    private suspend fun loadVideoPlaybackAccessToken(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?): PlaybackAccessToken? {
        val accessTokenHeaders = getPlaybackAccessTokenHeaders(gqlHeaders = gqlHeaders, randomDeviceId = true)
        return graphQL.loadPlaybackAccessToken(
            headers = accessTokenHeaders,
            vodId = videoId,
            playerType = playerType
        ).videoToken
    }

    private fun getPlaybackAccessTokenHeaders(gqlHeaders: Map<String, String>, randomDeviceId: Boolean?, xDeviceId: String? = null): Map<String, String> {
        return if (XtraApp.INSTANCE.applicationContext.prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, false)) {
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

    private fun buildUrl(url: String, vararg queryParams: String): Uri {
        val stringBuilder = StringBuilder(url)
        stringBuilder.append(queryParams[0])
            .append("=")
            .append(queryParams[1])
        for (i in 2 until queryParams.size step 2) {
            stringBuilder.append("&")
                .append(queryParams[i])
                .append("=")
                .append(queryParams[i + 1])
        }
        return stringBuilder.toString().toUri()
    }

    suspend fun loadRecentMessages(channelLogin: String, limit: String): Response<RecentMessagesResponse> = withContext(Dispatchers.IO) {
        misc.getRecentMessages(channelLogin, limit)
    }

    suspend fun loadGlobalStvEmotes(): Response<StvGlobalResponse> = withContext(Dispatchers.IO) {
        misc.getGlobalStvEmotes()
    }

    suspend fun loadStvEmotes(channelId: String): Response<StvChannelResponse> = withContext(Dispatchers.IO) {
        misc.getStvEmotes(channelId)
    }

    suspend fun loadGlobalBttvEmotes(): Response<BttvGlobalResponse> = withContext(Dispatchers.IO) {
        misc.getGlobalBttvEmotes()
    }

    suspend fun loadBttvEmotes(channelId: String): Response<BttvChannelResponse> = withContext(Dispatchers.IO) {
        misc.getBttvEmotes(channelId)
    }

    suspend fun loadGlobalFfzEmotes(): Response<FfzGlobalResponse> = withContext(Dispatchers.IO) {
        misc.getGlobalFfzEmotes()
    }

    suspend fun loadFfzEmotes(channelId: String): Response<FfzChannelResponse> = withContext(Dispatchers.IO) {
        misc.getFfzEmotes(channelId)
    }

    fun loadRecentEmotes() = recentEmotes.getAll()

    fun insertRecentEmotes(emotes: Collection<RecentEmote>) {
        GlobalScope.launch {
            val listSize = emotes.size
            val list = if (listSize <= RecentEmote.MAX_SIZE) {
                emotes
            } else {
                emotes.toList().subList(listSize - RecentEmote.MAX_SIZE, listSize)
            }
            recentEmotes.ensureMaxSizeAndInsert(list)
        }
    }

    fun loadVideoPositions(): LiveData<Map<Long, Long>> = videoPositions.getAll().map { list ->
        list.associate { it.id to it.position }
    }

    fun saveVideoPosition(position: VideoPosition) {
        val appContext = XtraApp.INSTANCE.applicationContext
        if (appContext.prefs().getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            GlobalScope.launch {
                videoPositions.insert(position)
            }
        }
    }
}