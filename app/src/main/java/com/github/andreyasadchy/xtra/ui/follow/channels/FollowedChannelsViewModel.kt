package com.github.andreyasadchy.xtra.ui.follow.channels

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
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
import com.github.andreyasadchy.xtra.model.ui.FollowOrderEnum
import com.github.andreyasadchy.xtra.model.ui.FollowSortEnum
import com.github.andreyasadchy.xtra.repository.*
import com.github.andreyasadchy.xtra.repository.datasource.FollowedChannelsDataSource
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
class FollowedChannelsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helix: HelixApi,
    private val apolloClient: ApolloClient,
    private val sortChannelRepository: SortChannelRepository,
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarksRepository: BookmarksRepository) : ViewModel() {

    private val _sortText = MutableLiveData<CharSequence>()
    val sortText: LiveData<CharSequence>
        get() = _sortText

    private val filter = MutableStateFlow(setUser(context))

    val sort: FollowSortEnum
        get() = filter.value.sort
    val order: FollowOrderEnum
        get() = filter.value.order

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { filter ->
        Pager(
            PagingConfig(pageSize = 15, prefetchDistance = 5, initialLoadSize = 15)
        ) {
            FollowedChannelsDataSource(
                localFollowsChannel = localFollowsChannel,
                offlineRepository = offlineRepository,
                bookmarksRepository = bookmarksRepository,
                userId = Account.get(context).id,
                helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"),
                helixToken = Account.get(context).helixToken,
                helixApi = helix,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                gqlToken = Account.get(context).gqlToken,
                gqlApi = graphQLRepository,
                apolloClient = apolloClient,
                apiPref = TwitchApiHelper.listFromPrefs(context.prefs().getString(C.API_PREF_FOLLOWED_CHANNELS, ""), TwitchApiHelper.followedChannelsApiDefaults),
                sort = filter.sort,
                order = filter.order
            )
        }.flow
    }.cachedIn(viewModelScope)

    private fun setUser(context: Context): Filter {
        val sortValues = runBlocking { sortChannelRepository.getById("followed_channels") }
        _sortText.value = context.getString(R.string.sort_and_order,
            when (sortValues?.videoSort) {
                FollowSortEnum.FOLLOWED_AT.value -> context.getString(R.string.time_followed)
                FollowSortEnum.ALPHABETICALLY.value -> context.getString(R.string.alphabetically)
                else -> context.getString(R.string.last_broadcast)
            },
            when (sortValues?.videoType) {
                FollowOrderEnum.ASC.value -> context.getString(R.string.ascending)
                else -> context.getString(R.string.descending)
            }
        )
        return Filter(
            sort = when (sortValues?.videoSort) {
                FollowSortEnum.FOLLOWED_AT.value -> FollowSortEnum.FOLLOWED_AT
                FollowSortEnum.ALPHABETICALLY.value -> FollowSortEnum.ALPHABETICALLY
                else -> FollowSortEnum.LAST_BROADCAST
            },
            order = when (sortValues?.videoType) {
                FollowOrderEnum.ASC.value -> FollowOrderEnum.ASC
                else -> FollowOrderEnum.DESC
            }
        )
    }

    fun filter(sort: FollowSortEnum, order: FollowOrderEnum, text: CharSequence, saveDefault: Boolean) {
        filter.value = filter.value.copy(sort = sort, order = order)
        _sortText.value = text
        if (saveDefault) {
            viewModelScope.launch {
                val sortDefaults = sortChannelRepository.getById("followed_channels")
                (sortDefaults?.apply {
                    videoSort = sort.value
                    videoType = order.value
                } ?: SortChannel(
                    id = "followed_channels",
                    videoSort = sort.value,
                    videoType = order.value
                )).let { sortChannelRepository.save(it) }
            }
        }
        val appContext = XtraApp.INSTANCE.applicationContext
        if (saveDefault != appContext.prefs().getBoolean(C.SORT_DEFAULT_FOLLOWED_CHANNELS, false)) {
            appContext.prefs().edit { putBoolean(C.SORT_DEFAULT_FOLLOWED_CHANNELS, saveDefault) }
        }
    }

    private data class Filter(
        val sort: FollowSortEnum = FollowSortEnum.LAST_BROADCAST,
        val order: FollowOrderEnum = FollowOrderEnum.DESC)
}
