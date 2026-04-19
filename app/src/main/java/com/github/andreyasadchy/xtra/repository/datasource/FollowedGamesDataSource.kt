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
    private val networkLibrary: String?,
) : PagingSource<Int, Game>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Game> {
        val list = mutableListOf<Game>()
        localFollowsGame.loadFollows().forEach {
            list.add(Game(
                id = it.gameId,
                slug = it.gameSlug,
                name = it.gameName,
                boxArtURL = it.boxArt,
                localFollow = true
            ))
        }
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    gqlQueryLoad()
                } else {
                    throw Exception()
                }
            } catch (e: Exception) {
                try {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        gqlLoad()
                    } else {
                        throw Exception()
                    }
                } catch (e: Exception) {
                    null
                }
            }?.let {
                if (it is LoadResult.Error && it.throwable.message == C.FAILED_INTEGRITY_CHECK) {
                    return it
                }
                it as? LoadResult.Page
            }?.data?.forEach { game ->
                val item = list.find { it.id == game.id }
                if (item == null) {
                    game.accountFollow = true
                    list.add(game)
                } else {
                    item.accountFollow = true
                    item.viewerCount = game.viewerCount
                    item.broadcasterCount = game.broadcasterCount
                    item.tags = game.tags
                }
            }
        }
        list.sortBy { it.name }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    private suspend fun gqlQueryLoad(): LoadResult<Int, Game> {
        val response = graphQLRepository.loadQueryUserFollowedGames(networkLibrary, gqlHeaders, 100)
        if (enableIntegrity) {
            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val list = response.data!!.user!!.followedGames!!.nodes!!.mapNotNull { item ->
            item?.let {
                Game(
                    id = it.id,
                    slug = it.slug,
                    name = it.displayName,
                    boxArtURL = it.boxArtURL,
                    viewerCount = it.viewersCount,
                    broadcasterCount = it.broadcastersCount,
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
            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val list = response.data!!.currentUser.followedGames.nodes.map { item ->
            item.let {
                Game(
                    id = it.id,
                    name = it.displayName,
                    boxArtURL = it.boxArtURL,
                    viewerCount = it.viewersCount ?: 0,
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
