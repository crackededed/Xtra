package com.github.andreyasadchy.xtra.ui.top

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.SortGame
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.SavedFiltersRepository
import com.github.andreyasadchy.xtra.repository.SortGameRepository
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.type.Language
import com.github.andreyasadchy.xtra.type.StreamSort
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
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
    private val sortGameRepository: SortGameRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val filter = MutableStateFlow<Filter?>(null)
    val sortText = MutableStateFlow<CharSequence?>(null)
    val filtersText = MutableStateFlow<CharSequence?>(null)

    val sort: String
        get() = filter.value?.sort ?: StreamsSortDialog.Companion.SORT_VIEWERS
    val tags: Array<String>
        get() = filter.value?.tags ?: emptyArray()
    val languages: Array<String>
        get() = filter.value?.languages ?: emptyArray()

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
                gqlQueryLanguages = languages.ifEmpty { null }?.mapNotNull { language ->
                    Language.entries.find { it.rawValue == language }
                },
                gqlQuerySort = when (sort) {
                    StreamsSortDialog.Companion.SORT_VIEWERS -> StreamSort.VIEWER_COUNT
                    StreamsSortDialog.Companion.SORT_VIEWERS_ASC -> StreamSort.VIEWER_COUNT_ASC
                    StreamsSortDialog.Companion.RECENT -> StreamSort.RECENT
                    else -> StreamSort.VIEWER_COUNT
                },
                gqlLanguages = languages.ifEmpty { null }?.toList(),
                gqlSort = when (sort) {
                    StreamsSortDialog.Companion.SORT_VIEWERS -> "VIEWER_COUNT"
                    StreamsSortDialog.Companion.SORT_VIEWERS_ASC -> "VIEWER_COUNT_ASC"
                    StreamsSortDialog.Companion.RECENT -> "RECENT"
                    else -> "VIEWER_COUNT"
                },
                tags = tags.ifEmpty { null }?.toList(),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
                graphQLRepository = graphQLRepository,
                helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
                helixRepository = helixRepository,
                enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                apiPref = (applicationContext.prefs().getString(C.API_PREFS_STREAMS, null) ?: C.DEFAULT_API_PREFS_STREAMS).split(',').mapNotNull {
                    val split = it.split(':')
                    val key = split[0]
                    val enabled = split[1] != "0"
                    if (enabled) key else null
                },
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            )
        }.flow
    }.cachedIn(viewModelScope)

    suspend fun getSortGame(id: String): SortGame? {
        return sortGameRepository.getById(id)
    }

    suspend fun saveSortGame(item: SortGame) {
        sortGameRepository.save(item)
    }

    suspend fun saveFilters(item: SavedFilter) {
        savedFiltersRepository.saveFilter(item)
    }

    fun setFilter(sort: String?, tags: Array<String>?, languages: Array<String>?) {
        filter.value = Filter(sort, tags, languages)
    }

    class Filter(
        val sort: String?,
        val tags: Array<String>?,
        val languages: Array<String>?,
    )
}
