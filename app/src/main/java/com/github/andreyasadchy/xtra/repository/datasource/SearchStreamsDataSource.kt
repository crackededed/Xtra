package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C

class SearchStreamsDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val enableIntegrity: Boolean,
    private val apiPref: List<String>,
    private val networkLibrary: String?,
) : PagingSource<Int, Stream>() {
    private var api: String? = null
    private var offset: String? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return if (query.isBlank()) {
            LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = null
            )
        } else {
            if (!offset.isNullOrBlank()) {
                try {
                    loadFromApi(api, params)
                } catch (e: Exception) {
                    LoadResult.Error(e)
                }
            } else {
                try {
                    loadFromApi(apiPref.getOrNull(0), params)
                } catch (e: Exception) {
                    try {
                        loadFromApi(apiPref.getOrNull(1), params)
                    } catch (e: Exception) {
                        LoadResult.Error(e)
                    }
                }
            }
        }
    }

    private suspend fun loadFromApi(apiPref: String?, params: LoadParams<Int>): LoadResult<Int, Stream> {
        api = apiPref
        return when (apiPref) {
            C.GQL -> gqlQueryLoad(params)
            C.HELIX -> if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) helixLoad(params) else throw Exception()
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): LoadResult<Int, Stream> {
        val response = graphQLRepository.loadQuerySearchStreams(networkLibrary, gqlHeaders, query, params.loadSize, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.searchStreams!!
        val list = data.edges!!.mapNotNull { item ->
            item.node?.let {
                Stream(
                    id = it.id,
                    channelId = it.broadcaster?.id,
                    channelLogin = it.broadcaster?.login,
                    channelName = it.broadcaster?.displayName,
                    gameId = it.game?.id,
                    gameSlug = it.game?.slug,
                    gameName = it.game?.displayName,
                    title = it.broadcaster?.broadcastSettings?.title,
                    viewerCount = it.viewersCount,
                    startedAt = it.createdAt?.toString(),
                    thumbnailUrl = it.previewImageURL,
                    profileImageUrl = it.broadcaster?.profileImageURL,
                    tags = it.freeformTags?.mapNotNull { tag -> tag.name }
                )
            }
        }
        offset = data.edges.lastOrNull()?.cursor?.toString()
        val nextPage = data.pageInfo?.hasNextPage != false
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (!offset.isNullOrBlank() && nextPage) {
                (params.key ?: 1) + 1
            } else null
        )
    }

    private suspend fun helixLoad(params: LoadParams<Int>): LoadResult<Int, Stream> {
        val response = helixRepository.getSearchChannels(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            query = query,
            limit = params.loadSize,
            offset = offset,
            live = true,
        )
        val list = response.data.mapNotNull {
            if (it.isLive == true) {
                Stream(
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    startedAt = it.startedAt,
                    profileImageUrl = it.profileImageUrl,
                    tags = it.tags
                )
            } else null
        }
        offset = response.pagination?.cursor
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (!offset.isNullOrBlank()) {
                (params.key ?: 1) + 1
            } else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
