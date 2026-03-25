package com.github.andreyasadchy.xtra.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MediaButtonReceiver: BroadcastReceiver() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null && intent != null && intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                        || keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                        val savedStates = runBlocking {
                            playerRepository.getPlaybackStates()
                        }
                        if (savedStates.isNotEmpty()) {
                            when (context.prefs().getString(C.PLAYER, "ExoPlayer")) {
                                "MediaPlayer" -> {}
                                else -> {
                                    if (context.prefs().getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, false)) {
                                        context.startForegroundService(Intent(context, ExoPlayerService::class.java).apply {
                                            fillIn(intent, 0)
                                        })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val savedStates = runBlocking {
                        playerRepository.getPlaybackStates()
                    }
                    if (savedStates.isNotEmpty()) {
                        when (context.prefs().getString(C.PLAYER, "ExoPlayer")) {
                            "MediaPlayer" -> {}
                            else -> {
                                if (context.prefs().getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, false)) {
                                    context.startService(Intent(context, ExoPlayerService::class.java).apply {
                                        fillIn(intent, 0)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}