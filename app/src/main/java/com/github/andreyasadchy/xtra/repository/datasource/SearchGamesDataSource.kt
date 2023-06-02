package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.SearchGamesQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class SearchGamesDataSource(
    private val query: String,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>?) : PagingSource<Int, Game>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Game> {
        return try {
            val response = if (query.isBlank()) listOf() else try {
                when (apiPref?.elementAt(0)?.second) {
                    C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                    C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                    C.GQL -> { api = C.GQL; gqlLoad() }
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref?.elementAt(1)?.second) {
                        C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                        C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                        C.GQL -> { api = C.GQL; gqlLoad() }
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    try {
                        when (apiPref?.elementAt(2)?.second) {
                            C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                            C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                            C.GQL -> { api = C.GQL; gqlLoad() }
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
                nextKey = if (!offset.isNullOrBlank() && (api != C.GQL_QUERY || nextPage)) {
                    nextPage = false
                    (params.key ?: 1) + 1
                } else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun helixLoad(params: LoadParams<Int>): List<Game> {
        val get = helixApi.getSearchGames(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            query = query,
            limit = params.loadSize,
            offset = offset
        )
        offset = get.cursor
        return get.data
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Game> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(SearchGamesQuery(
            query = query,
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.searchCategories!!
        val get = get1.edges!!
        val list = mutableListOf<Game>()
        for (edge in get) {
            edge.node?.let { i ->
                val tags = mutableListOf<Tag>()
                i.tags?.forEach { tag ->
                    tags.add(Tag(
                        id = tag.id,
                        name = tag.localizedName
                    ))
                }
                list.add(Game(
                    gameId = i.id,
                    gameName = i.displayName,
                    boxArtUrl = i.boxArtURL,
                    viewersCount = i.viewersCount ?: 0, // returns null if 0
                    broadcastersCount = i.broadcastersCount ?: 0, // returns null if 0
                    tags = tags
                ))
            }
        }
        offset = get1.edges.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(): List<Game> {
        val get = gqlApi.loadSearchGames(gqlHeaders, query, offset)
        offset = get.cursor
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Game>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
