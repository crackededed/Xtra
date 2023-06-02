package com.github.andreyasadchy.xtra.repository.datasource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.UserFollowedUsersQuery
import com.github.andreyasadchy.xtra.UsersLastBroadcastQuery
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.FollowOrderEnum
import com.github.andreyasadchy.xtra.model.ui.FollowSortEnum
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class FollowedChannelsDataSource(
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val userId: String?,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val gqlToken: String?,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>,
    private val sort: FollowSortEnum,
    private val order: FollowOrderEnum) : PagingSource<Int, User>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, User> {
        return try {
            val response = try {
                if (!offset.isNullOrBlank()) {
                    loadRange()
                } else {
                    val list = mutableListOf<User>()
                    for (i in localFollowsChannel.loadFollows().let { if (order == FollowOrderEnum.ASC) it.asReversed() else it }) {
                        list.add(User(channelId = i.userId, channelLogin = i.userLogin, channelName = i.userName, profileImageUrl = i.channelLogo, followLocal = true))
                    }
                    val remote = try {
                        when (apiPref.elementAt(0)?.second) {
                            C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad() } else throw Exception()
                            C.GQL_QUERY -> if (!gqlToken.isNullOrBlank()) { api = C.GQL_QUERY; gqlQueryLoad() } else throw Exception()
                            C.GQL -> if (!gqlToken.isNullOrBlank()) { api = C.GQL; gqlLoad() } else throw Exception()
                            else -> throw Exception()
                        }
                    } catch (e: Exception) {
                        try {
                            when (apiPref.elementAt(1)?.second) {
                                C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad() } else throw Exception()
                                C.GQL_QUERY -> if (!gqlToken.isNullOrBlank()) { api = C.GQL_QUERY; gqlQueryLoad() } else throw Exception()
                                C.GQL -> if (!gqlToken.isNullOrBlank()) { api = C.GQL; gqlLoad() } else throw Exception()
                                else -> throw Exception()
                            }
                        } catch (e: Exception) {
                            try {
                                when (apiPref.elementAt(2)?.second) {
                                    C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad() } else throw Exception()
                                    C.GQL_QUERY -> if (!gqlToken.isNullOrBlank()) { api = C.GQL_QUERY; gqlQueryLoad() } else throw Exception()
                                    C.GQL -> if (!gqlToken.isNullOrBlank()) { api = C.GQL; gqlLoad() } else throw Exception()
                                    else -> throw Exception()
                                }
                            } catch (e: Exception) {
                                listOf()
                            }
                        }
                    }
                    if (remote.isNotEmpty()) {
                        for (i in remote) {
                            val item = list.find { it.channelId == i.channelId }
                            if (item == null) {
                                i.followAccount = true
                                list.add(i)
                            } else {
                                item.followAccount = true
                                item.followedAt = i.followedAt
                                item.lastBroadcast = i.lastBroadcast
                            }
                        }
                    }
                    val allIds = mutableListOf<String>()
                    for (i in list) {
                        if (i.profileImageUrl == null || i.profileImageUrl?.contains("image_manager_disk_cache") == true || i.lastBroadcast == null) {
                            i.channelId?.let { allIds.add(it) }
                        }
                    }
                    if (allIds.isNotEmpty()) {
                        for (ids in allIds.chunked(100)) {
                            val get = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(UsersLastBroadcastQuery(Optional.Present(ids))).execute().data?.users
                            if (get != null) {
                                for (user in get) {
                                    val item = list.find { it.channelId == user?.id }
                                    if (item != null) {
                                        if (item.followLocal) {
                                            if (item.profileImageUrl == null || item.profileImageUrl?.contains("image_manager_disk_cache") == true) {
                                                val appContext = XtraApp.INSTANCE.applicationContext
                                                item.channelId?.let { id -> user?.profileImageURL?.let { profileImageURL -> updateLocalUser(appContext, id, profileImageURL) } }
                                            }
                                        } else {
                                            if (item.profileImageUrl == null) {
                                                item.profileImageUrl = user?.profileImageURL
                                            }
                                        }
                                        item.lastBroadcast = user?.lastBroadcast?.startedAt?.toString()
                                    }
                                }
                            }
                        }
                    }
                    if (order == FollowOrderEnum.ASC) {
                        when (sort) {
                            FollowSortEnum.FOLLOWED_AT -> list.sortedWith(compareBy(nullsLast()) { it.followedAt })
                            FollowSortEnum.LAST_BROADCAST -> list.sortedWith(compareBy(nullsLast()) { it.lastBroadcast })
                            else -> list.sortedWith(compareBy(nullsLast()) { it.channelLogin })
                        }
                    } else {
                        when (sort) {
                            FollowSortEnum.FOLLOWED_AT -> list.sortedWith(compareByDescending(nullsFirst()) { it.followedAt })
                            FollowSortEnum.LAST_BROADCAST -> list.sortedWith(compareByDescending(nullsFirst()) { it.lastBroadcast })
                            else -> list.sortedWith(compareByDescending(nullsFirst()) { it.channelLogin })
                        }
                    }
                }
            } catch (e: Exception) {
                listOf()
            }
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = if (!offset.isNullOrBlank() && (api == C.HELIX || nextPage)) {
                    nextPage = false
                    (params.key ?: 1) + 1
                } else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun helixLoad(): List<User> {
        val get = helixApi.getUserFollows(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            userId = userId,
            limit = 100,
            offset = offset
        )
        offset = get.cursor
        return get.data
    }

    private suspend fun gqlQueryLoad(): List<User> {
        val get1 = apolloClient.newBuilder().apply {
            gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) }
            gqlToken?.let { addHttpHeader("Authorization", TwitchApiHelper.addTokenPrefixGQL(it)) }
        }.build().query(UserFollowedUsersQuery(
            first = Optional.Present(100),
            after = Optional.Present(offset)
        )).execute().data!!.user!!.follows!!
        val get = get1.edges!!
        val list = mutableListOf<User>()
        for (i in get) {
            list.add(User(
                channelId = i?.node?.id,
                channelLogin = i?.node?.login,
                channelName = i?.node?.displayName,
                followedAt = i?.followedAt?.toString(),
                lastBroadcast = i?.node?.lastBroadcast?.startedAt?.toString(),
                profileImageUrl = i?.node?.profileImageURL,
            ))
        }
        offset = get.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(): List<User> {
        val get = gqlApi.loadFollowedChannels(gqlHeaders, gqlToken, 100, offset)
        offset = get.cursor
        nextPage = get.hasNextPage ?: true
        return get.data
    }

    private suspend fun loadRange(): List<User> {
        val list = if (!offset.isNullOrBlank()) {
            when (api) {
                C.HELIX -> helixLoad()
                C.GQL_QUERY -> if (nextPage) gqlQueryLoad() else listOf()
                C.GQL -> if (nextPage) gqlLoad() else listOf()
                else -> listOf()
            }
        } else listOf()
        val allIds = mutableListOf<String>()
        for (i in list) {
            if (i.profileImageUrl == null || i.lastBroadcast == null) {
                i.channelId?.let { allIds.add(it) }
            }
        }
        if (allIds.isNotEmpty()) {
            for (ids in allIds.chunked(100)) {
                val get = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(UsersLastBroadcastQuery(Optional.Present(ids))).execute().data?.users
                if (get != null) {
                    for (user in get) {
                        val item = list.find { it.channelId == user?.id }
                        if (item != null) {
                            if (item.followLocal) {
                                if (item.profileImageUrl == null || item.profileImageUrl?.contains("image_manager_disk_cache") == true) {
                                    val appContext = XtraApp.INSTANCE.applicationContext
                                    item.channelId?.let { id -> user?.profileImageURL?.let { profileImageURL -> updateLocalUser(appContext, id, profileImageURL) } }
                                }
                            } else {
                                if (item.profileImageUrl == null) {
                                    item.profileImageUrl = user?.profileImageURL
                                }
                            }
                            item.lastBroadcast = user?.lastBroadcast?.startedAt?.toString()
                        }
                    }
                }
            }
        }
        return list
    }

    private fun updateLocalUser(context: Context, userId: String, profileImageURL: String) {
        GlobalScope.launch {
            try {
                try {
                    Glide.with(context)
                        .asBitmap()
                        .load(TwitchApiHelper.getTemplateUrl(profileImageURL, "profileimage"))
                        .into(object: CustomTarget<Bitmap>() {
                            override fun onLoadCleared(placeholder: Drawable?) {

                            }

                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                DownloadUtils.savePng(context, "profile_pics", userId, resource)
                            }
                        })
                } catch (e: Exception) {

                }
                val downloadedLogo = File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${userId}.png").absolutePath
                localFollowsChannel.getFollowByUserId(userId)?.let { localFollowsChannel.updateFollow(it.apply {
                    channelLogo = downloadedLogo }) }
                for (i in offlineRepository.getVideosByUserId(userId.toInt())) {
                    offlineRepository.updateVideo(i.apply {
                        channelLogo = downloadedLogo })
                }
                for (i in bookmarksRepository.getBookmarksByUserId(userId)) {
                    bookmarksRepository.updateBookmark(i.apply {
                        userLogo = downloadedLogo })
                }
            } catch (e: Exception) {

            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, User>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
