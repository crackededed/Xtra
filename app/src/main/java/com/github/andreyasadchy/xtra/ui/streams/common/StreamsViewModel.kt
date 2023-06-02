package com.github.andreyasadchy.xtra.ui.streams.common

import android.content.Context
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.StreamSortEnum
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.type.StreamSort
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class StreamsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    savedStateHandle: SavedStateHandle) : ViewModel() {

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val filter = MutableStateFlow(Filter())

    val sort: StreamSortEnum
        get() = filter.value.sort

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            if (context.prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
                PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
            } else {
                PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
            }
        ) {
            if (args.gameId == null && args.gameName == null) {
                StreamsDataSource(
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    tags = args.tags?.toList(),
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_STREAMS, ""), TwitchApiHelper.streamsApiDefaults)
                )
            } else {
                GameStreamsDataSource(
                    gameId = args.gameId,
                    gameName = args.gameName,
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    gqlQuerySort = when (filter.sort) {
                        StreamSortEnum.VIEWERS_HIGH -> StreamSort.VIEWER_COUNT
                        StreamSortEnum.VIEWERS_LOW -> StreamSort.VIEWER_COUNT_ASC
                        else -> null },
                    gqlSort = filter.sort,
                    tags = args.tags?.toList(),
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_GAME_STREAMS, ""), TwitchApiHelper.gameStreamsApiDefaults)
                )
            }
        }.flow
    }.cachedIn(viewModelScope)

    fun filter(sort: StreamSortEnum) {
        filter.value = filter.value.copy(sort = sort)
    }

    data class Filter(
        val sort: StreamSortEnum = StreamSortEnum.VIEWERS_HIGH
    )
}
