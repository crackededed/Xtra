package com.github.andreyasadchy.xtra.ui.videos.game

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.SortGame
import com.github.andreyasadchy.xtra.model.ui.*
import com.github.andreyasadchy.xtra.repository.*
import com.github.andreyasadchy.xtra.repository.datasource.GameVideosDataSource
import com.github.andreyasadchy.xtra.type.BroadcastType
import com.github.andreyasadchy.xtra.type.VideoSort
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.videos.BaseVideosViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class GameVideosViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val sortGameRepository: SortGameRepository,
    repository: ApiRepository,
    playerRepository: PlayerRepository,
    bookmarksRepository: BookmarksRepository,
    savedStateHandle: SavedStateHandle) : BaseVideosViewModel(playerRepository, bookmarksRepository, repository) {

    private val _sortText = MutableLiveData<CharSequence>()
    val sortText: LiveData<CharSequence>
        get() = _sortText

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val filter = MutableStateFlow(setGame(context))

    val sort: VideoSortEnum
        get() = filter.value.sort
    val period: VideoPeriodEnum
        get() = filter.value.period
    val type: BroadcastTypeEnum
        get() = filter.value.broadcastType
    val languageIndex: Int
        get() = filter.value.languageIndex
    val saveSort: Boolean
        get() = filter.value.saveSort == true

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        ) {
            with(filter) {
                val langValues = context.resources.getStringArray(R.array.gqlUserLanguageValues).toList()
                val language = if (languageIndex != 0) {
                    langValues.elementAt(languageIndex)
                } else null
                GameVideosDataSource(
                    gameId = args.gameId,
                    gameName = args.gameName,
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    helixPeriod = period,
                    helixBroadcastTypes = broadcastType,
                    helixLanguage = language?.lowercase(),
                    helixSort = sort,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    gqlQueryLanguages = if (language != null) {
                        listOf(language)
                    } else null,
                    gqlQueryType = when (broadcastType) {
                        BroadcastTypeEnum.ARCHIVE -> BroadcastType.ARCHIVE
                        BroadcastTypeEnum.HIGHLIGHT -> BroadcastType.HIGHLIGHT
                        BroadcastTypeEnum.UPLOAD -> BroadcastType.UPLOAD
                        else -> null },
                    gqlQuerySort = when (sort) { VideoSortEnum.TIME -> VideoSort.TIME else -> VideoSort.VIEWS },
                    gqlType = if (broadcastType == BroadcastTypeEnum.ALL) { null }
                    else { broadcastType.value.uppercase() },
                    gqlSort = sort.value.uppercase(),
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_GAME_VIDEOS, ""), TwitchApiHelper.gameVideosApiDefaults))
            }
        }.flow
    }.cachedIn(viewModelScope)

    private fun setGame(context: Context): Filter {
        var sortValues = args.gameId?.let { runBlocking { sortGameRepository.getById(it) } }
        if (sortValues?.saveSort != true) {
            sortValues = runBlocking { sortGameRepository.getById("default") }
        }
        _sortText.value = context.getString(R.string.sort_and_period,
            when (sortValues?.videoSort) {
                VideoSortEnum.TIME.value -> context.getString(R.string.upload_date)
                else -> context.getString(R.string.view_count)
            },
            when (sortValues?.videoPeriod) {
                VideoPeriodEnum.DAY.value -> context.getString(R.string.today)
                VideoPeriodEnum.MONTH.value -> context.getString(R.string.this_month)
                VideoPeriodEnum.ALL.value -> context.getString(R.string.all_time)
                else -> context.getString(R.string.this_week)
            }
        )
        return Filter(
            saveSort = sortValues?.saveSort,
            sort = when (sortValues?.videoSort) {
                VideoSortEnum.TIME.value -> VideoSortEnum.TIME
                else -> VideoSortEnum.VIEWS
            },
            period = if (Account.get(context).helixToken.isNullOrBlank()) {
                VideoPeriodEnum.WEEK
            } else {
                when (sortValues?.videoPeriod) {
                    VideoPeriodEnum.DAY.value -> VideoPeriodEnum.DAY
                    VideoPeriodEnum.MONTH.value -> VideoPeriodEnum.MONTH
                    VideoPeriodEnum.ALL.value -> VideoPeriodEnum.ALL
                    else -> VideoPeriodEnum.WEEK
                }
            },
            broadcastType = when (sortValues?.videoType) {
                BroadcastTypeEnum.ARCHIVE.value -> BroadcastTypeEnum.ARCHIVE
                BroadcastTypeEnum.HIGHLIGHT.value -> BroadcastTypeEnum.HIGHLIGHT
                BroadcastTypeEnum.UPLOAD.value -> BroadcastTypeEnum.UPLOAD
                else -> BroadcastTypeEnum.ALL
            },
            languageIndex = sortValues?.videoLanguageIndex ?: 0
        )
    }

    fun filter(context: Context, sort: VideoSortEnum, period: VideoPeriodEnum, type: BroadcastTypeEnum, languageIndex: Int, text: CharSequence, saveSort: Boolean, saveDefault: Boolean) {
        filter.value = filter.value.copy(saveSort = saveSort, sort = sort, period = period, broadcastType = type, languageIndex = languageIndex)
        _sortText.value = text
        viewModelScope.launch {
            val sortValues = args.gameId?.let { sortGameRepository.getById(it) }
            if (saveSort) {
                sortValues?.apply {
                    this.saveSort = true
                    videoSort = sort.value
                    if (!Account.get(context).helixToken.isNullOrBlank()) videoPeriod = period.value
                    videoType = type.value
                    videoLanguageIndex = languageIndex
                } ?: args.gameId?.let { SortGame(
                    id = it,
                    saveSort = true,
                    videoSort = sort.value,
                    videoPeriod = if (Account.get(context).helixToken.isNullOrBlank()) null else period.value,
                    videoType = type.value,
                    videoLanguageIndex = languageIndex)
                }
            } else {
                sortValues?.apply {
                    this.saveSort = false
                }
            }?.let { sortGameRepository.save(it) }
            if (saveDefault) {
                (sortValues?.apply {
                    this.saveSort = saveSort
                } ?: args.gameId?.let { SortGame(
                    id = it,
                    saveSort = saveSort)
                })?.let { sortGameRepository.save(it) }
                val sortDefaults = sortGameRepository.getById("default")
                (sortDefaults?.apply {
                    videoSort = sort.value
                    if (!Account.get(context).helixToken.isNullOrBlank()) videoPeriod = period.value
                    videoType = type.value
                    videoLanguageIndex = languageIndex
                } ?: SortGame(
                    id = "default",
                    videoSort = sort.value,
                    videoPeriod = if (Account.get(context).helixToken.isNullOrBlank()) null else period.value,
                    videoType = type.value,
                    videoLanguageIndex = languageIndex
                )).let { sortGameRepository.save(it) }
            }
        }
        if (saveDefault != context.prefs().getBoolean(C.SORT_DEFAULT_GAME_VIDEOS, false)) {
            context.prefs().edit { putBoolean(C.SORT_DEFAULT_GAME_VIDEOS, saveDefault) }
        }
    }

    private data class Filter(
        val saveSort: Boolean?,
        val sort: VideoSortEnum = VideoSortEnum.VIEWS,
        val period: VideoPeriodEnum = VideoPeriodEnum.WEEK,
        val broadcastType: BroadcastTypeEnum = BroadcastTypeEnum.ALL,
        val languageIndex: Int = 0)
}
