package com.github.andreyasadchy.xtra.ui.team

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.Team
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.datasource.TeamMembersDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeamViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private val args = TeamFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val team = MutableStateFlow<Team?>(null)

    private var isLoading = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    ) {
        TeamMembersDataSource(
            teamName = args.teamName,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
            graphQLRepository = graphQLRepository,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
        )
    }.flow.cachedIn(viewModelScope)

    fun loadTeamInfo(teamName: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (teamName != null && team.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                val response = try {
                    val response = graphQLRepository.loadQueryTeam(networkLibrary, gqlHeaders, teamName)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            isLoading = false
                            return@launch
                        }
                    }
                    response.data!!.team?.let { team ->
                        Team(
                            displayName = team.displayName,
                            description = team.description,
                            logoUrl = team.logoURL,
                            bannerUrl = team.bannerURL,
                            memberCount = team.members?.totalCount,
                            ownerLogin = team.owner?.login,
                            ownerName = team.owner?.displayName,
                        )
                    }
                } catch (e: Exception) {
                    null
                }
                team.value = response
                isLoading = false
            }
        }
    }
}