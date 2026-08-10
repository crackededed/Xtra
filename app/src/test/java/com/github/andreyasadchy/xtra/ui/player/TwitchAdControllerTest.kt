package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitchAdControllerTest {

    @Test
    fun adWindowExcludesCurrentPlayerAndConsumesEachAlternateOnce() {
        val controller = TwitchAdController()

        assertEquals(
            listOf("embed", "popout", "autoplay"),
            controller.playerTypesForAd("site"),
        )
        assertEquals(emptyList<String>(), controller.playerTypesForAd("site"))
    }

    @Test
    fun cleanPlaylistAllowsAlternatesOnTheNextAdWindow() {
        val controller = TwitchAdController()

        controller.playerTypesForAd("site")
        controller.onCleanPlaylist()

        assertEquals(
            listOf("embed", "popout", "autoplay"),
            controller.playerTypesForAd("site"),
        )
    }

    @Test
    fun resetAllowsAlternatesOnTheNextAdWindow() {
        val controller = TwitchAdController()

        controller.playerTypesForAd("site")
        controller.reset()

        assertEquals(
            listOf("embed", "popout", "autoplay"),
            controller.playerTypesForAd("site"),
        )
    }
}
