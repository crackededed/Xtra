package com.github.andreyasadchy.xtra.repository.datasource

import android.os.Build
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.ui.common.BookmarksSortDialog.Companion.SORT_CREATED_AT
import com.github.andreyasadchy.xtra.ui.common.BookmarksSortDialog.Companion.SORT_CREATED_AT_ASC
import com.github.andreyasadchy.xtra.ui.common.BookmarksSortDialog.Companion.SORT_EXPIRED_AT_ASC
import com.github.andreyasadchy.xtra.ui.common.BookmarksSortDialog.Companion.SORT_SAVED_AT
import com.github.andreyasadchy.xtra.ui.common.BookmarksSortDialog.Companion.SORT_SAVED_AT_ASC
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar

class BookmarksDataSource(
    private val bookmarks: BookmarksRepository,
    private val sort: String?,
) : PagingSource<Int, Bookmark>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Bookmark> {
        return try {
            val list = when (sort) {
                SORT_EXPIRED_AT_ASC -> bookmarks.loadBookmarks().sortedBy(expirationDateComparator)
                SORT_CREATED_AT ->bookmarks.loadBookmarksCreatedAtDesc()
                SORT_CREATED_AT_ASC -> bookmarks.loadBookmarksCreatedAt()
                SORT_SAVED_AT -> bookmarks.loadBookmarksDesc()
                SORT_SAVED_AT_ASC -> bookmarks.loadBookmarks()
                else -> bookmarks.loadBookmarks().sortedByDescending(expirationDateComparator)
            }
            return LoadResult.Page(
                data = list,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Bookmark>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }

    private val expirationDateComparator: (Bookmark) -> Long = { bookmark ->
        val time = bookmark.createdAt?.let { TwitchApiHelper.parseIso8601DateUTC(it) }
        val days = when (bookmark.userType?.lowercase()) {
            "" -> 14
            "affiliate" -> 14
            else -> 60
        }
        if (time != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = Instant.ofEpochMilli(time).plus(days.toLong(), ChronoUnit.DAYS)
                val diff = Duration.between(Instant.now(), date)
                diff.seconds
            } else {
                val currentTime = Calendar.getInstance().time.time
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = time
                calendar.add(Calendar.DAY_OF_MONTH, days)
                val diff = ((calendar.time.time - currentTime) / 1000)
                diff
            }
        } else Long.MAX_VALUE
    }

}