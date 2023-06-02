package com.github.andreyasadchy.xtra.ui.search.tags

import android.content.Context
import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.datasource.TagsDataSourceGQL
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class TagSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val graphQLRepository: GraphQLRepository,
    savedStateHandle: SavedStateHandle) : ViewModel() {

    private val args = TagSearchFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = query.flatMapLatest { query ->
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
        ) {
            TagsDataSourceGQL(
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                getGameTags = args.getGameTags,
                query = query,
                api = graphQLRepository)
        }.flow
    }.cachedIn(viewModelScope)

    fun setQuery(query: String) {
        if (this.query.value != query) {
            this.query.value = query
        }
    }
}