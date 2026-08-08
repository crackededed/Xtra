package com.github.andreyasadchy.xtra.ui.multiview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class MultiviewViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    suspend fun resolveLiveStream(channelLogin: String): Stream? {
        val preferences = applicationContext.prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)

        try {
            graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                logins = listOf(channelLogin),
            ).data?.users?.firstOrNull()?.let { user ->
                user.stream?.let { stream ->
                    return Stream(
                        id = stream.id,
                        channelId = user.id,
                        channelLogin = user.login,
                        channelName = user.displayName,
                        channelImageURL = user.profileImageURL,
                        gameId = stream.game?.id,
                        gameSlug = stream.game?.slug,
                        gameName = stream.game?.displayName,
                        title = stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = stream.previewImageURL,
                        createdAt = stream.createdAt?.toString(),
                        viewerCount = stream.viewersCount,
                        tags = stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            }
        } catch (_: Exception) {
            // Helix below is a useful fallback when the persisted GraphQL query is unavailable.
        }

        return runCatching {
            helixRepository.getStreams(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                logins = listOf(channelLogin),
            ).data.firstOrNull()?.let { stream ->
                Stream(
                    id = stream.id,
                    channelId = stream.channelId,
                    channelLogin = stream.channelLogin,
                    channelName = stream.channelName,
                    gameId = stream.gameId,
                    gameName = stream.gameName,
                    title = stream.title,
                    thumbnailURL = stream.thumbnailURL,
                    createdAt = stream.startedAt,
                    viewerCount = stream.viewerCount,
                    tags = stream.tags,
                )
            }
        }.getOrNull()
    }

    suspend fun loadStreamPlaylist(channelLogin: String): String {
        val preferences = applicationContext.prefs()
        return playerRepository.loadStreamPlaylistUrl(
            context = applicationContext,
            networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                applicationContext,
                preferences.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
            ),
            channelLogin = channelLogin,
            randomDeviceId = preferences.getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
            xDeviceId = preferences.getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
            playerType = preferences.getString(C.TOKEN_PLAYER_TYPE, "site"),
            supportedCodecs = preferences.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
            proxyPlaybackAccessToken = preferences.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
            proxyHost = preferences.getString(C.PROXY_HOST, null),
            proxyPort = preferences.getString(C.PROXY_PORT, null)?.toIntOrNull(),
            proxyUser = preferences.getString(C.PROXY_USER, null),
            proxyPassword = preferences.getString(C.PROXY_PASSWORD, null),
            enableIntegrity = preferences.getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    companion object {
        val MultiviewViewModelFactory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as XtraApp
                val xtraModule = application.xtraModule
                MultiviewViewModel(
                    application.applicationContext,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.playerRepository,
                )
            }
        }
    }
}
