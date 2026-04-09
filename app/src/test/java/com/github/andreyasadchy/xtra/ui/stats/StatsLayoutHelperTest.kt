package com.github.andreyasadchy.xtra.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsLayoutHelperTest {

    @Test
    fun `calculateSpanCount returns one column when available width is smaller than minimum item width`() {
        assertEquals(1, StatsLayoutHelper.calculateSpanCount(320, 360))
    }

    @Test
    fun `calculateSpanCount returns multiple columns when width supports them`() {
        assertEquals(2, StatsLayoutHelper.calculateSpanCount(720, 320))
        assertEquals(3, StatsLayoutHelper.calculateSpanCount(1080, 320))
    }

    @Test
    fun `calculateSpanCount never returns less than one`() {
        assertEquals(1, StatsLayoutHelper.calculateSpanCount(0, 320))
    }
}
