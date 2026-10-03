package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.CfaLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads synthetic uncompressed CFA DNG files (TestDngFactory) back through
 * DngReader. Executable check for RAW decode correctness (AGENTS 62).
 */
class DngReaderTest {

    @Test
    fun `reads all four cfa layouts with correct channel mapping`() {
        for (layout in CfaLayout.entries) {
            val file = TestDngFactory.buildDng(layout)
            try {
                val raw = DngReader.read(file)
                assertEquals(layout, raw.metadata.cfa)
                assertEquals(4, raw.width)
                assertEquals(4095, raw.metadata.whiteLevel)
                assertEquals(1000, raw.pixelData[0].toInt())
                assertEquals(16, raw.pixelData.size)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `pixel values preserved end to end`() {
        val file = TestDngFactory.buildDng(CfaLayout.RGGB, pixelValue = 2048, width = 8, height = 6)
        try {
            val raw = DngReader.read(file)
            assertEquals(8, raw.width)
            assertEquals(6, raw.height)
            assertEquals(48, raw.pixelData.size)
            assertTrue(raw.pixelData.all { it.toInt() == 2048 })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `black level canonicalized to per color`() {
        val file = TestDngFactory.buildDng(CfaLayout.RGGB, blackLevel = listOf(16 to 1, 32 to 1, 48 to 1, 64 to 1))
        try {
            val raw = DngReader.read(file)
            val black = raw.metadata.blackLevelPerChannel
            assertEquals(3, black.size)
            // RGGB pattern positions: R,G,G,B → R=16, G=(32+48)/2=40, B=64.
            assertEquals(16f, black[0], 1e-6f)
            assertEquals(40f, black[1], 1e-6f)
            assertEquals(64f, black[2], 1e-6f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tolerates exotic and unknown tag types from real producers`() {
        val file = TestDngFactory.buildDng(CfaLayout.RGGB, withExoticTags = true)
        try {
            val raw = DngReader.read(file)
            assertEquals(CfaLayout.RGGB, raw.metadata.cfa)
            assertEquals(16, raw.pixelData.size)
            assertEquals(1000, raw.pixelData[0].toInt())
            // Garbage count=1 ForwardMatrix1 must be rejected, valid ColorMatrix1 kept.
            assertNull(raw.metadata.forwardMatrix1)
            assertNotNull(raw.metadata.colorMatrix1)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rejects non tiff data`() {
        val file = File.createTempFile("junk", ".bin")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertThrows(DngReader.DngFormatException::class.java) { DngReader.read(file) }
        file.delete()
    }

    @Test
    fun `as shot neutral and matrices preserved`() {
        val file = TestDngFactory.buildDng(CfaLayout.BGGR)
        try {
            val raw = DngReader.read(file)
            val neutral = raw.metadata.asShotNeutral!!
            assertEquals(3, neutral.size)
            assertEquals(1f, neutral[0], 1e-4f)
            val matrix = raw.metadata.colorMatrix1!!
            assertEquals(9, matrix.size)
            assertEquals(1f, matrix[0], 1e-4f)
            assertTrue(raw.metadata.timestampMs > 0)
        } finally {
            file.delete()
        }
    }
}
