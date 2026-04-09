package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChatModeHelperTest {

    @Test
    fun `sidebar chat stays hidden while floating chat is enabled`() {
        assertFalse(PlayerChatModeHelper.shouldShowSidebarChat(isChatOpen = true, isFloatingChatEnabled = true))
    }

    @Test
    fun `sidebar chat shows when chat is open and floating chat is disabled`() {
        assertTrue(PlayerChatModeHelper.shouldShowSidebarChat(isChatOpen = true, isFloatingChatEnabled = false))
    }

    @Test
    fun `sidebar chat stays hidden when chat is closed`() {
        assertFalse(PlayerChatModeHelper.shouldShowSidebarChat(isChatOpen = false, isFloatingChatEnabled = false))
    }
}
