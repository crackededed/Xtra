package com.github.andreyasadchy.xtra.ui.saved.stream_filters
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.StreamFilter
import com.github.andreyasadchy.xtra.repository.StreamFilterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamFiltersViewModel @Inject constructor(
    private val streamFilter: StreamFilterRepository
) : ViewModel() {

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    ) {
        streamFilter.loadStreamFiltersPagingSource()
    }.flow.cachedIn(viewModelScope)

    fun delete(item: StreamFilter) {
        viewModelScope.launch {
            streamFilter.delete(item)
        }
    }
}