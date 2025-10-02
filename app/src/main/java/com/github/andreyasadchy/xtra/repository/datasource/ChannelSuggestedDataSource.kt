package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import java.util.UUID

class ChannelSuggestedDataSource(
    channelLogin: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, Stream>() {
    private var query = channelLogin.orEmpty()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return if (query.isBlank()) {
            LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = null
            )
        } else {
            try {
                gqlLoad(params)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): LoadResult<Int, Stream> {

        // It is necessary to add a header 'X-Device-Id' to obtain similar streamers; otherwise, only popular streamers are returned.
        val headers: MutableMap<String, String> = HashMap()
        headers.put(C.HEADER_CLIENT_ID, gqlHeaders["Client-Id"].orEmpty())

        val randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 32) //X-Device-Id or Device-ID removes "commercial break in progress" (length 16 or 32)
        headers.put("X-Device-Id", randomId)

        val response = graphQLRepository.loadChannelSuggested(networkLibrary, headers.toMap(), query)

        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }

        /*
        edges[0] = "provider-side-nav-recommended-streams-1" (Popular streamers)
        edges[1] = "provider-side-nav-similar-streamer-currently-watching-1" (Similar streamers)
         */

        val data = response.data?.sideNav?.sections?.edges ?: return LoadResult.Page(
            data = emptyList(),
            prevKey = null,
            nextKey = null
        )

        // Only "similar streamers" matter.
        // Low-popularity streamers are not associated with others.
        if (data.size == 2 &&
            data[1].node.id != "provider-side-nav-similar-streamer-currently-watching-1") {
            // Empty result: Nothing here
            return LoadResult.Page(
                data = emptyList<Stream>(),
                prevKey = null,
                nextKey = null
            )
        }

        val list = data[1].node.content.edges.map { item ->
            item.node.let {
                Stream(
                    channelId = it.broadcaster?.id,
                    channelLogin = it.broadcaster?.login,
                    channelName = it.broadcaster?.displayName,
                    profileImageUrl = it.broadcaster?.profileImageURL,
                    viewerCount = it.viewersCount,
                    gameId = it.game?.id,
                    gameSlug = it.game?.slug,
                    gameName = it.game?.name,
                    title = it.broadcaster?.broadcastSettings?.title,
                    id = it.broadcaster?.id,
                    type = it.type
                )
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )

    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
