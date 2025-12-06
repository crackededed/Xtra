package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository

class TeamMembersDataSource(
    private val teamName: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, Stream>() {
    private var getLiveMembers = true
    private val liveMemberIds = mutableListOf<String>()
    private var liveOffset: String? = null
    private var offset: String? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            if (getLiveMembers) {
                val response = graphQLRepository.loadQueryTeamLiveMembers(networkLibrary, gqlHeaders, teamName!!, params.loadSize, liveOffset)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                val data = response.data!!.team!!.liveMembers
                val items = data?.edges
                val list = items?.mapNotNull { item ->
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
                            profileImageUrl = it.profileImageURL,
                            tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                        )
                    }
                } ?: emptyList()
                liveOffset = items?.lastOrNull()?.cursor?.toString()
                val nextPage = data?.pageInfo?.hasNextPage != false
                getLiveMembers = !liveOffset.isNullOrBlank() && nextPage
                liveMemberIds.addAll(list.mapNotNull { it.channelId })
                if (getLiveMembers) {
                    LoadResult.Page(
                        data = list,
                        prevKey = null,
                        nextKey = (params.key ?: 1) + 1
                    )
                } else {
                    val data = response.data!!.team!!.members
                    val items = data?.edges
                    val members = items?.mapNotNull { item ->
                        item?.node?.let {
                            if (it.id != null && !liveMemberIds.contains(it.id)) {
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
                                    profileImageUrl = it.profileImageURL,
                                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                                )
                            } else null
                        }
                    } ?: emptyList()
                    offset = items?.lastOrNull()?.cursor?.toString()
                    val nextPage = data?.pageInfo?.hasNextPage != false
                    LoadResult.Page(
                        data = list + members,
                        prevKey = null,
                        nextKey = if (!offset.isNullOrBlank() && nextPage) {
                            (params.key ?: 1) + 1
                        } else null
                    )
                }
            } else {
                val response = graphQLRepository.loadQueryTeamMembers(networkLibrary, gqlHeaders, teamName!!, params.loadSize, offset)
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let { return LoadResult.Error(Exception(it.message)) }
                }
                val data = response.data!!.team!!.members
                val items = data?.edges
                val list = items?.mapNotNull { item ->
                    item?.node?.let {
                        if (it.id != null && !liveMemberIds.contains(it.id)) {
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
                                profileImageUrl = it.profileImageURL,
                                tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name }
                            )
                        } else null
                    }
                } ?: emptyList()
                offset = items?.lastOrNull()?.cursor?.toString()
                val nextPage = data?.pageInfo?.hasNextPage != false
                LoadResult.Page(
                    data = list,
                    prevKey = null,
                    nextKey = if (!offset.isNullOrBlank() && nextPage) {
                        (params.key ?: 1) + 1
                    } else null
                )
            }
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