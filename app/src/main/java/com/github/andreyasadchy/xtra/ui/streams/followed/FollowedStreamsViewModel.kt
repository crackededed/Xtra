package com.github.andreyasadchy.xtra.ui.streams.followed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo.ApolloClient
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class FollowedStreamsViewModel @Inject constructor(
    @ApplicationContext applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val localFollowsChannel: LocalFollowChannelRepository,
) : ViewModel() {

    val flow = Pager(
        if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
        } else {
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        }
    ) {
        FollowedStreamsDataSource(
            localFollowsChannel = localFollowsChannel,
            userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixApi = helix,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            gqlApi = graphQLRepository,
            apolloClient = apolloClient,
            checkIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false) && applicationContext.prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true),
            apiPref = applicationContext.prefs().getString(C.API_PREFS_FOLLOWED_STREAMS, null)?.split(',') ?: TwitchApiHelper.followedStreamsApiDefaults
        )
    }.flow.cachedIn(viewModelScope)
}