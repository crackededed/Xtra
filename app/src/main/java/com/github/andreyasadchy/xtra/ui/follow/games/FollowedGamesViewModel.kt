package com.github.andreyasadchy.xtra.ui.follow.games

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowGameRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedGamesDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class FollowedGamesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val localFollowsGame: LocalFollowGameRepository) : ViewModel() {

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    ) {
        FollowedGamesDataSource(
            localFollowsGame = localFollowsGame,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
            gqlToken = Account.get(context).gqlToken,
            gqlApi = graphQLRepository,
            apolloClient = apolloClient,
            apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_FOLLOWED_GAMES, ""), TwitchApiHelper.followedGamesApiDefaults))
    }.flow.cachedIn(viewModelScope)
}
