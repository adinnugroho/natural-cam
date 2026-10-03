package com.adin.naturalcam.image.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** Runs independent full-frame ranges across the device CPU cores. */
internal object CpuParallel {
    private const val MIN_ITEMS_PER_TASK = 64 * 1024
    private val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val executor = Executors.newFixedThreadPool(parallelism, ThreadFactory { runnable ->
        Thread(runnable, "naturalcam-cpu").apply { isDaemon = true }
    })

    fun forEach(
        size: Int,
        minItemsPerTask: Int = MIN_ITEMS_PER_TASK,
        action: (start: Int, endExclusive: Int) -> Unit,
    ) {
        if (size <= minItemsPerTask || parallelism == 1) {
            action(0, size)
            return
        }

        val taskCount = min(parallelism, (size + minItemsPerTask - 1) / minItemsPerTask)
        val chunkSize = (size + taskCount - 1) / taskCount
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(taskCount)
        repeat(taskCount) { task ->
            val start = task * chunkSize
            val end = min(size, start + chunkSize)
            executor.execute {
                try {
                    if (start < end && failure.get() == null) action(start, end)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }
        }
        try {
            done.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        failure.get()?.let { error -> throw error }
    }
}
