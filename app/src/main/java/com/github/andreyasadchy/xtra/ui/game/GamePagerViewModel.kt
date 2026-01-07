package com.github.andreyasadchy.xtra.ui.game

import android.net.http.HttpEngine
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.LocalFollowGame
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowGameRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

@HiltViewModel
class GamePagerViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val localFollowsGame: LocalFollowGameRepository,
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)
    private var updatedLocalGame = false

    private val _game = MutableStateFlow<Game?>(null)
    val game: StateFlow<Game?> = _game

    fun loadGame(networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_game.value == null) {
            viewModelScope.launch {
                _game.value = try {
                    val response = graphQLRepository.loadQueryGame(
                        networkLibrary = networkLibrary,
                        headers = gqlHeaders,
                        id = args.gameId,
                        slug = args.gameSlug.takeIf { args.gameId.isNullOrBlank() },
                        name = args.gameName.takeIf { args.gameId.isNullOrBlank() && args.gameSlug.isNullOrBlank() },
                    )
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            return@launch
                        }
                    }
                    response.data!!.game?.let {
                        Game(
                            gameId = it.id,
                            gameSlug = it.slug,
                            gameName = it.displayName,
                            boxArtUrl = it.boxArtURL,
                            viewersCount = it.viewersCount,
                            broadcastersCount = it.broadcastersCount,
                            followersCount = it.followersCount,
                            tags = it.tags?.map { tag ->
                                Tag(
                                    id = tag.id,
                                    name = tag.localizedName
                                )
                            }
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getGames(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = args.gameId?.let { listOf(it) },
                                names = if (args.gameId.isNullOrBlank()) args.gameName?.let { listOf(it) } else null
                            ).data.firstOrNull()?.let {
                                Game(
                                    gameId = it.id,
                                    gameName = it.name,
                                    boxArtUrl = it.boxArtUrl
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
            }
        }
    }

    fun isFollowingGame(gameId: String?, gameName: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (_isFollowing.value == null) {
            viewModelScope.launch {
                try {
                    val isFollowing = if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        gameName?.let {
                            graphQLRepository.loadFollowingGame(networkLibrary, gqlHeaders, gameName).data?.game?.self?.follow != null
                        } == true
                    } else {
                        gameId?.let {
                            localFollowsGame.getFollowByGameId(it)
                        } != null
                    }
                    _isFollowing.value = isFollowing
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowGame(gameId: String?, gameSlug: String?, gameName: String?, setting: Int, filesDir: String, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val errorMessage = graphQLRepository.loadFollowGame(networkLibrary, gqlHeaders, gameId).also { response ->
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "follow"
                                return@launch
                            }
                        }
                    }.errors?.firstOrNull()?.message
                    if (!errorMessage.isNullOrBlank()) {
                        follow.value = Pair(true, errorMessage)
                    } else {
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                    }
                } else {
                    if (!gameId.isNullOrBlank()) {
                        File(filesDir, "box_art").mkdir()
                        val path = filesDir + File.separator + "box_art" + File.separator + gameId
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                try {
                                    graphQLRepository.loadQueryGameBoxArt(networkLibrary, gqlHeaders, gameId).data!!.game?.boxArtURL
                                } catch (e: Exception) {
                                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                                        helixRepository.getGames(
                                            networkLibrary = networkLibrary,
                                            headers = helixHeaders,
                                            ids = listOf(gameId)
                                        ).data.firstOrNull()?.boxArtUrl
                                    } else null
                                }.takeIf { !it.isNullOrBlank() }?.let { TwitchApiHelper.getTemplateUrl(it, "game") }?.let {
                                    when {
                                        networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                            val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                                httpEngine.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                            }
                                            if (response.first.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.second)
                                                }
                                            }
                                        }
                                        networkLibrary == "Cronet" && cronetEngine != null -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                                cronetEngine.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                                val response = request.future.get()
                                                if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                                    FileOutputStream(path).use {
                                                        it.write(response.responseBody as ByteArray)
                                                    }
                                                }
                                            } else {
                                                val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                    cronetEngine.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                                }
                                                if (response.first.httpStatusCode in 200..299) {
                                                    FileOutputStream(path).use {
                                                        it.write(response.second)
                                                    }
                                                }
                                            }
                                        }
                                        else -> {
                                            okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                                if (response.isSuccessful) {
                                                    FileOutputStream(path).use { outputStream ->
                                                        response.body.byteStream().use { inputStream ->
                                                            inputStream.copyTo(outputStream)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        localFollowsGame.saveFollow(LocalFollowGame(gameId, gameSlug, gameName, path))
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowGame(gameId: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        viewModelScope.launch {
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val errorMessage = graphQLRepository.loadUnfollowGame(networkLibrary, gqlHeaders, gameId).also { response ->
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "unfollow"
                                return@launch
                            }
                        }
                    }.errors?.firstOrNull()?.message
                    if (!errorMessage.isNullOrBlank()) {
                        follow.value = Pair(false, errorMessage)
                    } else {
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                    }
                } else {
                    if (gameId != null) {
                        localFollowsGame.getFollowByGameId(gameId)?.let { localFollowsGame.deleteFollow(it) }
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun updateLocalGame(filesDir: String, gameId: String?, gameName: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        if (!updatedLocalGame) {
            updatedLocalGame = true
            if (!gameId.isNullOrBlank()) {
                viewModelScope.launch {
                    File(filesDir, "box_art").mkdir()
                    val path = filesDir + File.separator + "box_art" + File.separator + gameId
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            try {
                                graphQLRepository.loadQueryGameBoxArt(networkLibrary, gqlHeaders, gameId).data!!.game?.boxArtURL
                            } catch (e: Exception) {
                                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                                    helixRepository.getGames(
                                        networkLibrary = networkLibrary,
                                        headers = helixHeaders,
                                        ids = listOf(gameId)
                                    ).data.firstOrNull()?.boxArtUrl
                                } else null
                            }.takeIf { !it.isNullOrBlank() }?.let { TwitchApiHelper.getTemplateUrl(it, "game") }?.let {
                                when {
                                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                            httpEngine.get().newUrlRequestBuilder(it, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).build().start()
                                        }
                                        if (response.first.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.second)
                                            }
                                        }
                                    }
                                    networkLibrary == "Cronet" && cronetEngine != null -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            val request = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                            cronetEngine.get().newUrlRequestBuilder(it, request.callback, cronetExecutor).build().start()
                                            val response = request.future.get()
                                            if (response.urlResponseInfo.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.responseBody as ByteArray)
                                                }
                                            }
                                        } else {
                                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                cronetEngine.get().newUrlRequestBuilder(it, getByteArrayCronetCallback(continuation), cronetExecutor).build().start()
                                            }
                                            if (response.first.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.second)
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.newCall(Request.Builder().url(it).build()).execute().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {

                        }
                    }
                    localFollowsGame.getFollowByGameId(gameId)?.let {
                        localFollowsGame.updateFollow(it.apply {
                            this.gameName = gameName
                            boxArt = path
                        })
                    }
                }
            }
        }
    }
}
