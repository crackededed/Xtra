package com.github.andreyasadchy.xtra.repository.datasource

import androidx.core.util.Pair
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.github.andreyasadchy.xtra.SearchVideosQuery
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C

class SearchVideosDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val gqlApi: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val apiPref: ArrayList<Pair<Long?, String?>?>?) : PagingSource<Int, Video>() {
    private var api: String? = null
    private var offset: String? = null
    private var nextPage: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Video> {
        return try {
            val response = if (query.isBlank()) listOf() else try {
                when (apiPref?.elementAt(0)?.second) {
                    C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                    C.GQL -> { api = C.GQL; gqlLoad() }
                    else -> throw Exception()
                }
            } catch (e: Exception) {
                try {
                    when (apiPref?.elementAt(1)?.second) {
                        C.GQL_QUERY -> { api = C.GQL_QUERY; gqlQueryLoad(params) }
                        C.GQL -> { api = C.GQL; gqlLoad() }
                        else -> throw Exception()
                    }
                } catch (e: Exception) {
                    listOf()
                }
            }
            LoadResult.Page(
                data = response,
                prevKey = null,
                nextKey = if (!offset.isNullOrBlank() && (api != C.GQL_QUERY || nextPage)) {
                    nextPage = false
                    (params.key ?: 1) + 1
                } else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun gqlQueryLoad(params: LoadParams<Int>): List<Video> {
        val get1 = apolloClient.newBuilder().apply { gqlHeaders.entries.forEach { addHttpHeader(it.key, it.value) } }.build().query(SearchVideosQuery(
            query = query,
            first = Optional.Present(params.loadSize),
            after = Optional.Present(offset)
        )).execute().data!!.searchFor!!.videos!!
        val get = get1.items!!
        val list = mutableListOf<Video>()
        for (i in get) {
            val tags = mutableListOf<Tag>()
            i.contentTags?.forEach { tag ->
                tags.add(Tag(
                    id = tag.id,
                    name = tag.localizedName
                ))
            }
            list.add(Video(
                id = i.id,
                channelId = i.owner?.id,
                channelLogin = i.owner?.login,
                channelName = i.owner?.displayName,
                type = i.broadcastType?.toString(),
                title = i.title,
                viewCount = i.viewCount,
                uploadDate = i.createdAt?.toString(),
                duration = i.lengthSeconds?.toString(),
                thumbnailUrl = i.previewThumbnailURL,
                gameId = i.game?.id,
                gameName = i.game?.displayName,
                profileImageUrl = i.owner?.profileImageURL,
                tags = tags,
                animatedPreviewURL =  i.animatedPreviewURL
            ))
        }
        offset = get1.cursor
        nextPage = get1.pageInfo?.hasNextPage ?: true
        return list
    }

    private suspend fun gqlLoad(): List<Video> {
        val get = gqlApi.loadSearchVideos(gqlHeaders, query, offset)
        offset = get.cursor
        return get.data
    }

    override fun getRefreshKey(state: PagingState<Int, Video>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
