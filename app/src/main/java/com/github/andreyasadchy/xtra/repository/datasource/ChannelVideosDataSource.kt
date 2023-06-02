package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.UserVideosQuery
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.ui.BroadcastTypeEnum
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.model.ui.VideoSortEnum
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.type.BroadcastType
import com.github.andreyasadchy.xtra.type.VideoSort
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class ChannelVideosDataSource(
    private val channelId: String?,
    private val channelLogin: String?,
    private val helixClientId: String?,
    private val helixToken: String?,
    private val helixPeriod: VideoPeriodEnum,
    private val helixBroadcastTypes: BroadcastTypeEnum,
    private val helixSort: VideoSortEnum,
    private val helixApi: HelixApi,
    private val gqlHeaders: Map<String, String>,
    private val gqlQueryType: BroadcastType?,
    private val gqlQuerySort: VideoSort?,
    private val gqlType: String?,
    private val gqlSort: String?,
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
                    C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                    C.GQL_QUERY -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL_QUERY; gqlQueryLoad(params) } else throw Exception()
                    C.GQL -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL; gqlLoad(params) } else throw Exception()
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref.elementAt(1)?.second) {
                        C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                        C.GQL_QUERY -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL_QUERY; gqlQueryLoad(params) } else throw Exception()
                        C.GQL -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL; gqlLoad(params) } else throw Exception()
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    try {
                        when (apiPref.elementAt(2)?.second) {
                            C.HELIX -> if (!helixToken.isNullOrBlank()) { api = C.HELIX; helixLoad(params) } else throw Exception()
                            C.GQL_QUERY -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL_QUERY; gqlQueryLoad(params) } else throw Exception()
                            C.GQL -> if (helixPeriod == VideoPeriodEnum.ALL) { api = C.GQL; gqlLoad(params) } else throw Exception()
                            else -> throw Exception()
                        }
                    } catch (e: Exception) {
                        listOf()
                    }
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

    private suspend fun helixLoad(params: LoadParams<Int>): List<Video> {
        val get = helixApi.getVideos(
            clientId = helixClientId,
            token = helixToken?.let { TwitchApiHelper.addTokenPrefixHelix(it) },
            channelId = channelId,
            period = helixPeriod,
            broadcastType = helixBroadcastTypes,
            sort = helixSort,
            limit = params.loadSize,
            offset = offset
        )
        offset = get.cursor
        return get.data
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Video> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(UserVideosQuery(
            id = if (!channelId.isNullOrBlank()) Optional.Present(channelId) else Optional.Absent,
            login = if (channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) Optional.Present(channelLogin) else Optional.Absent,
            sort = Optional.Present(gqlQuerySort),
            types = Optional.Present(gqlQueryType?.let { listOf(it) }),
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.user!!
        val get = get1.videos!!.edges!!
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
                channelId = channelId,
                channelLogin = get1.login,
                channelName = get1.displayName,
                gameId = i?.node?.game?.id,
                gameName = i?.node?.game?.displayName,
                type = i?.node?.broadcastType?.toString(),
                title = i?.node?.title,
                viewCount = i?.node?.viewCount,
                uploadDate = i?.node?.createdAt?.toString(),
                duration = i?.node?.lengthSeconds?.toString(),
                thumbnailUrl = i?.node?.previewThumbnailURL,
                profileImageUrl = get1.profileImageURL,
                tags = tags,
                animatedPreviewURL =  i?.node?.animatedPreviewURL
            ))
        }
        offset = get.lastOrNull()?.cursor?.toString()
        nextPage = get1.videos.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(params: LoadParams<Int>): List<Video> {
        val get = gqlApi.loadChannelVideos(gqlHeaders, channelLogin, gqlType, gqlSort, params.loadSize, offset)
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
