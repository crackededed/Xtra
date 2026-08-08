package com.github.andreyasadchy.xtra.repository.datasource

import android.net.http.HttpEngine
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.db.BookmarkIgnoredUsersDao
import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class FollowedChannelsDataSourceTest {

    private val testJson = Json.Default

    @Test
    fun subsequentLoadReturnsApiItemsAndNextKey() = runBlocking {
        val first = User(
            id = "first",
            login = "first",
            profileImageURL = "https://example.com/first.png",
            lastBroadcast = "2026-08-08T00:00:00Z",
        )
        val second = User(
            id = "second",
            login = "second",
            profileImageURL = "https://example.com/second.png",
            lastBroadcast = "2026-08-08T00:01:00Z",
        )
        val source = dataSource {
            PagingSource.LoadResult.Page(
                data = listOf(first, second),
                prevKey = 1,
                nextKey = 3,
            )
        }

        val result = source.load(appendParams())

        assertTrue(result is PagingSource.LoadResult.Page)
        result as PagingSource.LoadResult.Page
        assertEquals(listOf(first, second), result.data)
        assertEquals(3, result.nextKey)
    }

    @Test
    fun subsequentLoadPropagatesApiErrors() = runBlocking {
        val expected = IllegalStateException("offline")
        val source = dataSource {
            PagingSource.LoadResult.Error(expected)
        }

        val result = source.load(appendParams())

        assertTrue(result is PagingSource.LoadResult.Error)
        result as PagingSource.LoadResult.Error
        assertSame(expected, result.throwable)
    }

    private fun appendParams(): PagingSource.LoadParams<Int> {
        return PagingSource.LoadParams.Append(
            key = 2,
            loadSize = 100,
            placeholdersEnabled = false,
        )
    }

    private fun dataSource(
        pageLoader: suspend (PagingSource.LoadParams<Int>) -> PagingSource.LoadResult<Int, User>,
    ): FollowedChannelsDataSource {
        val offlineVideosDao = noOpDao<OfflineVideosDao>()
        val bookmarksDao = noOpDao<BookmarksDao>()
        return FollowedChannelsDataSource(
            sort = "last_broadcast",
            order = "desc",
            userId = null,
            localChannelFollowsRepository = LocalChannelFollowsRepository(
                noOpDao(),
                offlineVideosDao,
                bookmarksDao,
            ),
            offlineVideosRepository = OfflineVideosRepository(offlineVideosDao, bookmarksDao),
            bookmarksRepository = BookmarksRepository(
                bookmarksDao,
                noOpDao<BookmarkIgnoredUsersDao>(),
                offlineVideosDao,
            ),
            gqlHeaders = emptyMap(),
            graphQLRepository = GraphQLRepository(
                httpEngine = lazy<HttpEngine?> { null },
                cronetEngine = lazy<CronetEngine?> { null },
                cronetExecutor = lazy { Executors.newSingleThreadExecutor() },
                okHttpClient = lazy { OkHttpClient() },
                json = testJson,
            ),
            helixHeaders = emptyMap(),
            helixRepository = HelixRepository(
                httpEngine = lazy<HttpEngine?> { null },
                cronetEngine = lazy<CronetEngine?> { null },
                cronetExecutor = lazy { Executors.newSingleThreadExecutor() },
                okHttpClient = lazy { OkHttpClient() },
                json = testJson,
            ),
            enableIntegrity = false,
            networkLibrary = null,
            pageLoaderForTest = pageLoader,
            initialOffsetForTest = "cursor",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> noOpDao(): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as T
    }
}
