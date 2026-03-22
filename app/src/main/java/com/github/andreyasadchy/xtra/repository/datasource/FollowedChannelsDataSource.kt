package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.C

class FollowedChannelsDataSource(
    private val sort: String,
    private val order: String,
    private val userId: String?,
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarksRepository: BookmarksRepository,
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
        return if (!offset.isNullOrBlank()) {
            val list = mutableListOf<User>()
            val result = try {
                loadFromApi(api, params)
            } catch (e: Exception) {
                null
            }?.let {
                if (it is LoadResult.Error && it.throwable.message == "failed integrity check") {
                    return it
                }
                it as? LoadResult.Page
            }
            list.filter { it.lastBroadcast == null || it.profileImageURL == null }.mapNotNull { it.id }.chunked(100).forEach { ids ->
                val response = graphQLRepository.loadQueryUsersLastBroadcast(networkLibrary, gqlHeaders, ids)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                response.data?.users?.forEach { user ->
                    list.find { it.id == user?.id }?.let { item ->
                        if (item.profileImageURL == null) {
                            item.profileImageURL = user?.profileImageURL
                        }
                        item.lastBroadcast = user?.lastBroadcast?.startedAt?.toString()
                    }
                }
            }
            LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = result?.nextKey
            )
        } else {
            val list = mutableListOf<User>()
            localFollowsChannel.loadFollows().let { if (order == "asc") it.asReversed() else it }.forEach {
                list.add(User(
                    id = it.userId,
                    login = it.userLogin,
                    name = it.userName,
                    localFollow = true,
                ))
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
            result?.data?.forEach { user ->
                val item = list.find { it.id == user.id }
                if (item == null) {
                    user.accountFollow = true
                    list.add(user)
                } else {
                    list.remove(item)
                    list.add(
                        User(
                            id = item.id,
                            login = user.login ?: item.login,
                            name = user.name ?: item.name,
                            profileImageURL = user.profileImageURL,
                            lastBroadcast = user.lastBroadcast,
                            followedAt = user.followedAt,
                            accountFollow = true,
                            localFollow = item.localFollow,
                        )
                    )
                    if (item.localFollow && item.id != null && user.login != null && user.name != null
                        && (item.login != user.login || item.name != user.name)) {
                        localFollowsChannel.getFollowByUserId(item.id)?.let {
                            localFollowsChannel.updateFollow(it.apply {
                                userLogin = user.login
                                userName = user.name
                            })
                        }
                        offlineRepository.getVideosByUserId(item.id).forEach {
                            offlineRepository.updateVideo(it.apply {
                                channelLogin = user.login
                                channelName = user.name
                            })
                        }
                        bookmarksRepository.getBookmarksByUserId(item.id).forEach {
                            bookmarksRepository.updateBookmark(it.apply {
                                userLogin = user.login
                                userName = user.name
                            })
                        }
                    }
                }
            }
            list.filter {
                it.lastBroadcast == null || it.profileImageURL == null
            }.mapNotNull { it.id }.chunked(100).forEach { ids ->
                val response = graphQLRepository.loadQueryUsersLastBroadcast(networkLibrary, gqlHeaders, ids)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                response.data?.users?.forEach { user ->
                    list.find { it.id == user?.id }?.let { item ->
                        list.remove(item)
                        list.add(
                            User(
                                id = item.id,
                                login = user?.login ?: item.login,
                                name = user?.displayName ?: item.name,
                                profileImageURL = user?.profileImageURL,
                                lastBroadcast = user?.lastBroadcast?.startedAt?.toString(),
                                followedAt = item.followedAt,
                                accountFollow = item.accountFollow,
                                localFollow = item.localFollow,
                            )
                        )
                        if (item.localFollow && item.id != null && user?.login != null && user.displayName != null
                            && (item.login != user.login || item.name != user.displayName)) {
                            localFollowsChannel.getFollowByUserId(item.id)?.let {
                                localFollowsChannel.updateFollow(it.apply {
                                    userLogin = user.login
                                    userName = user.displayName
                                })
                            }
                            offlineRepository.getVideosByUserId(item.id).forEach {
                                offlineRepository.updateVideo(it.apply {
                                    channelLogin = user.login
                                    channelName = user.displayName
                                })
                            }
                            bookmarksRepository.getBookmarksByUserId(item.id).forEach {
                                bookmarksRepository.updateBookmark(it.apply {
                                    userLogin = user.login
                                    userName = user.displayName
                                })
                            }
                        }
                    }
                }
            }
            val sorted = if (order == "asc") {
                when (sort) {
                    "created_at" -> list.sortedWith(compareBy(nullsLast()) { it.followedAt })
                    "login" -> list.sortedWith(compareBy(nullsLast()) { it.login })
                    else -> list.sortedWith(compareBy(nullsLast()) { it.lastBroadcast })
                }
            } else {
                when (sort) {
                    "created_at" -> list.sortedWith(compareByDescending(nullsFirst()) { it.followedAt })
                    "login" -> list.sortedWith(compareByDescending(nullsFirst()) { it.login })
                    else -> list.sortedWith(compareByDescending(nullsFirst()) { it.lastBroadcast })
                }
            }
            LoadResult.Page(
                data = sorted,
                prevKey = null,
                nextKey = result?.nextKey
            )
        }
    }

    private suspend fun loadFromApi(apiPref: String?, params: LoadParams<Int>): LoadResult<Int, User> {
        api = apiPref
        return when (apiPref) {
            C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlQueryLoad(params) else throw Exception()
            C.GQL_PERSISTED_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) gqlLoad(params) else throw Exception()
            C.HELIX -> if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) helixLoad(params) else throw Exception()
            else -> throw Exception()
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, gqlHeaders, 100, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.user!!.follows!!
        val items = data.edges!!
        val list = items.mapNotNull { item ->
            item?.node?.let {
                User(
                    id = it.id,
                    login = it.login,
                    name = it.displayName,
                    profileImageURL = it.profileImageURL,
                    lastBroadcast = it.lastBroadcast?.startedAt?.toString(),
                    followedAt = item.followedAt?.toString(),
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

    private suspend fun gqlLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = graphQLRepository.loadFollowedChannels(networkLibrary, gqlHeaders, 100, offset)
        if (enableIntegrity) {
            response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
        }
        val data = response.data!!.user.follows
        val items = data.edges
        val list = items.map { item ->
            item.node.let {
                User(
                    id = it.id,
                    login = it.login,
                    name = it.displayName,
                    profileImageURL = it.profileImageURL,
                    followedAt = it.self?.follower?.followedAt,
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

    private suspend fun helixLoad(params: LoadParams<Int>): LoadResult<Int, User> {
        val response = helixRepository.getUserFollows(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            userId = userId,
            limit = 100,
            offset = offset,
        )
        val list = response.data.map {
            User(
                id = it.id,
                login = it.login,
                name = it.displayName,
                followedAt = it.followedAt,
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
