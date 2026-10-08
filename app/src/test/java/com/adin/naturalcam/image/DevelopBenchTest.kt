package com.adin.naturalcam.image

import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.DefaultImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.raw.DngReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * JVM benchmark for the RAW develop: NATURAL, and NATURAL with a styled frame so the
 * bloom and grain mask paths are exercised too (they are skipped at style defaults).
 * Asserts the frame is real, and prints time, allocation, and an ARGB checksum so a
 * math-preserving change can be shown to be bit-identical run over run.
 *
 * Wall clock here is *not* a device number: this is a desktop JVM with more cores and no
 * ART. What transfers is the shape — which stages dominate, and how much garbage a develop
 * allocates (that is the figure that predicts device GC behaviour at 12 MP).
 *
 * Skips itself when no repo DNG is present, so it never fails a checkout without the
 * (large, untracked) capture samples.
 */
class DevelopBenchTest {

    private val passes = 4

    private fun configurations(): List<Pair<String, ProcessingConfiguration>> = listOf(
        "natural" to ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL),
        "styled" to ProcessingConfiguration.forProfile(
            ProcessingProfile.NATURAL,
            style = StyleState(bloom = 0.25f, grain = 0.25f, saturation = 0.05f),
        ),
    )

    /**
     * Allocated bytes summed over every live thread: the CpuParallel workers run the heavy
     * loops, so a single thread's counter would miss the interesting half. Reflection
     * because `java.lang.management` is on the test JVM but not in the Android compile
     * classpath.
     */
    private fun allocatedBytes(): Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)!!
        val ids = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getAllThreadIds").invoke(bean) as LongArray
        Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", LongArray::class.java)
            .invoke(bean, ids) as LongArray
    }.getOrNull()?.sum() ?: -1L

    private fun sampleDng(): File? {
        val names = listOf("bias.dng", "direct-pure.dng", "latest-device.dng")
        val dirs = listOf(File(".."), File("../.."), File("."))
        for (dir in dirs) for (name in names) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
        }
        return null
    }

    @Test
    fun `natural develop of a real dng stays real and reports its cost`() {
        val file = sampleDng()
        assumeTrue("no repo DNG sample present", file != null)

        val raw = DngReader.read(file!!)
        for ((label, configuration) in configurations()) {
            val encoder = RecordingEncoder()
            val pipelineForConfiguration = DefaultImagePipeline(encoder)
            var fastest = Long.MAX_VALUE
            var allocatedMb = -1L
            repeat(passes) { pass ->
                val allocatedBefore = if (pass == passes - 1) allocatedBytes() else 0L
                val start = System.nanoTime()
                pipelineForConfiguration.processRaw(raw, configuration)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if (elapsed < fastest) fastest = elapsed
                if (allocatedBefore >= 0L) {
                    val after = allocatedBytes()
                    if (after >= 0L) allocatedMb = (after - allocatedBefore) / (1024 * 1024)
                }
            }

            val mean = encoder.argb.map { pixel ->
                0.2126 * ((pixel shr 16) and 0xFF) + 0.7152 * ((pixel shr 8) and 0xFF) + 0.0722 * (pixel and 0xFF)
            }.average()
            val crc = CRC32().apply { encoder.argb.forEach { update(it) } }.value

            // Portrait display rotation swaps the axes; the sensor frame is landscape.
            val rotated = raw.metadata.orientationDegrees == 90 || raw.metadata.orientationDegrees == 270
            val expectedWidth = if (rotated) raw.height else raw.width
            val expectedHeight = if (rotated) raw.width else raw.height
            println(
                "=== DEVELOP BENCH $label ${file.name} ${expectedWidth}x$expectedHeight " +
                    "best=${fastest}ms allocated=$allocatedMb MB crc32=$crc mean=%.2f ===".format(mean)
            )
            assertEquals("developed width", expectedWidth, encoder.lastWidth)
            assertEquals("developed height", expectedHeight, encoder.lastHeight)
            // A black or blown-out frame is the signature of a broken RAW path, not a scene.
            assertTrue("developed frame looks black or white: mean=$mean", mean in 8.0..250.0)
        }
    }
}
