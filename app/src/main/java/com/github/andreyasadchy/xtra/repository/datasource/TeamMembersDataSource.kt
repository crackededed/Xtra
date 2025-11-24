package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.gql.team.TeamLandingMemberListResponse
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.TeamMember
import com.github.andreyasadchy.xtra.repository.GraphQLRepository

class TeamMembersDataSource(
    private val teamName: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : PagingSource<Int, TeamMember>() {

    private var withLiveMembers = true
    private var withMembers = true
    private var liveMembersCursor: String? = null
    private var membersCursor: String? = null
    private var startedMembers = false

    private val loadedMemberIds = mutableSetOf<String>()

    private fun mapToTeamMembers(edges: List<TeamLandingMemberListResponse.UserEdge>?): List<TeamMember> {
        return edges.orEmpty().mapNotNull { it.node }.map { node ->
            TeamMember(
                id = node.id,
                displayName = node.displayName,
                login = node.login,
                profileImageURL = node.profileImageURL,
                stream = node.stream?.let {
                    Stream(id = it.id, viewerCount = it.viewersCount)
                }
            )
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TeamMember> {

        val response = graphQLRepository.loadTeamLandingMemberList(
            networkLibrary,
            gqlHeaders,
            teamName,
            withLiveMembers,
            withMembers,
            liveMembersCursor,
            membersCursor
        )

        if (enableIntegrity) {
            response.errors?.firstOrNull { it.message == "failed integrity check" }?.let {
                return LoadResult.Error(Exception(it.message))
            }
        }

        response.errors?.firstOrNull { it.message == "service timeout"}?.let {
            return LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = (params.key ?: 1) + 1
            )
        }

        val data = response.data!!.team!!

        val liveEdges = data.liveMembers?.edges.orEmpty()
        val liveMembersList = mapToTeamMembers(liveEdges)

        liveMembersCursor = liveEdges.lastOrNull()?.cursor

        val hasMoreLive = data.liveMembers?.pageInfo?.hasNextPage == true
        if (!hasMoreLive) {
            withLiveMembers = false
            startedMembers = true
            membersCursor = liveMembersCursor
        }

        val membersList = if (startedMembers && withMembers) {
            val edges = data.members?.edges.orEmpty()
            membersCursor = edges.lastOrNull()?.cursor
            if (membersCursor == null) {
                withMembers = false
            }
            mapToTeamMembers(edges)
        } else emptyList()

        val liveMemberIds = liveMembersList.map { it.id }
        loadedMemberIds.addAll(liveMemberIds)

        val nextPage = withLiveMembers ||
                (withMembers && data.members?.pageInfo?.hasNextPage == true)

        return LoadResult.Page(
            data = liveMembersList + membersList.filterNot { it.id in loadedMemberIds },
            prevKey = null,
            nextKey = if (nextPage) (params.key ?: 1) + 1 else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, TeamMember>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor)
        return page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
    }
}