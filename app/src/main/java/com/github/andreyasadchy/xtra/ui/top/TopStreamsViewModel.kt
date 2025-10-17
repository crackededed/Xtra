package com.github.andreyasadchy.xtra.ui.top

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.type.StreamSort
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentArgs
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
class TopStreamsViewModel @Inject constructor(
    @ApplicationContext applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val filter = MutableStateFlow<Filter?>(null)

    val sort: String
        get() = filter.value?.sort ?: StreamsSortDialog.Companion.SORT_VIEWERS

    val sortText = MutableStateFlow<CharSequence?>(null)

    val languages: Array<String>
        get() = filter.value?.languages ?: emptyArray()

    val filtersText = MutableStateFlow<CharSequence?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
                PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
            } else {
                PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
            }
        ) {
            StreamsDataSource(
                gqlQuerySort = when (sort) {
                    StreamsSortDialog.Companion.SORT_VIEWERS -> StreamSort.VIEWER_COUNT
                    StreamsSortDialog.Companion.SORT_VIEWERS_ASC -> StreamSort.VIEWER_COUNT_ASC
                    StreamsSortDialog.Companion.RECENT -> StreamSort.RECENT
                    else -> StreamSort.VIEWER_COUNT
                },
                gqlSort = when (sort) {
                    StreamsSortDialog.Companion.SORT_VIEWERS -> "VIEWER_COUNT"
                    StreamsSortDialog.Companion.SORT_VIEWERS_ASC -> "VIEWER_COUNT_ASC"
                    StreamsSortDialog.Companion.RECENT -> "RECENT"
                    else -> "VIEWER_COUNT"
                },
                tags = args.tags?.toList(),
                languages = languages.toList(),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
                graphQLRepository = graphQLRepository,
                helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
                helixRepository = helixRepository,
                enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                apiPref = applicationContext.prefs().getString(C.API_PREFS_STREAMS, null)?.split(',') ?: TwitchApiHelper.streamsApiDefaults,
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            )
        }.flow
    }.cachedIn(viewModelScope)

    fun setFilter(sort: String?, languages: Array<String>?) {
        filter.value = Filter(sort, languages)
    }

    class Filter(
        val sort: String?,
        val languages: Array<String>?,
    )
}
