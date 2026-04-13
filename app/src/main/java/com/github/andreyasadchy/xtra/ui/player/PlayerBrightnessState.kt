package com.github.andreyasadchy.xtra.ui.player

class PlayerBrightnessState {
    var originalBrightness: Float? = null
        private set

    fun captureOriginal(currentBrightness: Float) {
        if (originalBrightness == null) {
            originalBrightness = currentBrightness
        }
    }

    fun consumeRestoreBrightness(): Float? {
        val brightness = originalBrightness
        originalBrightness = null
        return brightness
    }
}
