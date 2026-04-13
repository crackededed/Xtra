package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerBrightnessStateTest {

    @Test
    fun `captureOriginal stores system default brightness`() {
        val state = PlayerBrightnessState()

        state.captureOriginal(-1f)

        assertEquals(-1f, state.originalBrightness)
    }

    @Test
    fun `captureOriginal keeps first brightness value`() {
        val state = PlayerBrightnessState()

        state.captureOriginal(0.2f)
        state.captureOriginal(0.8f)

        assertEquals(0.2f, state.originalBrightness)
    }

    @Test
    fun `consumeRestoreBrightness returns original system default and clears state`() {
        val state = PlayerBrightnessState()
        state.captureOriginal(-1f)

        assertEquals(-1f, state.consumeRestoreBrightness())
        assertNull(state.originalBrightness)
    }
}
