package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.UserFollowedGamesQuery
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowGameRepository
import com.github.andreyasadchy.xtra.util.C

class FollowedGamesDataSource(
    private val localFollowsGame: LocalFollowGameRepository,
    private val gqlHeaders: Map<String, String>,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>) : PagingSource<Int, Game>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Game> {
        return try {
            val response = try {
                val list = mutableListOf<Game>()
                for (i in localFollowsGame.loadFollows()) {
                    list.add(Game(gameId = i.gameId, gameName = i.gameName, boxArtUrl = i.boxArt, followLocal = true))
                }
                val remote = try {
                    when (apiPref.elementAt(0)?.second) {
                        C.GQL_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlQueryLoad() else throw Exception()
                        C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlLoad() else throw Exception()
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    try {
                        when (apiPref.elementAt(1)?.second) {
                            C.GQL_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlQueryLoad() else throw Exception()
                            C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlLoad() else throw Exception()
                            else -> throw Exception()
                        }
                    } catch (e: Exception) {
                        listOf()
                    }
                }
                if (remote.isNotEmpty()) {
                    for (i in remote) {
                        val item = list.find { it.gameId == i.gameId }
                        if (item == null) {
                            i.followAccount = true
                            list.add(i)
                        } else {
                            item.followAccount = true
                            item.viewersCount = i.viewersCount
                            item.broadcastersCount = i.broadcastersCount
                            item.tags = i.tags
                        }
                    }
                }
                list.sortBy { it.gameName }
                list
            } catch (e: Exception) {
                listOf()
            }
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun gqlQueryLoad(): List<Game> {
        val get1 = apolloClient.newBuilder().apply {
            gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) }
        }.build().query(UserFollowedGamesQuery(
            first = Optional.Present(100)
        )).execute().data!!.user!!.followedGames!!
        val get = get1.nodes!!
        val list = mutableListOf<Game>()
        for (i in get) {
            val tags = mutableListOf<Tag>()
            i?.tags?.forEach { tag ->
                tags.add(Tag(
                    id = tag.id,
                    name = tag.localizedName
                ))
            }
            list.add(Game(
                gameId = i?.id,
                gameName = i?.displayName,
                boxArtUrl = i?.boxArtURL,
                viewersCount = i?.viewersCount,
                broadcastersCount = i?.broadcastersCount,
                tags = tags
            ))
        }
        return list
    }

    private suspend fun gqlLoad(): List<Game> {
        val get = gqlApi.loadFollowedGames(gqlHeaders, 100)
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Game>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
