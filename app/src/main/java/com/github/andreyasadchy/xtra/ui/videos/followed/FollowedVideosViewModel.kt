package com.github.andreyasadchy.xtra.ui.videos.followed

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.apollographql.apollo3.ApolloClient
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.SortChannel
import com.github.andreyasadchy.xtra.model.ui.BroadcastTypeEnum
import com.github.andreyasadchy.xtra.model.ui.VideoPeriodEnum
import com.github.andreyasadchy.xtra.model.ui.VideoSortEnum
import com.github.andreyasadchy.xtra.repository.*
import com.github.andreyasadchy.xtra.repository.datasource.FollowedVideosDataSource
import com.github.andreyasadchy.xtra.type.BroadcastType
import com.github.andreyasadchy.xtra.type.VideoSort
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
class FollowedVideosViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val apolloClient: ApolloClient,
    private val sortChannelRepository: SortChannelRepository,
    repository: ApiRepository,
    playerRepository: PlayerRepository,
    bookmarksRepository: BookmarksRepository) : BaseVideosViewModel(playerRepository, bookmarksRepository, repository) {

    private val _sortText = MutableLiveData<CharSequence>()
    val sortText: LiveData<CharSequence>
        get() = _sortText

    private val filter = MutableStateFlow(setUser(context))

    val sort: VideoSortEnum
        get() = filter.value.sort
    val period: VideoPeriodEnum
        get() = filter.value.period
    val type: BroadcastTypeEnum
        get() = filter.value.broadcastType

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
        ) {
            with(filter) {
                FollowedVideosDataSource(
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                    gqlToken = Account.get(context).gqlToken,
                    gqlQueryType = when (broadcastType) {
                        BroadcastTypeEnum.ARCHIVE -> BroadcastType.ARCHIVE
                        BroadcastTypeEnum.HIGHLIGHT -> BroadcastType.HIGHLIGHT
                        BroadcastTypeEnum.UPLOAD -> BroadcastType.UPLOAD
                        else -> null },
                    gqlQuerySort = when (sort) { VideoSortEnum.TIME -> VideoSort.TIME else -> VideoSort.VIEWS },
                    gqlApi = graphQLRepository,
                    apolloClient = apolloClient,
                    apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_FOLLOWED_VIDEOS, ""), TwitchApiHelper.followedVideosApiDefaults))
            }
        }.flow
    }.cachedIn(viewModelScope)

    private fun setUser(context: Context): Filter {
        val sortValues = runBlocking { sortChannelRepository.getById("followed_videos") }
        _sortText.value = context.getString(R.string.sort_and_period,
            when (sortValues?.videoSort) {
                VideoSortEnum.VIEWS.value -> context.getString(R.string.view_count)
                else -> context.getString(R.string.upload_date)
            }, context.getString(R.string.all_time)
        )
        return Filter(
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

    fun filter(sort: VideoSortEnum, period: VideoPeriodEnum, type: BroadcastTypeEnum, text: CharSequence, saveDefault: Boolean) {
        filter.value = filter.value.copy(sort = sort, period = period, broadcastType = type)
        _sortText.value = text
        if (saveDefault) {
            viewModelScope.launch {
                val sortDefaults = sortChannelRepository.getById("followed_videos")
                (sortDefaults?.apply {
                    videoSort = sort.value
                    videoType = type.value
                } ?: SortChannel(
                    id = "followed_videos",
                    videoSort = sort.value,
                    videoType = type.value
                )).let { sortChannelRepository.save(it) }
            }
        }
        val appContext = XtraApp.INSTANCE.applicationContext
        if (saveDefault != appContext.prefs().getBoolean(C.SORT_DEFAULT_FOLLOWED_VIDEOS, false)) {
            appContext.prefs().edit { putBoolean(C.SORT_DEFAULT_FOLLOWED_VIDEOS, saveDefault) }
        }
    }

    private data class Filter(
        val sort: VideoSortEnum = VideoSortEnum.TIME,
        val period: VideoPeriodEnum = VideoPeriodEnum.ALL,
        val broadcastType: BroadcastTypeEnum = BroadcastTypeEnum.ALL)
}
