package com.github.andreyasadchy.xtra.ui.game.clips

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.SortGame
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.SortGameRepository
import com.github.andreyasadchy.xtra.repository.datasource.GameClipsDataSource
import com.github.andreyasadchy.xtra.type.ClipsPeriod
import com.github.andreyasadchy.xtra.type.Language
import com.github.andreyasadchy.xtra.ui.common.VideosSortDialog
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
class GameClipsViewModel @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val sortGameRepository: SortGameRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val filter = MutableStateFlow<Filter?>(null)
    val sortText = MutableStateFlow<CharSequence?>(null)
    val filtersText = MutableStateFlow<CharSequence?>(null)

    val period: String
        get() = filter.value?.period ?: VideosSortDialog.PERIOD_WEEK
    val languages: Array<String>
        get() = filter.value?.languages ?: emptyArray()

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 20, prefetchDistance = 3, initialLoadSize = 20)
        ) {
            val started = when (period) {
                VideosSortDialog.PERIOD_ALL -> null
                else -> TwitchApiHelper.getClipTime(
                    when (period) {
                        VideosSortDialog.PERIOD_DAY -> 1
                        VideosSortDialog.PERIOD_WEEK -> 7
                        VideosSortDialog.PERIOD_MONTH -> 30
                        else -> 7
                    }
                )
            }
            val ended = when (period) {
                VideosSortDialog.PERIOD_ALL -> null
                else -> TwitchApiHelper.getClipTime(0)
            }
            val gqlQueryPeriod = when (period) {
                VideosSortDialog.PERIOD_DAY -> ClipsPeriod.LAST_DAY
                VideosSortDialog.PERIOD_WEEK -> ClipsPeriod.LAST_WEEK
                VideosSortDialog.PERIOD_MONTH -> ClipsPeriod.LAST_MONTH
                VideosSortDialog.PERIOD_ALL -> ClipsPeriod.ALL_TIME
                else -> ClipsPeriod.LAST_WEEK
            }
            val gqlPeriod = when (period) {
                VideosSortDialog.PERIOD_DAY -> "LAST_DAY"
                VideosSortDialog.PERIOD_WEEK -> "LAST_WEEK"
                VideosSortDialog.PERIOD_MONTH -> "LAST_MONTH"
                VideosSortDialog.PERIOD_ALL -> "ALL_TIME"
                else -> "LAST_WEEK"
            }
            GameClipsDataSource(
                gameId = args.gameId,
                gameSlug = args.gameSlug,
                gameName = args.gameName,
                gqlQueryLanguages = languages.ifEmpty { null }?.mapNotNull { language ->
                    Language.entries.find { it.rawValue == language }
                },
                gqlQueryPeriod = gqlQueryPeriod,
                gqlLanguages = languages.ifEmpty { null }?.toList(),
                gqlPeriod = gqlPeriod,
                startedAt = started,
                endedAt = ended,
                helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
                helixRepository = helixRepository,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
                graphQLRepository = graphQLRepository,
                enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                apiPref = (applicationContext.prefs().getString(C.API_PREFS_GAME_CLIPS, null) ?: C.DEFAULT_API_PREFS_GAME_CLIPS).split(',').mapNotNull {
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

    suspend fun deleteSortGame(item: SortGame) {
        sortGameRepository.delete(item)
    }

    fun setFilter(period: String?, languages: Array<String>?) {
        filter.value = Filter(period, languages)
    }

    class Filter(
        val period: String?,
        val languages: Array<String>?,
    )
}
