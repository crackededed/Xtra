package com.github.andreyasadchy.xtra.ui.saved.filters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.repository.SavedFiltersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FiltersViewModel @Inject internal constructor(
    private val savedFiltersRepository: SavedFiltersRepository,
) : ViewModel() {

    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30),
    ) {
        savedFiltersRepository.loadFiltersPagingSource()
    }.flow.cachedIn(viewModelScope)

    fun delete(item: SavedFilter) {
        viewModelScope.launch {
            savedFiltersRepository.deleteFilter(item)
        }
    }
}