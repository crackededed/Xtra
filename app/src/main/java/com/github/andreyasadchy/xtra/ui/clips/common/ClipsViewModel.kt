package com.github.andreyasadchy.xtra.ui.clips.common

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.SortChannel
import com.github.andreyasadchy.xtra.model.offline.SortGame
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.repository.*
import com.github.andreyasadchy.xtra.repository.datasource.ChannelClipsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GameClipsDataSource
import com.github.andreyasadchy.xtra.type.ClipsPeriod
import com.github.andreyasadchy.xtra.type.Language
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentArgs
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
class ClipsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val sortChannelRepository: SortChannelRepository,
    private val sortGameRepository: SortGameRepository,
    savedStateHandle: SavedStateHandle) : ViewModel() {

    private val _sortText = MutableLiveData<CharSequence>()
    val sortText: LiveData<CharSequence>
        get() = _sortText

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val filter = MutableStateFlow(loadClips(context))

    val period: VideoPeriodEnum
        get() = filter.value.period
    val languageIndex: Int
        get() = filter.value.languageIndex
    val saveSort: Boolean
        get() = filter.value.saveSort == true

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 20, prefetchDistance = 3, initialLoadSize = 20)
        ) {
            val started = when (filter.period) {
                VideoPeriodEnum.ALL -> null
                else -> TwitchApiHelper.getClipTime(filter.period)
            }
            val ended = when (filter.period) {
                VideoPeriodEnum.ALL -> null
                else -> TwitchApiHelper.getClipTime()
            }
            val gqlQueryPeriod = when (filter.period) {
                VideoPeriodEnum.DAY -> ClipsPeriod.LAST_DAY
                VideoPeriodEnum.WEEK -> ClipsPeriod.LAST_WEEK
                VideoPeriodEnum.MONTH -> ClipsPeriod.LAST_MONTH
                else -> ClipsPeriod.ALL_TIME }
            val gqlPeriod = when (filter.period) {
                VideoPeriodEnum.DAY -> "LAST_DAY"
                VideoPeriodEnum.WEEK -> "LAST_WEEK"
                VideoPeriodEnum.MONTH -> "LAST_MONTH"
                else -> "ALL_TIME" }
            if (args.channelId != null || args.channelLogin != null) {
                ChannelClipsDataSource(
                    channelId = args.channelId,
                    channelLogin = args.channelLogin,
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    started_at = started,
                    ended_at = ended,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    gqlQueryPeriod = gqlQueryPeriod,
                    gqlPeriod = gqlPeriod,
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_GAME_CLIPS, ""), TwitchApiHelper.gameClipsApiDefaults))
            } else {
                val langList = mutableListOf<Language>()
                val langValues = context.resources.getStringArray(R.array.gqlUserLanguageValues).toList()
                if (filter.languageIndex != 0) {
                    val item = Language.values().find { lang -> lang.rawValue == langValues.elementAt(filter.languageIndex) }
                    if (item != null) {
                        langList.add(item)
                    }
                }
                GameClipsDataSource(
                    gameId = args.gameId,
                    gameName = args.gameName,
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    started_at = started,
                    ended_at = ended,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    gqlQueryLanguages = langList.ifEmpty { null },
                    gqlQueryPeriod = gqlQueryPeriod,
                    gqlPeriod = gqlPeriod,
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_GAME_CLIPS, ""), TwitchApiHelper.gameClipsApiDefaults))
            }
        }.flow
    }.cachedIn(viewModelScope)

    private fun loadClips(context: Context): Filter {
        var sortValuesGame: SortGame? = null
        var sortValuesChannel: SortChannel? = null
        if (!args.gameId.isNullOrBlank() || !args.gameName.isNullOrBlank()) {
            sortValuesGame = args.gameId?.let { runBlocking { sortGameRepository.getById(it) } }
            if (sortValuesGame?.saveSort != true) {
                sortValuesGame = runBlocking { sortGameRepository.getById("default") }
            }
        } else {
            if (!args.channelId.isNullOrBlank() || !args.channelLogin.isNullOrBlank()) {
                sortValuesChannel = args.channelId?.let { runBlocking { sortChannelRepository.getById(it) } }
                if (sortValuesChannel?.saveSort != true) {
                    sortValuesChannel = runBlocking { sortChannelRepository.getById("default") }
                }
            }
        }
        _sortText.value = context.getString(R.string.sort_and_period, context.getString(R.string.view_count),
            when (sortValuesGame?.clipPeriod ?: sortValuesChannel?.clipPeriod) {
                VideoPeriodEnum.DAY.value -> context.getString(R.string.today)
                VideoPeriodEnum.MONTH.value -> context.getString(R.string.this_month)
                VideoPeriodEnum.ALL.value -> context.getString(R.string.all_time)
                else -> context.getString(R.string.this_week)
            }
        )
        return Filter(
            saveSort = sortValuesGame?.saveSort ?: sortValuesChannel?.saveSort,
            period = when (sortValuesGame?.clipPeriod ?: sortValuesChannel?.clipPeriod) {
                VideoPeriodEnum.DAY.value -> VideoPeriodEnum.DAY
                VideoPeriodEnum.MONTH.value -> VideoPeriodEnum.MONTH
                VideoPeriodEnum.ALL.value -> VideoPeriodEnum.ALL
                else -> VideoPeriodEnum.WEEK
            },
            languageIndex = sortValuesGame?.clipLanguageIndex ?: 0
        )
    }

    fun filter(period: VideoPeriodEnum, languageIndex: Int, text: CharSequence, saveSort: Boolean, saveDefault: Boolean) {
        filter.value = filter.value.copy(saveSort = saveSort, period = period, languageIndex = languageIndex)
        _sortText.value = text
        viewModelScope.launch {
            if (!args.gameId.isNullOrBlank() || !args.gameName.isNullOrBlank()) {
                val sortValues = args.gameId?.let { sortGameRepository.getById(it) }
                if (saveSort) {
                    sortValues?.apply {
                        this.saveSort = true
                        clipPeriod = period.value
                        clipLanguageIndex = languageIndex
                    } ?: args.gameId?.let { SortGame(
                        id = it,
                        saveSort = true,
                        clipPeriod = period.value,
                        clipLanguageIndex = languageIndex)
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
                        clipPeriod = period.value
                        clipLanguageIndex = languageIndex
                    } ?: SortGame(
                        id = "default",
                        clipPeriod = period.value,
                        clipLanguageIndex = languageIndex
                    )).let { sortGameRepository.save(it) }
                }
                val appContext = XtraApp.INSTANCE.applicationContext
                if (saveDefault != appContext.prefs().getBoolean(C.SORT_DEFAULT_GAME_CLIPS, false)) {
                    appContext.prefs().edit { putBoolean(C.SORT_DEFAULT_GAME_CLIPS, saveDefault) }
                }
            } else {
                if (!args.channelId.isNullOrBlank() || !args.channelLogin.isNullOrBlank()) {
                    val sortValues = args.channelId?.let { sortChannelRepository.getById(it) }
                    if (saveSort) {
                        sortValues?.apply {
                            this.saveSort = true
                            clipPeriod = period.value
                        } ?: args.channelId?.let { SortChannel(
                            id = it,
                            saveSort = true,
                            clipPeriod = period.value)
                        }
                    } else {
                        sortValues?.apply {
                            this.saveSort = false
                        }
                    }?.let { sortChannelRepository.save(it) }
                    if (saveDefault) {
                        (sortValues?.apply {
                            this.saveSort = saveSort
                        } ?: args.channelId?.let { SortChannel(
                            id = it,
                            saveSort = saveSort)
                        })?.let { sortChannelRepository.save(it) }
                        val sortDefaults = sortChannelRepository.getById("default")
                        (sortDefaults?.apply {
                            clipPeriod = period.value
                        } ?: SortChannel(
                            id = "default",
                            clipPeriod = period.value
                        )).let { sortChannelRepository.save(it) }
                    }
                    val appContext = XtraApp.INSTANCE.applicationContext
                    if (saveDefault != appContext.prefs().getBoolean(C.SORT_DEFAULT_CHANNEL_CLIPS, false)) {
                        appContext.prefs().edit { putBoolean(C.SORT_DEFAULT_CHANNEL_CLIPS, saveDefault) }
                    }
                }
            }
        }
    }

    private data class Filter(
        val saveSort: Boolean?,
        val period: VideoPeriodEnum = VideoPeriodEnum.WEEK,
        val languageIndex: Int = 0)
}
