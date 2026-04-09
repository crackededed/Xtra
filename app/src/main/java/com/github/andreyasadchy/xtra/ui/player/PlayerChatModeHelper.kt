package com.github.andreyasadchy.xtra.ui.player

object PlayerChatModeHelper {
    fun shouldShowSidebarChat(isChatOpen: Boolean, isFloatingChatEnabled: Boolean): Boolean {
        return isChatOpen && !isFloatingChatEnabled
    }
}
