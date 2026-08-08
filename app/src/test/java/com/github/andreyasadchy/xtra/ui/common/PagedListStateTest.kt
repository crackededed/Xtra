package com.github.andreyasadchy.xtra.ui.common

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

class PagedListStateTest {

    @Test
    fun emptyRefreshShowsLoading() {
        assertEquals(
            PagedListContentState.Loading,
            pagedListContentState(LoadState.Loading, itemCount = 0),
        )
    }

    @Test
    fun emptyRefreshErrorShowsError() {
        assertEquals(
            PagedListContentState.Error,
            pagedListContentState(LoadState.Error(IllegalStateException("offline")), itemCount = 0),
        )
    }

    @Test
    fun successfulEmptyRefreshShowsEmpty() {
        assertEquals(
            PagedListContentState.Empty,
            pagedListContentState(LoadState.NotLoading(endOfPaginationReached = true), itemCount = 0),
        )
    }

    @Test
    fun existingContentWinsOverRefreshError() {
        assertEquals(
            PagedListContentState.Content,
            pagedListContentState(LoadState.Error(IllegalStateException("offline")), itemCount = 4),
        )
    }
}
