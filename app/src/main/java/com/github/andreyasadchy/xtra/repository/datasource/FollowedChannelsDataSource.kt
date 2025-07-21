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
            list.filter { it.lastBroadcast == null || it.profileImageUrl == null }.mapNotNull { it.channelId }.chunked(100).forEach { ids ->
                val response = graphQLRepository.loadQueryUsersLastBroadcast(networkLibrary, gqlHeaders, ids)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                response.data?.users?.forEach { user ->
                    list.find { it.channelId == user?.id }?.let { item ->
                        if (item.profileImageUrl == null) {
                            item.profileImageUrl = user?.profileImageURL
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
                    channelId = it.userId,
                    channelLogin = it.userLogin,
                    channelName = it.userName,
                    followLocal = true
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
                val item = list.find { it.channelId == user.channelId }
                if (item == null) {
                    user.followAccount = true
                    list.add(user)
                } else {
                    list.remove(item)
                    list.add(
                        User(
                            channelId = item.channelId,
                            channelLogin = user.channelLogin ?: item.channelLogin,
                            channelName = user.channelName ?: item.channelName,
                            profileImageUrl = user.profileImageUrl,
                            followedAt = user.followedAt,
                            lastBroadcast = user.lastBroadcast,
                            followAccount = true,
                            followLocal = item.followLocal,
                        )
                    )
                    if (item.followLocal && item.channelId != null && user.channelLogin != null && user.channelName != null
                        && (item.channelLogin != user.channelLogin || item.channelName != user.channelName)) {
                        localFollowsChannel.getFollowByUserId(item.channelId)?.let {
                            localFollowsChannel.updateFollow(it.apply {
                                userLogin = user.channelLogin
                                userName = user.channelName
                            })
                        }
                        offlineRepository.getVideosByUserId(item.channelId).forEach {
                            offlineRepository.updateVideo(it.apply {
                                channelLogin = user.channelLogin
                                channelName = user.channelName
                            })
                        }
                        bookmarksRepository.getBookmarksByUserId(item.channelId).forEach {
                            bookmarksRepository.updateBookmark(it.apply {
                                userLogin = user.channelLogin
                                userName = user.channelName
                            })
                        }
                    }
                }
            }
            list.filter {
                it.lastBroadcast == null || it.profileImageUrl == null
            }.mapNotNull { it.channelId }.chunked(100).forEach { ids ->
                val response = graphQLRepository.loadQueryUsersLastBroadcast(networkLibrary, gqlHeaders, ids)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                response.data?.users?.forEach { user ->
                    list.find { it.channelId == user?.id }?.let { item ->
                        list.remove(item)
                        list.add(
                            User(
                                channelId = item.channelId,
                                channelLogin = user?.login ?: item.channelLogin,
                                channelName = user?.displayName ?: item.channelName,
                                profileImageUrl = user?.profileImageURL,
                                followedAt = item.followedAt,
                                lastBroadcast = user?.lastBroadcast?.startedAt?.toString(),
                                followAccount = item.followAccount,
                                followLocal = item.followLocal,
                            )
                        )
                        if (item.followLocal && item.channelId != null && user?.login != null && user.displayName != null
                            && (item.channelLogin != user.login || item.channelName != user.displayName)) {
                            localFollowsChannel.getFollowByUserId(item.channelId)?.let {
                                localFollowsChannel.updateFollow(it.apply {
                                    userLogin = user.login
                                    userName = user.displayName
                                })
                            }
                            offlineRepository.getVideosByUserId(item.channelId).forEach {
                                offlineRepository.updateVideo(it.apply {
                                    channelLogin = user.login
                                    channelName = user.displayName
                                })
                            }
                            bookmarksRepository.getBookmarksByUserId(item.channelId).forEach {
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
                    "login" -> list.sortedWith(compareBy(nullsLast()) { it.channelLogin })
                    else -> list.sortedWith(compareBy(nullsLast()) { it.lastBroadcast })
                }
            } else {
                when (sort) {
                    "created_at" -> list.sortedWith(compareByDescending(nullsFirst()) { it.followedAt })
                    "login" -> list.sortedWith(compareByDescending(nullsFirst()) { it.channelLogin })
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
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    followedAt = item.followedAt?.toString(),
                    lastBroadcast = it.lastBroadcast?.startedAt?.toString(),
                    profileImageUrl = it.profileImageURL,
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
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    followedAt = it.self?.follower?.followedAt,
                    profileImageUrl = it.profileImageURL,
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
                channelId = it.channelId,
                channelLogin = it.channelLogin,
                channelName = it.channelName,
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
