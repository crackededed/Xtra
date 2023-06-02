package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.GameStreamsQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.StreamSortEnum
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.type.StreamSort
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class GameStreamsDataSource(
    private val gameId: String?,
    private val gameName: String?,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val gqlQuerySort: StreamSort?,
    private val gqlSort: StreamSortEnum?,
    private val tags: List<String>?,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>) : PagingSource<Int, Stream>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            val response = try {
                when (apiPref.elementAt(0)?.second) {
                    C.HELIX -> if (!helixToken.isNullOrBlank() && (gqlSort == StreamSortEnum.VIEWERS_HIGH || gqlSort == null) && tags.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                    C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                    C.GQL -> { api = C.GQL; gqlLoad(params) }
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref.elementAt(1)?.second) {
                        C.HELIX -> if (!helixToken.isNullOrBlank() && (gqlSort == StreamSortEnum.VIEWERS_HIGH || gqlSort == null) && tags.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                        C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                        C.GQL -> { api = C.GQL; gqlLoad(params) }
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    try {
                        when (apiPref.elementAt(2)?.second) {
                            C.HELIX -> if (!helixToken.isNullOrBlank() && (gqlSort == StreamSortEnum.VIEWERS_HIGH || gqlSort == null) && tags.isNullOrEmpty()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                            C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                            C.GQL -> { api = C.GQL; gqlLoad(params) }
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

    private suspend fun helixLoad(params: LoadParams<Int>): List<Stream> {
        val get = helixApi.getStreams(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            gameId = gameId,
            limit = params.loadSize,
            offset = offset
        )
        val list = mutableListOf<Stream>()
        get.data.let { list.addAll(it) }
        val ids = mutableListOf<String>()
        for (i in list) {
            i.channelId?.let { ids.add(it) }
        }
        if (ids.isNotEmpty()) {
            val users = helixApi.getUsers(clientId = helixClientId, token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) }, ids = ids).data
            for (i in users) {
                val items = list.filter { it.channelId == i.channelId }
                for (item in items) {
                    item.profileImageUrl = i.profileImageUrl
                }
            }
        }
        offset = get.cursor
        return list
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Stream> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(GameStreamsQuery(
            id = if (!gameId.isNullOrBlank()) Optional.Present(gameId) else Optional.Absent,
            name = if (gameId.isNullOrBlank() && !gameName.isNullOrBlank()) Optional.Present(gameName) else Optional.Absent,
            sort = Optional.Present(gqlQuerySort),
            tags = Optional.Present(tags),
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.game!!.streams!!
        val get = get1.edges!!
        val list = mutableListOf<Stream>()
        for (i in get) {
            list.add(Stream(
                id = i?.node?.id,
                channelId = i?.node?.broadcaster?.id,
                channelLogin = i?.node?.broadcaster?.login,
                channelName = i?.node?.broadcaster?.displayName,
                type = i?.node?.type,
                title = i?.node?.broadcaster?.broadcastSettings?.title,
                viewerCount = i?.node?.viewersCount,
                startedAt = i?.node?.createdAt?.toString(),
                thumbnailUrl = i?.node?.previewImageURL,
                profileImageUrl = i?.node?.broadcaster?.profileImageURL,
                tags = i?.node?.freeformTags?.mapNotNull { it.name }
            ))
        }
        offset = get.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): List<Stream> {
        val get = gqlApi.loadGameStreams(gqlHeaders, gameName, gqlSort?.value, tags, params.loadSize, offset)
        offset = get.cursor
        nextPage = get.hasNextPage ?: true
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
