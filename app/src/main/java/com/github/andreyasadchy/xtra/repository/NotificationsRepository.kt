package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.NotificationEventsDao
import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class NotificationsRepository(
    private val shownNotificationsDao: ShownNotificationsDao,
    private val notificationUsersDao: NotificationUsersDao,
    private val notificationEventsDao: NotificationEventsDao,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) {

    suspend fun getNewStreams(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
        includeFollowedStreams: Boolean = true,
        preferHelix: Boolean = false,
        enqueueNotificationEvents: Boolean = false,
    ): List<Stream> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Stream>()
        val notificationIds = notificationUsersDao.getAll().map { it.channelId }
        if (notificationIds.isNotEmpty() &&
            gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() &&
            helixHeaders[C.HEADER_TOKEN].isNullOrBlank()
        ) {
            return@withContext emptyList()
        }
        if (notificationIds.isNotEmpty()) {
            val localStreams = if (preferHelix && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    helixLocal(networkLibrary, helixHeaders, notificationIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (helixException: Exception) {
                    if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        throw helixException
                    }
                    gqlQueryLocal(networkLibrary, gqlHeaders, notificationIds)
                }
            } else if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    gqlQueryLocal(networkLibrary, gqlHeaders, notificationIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (gqlException: Exception) {
                    if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        throw gqlException
                    }
                    helixLocal(networkLibrary, helixHeaders, notificationIds)
                }
            } else {
                helixLocal(networkLibrary, helixHeaders, notificationIds)
            }
            list.addAll(localStreams)
        }
        if (includeFollowedStreams && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                gqlQueryLoad(networkLibrary, gqlHeaders)
                    .filterNot { item -> list.any { it.channelId == item.channelId } }
                    .let(list::addAll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (list.isEmpty()) {
                    throw e
                }
            }
        }
        val liveStreams = list.distinctBy { it.channelId ?: it.id }
        val liveList = liveStreams.mapNotNull { stream ->
            stream.channelId.takeUnless { it.isNullOrBlank() }?.let { channelId ->
                stream.startedAtMillis()?.let { startedAt ->
                    ShownNotification(channelId, startedAt)
                }
            }
        }
        val oldList = shownNotificationsDao.getAll()
        oldList.filter { item -> liveList.find { it.channelId == item.channelId } == null }.let {
            shownNotificationsDao.deleteList(it)
        }
        val oldByChannel = oldList.associateBy { it.channelId }
        val newStreams = liveStreams.filter { stream ->
            val channelId = stream.channelId ?: return@filter false
            val startedAt = stream.startedAtMillis() ?: return@filter false
            oldByChannel[channelId]?.startedAt?.let { it >= startedAt } != true
        }
        if (enqueueNotificationEvents) {
            newStreams.mapNotNull { stream ->
                stream.startedAtMillis()?.let { NotificationEvent.fromStream(stream, it) }
            }.let(notificationEventsDao::insertList)
        }
        shownNotificationsDao.insertList(liveList)
        newStreams
    }

    suspend fun syncNotificationUsers(networkLibrary: String?, gqlHeaders: Map<String, String>) = withContext(Dispatchers.IO) {
        if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            return@withContext false
        }
        val users = mutableListOf<NotificationUser>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, gqlHeaders, 100, offset)
            response.errors?.firstOrNull()?.let { throw Exception(it.message) }
            val data = response.data!!.user!!.follows!!
            val items = data.edges!!
            users.addAll(items.mapNotNull { item ->
                item?.node?.takeIf {
                    it.self?.follower?.notificationSettings?.isEnabled == true
                }?.id?.let(::NotificationUser)
            })
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!offset.isNullOrBlank() && data.pageInfo?.hasNextPage == true)
        notificationUsersDao.replaceAll(users)
        val enabledIds = users.mapTo(hashSetOf()) { it.channelId }
        notificationEventsDao.getAll()
            .filterNot { it.channelId in enabledIds }
            .forEach { notificationEventsDao.delete(it.eventId) }
        true
    }

    suspend fun getNotificationUserIds(): List<String> = withContext(Dispatchers.IO) {
        notificationUsersDao.getAll().map { it.channelId }
    }

    suspend fun enqueueNotification(stream: Stream, startedAt: Long? = null): String? = withContext(Dispatchers.IO) {
        val start = startedAt ?: stream.startedAtMillis() ?: return@withContext null
        NotificationEvent.fromStream(stream, start)?.also { notificationEventsDao.insert(it) }?.eventId
    }

    suspend fun getPendingNotificationEvents(): List<NotificationEvent> = withContext(Dispatchers.IO) {
        notificationEventsDao.getAll()
    }

    suspend fun markNotificationDelivered(eventId: String) = withContext(Dispatchers.IO) {
        notificationEventsDao.delete(eventId)
    }

    suspend fun clearPendingNotificationEvents() = withContext(Dispatchers.IO) {
        notificationEventsDao.deleteAll()
    }

    suspend fun clearNotificationState() = withContext(Dispatchers.IO) {
        notificationUsersDao.deleteAll()
        shownNotificationsDao.deleteList(shownNotificationsDao.getAll())
        notificationEventsDao.deleteAll()
    }

    private suspend fun gqlQueryLoad(networkLibrary: String?, gqlHeaders: Map<String, String>): List<Stream> {
        val list = mutableListOf<Stream>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders, 100, offset)
            val data = response.data!!.user!!.followedLiveUsers!!
            val items = data.edges!!
            items.mapNotNull { item ->
                item?.node?.let {
                    if (it.self?.follower?.notificationSettings?.isEnabled == true) {
                        Stream(
                            id = it.stream?.id,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameId = it.stream?.game?.id,
                            gameSlug = it.stream?.game?.slug,
                            gameName = it.stream?.game?.displayName,
                            title = it.stream?.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = it.stream?.previewImageURL,
                            createdAt = it.stream?.createdAt?.toString(),
                            viewerCount = it.stream?.viewersCount,
                            tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                        )
                    } else null
                }
            }.let { list.addAll(it) }
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!items.lastOrNull()?.cursor?.toString().isNullOrBlank() && data.pageInfo?.hasNextPage == true)
        return list
    }

    private suspend fun gqlQueryLocal(networkLibrary: String?, gqlHeaders: Map<String, String>, ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map { list ->
            graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, list)
        }.flatMap { it.data!!.users!! }
        return items.mapNotNull { item ->
            item?.let {
                if (it.stream?.viewersCount != null) {
                    Stream(
                        id = it.stream.id,
                        channelId = it.id,
                        channelLogin = it.login,
                        channelName = it.displayName,
                        channelImageURL = it.profileImageURL,
                        gameId = it.stream.game?.id,
                        gameSlug = it.stream.game?.slug,
                        gameName = it.stream.game?.displayName,
                        title = it.stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = it.stream.previewImageURL,
                        createdAt = it.stream.createdAt?.toString(),
                        viewerCount = it.stream.viewersCount,
                        tags = it.stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                } else null
            }
        }
    }

    private suspend fun helixLocal(networkLibrary: String?, helixHeaders: Map<String, String>, ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map {
            helixRepository.getStreams(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it,
            )
        }.flatMap { it.data }
        return items.mapNotNull {
            if (it.viewerCount != null) {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    thumbnailURL = it.thumbnailURL,
                    createdAt = it.startedAt,
                    viewerCount = it.viewerCount,
                    tags = it.tags,
                )
            } else null
        }
    }

    suspend fun saveList(list: List<ShownNotification>) = withContext(Dispatchers.IO) {
        shownNotificationsDao.insertList(list)
    }

    suspend fun getUserById(id: String) = withContext(Dispatchers.IO) {
        notificationUsersDao.getById(id)
    }

    suspend fun saveUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.insert(item)
    }

    suspend fun deleteUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.delete(item)
        notificationEventsDao.deleteForChannel(item.channelId)
    }

    private fun Stream.startedAtMillis(): Long? = createdAt
        ?.takeIf { it.isNotBlank() }
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?.takeIf { it > 0 }
}
