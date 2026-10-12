package com.echohome.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
    @Test fun roundTripsEveryPercent() {
        for (p in 0..100) {
            val (step, trim) = VolumeMath.split(p, 15)
            assertEquals("percent $p", p, VolumeMath.percent(step, 15, trim))
        }
    }

    @Test fun stepIsRoundedUpAndTrimmed() {
        val (step, trim) = VolumeMath.split(35, 15) // 35% of 15 = 5.25 -> step 6 (40%), trim 0.875
        assertEquals(6, step)
        assertEquals(0.875f, trim, 0.001f)
    }

    @Test fun mutedAndFullAreExact() {
        assertEquals(0 to 1f, VolumeMath.split(0, 15))
        assertEquals(15 to 1f, VolumeMath.split(100, 15))
    }

    @Test fun withoutTrimUsesNearestStep() {
        assertEquals(5 to 1f, VolumeMath.split(35, 15, canTrim = false))
        assertEquals(1 to 1f, VolumeMath.split(1, 15, canTrim = false)) // never silent by accident
    }
}
