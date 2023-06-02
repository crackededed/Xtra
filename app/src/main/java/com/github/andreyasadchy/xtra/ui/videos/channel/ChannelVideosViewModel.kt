package com.github.andreyasadchy.xtra.ui.videos.channel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.api.HelixApi
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.SortChannel
import com.github.andreyasadchy.xtra.model.ui.BroadcastTypeEnum
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.model.ui.VideoSortEnum
import com.github.andreyasadchy.xtra.repository.*
import com.github.andreyasadchy.xtra.repository.datasource.ChannelVideosDataSource
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
class ChannelVideosViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val sortChannelRepository: SortChannelRepository,
    repository: ApiRepository,
    playerRepository: PlayerRepository,
    bookmarksRepository: BookmarksRepository,
    savedStateHandle: SavedStateHandle) : BaseVideosViewModel(playerRepository, bookmarksRepository, repository) {

    private val _sortText = MutableLiveData<CharSequence>()
    val sortText: LiveData<CharSequence>
        get() = _sortText

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    private val filter = MutableStateFlow(setChannelId(context))

    val sort: VideoSortEnum
        get() = filter.value.sort
    val period: VideoPeriodEnum
        get() = filter.value.period
    val type: BroadcastTypeEnum
        get() = filter.value.broadcastType
    val saveSort: Boolean
        get() = filter.value.saveSort == true

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        ) {
            with(filter) {
                ChannelVideosDataSource(
                    channelId = args.channelId,
                    channelLogin = args.channelLogin,
                    helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                    helixToken = Account.get(context).helixToken,
                    helixPeriod = period,
                    helixBroadcastTypes = broadcastType,
                    helixSort = sort,
                    helixApi = helix,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
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
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_CHANNEL_VIDEOS, ""), TwitchApiHelper.channelVideosApiDefaults))
            }
        }.flow
    }.cachedIn(viewModelScope)

    private fun setChannelId(context: Context): Filter {
        var sortValues = args.channelId?.let { runBlocking { sortChannelRepository.getById(it) } }
        if (sortValues?.saveSort != true) {
            sortValues = runBlocking { sortChannelRepository.getById("default") }
        }
        _sortText.value = context.getString(R.string.sort_and_period,
            when (sortValues?.videoSort) {
                VideoSortEnum.VIEWS.value -> context.getString(R.string.view_count)
                else -> context.getString(R.string.upload_date)
            }, context.getString(R.string.all_time)
        )
        return Filter(
            saveSort = sortValues?.saveSort,
            sort = when (sortValues?.videoSort) {
                VideoSortEnum.VIEWS.value -> VideoSortEnum.VIEWS
                else -> VideoSortEnum.TIME
            },
            broadcastType = when (sortValues?.videoType) {
                BroadcastTypeEnum.ARCHIVE.value -> BroadcastTypeEnum.ARCHIVE
                BroadcastTypeEnum.HIGHLIGHT.value -> BroadcastTypeEnum.HIGHLIGHT
                BroadcastTypeEnum.UPLOAD.value -> BroadcastTypeEnum.UPLOAD
                else -> BroadcastTypeEnum.ALL
            }
        )
    }

    fun filter(sort: VideoSortEnum, type: BroadcastTypeEnum, text: CharSequence, saveSort: Boolean, saveDefault: Boolean) {
        filter.value = filter.value.copy(saveSort = saveSort, sort = sort, broadcastType = type)
        _sortText.value = text
        viewModelScope.launch {
            val sortValues = args.channelId?.let { sortChannelRepository.getById(it) }
            if (saveSort) {
                sortValues?.apply {
                    this.saveSort = true
                    videoSort = sort.value
                    videoType = type.value
                } ?: args.channelId?.let { SortChannel(
                    id = it,
                    saveSort = true,
                    videoSort = sort.value,
                    videoType = type.value)
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
                    videoSort = sort.value
                    videoType = type.value
                } ?: SortChannel(
                    id = "default",
                    videoSort = sort.value,
                    videoType = type.value
                )).let { sortChannelRepository.save(it) }
            }
        }
        val appContext = XtraApp.INSTANCE.applicationContext
        if (saveDefault != appContext.prefs().getBoolean(C.SORT_DEFAULT_CHANNEL_VIDEOS, false)) {
            appContext.prefs().edit { putBoolean(C.SORT_DEFAULT_CHANNEL_VIDEOS, saveDefault) }
        }
    }

    private data class Filter(
        val saveSort: Boolean?,
        val sort: VideoSortEnum = VideoSortEnum.TIME,
        val period: VideoPeriodEnum = VideoPeriodEnum.ALL,
        val broadcastType: BroadcastTypeEnum = BroadcastTypeEnum.ALL)
}
