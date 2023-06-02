package com.github.andreyasadchy.xtra.repository

import android.util.Base64
import androidx.core.util.Pair
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.BadgesQuery
import com.github.andreyasadchy.xtra.GameBoxArtQuery
import com.github.andreyasadchy.xtra.UserBadgesQuery
import com.github.andreyasadchy.xtra.UserChannelPageQuery
import com.github.andreyasadchy.xtra.UserCheerEmotesQuery
import com.github.andreyasadchy.xtra.UserEmotesQuery
import com.github.andreyasadchy.xtra.UserMessageClickedQuery
import com.github.andreyasadchy.xtra.UserQuery
import com.github.andreyasadchy.xtra.UserResultIDQuery
import com.github.andreyasadchy.xtra.UserResultLoginQuery
import com.github.andreyasadchy.xtra.UsersStreamQuery
import com.github.andreyasadchy.xtra.UsersTypeQuery
import com.github.andreyasadchy.xtra.VideoQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.api.MiscApi
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.gql.video.VideoMessagesDataResponse
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.type.BadgeImageSize
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiRepository @Inject constructor(
    private val apolloClient: ApolloClient,
    private val helix: HelixApi,
    private val gql: GraphQLRepository,
    private val misc: MiscApi) {

    private fun getApolloClient(gqlHeaders: Map<String, String>, token: String? = null): ApolloClient {
        return apolloClient.newBuilder().apply {
            gqlHeaders.entries.forEach {
                addHttpHeader(it.key, it.value)
            }
            token?.let { addHttpHeader("Authorization", TwitchApiHelper.addTokenPrefixGQL(it)) }
        }.build()
    }

    suspend fun loadGameBoxArt(gameId: String, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): String? = withContext(Dispatchers.IO) {
        try {
            getApolloClient(gqlHeaders).query(GameBoxArtQuery(Optional.Present(gameId))).execute().data?.game?.boxArtURL
        } catch (e: Exception) {
            helix.getGames(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = listOf(gameId)
            ).data.firstOrNull()?.boxArt
        }
    }

    suspend fun loadStream(channelId: String?, channelLogin: String?, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): Stream? = withContext(Dispatchers.IO) {
        try {
            val get = getApolloClient(gqlHeaders).query(UsersStreamQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(listOf(channelId)) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(listOf(channelLogin)) else Optional.Absent,
            )).execute().data?.users?.firstOrNull()
            if (get != null) {
                Stream(id = get.stream?.id, channelId = channelId, channelLogin = get.login, channelName = get.displayName, gameId = get.stream?.game?.id,
                    gameName = get.stream?.game?.displayName, type = get.stream?.type, title = get.stream?.broadcaster?.broadcastSettings?.title,
                    viewerCount = get.stream?.viewersCount, startedAt = get.stream?.createdAt?.toString(), thumbnailUrl = get.stream?.previewImageURL,
                    profileImageUrl = get.profileImageURL)
            } else null
        } catch (e: Exception) {
            try {
                helix.getStreams(
                    clientId = helixClientId,
                    token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                ).data.firstOrNull()
            } catch (e: Exception) {
                gql.loadViewerCount(gqlHeaders, channelLogin).data
            }
        }
    }

    suspend fun loadVideo(videoId: String?, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): Video? = withContext(Dispatchers.IO) {
        try {
            val get = getApolloClient(gqlHeaders).query(VideoQuery(Optional.Present(videoId))).execute().data
            if (get != null) {
                Video(id = videoId, channelId = get.video?.owner?.id, channelLogin = get.video?.owner?.login, channelName = get.video?.owner?.displayName,
                    profileImageUrl = get.video?.owner?.profileImageURL, title = get.video?.title, uploadDate = get.video?.createdAt?.toString(), thumbnailUrl = get.video?.previewThumbnailURL,
                    type = get.video?.broadcastType?.toString(), duration = get.video?.lengthSeconds?.toString(), animatedPreviewURL = get.video?.animatedPreviewURL)
            } else null
        } catch (e: Exception) {
            helix.getVideos(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = videoId?.let { listOf(it) }
            ).data.firstOrNull()
        }
    }

    suspend fun loadVideos(ids: List<String>, helixClientId: String?, helixToken: String?): List<Video>? = withContext(Dispatchers.IO) {
        helix.getVideos(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            ids = ids
        ).data
    }

    suspend fun loadClip(clipId: String?, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): Clip? = withContext(Dispatchers.IO) {
        try {
            val user = try {
                gql.loadClipData(gqlHeaders, clipId).data
            } catch (e: Exception) {
                null
            }
            val video = gql.loadClipVideo(gqlHeaders, clipId).data
            Clip(id = clipId, channelId = user?.channelId, channelLogin = user?.channelLogin, channelName = user?.channelName,
                profileImageUrl = user?.profileImageUrl, videoId = video?.videoId, duration = video?.duration, vodOffset = video?.vodOffset ?: user?.vodOffset)
        } catch (e: Exception) {
            helix.getClips(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = clipId?.let { listOf(it) }
            ).data.firstOrNull()
        }
    }

    suspend fun loadUserChannelPage(channelId: String?, channelLogin: String?, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): Stream? = withContext(Dispatchers.IO) {
        try {
            getApolloClient(gqlHeaders).query(UserChannelPageQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
            )).execute().data?.user?.let { i ->
                Stream(
                    id = i.stream?.id,
                    channelId = i.id,
                    channelLogin = i.login,
                    channelName = i.displayName,
                    gameId = i.stream?.game?.id,
                    gameName = i.stream?.game?.displayName,
                    type = i.stream?.type,
                    title = i.stream?.title,
                    viewerCount = i.stream?.viewersCount,
                    startedAt = i.stream?.createdAt?.toString(),
                    profileImageUrl = i.profileImageURL,
                    user = User(
                        channelId = i.id,
                        channelLogin = i.login,
                        channelName = i.displayName,
                        type = when {
                            i.roles?.isStaff == true -> "staff"
                            i.roles?.isSiteAdmin == true -> "admin"
                            i.roles?.isGlobalMod == true -> "global_mod"
                            else -> null
                        },
                        broadcasterType = when {
                            i.roles?.isPartner == true -> "partner"
                            i.roles?.isAffiliate == true -> "affiliate"
                            else -> null
                        },
                        profileImageUrl = i.profileImageURL,
                        createdAt = i.createdAt?.toString(),
                        followersCount = i.followers?.totalCount,
                        bannerImageURL = i.bannerImageURL,
                        lastBroadcast = i.lastBroadcast?.startedAt?.toString()
                    )
                )
            }
        } catch (e: Exception) {
            helix.getStreams(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
            ).data.firstOrNull()
        }
    }

    suspend fun loadUser(channelId: String?, channelLogin: String?, helixClientId: String?, helixToken: String?): User? = withContext(Dispatchers.IO) {
        helix.getUsers(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            ids = channelId?.let { listOf(it) },
            logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
        ).data.firstOrNull()
    }

    suspend fun loadCheckUser(channelId: String? = null, channelLogin: String? = null, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): User? = withContext(Dispatchers.IO) {
        try {
            getApolloClient(gqlHeaders).query(UserQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
            )).execute().data?.user?.let { i ->
                User(
                    channelId = i.id,
                    channelLogin = i.login,
                    channelName = i.displayName,
                    profileImageUrl = i.profileImageURL
                )
            }
        } catch (e: Exception) {
            helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
            ).data.firstOrNull()
        }
    }

    suspend fun loadUserMessageClicked(channelId: String? = null, channelLogin: String? = null, targetId: String?, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): User? = withContext(Dispatchers.IO) {
        try {
            getApolloClient(gqlHeaders).query(UserMessageClickedQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
                targetId = Optional.Present(targetId)
            )).execute().data?.user?.let { i ->
                User(
                    channelId = i.id,
                    channelLogin = i.login,
                    channelName = i.displayName,
                    profileImageUrl = i.profileImageURL,
                    bannerImageURL = i.bannerImageURL,
                    createdAt = i.createdAt?.toString(),
                    followedAt = i.follow?.followedAt?.toString()
                )
            }
        } catch (e: Exception) {
            helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
            ).data.firstOrNull()
        }
    }

    suspend fun loadUserTypes(ids: List<String>, helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>): List<User>? = withContext(Dispatchers.IO) {
        try {
            getApolloClient(gqlHeaders).query(UsersTypeQuery(Optional.Present(ids))).execute().data?.users?.let { get ->
                val list = mutableListOf<User>()
                for (i in get) {
                    list.add(User(
                        channelId = i?.id,
                        broadcasterType = when {
                            i?.roles?.isPartner == true -> "partner"
                            i?.roles?.isAffiliate == true -> "affiliate"
                            else -> null
                        },
                        type = when {
                            i?.roles?.isStaff == true -> "staff"
                            i?.roles?.isSiteAdmin == true -> "admin"
                            i?.roles?.isGlobalMod == true -> "global_mod"
                            else -> null
                        }
                    ))
                }
                list
            }
        } catch (e: Exception) {
            helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                ids = ids
            ).data
        }
    }

    suspend fun loadGlobalBadges(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, emoteQuality: String): List<TwitchBadge> = withContext(Dispatchers.IO) {
        try {
            val badges = mutableListOf<TwitchBadge>()
            val get = getApolloClient(gqlHeaders).query(BadgesQuery(Optional.Present(when (emoteQuality) {"4" -> BadgeImageSize.QUADRUPLE "3" -> BadgeImageSize.QUADRUPLE "2" -> BadgeImageSize.DOUBLE else -> BadgeImageSize.NORMAL}))).execute().data
            get?.badges?.forEach {
                if (it != null) {
                    it.setID?.let { setId ->
                        it.version?.let { version ->
                            it.imageURL?.let { url ->
                                badges.add(TwitchBadge(
                                    setId = setId,
                                    version = version,
                                    url1x = url,
                                    url2x = url,
                                    url3x = url,
                                    url4x = url,
                                    title = it.title
                                ))
                            }
                        }
                    }
                }
            }
            badges
        } catch (e: Exception) {
            try {
                gql.loadChatBadges(gqlHeaders, "").global
            } catch (e: Exception) {
                helix.getGlobalBadges(
                    clientId = helixClientId,
                    token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }
                ).data
            }
        }
    }

    suspend fun loadChannelBadges(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, channelId: String?, channelLogin: String?, emoteQuality: String): List<TwitchBadge> = withContext(Dispatchers.IO) {
        try {
            val badges = mutableListOf<TwitchBadge>()
            val get = getApolloClient(gqlHeaders).query(UserBadgesQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
                quality = Optional.Present(when (emoteQuality) {"4" -> BadgeImageSize.QUADRUPLE "3" -> BadgeImageSize.QUADRUPLE "2" -> BadgeImageSize.DOUBLE else -> BadgeImageSize.NORMAL})
            )).execute().data
            get?.user?.broadcastBadges?.forEach {
                if (it != null) {
                    it.setID?.let { setId ->
                        it.version?.let { version ->
                            it.imageURL?.let { url ->
                                badges.add(TwitchBadge(
                                    setId = setId,
                                    version = version,
                                    url1x = url,
                                    url2x = url,
                                    url3x = url,
                                    url4x = url,
                                    title = it.title
                                ))
                            }
                        }
                    }
                }
            }
            badges
        } catch (e: Exception) {
            try {
                gql.loadChatBadges(gqlHeaders, channelLogin).channel
            } catch (e: Exception) {
                helix.getChannelBadges(
                    clientId = helixClientId,
                    token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                    userId = channelId
                ).data
            }
        }
    }

    suspend fun loadCheerEmotes(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, channelId: String?, channelLogin: String?, animateGifs: Boolean): List<CheerEmote> = withContext(Dispatchers.IO) {
        try {
            val emotes = mutableListOf<CheerEmote>()
            val get = getApolloClient(gqlHeaders).query(UserCheerEmotesQuery(
                id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
                login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
            )).execute().data
            get?.cheerConfig?.displayConfig?.let { config ->
                config.backgrounds?.let {
                    val background = config.backgrounds.find { it == "dark" } ?: config.backgrounds.last()
                    config.colors?.let {
                        val colors = config.colors
                        config.scales?.let {
                            val scale = config.scales
                            config.types?.let {
                                val type = if (animateGifs) {
                                    config.types.find { it.animation == "animated" } ?: config.types.find { it.animation == "static" }
                                } else {
                                    config.types.find { it.animation == "static" }
                                } ?: config.types.first()
                                if (type.animation != null && type.extension != null) {
                                    get.cheerConfig.groups?.forEach { group ->
                                        group.templateURL?.let { template ->
                                            group.nodes?.forEach { emote ->
                                                emote.prefix?.let { prefix ->
                                                    emote.tiers?.forEach { tier ->
                                                        val item = colors.find { it.bits == tier?.bits }
                                                        if (item?.bits != null) {
                                                            val url = template
                                                                .replaceFirst("PREFIX", prefix.lowercase())
                                                                .replaceFirst("BACKGROUND", background)
                                                                .replaceFirst("ANIMATION", type.animation)
                                                                .replaceFirst("TIER", item.bits.toString())
                                                                .replaceFirst("EXTENSION", type.extension)
                                                            emotes.add(CheerEmote(
                                                                name = prefix,
                                                                url1x = (scale.find { it.startsWith("1") })?.let { url.replaceFirst("SCALE", it) } ?: scale.last(),
                                                                url2x = (scale.find { it.startsWith("2") })?.let { url.replaceFirst("SCALE", it) },
                                                                url3x = (scale.find { it.startsWith("3") })?.let { url.replaceFirst("SCALE", it) },
                                                                url4x = (scale.find { it.startsWith("4") })?.let { url.replaceFirst("SCALE", it) },
                                                                type = if (type.animation == "animated") "gif" else null,
                                                                isAnimated = type.animation == "animated",
                                                                minBits = item.bits,
                                                                color = item.color
                                                            ))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    get.user?.cheer?.cheerGroups?.forEach { group ->
                                        group.templateURL?.let { template ->
                                            group.nodes?.forEach { emote ->
                                                emote.prefix?.let { prefix ->
                                                    emote.tiers?.forEach { tier ->
                                                        val item = colors.find { it.bits == tier?.bits }
                                                        if (item?.bits != null) {
                                                            val url = template
                                                                .replaceFirst("PREFIX", prefix.lowercase())
                                                                .replaceFirst("BACKGROUND", background)
                                                                .replaceFirst("ANIMATION", type.animation)
                                                                .replaceFirst("TIER", item.bits.toString())
                                                                .replaceFirst("EXTENSION", type.extension)
                                                            emotes.add(CheerEmote(
                                                                name = prefix,
                                                                url1x = (scale.find { it.startsWith("1") })?.let { url.replaceFirst("SCALE", it) } ?: scale.last(),
                                                                url2x = (scale.find { it.startsWith("2") })?.let { url.replaceFirst("SCALE", it) },
                                                                url3x = (scale.find { it.startsWith("3") })?.let { url.replaceFirst("SCALE", it) },
                                                                url4x = (scale.find { it.startsWith("4") })?.let { url.replaceFirst("SCALE", it) },
                                                                type = if (type.animation == "animated") "gif" else null,
                                                                isAnimated = type.animation == "animated",
                                                                minBits = item.bits,
                                                                color = item.color
                                                            ))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            emotes
        } catch (e: Exception) {
            try {
                gql.loadCheerEmotes(gqlHeaders, channelLogin, animateGifs)
            } catch (e: Exception) {
                val data = mutableListOf<CheerEmote>()
                helix.getCheerEmotes(
                    clientId = helixClientId,
                    token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                    userId = channelId
                ).data.forEach { emote ->
                    val urls = if (animateGifs) { emote.animated ?: emote.static } else { emote.static }
                    data.add(CheerEmote(
                        name = emote.name,
                        url1x = urls?.get("1")?.takeIf { !it.isJsonNull }?.asString,
                        url2x = urls?.get("2")?.takeIf { !it.isJsonNull }?.asString,
                        url3x = urls?.get("3")?.takeIf { !it.isJsonNull }?.asString,
                        url4x = urls?.get("4")?.takeIf { !it.isJsonNull }?.asString,
                        type = if (urls == emote.animated) "gif" else null,
                        isAnimated = urls == emote.animated,
                        minBits = emote.minBits,
                        color = emote.color
                    ))
                }
                data
            }
        }
    }

    suspend fun loadUserEmotes(gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?): List<TwitchEmote> = withContext(Dispatchers.IO) {
        try {
            gql.loadUserEmotes(gqlHeaders, gqlToken, channelId).data
        } catch (e: Exception) {
            val emotes = mutableListOf<TwitchEmote>()
            getApolloClient(gqlHeaders, gqlToken).query(UserEmotesQuery()).execute().data?.user?.emoteSets?.forEach { set ->
                set.emotes?.forEach { emote ->
                    if (emote?.token != null && (!emote.type?.toString().equals("follower", true) || (emote.owner?.id == null || emote.owner.id == channelId))) {
                        emotes.add(TwitchEmote(
                            id = emote.id,
                            name = emote.token.removePrefix("\\").replace("?\\", ""),
                            setId = emote.setID,
                            ownerId = emote.owner?.id
                        ))
                    }
                }
            }
            emotes
        }
    }

    suspend fun loadEmotesFromSet(helixClientId: String?, helixToken: String?, setIds: List<String>, animateGifs: Boolean): List<TwitchEmote> = withContext(Dispatchers.IO) {
        val data = mutableListOf<TwitchEmote>()
        helix.getEmotesFromSet(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            setIds = setIds
        ).data.forEach { emote ->
            val format = (if (animateGifs) {
                emote.format.find { it.asString == "animated" } ?: emote.format.find { it.asString == "static" }
            } else {
                emote.format.find { it.asString == "static" }
            } ?: emote.format.first()).asString
            val theme = (emote.theme.find { it.asString == "dark" } ?: emote.theme.last()).asString
            val url = emote.template
                .replaceFirst("{{id}}", emote.id)
                .replaceFirst("{{format}}", format)
                .replaceFirst("{{theme_mode}}", theme)
            data.add(TwitchEmote(
                name = emote.name,
                url1x = url.replaceFirst("{{scale}}", (emote.scale.find { it.asString.startsWith("1") } ?: emote.scale.last()).asString),
                url2x = url.replaceFirst("{{scale}}", (emote.scale.find { it.asString.startsWith("2") } ?: emote.scale.find { it.asString.startsWith("1") } ?: emote.scale.last()).asString),
                url3x = url.replaceFirst("{{scale}}", (emote.scale.find { it.asString.startsWith("3") } ?: emote.scale.find { it.asString.startsWith("2") } ?: emote.scale.find { it.asString.startsWith("1") } ?: emote.scale.last()).asString),
                url4x = url.replaceFirst("{{scale}}", (emote.scale.find { it.asString.startsWith("3") } ?: emote.scale.find { it.asString.startsWith("2") } ?: emote.scale.find { it.asString.startsWith("1") } ?: emote.scale.last()).asString),
                type = if (format == "animated") "gif" else null,
                setId = emote.setId,
                ownerId = emote.ownerId
            ))
        }
        data
    }

    suspend fun loadUserFollowing(helixClientId: String?, helixToken: String?, targetId: String?, userId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, targetLogin: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!gqlToken.isNullOrBlank()) gql.loadFollowingUser(gqlHeaders, gqlToken, targetLogin).following else throw Exception()
        } catch (e: Exception) {
            helix.getUserFollows(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                targetId = targetId,
                userId = userId
            ).data.firstOrNull()?.channelId == targetId
        }
    }

    suspend fun loadGameFollowing(gqlHeaders: Map<String, String>, gqlToken: String?, gameName: String?): Boolean = withContext(Dispatchers.IO) {
        gql.loadFollowingGame(gqlHeaders, gqlToken, gameName).following
    }

    suspend fun loadVideoMessages(gqlHeaders: Map<String, String>, videoId: String, offset: Int? = null, cursor: String? = null): VideoMessagesDataResponse = withContext(Dispatchers.IO) {
        gql.loadVideoMessages(gqlHeaders, videoId, offset, cursor)
    }

    suspend fun loadVideoGames(gqlHeaders: Map<String, String>, videoId: String?): List<Game> = withContext(Dispatchers.IO) {
        gql.loadVideoGames(gqlHeaders, videoId).data
    }

    suspend fun loadChannelViewerList(gqlHeaders: Map<String, String>, channelLogin: String?): ChannelViewerList = withContext(Dispatchers.IO) {
        gql.loadChannelViewerList(gqlHeaders, channelLogin).data
    }

    suspend fun loadClaimPoints(gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, channelLogin: String?) = withContext(Dispatchers.IO) {
        try {
            val claimId = gql.loadChannelPointsContext(gqlHeaders, gqlToken, channelLogin).availableClaimId
            gql.loadClaimPoints(gqlHeaders, gqlToken, channelId, claimId)
        } catch (e: Exception) {

        }
    }

    suspend fun loadJoinRaid(gqlHeaders: Map<String, String>, gqlToken: String?, raidId: String?) = withContext(Dispatchers.IO) {
        gql.loadJoinRaid(gqlHeaders, gqlToken, raidId)
    }

    suspend fun loadMinuteWatched(userId: String?, streamId: String?, channelId: String?, channelLogin: String?) = withContext(Dispatchers.IO) {
        try {
            val pageResponse = channelLogin?.let { misc.getChannelPage(it).string() }
            if (!pageResponse.isNullOrBlank()) {
                val settingsRegex = Regex("(https://static.twitchcdn.net/config/settings.*?js)")
                val settingsUrl = settingsRegex.find(pageResponse)?.value
                val settingsResponse = settingsUrl?.let { misc.getUrl(it).string() }
                if (!settingsResponse.isNullOrBlank()) {
                    val spadeRegex = Regex("\"spade_url\":\"(.*?)\"")
                    val spadeUrl = spadeRegex.find(settingsResponse)?.groups?.get(1)?.value
                    if (!spadeUrl.isNullOrBlank()) {
                        val json = JsonObject().apply {
                            addProperty("event", "minute-watched")
                            add("properties", JsonObject().apply {
                                addProperty("channel_id", channelId)
                                addProperty("broadcast_id", streamId)
                                addProperty("player", "site")
                                addProperty("user_id", userId?.toInt())
                            })
                        }
                        val spadeRequest = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP).toRequestBody()
                        misc.postUrl(spadeUrl, spadeRequest)
                    }
                }
            }
        } catch (e: Exception) {

        }
    }

    suspend fun followUser(gqlHeaders: Map<String, String>, gqlToken: String?, userId: String?): String? = withContext(Dispatchers.IO) {
        gql.loadFollowUser(gqlHeaders, gqlToken, userId).error
    }

    suspend fun unfollowUser(gqlHeaders: Map<String, String>, gqlToken: String?, userId: String?): String? = withContext(Dispatchers.IO) {
        gql.loadUnfollowUser(gqlHeaders, gqlToken, userId).error
    }

    suspend fun followGame(gqlHeaders: Map<String, String>, gqlToken: String?, gameId: String?): String? = withContext(Dispatchers.IO) {
        gql.loadFollowGame(gqlHeaders, gqlToken, gameId).error
    }

    suspend fun unfollowGame(gqlHeaders: Map<String, String>, gqlToken: String?, gameId: String?): String? = withContext(Dispatchers.IO) {
        gql.loadUnfollowGame(gqlHeaders, gqlToken, gameId).error
    }

    suspend fun sendAnnouncement(helixClientId: String?, helixToken: String?, userId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, message: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.sendAnnouncement(gqlHeaders, gqlToken, channelId, message, color)
        } else {
            val json = JsonObject().apply {
                addProperty("message", message)
                color?.let { addProperty("color", it) }
            }
            helix.sendAnnouncement(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, userId, json)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun banUser(helixClientId: String?, helixToken: String?, userId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?, duration: String? = null, reason: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.banUser(gqlHeaders, gqlToken, channelId, targetLogin, duration, reason)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            val json = JsonObject().apply {
                add("data", JsonObject().apply {
                    duration?.toIntOrNull()?.let { addProperty("duration", it) }
                    addProperty("reason", reason)
                    addProperty("user_id", targetId)
                })
            }
            helix.banUser(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, userId, json)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun unbanUser(helixClientId: String?, helixToken: String?, userId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.unbanUser(gqlHeaders, gqlToken, channelId, targetLogin)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.unbanUser(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, userId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun deleteMessages(helixClientId: String?, helixToken: String?, channelId: String?, userId: String?, messageId: String? = null): String? = withContext(Dispatchers.IO) {
        val response = helix.deleteMessages(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, userId, messageId)
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun updateChatColor(helixClientId: String?, helixToken: String?, userId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.updateChatColor(gqlHeaders, gqlToken, color)
        } else {
            helix.updateChatColor(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, userId, color)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun getChatColor(helixClientId: String?, helixToken: String?, userId: String?): String? = withContext(Dispatchers.IO) {
        val response = helix.getChatColor(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, userId)
        if (response.isSuccessful) {
            response.body()?.takeIf { it.isJsonObject }?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray?.first()?.takeIf { it.isJsonObject }?.asJsonObject?.get("color")?.takeIf { !it.isJsonNull }?.asString
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun startCommercial(helixClientId: String?, helixToken: String?, channelId: String?, length: String?): String? = withContext(Dispatchers.IO) {
        val json = JsonObject().apply {
            addProperty("broadcaster_id", channelId)
            addProperty("length", length?.toIntOrNull())
        }
        val response = helix.startCommercial(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, json)
        if (response.isSuccessful) {
            response.body()?.takeIf { it.isJsonObject }?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray?.first()?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.takeIf { !it.isJsonNull }?.asString
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun updateChatSettings(helixClientId: String?, helixToken: String?, channelId: String?, userId: String?, emote: Boolean? = null, followers: Boolean? = null, followersDuration: String? = null, slow: Boolean? = null, slowDuration: Int? = null, subs: Boolean? = null, unique: Boolean? = null): String? = withContext(Dispatchers.IO) {
        val json = JsonObject().apply {
            emote?.let { addProperty("emote_mode", it) }
            followers?.let { addProperty("follower_mode", it) }
            followersDuration?.toIntOrNull()?.let { addProperty("follower_mode_duration", it) }
            slow?.let { addProperty("slow_mode", it) }
            slowDuration?.let { addProperty("slow_mode_wait_time", it) }
            subs?.let { addProperty("subscriber_mode", it) }
            unique?.let { addProperty("unique_chat_mode", it) }
        }
        val response = helix.updateChatSettings(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, userId, json)
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun createStreamMarker(helixClientId: String?, helixToken: String?, channelId: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelLogin: String?, description: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.createStreamMarker(gqlHeaders, gqlToken, channelLogin)
        } else {
            val json = JsonObject().apply {
                addProperty("user_id", channelId)
                description?.let { addProperty("description", it) }
            }
            helix.createStreamMarker(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, json)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun getModerators(helixClientId: String?, helixToken: String?, channelId: String?, gqlHeaders: Map<String, String>, channelLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = gql.getModerators(gqlHeaders, channelLogin)
        //val response = helix.getModerators(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, null, null)
        if (response.isSuccessful) {
            response.body()?.data?.map { it.channelLogin }.toString()
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun addModerator(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.addModerator(gqlHeaders, gqlToken, channelId, targetLogin)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.addModerator(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun removeModerator(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.removeModerator(gqlHeaders, gqlToken, channelId, targetLogin)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.removeModerator(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun startRaid(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            val targetId = loadCheckUser(
                channelLogin = targetLogin,
                helixClientId = helixClientId,
                helixToken = helixToken,
                gqlHeaders = gqlHeaders
            )?.channelId
            gql.startRaid(gqlHeaders, gqlToken, channelId, targetId)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.startRaid(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun cancelRaid(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.cancelRaid(gqlHeaders, gqlToken, channelId)
        } else {
            helix.cancelRaid(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId)
        }
        if (response.isSuccessful) {
            response.body().toString()
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun getVips(helixClientId: String?, helixToken: String?, channelId: String?, gqlHeaders: Map<String, String>, channelLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = gql.getVips(gqlHeaders, channelLogin)
        //val response = helix.getVips(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, null, null)
        if (response.isSuccessful) {
            response.body()?.data?.map { it.channelLogin }.toString()
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun addVip(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.addVip(gqlHeaders, gqlToken, channelId, targetLogin)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.addVip(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun removeVip(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>, gqlToken: String?, channelId: String?, targetLogin: String?): String? = withContext(Dispatchers.IO) {
        val response = if (!gqlToken.isNullOrBlank()) {
            gql.removeVip(gqlHeaders, gqlToken, channelId, targetLogin)
        } else {
            val targetId = helix.getUsers(
                clientId = helixClientId,
                token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
                logins = targetLogin?.let { listOf(it) }
            ).data.firstOrNull()?.channelId
            helix.removeVip(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, channelId, targetId)
        }
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun sendWhisper(helixClientId: String?, helixToken: String?, userId: String?, targetLogin: String?, message: String?): String? = withContext(Dispatchers.IO) {
        val targetId = helix.getUsers(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            logins = targetLogin?.let { listOf(it) }
        ).data.firstOrNull()?.channelId
        val json = JsonObject().apply {
            addProperty("message", message)
        }
        val response = helix.sendWhisper(helixClientId, helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, userId, targetId, json)
        if (response.isSuccessful) {
            null
        } else {
            response.errorBody()?.string()
        }
    }

    suspend fun loadUserResult(channelId: String? = null, channelLogin: String? = null, gqlHeaders: Map<String, String>): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        try {
            if (!channelId.isNullOrBlank()) {
                getApolloClient(gqlHeaders).query(UserResultIDQuery(channelId)).execute().data?.userResultByID?.let {
                    when {
                        it.onUser != null -> Pair(null, null)
                        it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                        it.onUserError != null -> Pair(it.__typename, null)
                        else -> null
                    }
                }
            } else if (!channelLogin.isNullOrBlank()) {
                getApolloClient(gqlHeaders).query(UserResultLoginQuery(channelLogin)).execute().data?.userResultByLogin?.let {
                    when {
                        it.onUser != null -> Pair(null, null)
                        it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                        it.onUserError != null -> Pair(it.__typename, null)
                        else -> null
                    }
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }
}