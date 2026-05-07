package com.github.andreyasadchy.xtra.ui.following.streams

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

class FollowedStreamsViewModel(
    applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val flow = Pager(
        if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
        } else {
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        }
    ) {
        FollowedStreamsDataSource(
            userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
            localChannelFollowsRepository = localChannelFollowsRepository,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }.flow.cachedIn(viewModelScope)

    companion object {
        val FollowedStreamsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                FollowedStreamsViewModel(application.applicationContext, xtraModule.localChannelFollowsRepository, xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}