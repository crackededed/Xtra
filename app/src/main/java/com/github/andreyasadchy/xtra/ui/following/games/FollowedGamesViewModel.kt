package com.github.andreyasadchy.xtra.ui.following.games

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
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
    @ApplicationContext applicationContext: Context,
    private val localFollowsGame: LocalFollowGameRepository,
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    ) {
        FollowedGamesDataSource(
            localFollowsGame = localFollowsGame,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            apiPref = (applicationContext.prefs().getString(C.API_PREFS_FOLLOWED_GAMES, null) ?: C.DEFAULT_API_PREFS_FOLLOWED_GAMES).split(',').mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[1] != "0"
                if (enabled) key else null
            },
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
        )
    }.flow.cachedIn(viewModelScope)
}
