package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.UserFollowedVideosQuery
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.type.BroadcastType
import com.github.andreyasadchy.xtra.type.VideoSort
import com.github.andreyasadchy.xtra.util.C

class FollowedVideosDataSource(
    private val gqlHeaders: Map<String, String>,
    private val gqlQueryType: BroadcastType?,
    private val gqlQuerySort: VideoSort?,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>) : PagingSource<Int, Video>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Video> {
        return try {
            val response = try {
                when (apiPref.elementAt(0)?.second) {
                    C.GQL_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) { api = C.GQL_QUERY; gqlQueryLoad(params) } else throw Exception()
                    C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && gqlQueryType == BroadcastType.ARCHIVE && gqlQuerySort == VideoSort.TIME) { api = C.GQL; gqlLoad(params) } else throw Exception()
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref.elementAt(1)?.second) {
                        C.GQL_QUERY -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) { api = C.GQL_QUERY; gqlQueryLoad(params) } else throw Exception()
                        C.GQL -> if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && gqlQueryType == BroadcastType.ARCHIVE && gqlQuerySort == VideoSort.TIME) { api = C.GQL; gqlLoad(params) } else throw Exception()
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    listOf()
                }
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

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Video> {
        val get1 = apolloClient.newBuilder().apply {
            gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) }
        }.build().query(UserFollowedVideosQuery(
            sort = Optional.Present(gqlQuerySort),
            type = Optional.Present(gqlQueryType?.let { listOf(it) }),
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.user!!.followedVideos!!
        val get = get1.edges!!
        val list = mutableListOf<Video>()
        for (i in get) {
            val tags = mutableListOf<Tag>()
            i?.node?.contentTags?.forEach { tag ->
                tags.add(Tag(
                    id = tag.id,
                    name = tag.localizedName
                ))
            }
            list.add(Video(
                id = i?.node?.id,
                channelId = i?.node?.owner?.id,
                channelLogin = i?.node?.owner?.login,
                channelName = i?.node?.owner?.displayName,
                gameId = i?.node?.game?.id,
                gameName = i?.node?.game?.displayName,
                type = i?.node?.broadcastType?.toString(),
                title = i?.node?.title,
                viewCount = i?.node?.viewCount,
                uploadDate = i?.node?.createdAt?.toString(),
                duration = i?.node?.lengthSeconds?.toString(),
                thumbnailUrl = i?.node?.previewThumbnailURL,
                profileImageUrl = i?.node?.owner?.profileImageURL,
                tags = tags,
                animatedPreviewURL =  i?.node?.animatedPreviewURL
            ))
        }
        offset = get.lastOrNull()?.cursor?.toString()
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): List<Video> {
        val get = gqlApi.loadFollowedVideos(gqlHeaders, params.loadSize, offset)
        offset = get.cursor
        nextPage = get.hasNextPage ?: true
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Video>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
