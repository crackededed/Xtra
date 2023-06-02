package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.GameClipsQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.type.ClipsPeriod
import com.github.andreyasadchy.xtra.type.Language
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class GameClipsDataSource(
    private val gameId: String?,
    private val gameName: String?,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val started_at: String?,
    private val ended_at: String?,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val gqlQueryLanguages: List<Language>?,
    private val gqlQueryPeriod: ClipsPeriod?,
    private val gqlPeriod: String?,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>) : PagingSource<Int, Clip>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Clip> {
        return try {
            val response = try {
                when (apiPref.elementAt(0)?.second) {
                    C.HELIX -> if (!helixToken.isNullOrBlank() && gqlQueryLanguages.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                    C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                    C.GQL -> if (gqlQueryLanguages.isNullOrEmpty()) { api = C.GQL; gqlLoad(params) } else throw Exception()
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref.elementAt(1)?.second) {
                        C.HELIX -> if (!helixToken.isNullOrBlank() && gqlQueryLanguages.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                        C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                        C.GQL -> if (gqlQueryLanguages.isNullOrEmpty()) { api = C.GQL; gqlLoad(params) } else throw Exception()
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    try {
                        when (apiPref.elementAt(2)?.second) {
                            C.HELIX -> if (!helixToken.isNullOrBlank() && gqlQueryLanguages.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                            C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                            C.GQL -> if (gqlQueryLanguages.isNullOrEmpty()) { api = C.GQL; gqlLoad(params) } else throw Exception()
                            else -> throw Exception()
                        }
                    } catch (e: Exception) {
                        listOf()
                    }
                }
            }
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = if (!offset.isNullOrBlank() && (api == C.HELIX || nextPage)) {
                    nextPage = false
                    (params.key ?: 1) + 1
                } else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun helixLoad(params: LoadParams<Int>): List<Clip> {
        val get = helixApi.getClips(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            gameId = gameId,
            started_at = started_at,
            ended_at = ended_at,
            limit = params.loadSize,
            cursor = offset
        )
        val list = mutableListOf<Clip>()
        get.data.let { list.addAll(it) }
        val userIds = mutableListOf<String>()
        for (i in list) {
            i.channelId?.let { userIds.add(it) }
        }
        if (userIds.isNotEmpty()) {
            val users = helixApi.getUsers(clientId = helixClientId, token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, ids = userIds).data
            for (i in users) {
                val items = list.filter { it.channelId == i.channelId }
                for (item in items) {
                    item.channelLogin = i.channelLogin
                    item.profileImageUrl = i.profileImageUrl
                }
            }
        }
        offset = get.cursor
        return list
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Clip> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(GameClipsQuery(
            id = if (!gameId.isNullOrBlank()) Optional.Present(gameId) else Optional.Absent,
            name = if (gameId.isNullOrBlank() && !gameName.isNullOrBlank()) Optional.Present(gameName) else Optional.Absent,
            languages = Optional.Present(gqlQueryLanguages),
            sort = Optional.Present(gqlQueryPeriod),
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.game!!.clips!!
        val get = get1.edges!!
        val list = mutableListOf<Clip>()
        for (i in get) {
            list.add(Clip(
                id = i?.node?.slug,
                channelId = i?.node?.broadcaster?.id,
                channelLogin = i?.node?.broadcaster?.login,
                channelName = i?.node?.broadcaster?.displayName,
                videoId = i?.node?.video?.id,
                vodOffset = i?.node?.videoOffsetSeconds,
                title = i?.node?.title,
                viewCount = i?.node?.viewCount,
                uploadDate = i?.node?.createdAt?.toString(),
                duration = i?.node?.durationSeconds?.toDouble(),
                thumbnailUrl = i?.node?.thumbnailURL,
                profileImageUrl = i?.node?.broadcaster?.profileImageURL,
                videoAnimatedPreviewURL = i?.node?.video?.animatedPreviewURL
            ))
        }
        offset = get.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): List<Clip> {
        val get = gqlApi.loadGameClips(gqlHeaders, gameName, gqlPeriod, params.loadSize, offset)
        offset = get.cursor
        nextPage = get.hasNextPage ?: true
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Clip>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
