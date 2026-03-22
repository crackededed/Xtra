package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
 import com.github.andreyasadchy.xtra.repository.BookmarksRepository
 import com.github.andreyasadchy.xtra.repository.GraphQLRepository
 import com.github.andreyasadchy.xtra.repository.HelixRepository
 import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
 import com.github.andreyasadchy.xtra.repository.NotificationUsersRepository
 import com.github.andreyasadchy.xtra.repository.OfflineRepository
 import com.github.andreyasadchy.xtra.repository.PlayerRepository
 import com.github.andreyasadchy.xtra.repository.ShownNotificationsRepository
 import com.github.andreyasadchy.xtra.repository.TranslateAllMessagesUsersRepository
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.ExperimentalCoroutinesApi
 import kotlinx.coroutines.test.StandardTestDispatcher
 import kotlinx.coroutines.test.resetMain
 import kotlinx.coroutines.test.runTest
 import kotlinx.coroutines.test.setMain
 import okhttp3.OkHttpClient
 import org.junit.After
 import org.junit.Assert.*
 import org.junit.Before
 import org.junit.Test
 import org.mockito.kotlin.mock
 import java.util.concurrent.ExecutorService
 
 @OptIn(ExperimentalCoroutinesApi::class)
 class PlayerViewModelTest {
 
     private val testDispatcher = StandardTestDispatcher()
     private lateinit var graphQLRepository: GraphQLRepository
     private lateinit var helixRepository: HelixRepository
     private lateinit var localFollowsChannel: LocalFollowChannelRepository
     private lateinit var shownNotificationsRepository: ShownNotificationsRepository
     private lateinit var notificationUsersRepository: NotificationUsersRepository
     private lateinit var translateAllMessagesUsersRepository: TranslateAllMessagesUsersRepository
     private lateinit var okHttpClient: OkHttpClient
     private lateinit var playerRepository: PlayerRepository
     private lateinit var bookmarksRepository: BookmarksRepository
     private lateinit var offlineRepository: OfflineRepository
     private lateinit var cronetExecutor: ExecutorService
     private lateinit var viewModel: PlayerViewModel
 
     @Before
     fun setup() {
         Dispatchers.setMain(testDispatcher)
         graphQLRepository = mock()
         helixRepository = mock()
         localFollowsChannel = mock()
         shownNotificationsRepository = mock()
         notificationUsersRepository = mock()
         translateAllMessagesUsersRepository = mock()
         okHttpClient = mock()
         playerRepository = mock()
         bookmarksRepository = mock()
         offlineRepository = mock()
         cronetExecutor = mock()
         
         viewModel = PlayerViewModel(
             graphQLRepository = graphQLRepository,
             helixRepository = helixRepository,
             localFollowsChannel = localFollowsChannel,
             shownNotificationsRepository = shownNotificationsRepository,
             notificationUsersRepository = notificationUsersRepository,
             translateAllMessagesUsersRepository = translateAllMessagesUsersRepository,
             httpEngine = null,
             cronetEngine = null,
             cronetExecutor = cronetExecutor,
             okHttpClient = okHttpClient,
             playerRepository = playerRepository,
             bookmarksRepository = bookmarksRepository,
             offlineRepository = offlineRepository
         )
     }
 
     @After
     fun tearDown() {
         Dispatchers.resetMain()
     }
 
     @Test
     fun `initial state has null values`() {
         assertNull(viewModel.streamResult.value)
         assertNull(viewModel.stream.value)
         assertNull(viewModel.videoResult.value)
         assertNull(viewModel.clipUrls.value)
         assertNull(viewModel.isFollowing.value)
         assertNull(viewModel.gamesList.value)
         assertNull(viewModel.isBookmarked.value)
         assertNull(viewModel.integrity.value)
     }
 
     @Test
     fun `loaded initial state is false`() {
         assertFalse(viewModel.loaded.value)
     }
 
     @Test
    fun `qualities initial state is null`() {
        assertNull(viewModel.qualities)
    }
 
     @Test
     fun `quality initial state is null`() {
         assertNull(viewModel.quality)
     }
 
     @Test
     fun `userHasChangedQuality initial state is false`() {
         assertFalse(viewModel.userHasChangedQuality)
     }
 
     @Test
     fun `started initial state is false`() {
         assertFalse(viewModel.started)
     }
 
     @Test
     fun `resume initial state is false`() {
         assertFalse(viewModel.resume)
     }
 
     @Test
     fun `hidden initial state is false`() {
         assertFalse(viewModel.hidden)
     }
 
     @Test
     fun `useCustomProxy initial state is false`() {
         assertFalse(viewModel.useCustomProxy)
     }
 
     @Test
     fun `playingAds initial state is false`() {
         assertFalse(viewModel.playingAds)
     }
 
     @Test
     fun `usingProxy initial state is false`() {
         assertFalse(viewModel.usingProxy)
     }
 
     @Test
     fun `stopProxy initial state is false`() {
         assertFalse(viewModel.stopProxy)
     }
 
     @Test
     fun `shouldRetry initial state is true`() {
         assertTrue(viewModel.shouldRetry)
     }
 
     @Test
    fun `Stream model creation with basic fields`() {
        val stream = Stream(
            id = "stream123",
            channelId = "channel456",
            channelLogin = "teststreamer",
            channelName = "TestStreamer",
            channelImageURL = "https://example.com/profile.jpg",
            gameId = "game789",
            gameName = "Just Chatting",
            title = "Test Stream Title",
            thumbnailURL = "https://example.com/thumb.jpg",
            viewerCount = 1000,
            createdAt = "2025-01-01T00:00:00Z"
        )
         
         assertEquals("stream123", stream.id)
         assertEquals("channel456", stream.channelId)
         assertEquals("teststreamer", stream.channelLogin)
         assertEquals("TestStreamer", stream.channelName)
        assertEquals("https://example.com/profile.jpg", stream.channelImageURL)
        assertEquals("game789", stream.gameId)
        assertEquals("Just Chatting", stream.gameName)
        assertEquals("Test Stream Title", stream.title)
        assertEquals("https://example.com/thumb.jpg", stream.thumbnailURL)
        assertEquals(1000, stream.viewerCount)
        assertEquals("2025-01-01T00:00:00Z", stream.createdAt)
    }
 
     @Test
     fun `Stream with null optional fields`() {
         val stream = Stream(
             id = "stream123",
             channelLogin = "teststreamer"
         )
         
         assertEquals("stream123", stream.id)
         assertEquals("teststreamer", stream.channelLogin)
         assertNull(stream.channelId)
         assertNull(stream.gameName)
         assertNull(stream.viewerCount)
     }
 
     @Test
    fun `Game model creation with VOD position`() {
        val game = Game(
            id = "game123",
            name = "Minecraft",
            boxArtURL = "https://example.com/boxart.jpg",
            vodPosition = 3600000,
            vodDuration = 7200000
        )
        
        assertEquals("game123", game.id)
        assertEquals("Minecraft", game.name)
        assertEquals("https://example.com/boxart.jpg", game.boxArtURL)
        assertEquals(3600000, game.vodPosition)
        assertEquals(7200000, game.vodDuration)
    }
 
     @Test
     fun `mutable state can be updated`() = runTest {
         viewModel.loaded.value = true
         assertTrue(viewModel.loaded.value)
         
         viewModel.stream.value = Stream(id = "test", channelLogin = "tester")
         assertEquals("test", viewModel.stream.value?.id)
     }
 
     @Test
    fun `quality can be set and retrieved`() {
        viewModel.quality = VideoQuality("720p60")
        assertEquals("720p60", viewModel.quality?.name)
        
        viewModel.quality = VideoQuality("1080p60")
        assertEquals("1080p60", viewModel.quality?.name)
    }

    @Test
    fun `previousQuality tracks quality changes`() {
        viewModel.quality = VideoQuality("720p60")
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = VideoQuality("1080p60")
        
        assertEquals("720p60", viewModel.previousQuality?.name)
        assertEquals("1080p60", viewModel.quality?.name)
    }
 
     @Test
     fun `userHasChangedQuality flag works correctly`() {
         assertFalse(viewModel.userHasChangedQuality)
         
         viewModel.userHasChangedQuality = true
         assertTrue(viewModel.userHasChangedQuality)
     }
 
     @Test
     fun `playbackPosition can be set and retrieved`() {
         assertNull(viewModel.playbackPosition)
         
         viewModel.playbackPosition = 5000L
         assertEquals(5000L, viewModel.playbackPosition)
     }
 
     @Test
     fun `backupQualities can be set and retrieved`() {
         assertNull(viewModel.backupQualities)
         
         viewModel.backupQualities = listOf("720p60", "480p30", "360p30")
         assertEquals(3, viewModel.backupQualities?.size)
         assertTrue(viewModel.backupQualities?.contains("720p60") == true)
     }
 
     @Test
     fun `integrity state flow updates correctly`() = runTest {
         assertNull(viewModel.integrity.value)
         
         viewModel.integrity.value = "refreshStream"
         assertEquals("refreshStream", viewModel.integrity.value)
         
         viewModel.integrity.value = null
         assertNull(viewModel.integrity.value)
     }
 
     @Test
     fun `follow state flow updates correctly`() = runTest {
         assertNull(viewModel.follow.value)
         
         viewModel.follow.value = Pair(true, null)
         assertTrue(viewModel.follow.value?.first == true)
         assertNull(viewModel.follow.value?.second)
         
         viewModel.follow.value = Pair(false, "Error message")
         assertFalse(viewModel.follow.value?.first == true)
         assertEquals("Error message", viewModel.follow.value?.second)
     }
 }
