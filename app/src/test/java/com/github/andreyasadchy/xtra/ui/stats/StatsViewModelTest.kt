 package com.github.andreyasadchy.xtra.ui.stats
 
 import com.github.andreyasadchy.xtra.model.stats.CategoryWatchTime
 import com.github.andreyasadchy.xtra.model.stats.HourlyWatchTime
 import com.github.andreyasadchy.xtra.model.stats.ScreenTime
 import com.github.andreyasadchy.xtra.model.stats.StreamWatchStats
 import com.github.andreyasadchy.xtra.model.stats.StreamerLoyalty
 import com.github.andreyasadchy.xtra.model.stats.WatchStreak
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.ExperimentalCoroutinesApi
 import kotlinx.coroutines.flow.flowOf
 import kotlinx.coroutines.test.StandardTestDispatcher
 import kotlinx.coroutines.test.resetMain
 import kotlinx.coroutines.test.runTest
 import kotlinx.coroutines.test.setMain
 import org.junit.After
 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertTrue
 import org.junit.Before
 import org.junit.Test
 import org.mockito.kotlin.any
 import org.mockito.kotlin.mock
 import org.mockito.kotlin.whenever
 
 @OptIn(ExperimentalCoroutinesApi::class)
 class StatsViewModelTest {
 
     private val testDispatcher = StandardTestDispatcher()
     private lateinit var repository: StatsRepository
 
     @Before
     fun setup() {
         Dispatchers.setMain(testDispatcher)
         repository = mock()
     }
 
     @After
     fun tearDown() {
         Dispatchers.resetMain()
     }
    
    private fun setupDefaultMocks() {
        whenever(repository.getAllScreenTime()).thenReturn(flowOf(emptyList()))
        whenever(repository.getTopWatchedStreams(any())).thenReturn(flowOf(emptyList()))
        whenever(repository.getWatchStreakFlow()).thenReturn(flowOf(null))
    }
 
     @Test
     fun `screenTime flow emits empty list initially`() = runTest {
        setupDefaultMocks()
         whenever(repository.getCategoryBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getHourlyBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getMonthlySummary(any())).thenReturn(emptyList())
         whenever(repository.getStreamerLoyalty(any())).thenReturn(emptyList())
         
        val viewModel = StatsViewModel(repository)
         testDispatcher.scheduler.advanceUntilIdle()
         
         assertTrue(viewModel.screenTime.value.isEmpty())
     }
 
     @Test
    fun `categoryBreakdown loads data on init`() = runTest {
        setupDefaultMocks()
        val categories = listOf(
            CategoryWatchTime("123", "Just Chatting", 3600L, 5),
            CategoryWatchTime("456", "Minecraft", 1800L, 3)
         )
        whenever(repository.getCategoryBreakdown(any(), any())).thenReturn(categories)
         whenever(repository.getHourlyBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getMonthlySummary(any())).thenReturn(emptyList())
         whenever(repository.getStreamerLoyalty(any())).thenReturn(emptyList())
         
        val viewModel = StatsViewModel(repository)
         testDispatcher.scheduler.advanceUntilIdle()
         
        assertEquals(2, viewModel.categoryBreakdown.value.size)
        assertEquals("Just Chatting", viewModel.categoryBreakdown.value[0].gameName)
     }
 
     @Test
    fun `hourlyBreakdown loads 24 hours of data`() = runTest {
        setupDefaultMocks()
        val hourly = (0..23).map { HourlyWatchTime(it, (it * 100).toLong(), it) }
         whenever(repository.getCategoryBreakdown(any(), any())).thenReturn(emptyList())
        whenever(repository.getHourlyBreakdown(any(), any())).thenReturn(hourly)
         whenever(repository.getMonthlySummary(any())).thenReturn(emptyList())
         whenever(repository.getStreamerLoyalty(any())).thenReturn(emptyList())
         
        val viewModel = StatsViewModel(repository)
         testDispatcher.scheduler.advanceUntilIdle()
         
        assertEquals(24, viewModel.hourlyBreakdown.value.size)
     }
 
     @Test
    fun `streamerLoyalty loads and calculates scores`() = runTest {
        setupDefaultMocks()
        val loyalty = listOf(
            StreamerLoyalty("123", "TopStreamer", "topstreamer", 10000L, 50, 20, 100f),
            StreamerLoyalty("456", "SecondStreamer", "secondstreamer", 5000L, 25, 10, 50f)
        )
         whenever(repository.getCategoryBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getHourlyBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getMonthlySummary(any())).thenReturn(emptyList())
        whenever(repository.getStreamerLoyalty(any())).thenReturn(loyalty)
         
        val viewModel = StatsViewModel(repository)
         testDispatcher.scheduler.advanceUntilIdle()
         
        assertEquals(2, viewModel.streamerLoyalty.value.size)
        assertEquals(100f, viewModel.streamerLoyalty.value[0].loyaltyScore)
     }
 
    fun `empty data returns empty lists`() = runTest {
        setupDefaultMocks()
         whenever(repository.getCategoryBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getHourlyBreakdown(any(), any())).thenReturn(emptyList())
         whenever(repository.getMonthlySummary(any())).thenReturn(emptyList())
        whenever(repository.getStreamerLoyalty(any())).thenReturn(emptyList())
         
        val viewModel = StatsViewModel(repository)
         testDispatcher.scheduler.advanceUntilIdle()
         
        assertTrue(viewModel.categoryBreakdown.value.isEmpty())
        assertTrue(viewModel.hourlyBreakdown.value.isEmpty())
        assertTrue(viewModel.streamerLoyalty.value.isEmpty())
     }
 }
