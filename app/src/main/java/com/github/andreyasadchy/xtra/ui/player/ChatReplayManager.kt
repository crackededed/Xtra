package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.util.chat.OnChatMessageReceivedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.fixedRateTimer
import kotlin.math.max

class ChatReplayManager @Inject constructor(
    private val gqlHeaders: Map<String, String>,
    private val repository: ApiRepository,
    private val videoId: String,
    private val startTime: Double,
    private val currentPosition: () -> Double,
    private val messageListener: OnChatMessageReceivedListener,
    private val clearMessages: () -> Unit,
    private val coroutineScope: CoroutineScope) {

    private val timer: Timer
    private var cursor: String? = null
    private val list = LinkedList<VideoChatMessage>()
    private var isLoading = false
    private lateinit var offsetJob: Job
    private var nextJob: Job? = null

    init {
        load(startTime)
        var lastCheckedPosition = 0.0
        timer = fixedRateTimer(period = 1000L, action = {
            val position = currentPosition()
            if (position - lastCheckedPosition !in 0.0..20.0) {
                offsetJob.cancel()
                list.clear()
                clearMessages()
                load(startTime + position)
            }
            lastCheckedPosition = position
        })
    }

    fun stop() {
        offsetJob.cancel()
        nextJob?.cancel()
        timer.cancel()
    }

    private fun load(offset: Double) {
        offsetJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                val log = repository.loadVideoMessages(gqlHeaders = gqlHeaders, videoId = videoId, offset = offset.toInt())
                isLoading = false
                list.addAll(log.data)
                cursor = if (log.hasNextPage != false) log.cursor else null
                while (isActive) {
                    val message: VideoChatMessage? = try {
                        list.poll()
                    } catch (e: NoSuchElementException) { //wtf?
                        null
                    }
                    if (message?.offsetSeconds != null) {
                        val messageOffset = message.offsetSeconds.toDouble()
                        var position: Double
                        while ((currentPosition() + startTime).also { p -> position = p } < messageOffset) {
                            delay(max((messageOffset - position) * 1000.0, 0.0).toLong())
                        }
                        if (position - messageOffset < 20.0) {
                            messageListener.onMessage(message)
                            if (list.size == 25) {
                                loadNext()
                            }
                        }
                    } else if (isLoading) {
                        delay(1000L)
                    } else if (cursor == null) {
                        break
                    }
                }
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    private fun loadNext() {
        cursor?.let { c ->
            nextJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    isLoading = true
                    val log = repository.loadVideoMessages(gqlHeaders = gqlHeaders, videoId = videoId, cursor = c)
                    list.addAll(log.data)
                    cursor = if (log.hasNextPage != false) log.cursor else null
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
    }
}