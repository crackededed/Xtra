package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.util.C

class FollowedStreamsDataSource(
    private val userId: String?,
    private val localFollowsChannel: LocalFollowChannelRepository,
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
        return if (!offset.isNullOrBlank()) {
            try {
                loadFromApi(api, params)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        } else {
            val list = mutableListOf<Stream>()
            localFollowsChannel.loadFollows().mapNotNull { it.userId }.takeIf { it.isNotEmpty() }?.let {
                try {
                    gqlQueryLocal(it)
                } catch (e: Exception) {
                    try {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) helixLocal(it) else throw Exception()
                    } catch (e: Exception) {
                        null
                    }
                }
            }?.let {
                if (it is LoadResult.Error && it.throwable.message == "failed integrity check") {
                    return it
                }
                (it as? LoadResult.Page)?.data?.let { list.addAll(it) }
            }
            val result = if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    loadFromApi(apiPref.getOrNull(0), params)
                } catch (e: Exception) {
                    try {
                        loadFromApi(apiPref.getOrNull(1), params)
                    } catch (e: Exception) {
                        try {
                            loadFromApi(apiPref.getOrNull(2), params)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }?.let {
                    if (it is LoadResult.Error && it.throwable.message == "failed integrity check") {
                        return it
                    }
                    it as? LoadResult.Page
                }
            } else null
            result?.data?.forEach { stream ->
                val item = list.find { it.channelId == stream.channelId }
                if (item == null) {
                    list.add(stream)
                }
            }
            list.sortByDescending { it.viewerCount }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = result?.nextKey
            )
        }
    }

    private suspend fun loadFromApi(apiPref: String?, params: LoadParams<Int>): LoadResult<Int, Stream> {
        api = apiPref
        return when (apiPref) {
            C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlQueryLoad(params) else throw Exception()
            C.GQL_PERSISTED_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlLoad(params) else throw Exception()
            C.HELIX -> if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) helixLoad(params) else throw Exception()
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): LoadResult<Int, Stream> {
        val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders, 100, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.user!!.followedLiveUsers!!
        val items = data.edges!!
        val list = items.mapNotNull { item ->
            item?.node?.let {
                Stream(
                    id = it.stream?.id,
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    gameId = it.stream?.game?.id,
                    gameSlug = it.stream?.game?.slug,
                    gameName = it.stream?.game?.displayName,
                    title = it.stream?.broadcaster?.broadcastSettings?.title,
                    viewerCount = it.stream?.viewersCount,
                    startedAt = it.stream?.createdAt?.toString(),
                    thumbnailUrl = it.stream?.previewImageURL,
                    profileImageUrl = it.profileImageURL,
                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                )
            }
        }
        offset = items.lastOrNull()?.cursor?.toString()
        val nextPage = data.pageInfo?.hasNextPage != false
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (!offset.isNullOrBlank() && nextPage) {
                (params.key ?: 1) + 1
            } else null
        )
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): LoadResult<Int, Stream> {
        val response = graphQLRepository.loadFollowedStreams(networkLibrary, gqlHeaders, 100, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.currentUser.followedLiveUsers
        val items = data.edges
        val list = items.map { item ->
            item.node.let {
                Stream(
                    id = it.stream?.id,
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    gameId = it.stream?.game?.id,
                    gameSlug = it.stream?.game?.slug,
                    gameName = it.stream?.game?.displayName,
                    title = it.stream?.title,
                    viewerCount = it.stream?.viewersCount,
                    startedAt = it.stream?.createdAt,
                    thumbnailUrl = it.stream?.previewImageURL,
                    profileImageUrl = it.profileImageURL,
                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                )
            }
        }
        offset = items.lastOrNull()?.cursor
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
        val response = helixRepository.getFollowedStreams(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            userId = userId,
            limit = 100,
            offset = offset,
        )
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it,
            ).data
        }
        val list = response.data.map {
            Stream(
                id = it.id,
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
                gameId = it.gameId,
                gameName = it.gameName,
                title = it.title,
                viewerCount = it.viewerCount,
                startedAt = it.startedAt,
                thumbnailUrl = it.thumbnailUrl,
                profileImageUrl = it.channelId?.let { id ->
                    users.find { user -> user.channelId == id }?.profileImageUrl
                },
                tags = it.tags
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

    private suspend fun gqlQueryLocal(ids: List<String>): LoadResult<Int, Stream> {
        val items = ids.chunked(100).map { list ->
            graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, list).also { response ->
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
            }
        }.flatMap { it.data!!.users!! }
        val list = items.mapNotNull { item ->
            item?.let {
                if (it.stream?.viewersCount != null) {
                    Stream(
                        id = it.stream.id,
                        channelId = it.id,
                        channelLogin = it.login,
                        channelName = it.displayName,
                        gameId = it.stream.game?.id,
                        gameSlug = it.stream.game?.slug,
                        gameName = it.stream.game?.displayName,
                        title = it.stream.broadcaster?.broadcastSettings?.title,
                        viewerCount = it.stream.viewersCount,
                        startedAt = it.stream.createdAt?.toString(),
                        thumbnailUrl = it.stream.previewImageURL,
                        profileImageUrl = it.profileImageURL,
                        tags = it.stream.freeformTags?.mapNotNull { tag -> tag.name }
                    )
                } else null
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = null
        )
    }

    private suspend fun helixLocal(ids: List<String>): LoadResult<Int, Stream> {
        val items = ids.chunked(100).map {
            helixRepository.getStreams(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it,
            )
        }.flatMap { it.data }
        val users = items.mapNotNull { it.channelId }.chunked(100).map {
            helixRepository.getUsers(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it,
            )
        }.flatMap { it.data }
        val list = items.mapNotNull {
            if (it.viewerCount != null) {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    viewerCount = it.viewerCount,
                    startedAt = it.startedAt,
                    thumbnailUrl = it.thumbnailUrl,
                    profileImageUrl = it.channelId?.let { id ->
                        users.find { user -> user.channelId == id }?.profileImageUrl
                    },
                    tags = it.tags
                )
            } else null
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