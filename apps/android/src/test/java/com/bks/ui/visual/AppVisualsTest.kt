package com.bks.ui.visual

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisualsTest {
    @Test
    fun keepsFullResolutionWhenTargetNeedsSourcePixels() {
        assertEquals(1, calculateDecodeSampleSize(1080, 2400, 1440, 3200))
    }

    @Test
    fun downsamplesOnlyWhenBothDimensionsRemainLargeEnough() {
        assertEquals(2, calculateDecodeSampleSize(2160, 4800, 1080, 2400))
        assertEquals(1, calculateDecodeSampleSize(1080, 2400, 720, 1600))
    }

    @Test
    fun invalidDimensionsKeepDefaultDecode() {
        assertEquals(1, calculateDecodeSampleSize(0, 2400, 720, 1600))
        assertEquals(1, calculateDecodeSampleSize(1080, 2400, 0, 1600))
    }

    @Test
    fun forcedSampleSizeIsAppliedToWallpaperDecode() {
        assertEquals(2, calculateDecodeSampleSize(1080, 2400, 1440, 3200, minimumSampleSize = 2))
    }
}
