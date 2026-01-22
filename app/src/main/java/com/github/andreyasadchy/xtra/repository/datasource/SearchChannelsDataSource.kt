package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C

class SearchChannelsDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val enableIntegrity: Boolean,
    private val apiPref: List<String>,
    private val networkLibrary: String?,
) : PagingSource<Int, User>() {
    private var api: String? = null
    private var offset: String? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, User> {
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
                        try {
                            loadFromApi(apiPref.getOrNull(2), params)
                        } catch (e: Exception) {
                            LoadResult.Error(e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadFromApi(apiPref: String?, params: LoadParams<Int>): LoadResult<Int, User> {
        api = apiPref
        return when (apiPref) {
            C.GQL -> gqlQueryLoad(params)
            C.GQL_PERSISTED_QUERY -> gqlLoad(params)
            C.HELIX -> if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) helixLoad(params) else throw Exception()
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = graphQLRepository.loadQuerySearchChannels(networkLibrary, gqlHeaders, query, params.loadSize, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.searchUsers!!
        val list = data.edges!!.mapNotNull { item ->
            item.node?.let {
                User(
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    profileImageUrl = it.profileImageURL,
                    followersCount = it.followers?.totalCount,
                    isLive = it.stream?.viewersCount != null
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

    private suspend fun gqlLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = graphQLRepository.loadSearchChannels(networkLibrary, gqlHeaders, query, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.searchFor.channels
        val list = data.edges.map { item ->
            item.item.let {
                User(
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    profileImageUrl = it.profileImageURL,
                    followersCount = it.followers?.totalCount,
                    isLive = it.stream?.viewersCount != null
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

    private suspend fun helixLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = helixRepository.getSearchChannels(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            query = query,
            limit = params.loadSize,
            offset = offset,
        )
        val list = response.data.map {
            User(
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
                profileImageUrl = it.profileImageUrl,
                isLive = it.isLive
            )
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

    override fun getRefreshKey(state: PagingState<Int, User>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
