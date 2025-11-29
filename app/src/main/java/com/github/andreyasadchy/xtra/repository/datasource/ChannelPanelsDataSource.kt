package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlin.String

class ChannelPanelsDataSource(
    private val channelId: String?,
    private val channelLogin: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, ChannelPanel>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelPanel> {

        val response = graphQLRepository.loadChannelPanels(networkLibrary, gqlHeaders, channelId)

        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let {
                return LoadResult.Error(Exception(it.message))
            }
        }

        val list =
            response.data?.user?.panels.orEmpty().filter { it.type != "EXTENSION" }.map { panel ->
                ChannelPanel(
                    id = panel.id,
                    type = panel.type,
                    title = panel.title,
                    imageURL = panel.imageURL,
                    linkURL = panel.linkURL,
                    description = panel.description,
                    altText = panel.altText
                )
            }

        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, ChannelPanel>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
