package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository

class TagsDataSource(
    private val getGameTags: Boolean,
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, Tag>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Tag> {
        return try {
            val response = query.takeIf { it.isNotBlank() }?.let {
                if (getGameTags) {
                    val response = graphQLRepository.loadGameTags(networkLibrary, gqlHeaders, query, 100)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                    }
                    response.data?.searchCategoryTags?.map {
                        Tag(
                            id = it.id,
                            name = it.localizedName,
                            scope = it.scope
                        )
                    }
                } else {
                    val response = graphQLRepository.loadFreeformTags(networkLibrary, gqlHeaders, query, 100)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                    }
                    response.data?.searchFreeformTags?.edges?.map { edge ->
                        Tag(
                            name = edge.node.tagName
                        )
                    }
                }
            } ?: emptyList()
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Tag>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
