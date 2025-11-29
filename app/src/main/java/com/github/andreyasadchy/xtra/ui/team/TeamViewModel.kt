package com.github.andreyasadchy.xtra.ui.team

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.Team
import com.github.andreyasadchy.xtra.model.ui.TeamMember
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.datasource.TeamMembersDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _team = MutableStateFlow<Team?>(null)
    val team: StateFlow<Team?> = _team

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

    fun loadTeam(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        enableIntegrity: Boolean
    ) {
        if (_team.value == null) {
            viewModelScope.launch {
                _team.value = try {
                    val response =
                        graphQLRepository.loadQueryTeam(
                            networkLibrary,
                            gqlHeaders,
                            name = args.teamName
                        )
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            return@launch
                        }
                    }
                    response.data!!.team?.let {
                        Team(
                            id = it.id,
                            backgroundImageURL = it.backgroundImageURL,
                            bannerURL = it.bannerURL,
                            description = it.description,
                            displayName = it.displayName,
                            logoURL = it.logoURL,
                            owner = it.owner?.let { owner ->
                                User(
                                    channelId = owner.id,
                                    channelLogin = owner.login
                                )
                            }
                        )
                    }
                } catch (e: Exception) {
                    try {
                        val response =
                            graphQLRepository.loadTeamsLandingBody(
                                networkLibrary,
                                gqlHeaders,
                                args.teamName
                            )
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "refresh"
                                return@launch
                            }
                        }
                        response.data!!.team.let {
                            Team(
                                id = it.id,
                                backgroundImageURL = it.backgroundImageURL,
                                bannerURL = it.bannerURL,
                                description = it.description,
                                displayName = it.displayName,
                                logoURL = it.logoURL,
                                owner = it.owner?.let { owner ->
                                    User(
                                        channelId = owner.id,
                                        channelLogin = owner.login
                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun retry(networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_team.value == null) {
            loadTeam(networkLibrary, gqlHeaders, enableIntegrity)
        }
    }
}