package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowGameRepository
import com.github.andreyasadchy.xtra.util.C

class FollowedGamesDataSource(
    private val localFollowsGame: LocalFollowGameRepository,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val apiPref: List<String>,
    private val networkLibrary: String?,
) : PagingSource<Int, Game>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Game> {
        val list = mutableListOf<Game>()

        localFollowsGame.loadFollows().forEach {
            list.add(Game(
                gameId = it.gameId,
                gameSlug = it.gameSlug,
                gameName = it.gameName,
                boxArtUrl = it.boxArt,
                followLocal = true
            ))
        }
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                loadFromApi(apiPref.getOrNull(0))
            } catch (e: Exception) {
                try {
                    loadFromApi(apiPref.getOrNull(1))
                } catch (e: Exception) {
                    null
                }
            }?.let {
                if (it is LoadResult.Error && it.throwable.message == "failed integrity check") {
                    return it
                }
                it as? LoadResult.Page
            }?.data?.forEach { game ->
                val item = list.find { it.gameId == game.gameId }
                if (item == null) {
                    game.followAccount = true
                    list.add(game)
                } else {
                    item.followAccount = true
                    item.viewersCount = game.viewersCount
                    item.followersCount = game.followersCount
                    item.broadcastersCount = game.broadcastersCount
                    item.tags = game.tags
                }
            }
        }
        list.sortBy { it.gameName }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    private suspend fun loadFromApi(apiPref: String?): LoadResult<Int, Game> {
        return when (apiPref) {
            C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlQueryLoad() else throw Exception()
            C.GQL_PERSISTED_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlLoad() else throw Exception()
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(): LoadResult<Int, Game> {
        val response = graphQLRepository.loadQueryUserFollowedGames(networkLibrary, gqlHeaders, 100)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val list = response.data!!.user!!.followedGames!!.nodes!!.mapNotNull { item ->
            item?.let {
                Game(
                    gameId = it.id,
                    gameSlug = it.slug,
                    gameName = it.displayName,
                    boxArtUrl = it.boxArtURL,
                    viewersCount = it.viewersCount,
                    followersCount = it.followersCount,
                    broadcastersCount = it.broadcastersCount,
                    tags = it.tags?.map { tag ->
                        Tag(
                            id = tag.id,
                            name = tag.localizedName
                        )
                    }
                )
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    private suspend fun gqlLoad(): LoadResult<Int, Game> {
        val response = graphQLRepository.loadFollowedGames(networkLibrary, gqlHeaders, 100)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val list = response.data!!.currentUser.followedGames.nodes.map { item ->
            item.let {
                Game(
                    gameId = it.id,
                    gameName = it.displayName,
                    boxArtUrl = it.boxArtURL,
                    viewersCount = it.viewersCount ?: 0,
                    followersCount = it.followersCount ?: 0,
                    tags = it.tags?.map { tag ->
                        Tag(
                            id = tag.id,
                            name = tag.localizedName
                        )
                    }
                )
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Game>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
