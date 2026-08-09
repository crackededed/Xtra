package com.github.andreyasadchy.xtra.ui.common

import androidx.paging.LoadState

internal enum class PagedListContentState {
    Loading,
    Content,
    Empty,
    Error,
}

internal enum class PagedListErrorState {
    Refresh,
    Page,
}

internal fun pagedListContentState(refresh: LoadState, itemCount: Int): PagedListContentState {
    return when {
        itemCount > 0 -> PagedListContentState.Content
        refresh is LoadState.Loading -> PagedListContentState.Loading
        refresh is LoadState.Error -> PagedListContentState.Error
        else -> PagedListContentState.Empty
    }
}

internal fun pagedListErrorState(
    refresh: LoadState,
    append: LoadState,
    prepend: LoadState,
): PagedListErrorState? {
    return when {
        refresh is LoadState.Error -> PagedListErrorState.Refresh
        append is LoadState.Error || prepend is LoadState.Error -> PagedListErrorState.Page
        else -> null
    }
}
