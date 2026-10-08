package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.CpuParallel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicIntegerArray

/** Parallel branch of [CpuParallel.forEach]: every existing frame is <= MIN_ITEMS_PER_TASK. */
class CpuParallelTest {

    @Test
    fun `parallel ranges visit every index exactly once`() {
        val size = 300_000
        val visits = AtomicIntegerArray(size)
        CpuParallel.forEach(size, minItemsPerTask = 100) { start, endExclusive ->
            for (i in start until endExclusive) visits.incrementAndGet(i)
        }

        for (i in 0 until size) {
            assertEquals("index $i visited wrong number of times", 1, visits.get(i))
        }
    }

    @Test
    fun `worker failure is rethrown by the caller`() {
        val boom = IllegalStateException("worker boom")
        try {
            CpuParallel.forEach(300_000, minItemsPerTask = 100) { start, _ ->
                if (start == 0) throw boom
            }
            fail("expected forEach to rethrow the worker exception")
        } catch (thrown: IllegalStateException) {
            assertEquals(boom, thrown)
        }
    }
}
