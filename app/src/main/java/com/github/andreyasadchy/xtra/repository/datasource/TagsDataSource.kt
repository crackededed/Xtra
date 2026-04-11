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
            if (query.isNotBlank()) {
                gqlQueryLoad()
            } else {
                LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null
                )
            }
        } catch (e: Exception) {
            try {
                gqlLoad()
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    private suspend fun gqlQueryLoad(): LoadResult<Int, Tag> {
        return if (getGameTags) {
            val response = graphQLRepository.loadQuerySearchGameTags(networkLibrary, gqlHeaders, query, 100)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
            }
            val list = response.data!!.searchCategoryTags!!.map { item ->
                Tag(
                    id = item.id,
                    name = item.localizedName,
                )
            }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        } else {
            val response = graphQLRepository.loadQuerySearchFreeformTags(networkLibrary, gqlHeaders, query, 100)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
            }
            val data = response.data!!.searchFreeformTags!!
            val items = data.edges!!
            val list = items.mapNotNull { item ->
                item?.node?.let {
                    Tag(
                        name = it.tagName
                    )
                }
            }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        }
    }

    private suspend fun gqlLoad(): LoadResult<Int, Tag> {
        return if (getGameTags) {
            val response = graphQLRepository.loadGameTags(networkLibrary, gqlHeaders, query, 100)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
            }
            val list = response.data!!.searchCategoryTags.map {
                Tag(
                    id = it.id,
                    name = it.localizedName,
                )
            }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        } else {
            val response = graphQLRepository.loadFreeformTags(networkLibrary, gqlHeaders, query, 100)
            if (enableIntegrity) {
                response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
            }
            val data = response.data!!.searchFreeformTags
            val items = data.edges
            val list = items.map { item ->
                Tag(
                    name = item.node.tagName
                )
            }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Tag>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
