package com.autoaccounting.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransitionsTest {
    @Test
    fun pageTransitionOffsetsMoveFromRightToLeft() {
        assertEquals(1080, pageEnterOffsetX(1080))
        assertEquals(-1080, pageExitOffsetX(1080))
    }
}
