package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.SearchStreamsQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class SearchStreamsDataSource(
    private val query: String,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>?) : PagingSource<Int, Stream>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            val response = if (query.isBlank()) listOf() else try {
                when (apiPref?.elementAt(0)?.second) {
                    C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                    C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref?.elementAt(1)?.second) {
                        C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                        C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    listOf()
                }
            }
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = if (!offset.isNullOrBlank() && (api != C.GQL_QUERY || nextPage)) {
                    nextPage = false
                    (params.key ?: 1) + 1
                } else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun helixLoad(params: LoadParams<Int>): List<Stream> {
        val get = helixApi.getSearchChannels(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            query = query,
            limit = params.loadSize,
            offset = offset,
            live = true
        )
        val list = mutableListOf<Stream>()
        get.data.forEach {
            list.add(Stream(
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
                gameId = it.stream?.gameId,
                gameName = it.stream?.gameName,
                title = it.stream?.title,
                startedAt = it.stream?.startedAt,
                profileImageUrl = it.profileImageUrl,
                tags = it.stream?.tags
            ))
        }
        offset = get.cursor
        return list
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Stream> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(SearchStreamsQuery(
            query = query,
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.searchStreams!!
        val get = get1.edges!!
        val list = mutableListOf<Stream>()
        for (edge in get) {
            edge.node?.let { i ->
                list.add(Stream(
                    id = i.id,
                    channelId = i.broadcaster?.id,
                    channelLogin = i.broadcaster?.login,
                    channelName = i.broadcaster?.displayName,
                    gameId = i.game?.id,
                    gameName = i.game?.displayName,
                    type = i.type,
                    title = i.broadcaster?.broadcastSettings?.title,
                    viewerCount = i.viewersCount,
                    startedAt = i.createdAt?.toString(),
                    thumbnailUrl = i.previewImageURL,
                    profileImageUrl = i.broadcaster?.profileImageURL,
                    tags = i.freeformTags?.mapNotNull { it.name }
                ))
            }
        }
        offset = get1.edges.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
