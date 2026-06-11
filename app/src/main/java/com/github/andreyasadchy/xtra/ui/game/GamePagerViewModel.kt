package com.github.andreyasadchy.xtra.ui.game

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.LocalGameFollow
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalGameFollowsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

class GamePagerViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val localGameFollowsRepository: LocalGameFollowsRepository,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

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
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            return@launch
                        }
                    }
                    response.data!!.game?.let {
                        Game(
                            id = it.id,
                            slug = it.slug,
                            name = it.displayName,
                            boxArtURL = it.boxArtURL,
                            viewerCount = it.viewersCount,
                            broadcasterCount = it.broadcastersCount,
                            followerCount = it.followersCount,
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
                                    id = it.id,
                                    name = it.name,
                                    boxArtURL = it.boxArtURL
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

    fun isFollowingGame(gameId: String?, gameSlug: String?, gameName: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (_isFollowing.value == null) {
            viewModelScope.launch {
                try {
                    val isFollowing = if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        graphQLRepository.loadQueryFollowingGame(
                            networkLibrary = networkLibrary,
                            headers = gqlHeaders,
                            id = gameId,
                            slug = gameSlug.takeIf { gameId.isNullOrBlank() },
                            name = gameName.takeIf { gameId.isNullOrBlank() && gameSlug.isNullOrBlank() },
                        ).data?.game?.self?.follow?.followedAt != null
                    } else {
                        gameId?.let {
                            localGameFollowsRepository.getById(it)
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
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("follow")
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
                                        ).data.firstOrNull()?.boxArtURL
                                    } else null
                                }.takeIf { !it.isNullOrBlank() }?.let { TwitchApiHelper.getGameBoxArt(it) }?.let { url ->
                                    when {
                                        networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                            val response = suspendCancellableCoroutine { continuation ->
                                                val timeout = NetworkUtils.HttpEngineTimeout()
                                                val request = httpEngine.value!!.newUrlRequestBuilder(
                                                    url,
                                                    cronetExecutor.value,
                                                    NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                                ).build()
                                                timeout.start(request, continuation)
                                                request.start()
                                                continuation.invokeOnCancellation {
                                                    request.cancel()
                                                    timeout.stop()
                                                }
                                            }
                                            if (response.info.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.body)
                                                }
                                            }
                                        }
                                        networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                            val response = suspendCancellableCoroutine { continuation ->
                                                val timeout = NetworkUtils.CronetTimeout()
                                                val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                    url,
                                                    NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                    cronetExecutor.value
                                                ).build()
                                                timeout.start(request, continuation)
                                                request.start()
                                                continuation.invokeOnCancellation {
                                                    request.cancel()
                                                    timeout.stop()
                                                }
                                            }
                                            if (response.info.httpStatusCode in 200..299) {
                                                FileOutputStream(path).use {
                                                    it.write(response.body)
                                                }
                                            }
                                        }
                                        else -> {
                                            okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
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
                        localGameFollowsRepository.save(LocalGameFollow(gameId, gameSlug, gameName, path))
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
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("unfollow")
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
                        localGameFollowsRepository.getById(gameId)?.let { localGameFollowsRepository.delete(it) }
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
                                    ).data.firstOrNull()?.boxArtURL
                                } else null
                            }.takeIf { !it.isNullOrBlank() }?.let { TwitchApiHelper.getGameBoxArt(it) }?.let { url ->
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
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
                    localGameFollowsRepository.getById(gameId)?.let {
                        localGameFollowsRepository.update(it.apply {
                            this.gameName = gameName
                            boxArt = path
                        })
                    }
                }
            }
        }
    }

    companion object {
        val GamePagerViewModelFactory = viewModelFactory {
            initializer {
                val savedStateHandle = createSavedStateHandle()
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                GamePagerViewModel(xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.localGameFollowsRepository, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, savedStateHandle)
            }
        }
    }
}
