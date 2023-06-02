package com.github.andreyasadchy.xtra.ui.search.streams

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource
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
class StreamSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient) : ViewModel() {

    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = query.flatMapLatest { query ->
        Pager(
            if (context.prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
                PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
            } else {
                PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
            }
        ) {
            SearchStreamsDataSource(
                query = query,
                helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                helixToken = Account.get(context).helixToken,
                helixApi = helix,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                apolloClient = apolloClient,
                apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_SEARCH_STREAMS, ""), TwitchApiHelper.searchStreamsApiDefaults))
        }.flow
    }.cachedIn(viewModelScope)

    fun setQuery(query: String) {
        if (this.query.value != query) {
            this.query.value = query
        }
    }
}