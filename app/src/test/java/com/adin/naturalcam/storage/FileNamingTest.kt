package com.adin.naturalcam.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** 2009-02-13T23:31:30.123Z. */
private const val EPOCH_MS = 1_234_567_890_123L

class FileNamingTest {

    @Test
    fun baseNameFormatsInLocalTime() {
        withDefaultTz("UTC") {
            assertEquals("20090213_233130_123", FileNaming.baseName(EPOCH_MS))
        }
        withDefaultTz("GMT+05:30") {
            assertEquals("20090214_050130_123", FileNaming.baseName(EPOCH_MS))
        }
    }

    @Test
    fun jpegAndDngShareTheBaseName() {
        withDefaultTz("UTC") {
            val base = FileNaming.baseName(EPOCH_MS)
            assertEquals("$base.jpg", FileNaming.jpegName(EPOCH_MS))
            assertEquals("$base.dng", FileNaming.dngName(EPOCH_MS))
        }
    }

    @Test
    fun tempNameConcatenatesCaptureIdAndSuffix() {
        assertEquals("cap-42.dng", FileNaming.tempName("cap-42", ".dng"))
    }

    private fun withDefaultTz(zone: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
