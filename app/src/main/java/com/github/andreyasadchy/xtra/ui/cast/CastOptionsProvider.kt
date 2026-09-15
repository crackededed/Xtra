package com.github.andreyasadchy.xtra.ui.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Phase 0/1: cast configuration with the default media receiver.
 *
 * Deliberately does not use a custom receiver application id: for the
 * minimal prototype (device discovery + session management, no media load
 * yet) the default media receiver is enough. Registered via
 * OPTIONS_PROVIDER_CLASS_NAME in AndroidManifest.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
