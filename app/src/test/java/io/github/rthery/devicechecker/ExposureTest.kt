package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.interactive.analyzeExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExposureTest {

    private fun times(count: Int) = List(count) { it * 33_333L }

    @Test
    fun `dark start that overshoots then settles passes`() {
        val ramp = listOf(0.0, 2.0, 5.0, 13.0, 27.0, 45.0, 64.0, 80.0, 92.0, 99.0, 95.0, 90.0, 86.0)
        val lumas = ramp + List(70) { 84.0 }
        val exposure = analyzeExposure(times(lumas.size), lumas)!!
        assertEquals(84.0, exposure.settledLuma, 0.1)
        assertEquals(366L, exposure.settleMs)
        assertEquals(0, exposure.swings)
        assertEquals(CheckStatus.PASS, exposure.status)
    }

    @Test
    fun `alternating brightness counts as flicker`() {
        val lumas = List(90) { if (it % 2 == 0) 100.0 else 108.0 }
        val exposure = analyzeExposure(times(lumas.size), lumas)!!
        assertNotNull(exposure.settleMs)
        assertEquals(CheckStatus.WARN, exposure.status)
    }

    @Test
    fun `short clip that ends before settling is not a warning`() {
        val lumas = List(15) { it * 8.0 }
        val exposure = analyzeExposure(times(lumas.size), lumas)!!
        assertNull(exposure.settleMs)
        assertEquals(CheckStatus.PASS, exposure.status)
    }

    @Test
    fun `brightness drifting through a long clip warns`() {
        val lumas = List(90) { it * 2.0 }
        val exposure = analyzeExposure(times(lumas.size), lumas)!!
        assertEquals(CheckStatus.WARN, exposure.status)
    }

    @Test
    fun `slow settle warns`() {
        val lumas = List(75) { 20.0 + it } + List(20) { 95.0 }
        val exposure = analyzeExposure(times(lumas.size), lumas)!!
        assertEquals(CheckStatus.WARN, exposure.status)
    }
}
