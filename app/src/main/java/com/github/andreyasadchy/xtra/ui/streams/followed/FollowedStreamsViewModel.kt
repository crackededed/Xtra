package com.github.andreyasadchy.xtra.ui.streams.followed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class FollowedStreamsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val localFollowsChannel: LocalFollowChannelRepository) : ViewModel() {

    val flow = Pager(
        if (context.prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
        } else {
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        }
    ) {
        FollowedStreamsDataSource(
            localFollowsChannel = localFollowsChannel,
            userId = Account.get(context).id,
            helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
            helixToken = Account.get(context).helixToken,
            helixApi = helix,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
            gqlToken = Account.get(context).gqlToken,
            gqlApi = graphQLRepository,
            apolloClient = apolloClient,
            apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_FOLLOWED_STREAMS, ""), TwitchApiHelper.followedStreamsApiDefaults))
    }.flow.cachedIn(viewModelScope)
}