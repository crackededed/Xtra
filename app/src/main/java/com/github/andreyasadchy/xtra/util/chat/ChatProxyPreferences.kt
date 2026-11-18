package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.io.IOException

object ChatProxyPreferences {
    const val ENABLED = "chat_proxy_enabled"
    const val TYPE = "chat_proxy_type"
    const val HOST = "chat_proxy_host"
    const val PORT = "chat_proxy_port"
    const val USERNAME = "chat_proxy_username"
    const val PASSWORD = "chat_proxy_password"

    fun read(context: Context): ChatProxy? =
        if (context.prefs().getBoolean(ENABLED, false)) {
            configuration(context) ?: throw IOException(context.getString(R.string.chat_proxy_invalid))
        } else null

    fun configuration(context: Context): ChatProxy? {
        val preferences = context.prefs()
        return ChatProxy.parse(
            preferences.getString(TYPE, "SOCKS5"),
            preferences.getString(HOST, null),
            preferences.getString(PORT, null),
            preferences.getString(USERNAME, null),
            context.tokenPrefs().getString(PASSWORD, null),
        )
    }
}
