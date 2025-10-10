package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository

class ChannelSuggestedDataSource(
    private val channelLogin: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, Stream>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            val response = graphQLRepository.loadChannelSuggested(networkLibrary, gqlHeaders, channelLogin)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
            }
            val list = response.data!!.sideNav.sections.edges.find {
                it.node.id == "provider-side-nav-similar-streamer-currently-watching-1"
            }?.node?.content?.edges?.map { item ->
                item.node.let {
                    Stream(
                        id = it.id,
                        channelId = it.broadcaster?.id,
                        channelLogin = it.broadcaster?.login,
                        channelName = it.broadcaster?.displayName,
                        gameId = it.game?.id,
                        gameSlug = it.game?.slug,
                        gameName = it.game?.displayName,
                        type = it.type,
                        title = it.broadcaster?.broadcastSettings?.title,
                        viewerCount = it.viewersCount,
                        profileImageUrl = it.broadcaster?.profileImageURL,
                        tags = it.freeformTags?.mapNotNull { tag -> tag.name }
                    )
                }
            } ?: emptyList()
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
