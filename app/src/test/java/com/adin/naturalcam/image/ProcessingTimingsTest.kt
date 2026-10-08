package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.ProcessingTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTimingsTest {

    @Test
    fun `stages aggregate by name in first-seen order`() {
        val timings = ProcessingTimings()
        timings.record("demosaic", 4)
        timings.record("tone", 3)
        timings.record("demosaic", 5)

        assertEquals(listOf("demosaic", "tone"), timings.stages)
        // Three records, two names: the repeated name is summed, not appended.
        assertEquals(9L, timings.millisOf("demosaic"))
        assertEquals(3L, timings.millisOf("tone"))
        assertEquals("demosaic=9ms tone=3ms", timings.summary())
        assertEquals(0L, timings.millisOf("absent"))
    }

    @Test
    fun `decided facts are reported after the durations and keep their last value`() {
        val timings = ProcessingTimings()
        timings.record("tone", 2)
        timings.fact("grainMeasured", "0.01553")
        timings.fact("chromaStrength", "0.850")
        // Re-deciding the same fact replaces it: the log must show what was applied, not a history.
        timings.fact("chromaStrength", "1.000")
        timings.fact("grainMeasured", "0.01553")

        assertEquals("0.01553", timings.factOf("grainMeasured"))
        assertEquals("1.000", timings.factOf("chromaStrength"))
        assertEquals(null, timings.factOf("absent"))
        assertEquals("tone=2ms grainMeasured=0.01553 chromaStrength=1.000", timings.summary())
    }

    @Test
    fun `measured block returns its value and records the span`() {
        val timings = ProcessingTimings()
        val value = timings.measure("grainEstimate") { 42 }
        timings.record("save", 7)

        assertEquals(42, value)
        assertEquals(7L, timings.millisOf("save"))
        assertTrue(timings.summary().startsWith("grainEstimate="))
        assertTrue(timings.summary().endsWith("save=7ms"))
    }
}
