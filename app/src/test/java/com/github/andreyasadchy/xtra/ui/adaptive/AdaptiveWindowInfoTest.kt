package com.github.andreyasadchy.xtra.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWindowInfoTest {

    @Test
    fun `widths under 600dp are compact`() {
        assertEquals(WidthTier.COMPACT, AdaptiveWindowInfo.widthTierFor(599))
    }

    @Test
    fun `widths from 600dp to 839dp are medium`() {
        assertEquals(WidthTier.MEDIUM, AdaptiveWindowInfo.widthTierFor(600))
        assertEquals(WidthTier.MEDIUM, AdaptiveWindowInfo.widthTierFor(839))
    }

    @Test
    fun `widths from 840dp are expanded`() {
        assertEquals(WidthTier.EXPANDED, AdaptiveWindowInfo.widthTierFor(840))
    }
}
