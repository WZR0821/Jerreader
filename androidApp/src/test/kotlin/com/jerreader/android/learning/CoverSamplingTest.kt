package com.jerreader.android.learning

import com.jerreader.android.library.coverSampleSize
import org.junit.Assert.*
import org.junit.Test

class CoverSamplingTest {
    @Test fun thumbnailBoundsStayWithinLimitForLargeAndRotatedCovers() {
        for ((width, height) in listOf(400 to 600, 600 to 400, 4096 to 8192, 8192 to 4096, 1537 to 1000)) {
            val sample = coverSampleSize(width, height)
            assertTrue(sample >= 1 && sample and (sample - 1) == 0)
            assertTrue((maxOf(width, height) + sample - 1) / sample <= 768)
        }
        assertEquals(1, coverSampleSize(400, 600))
    }
}
