package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C

class SearchVideosDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val apiPref: List<String>,
    private val useCronet: Boolean,
) : PagingSource<Int, Video>() {
    private var api: String? = null
    private var offset: String? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Video> {
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

    private suspend fun loadFromApi(apiPref: String?, params: LoadParams<Int>): LoadResult<Int, Video> {
        api = apiPref
        return when (apiPref) {
            C.GQL -> gqlQueryLoad(params)
            C.GQL_PERSISTED_QUERY -> gqlLoad(params)
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): LoadResult<Int, Video> {
        val response = graphQLRepository.loadQuerySearchVideos(useCronet, gqlHeaders, query, params.loadSize, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.searchFor!!.videos!!
        val list = data.items!!.map {
            Video(
                id = it.id,
                channelId = it.owner?.id,
                channelLogin = it.owner?.login,
                channelName = it.owner?.displayName,
                type = it.broadcastType?.toString(),
                title = it.title,
                viewCount = it.viewCount,
                uploadDate = it.createdAt?.toString(),
                duration = it.lengthSeconds?.toString(),
                thumbnailUrl = it.previewThumbnailURL,
                gameId = it.game?.id,
                gameSlug = it.game?.slug,
                gameName = it.game?.displayName,
                profileImageUrl = it.owner?.profileImageURL,
                tags = it.contentTags?.map { tag ->
                    Tag(
                        id = tag.id,
                        name = tag.localizedName
                    )
                },
                animatedPreviewURL = it.animatedPreviewURL
            )
        }
        offset = data.cursor
        val nextPage = data.pageInfo?.hasNextPage != false
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (!offset.isNullOrBlank() && nextPage) {
                (params.key ?: 1) + 1
            } else null
        )
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): LoadResult<Int, Video> {
        val response = graphQLRepository.loadSearchVideos(useCronet, gqlHeaders, query, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.searchFor.videos
        val list = data.edges.map { item ->
            item.item.let {
                Video(
                    id = it.id,
                    channelId = it.owner?.id,
                    channelLogin = it.owner?.login,
                    channelName = it.owner?.displayName,
                    title = it.title,
                    viewCount = it.viewCount,
                    uploadDate = it.createdAt,
                    duration = it.lengthSeconds?.toString(),
                    thumbnailUrl = it.previewThumbnailURL,
                    gameId = it.game?.id,
                    gameSlug = it.game?.slug,
                    gameName = it.game?.displayName,
                )
            }
        }
        offset = data.cursor
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (!offset.isNullOrBlank()) {
                (params.key ?: 1) + 1
            } else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Video>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
