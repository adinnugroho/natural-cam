package com.adin.naturalcam.image

import com.adin.naturalcam.image.processing.ImageRotation
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageRotationTest {

    /**
     * Source 2x3 grid (w=2, h=3), values are labels:
     *   a b
     *   c d
     *   e f
     */
    private fun src() = intArrayOf(1, 2, 3, 4, 5, 6)

    @Test
    fun `90 clockwise moves first row to last column`() {
        val r = ImageRotation.rotate(src(), 2, 3, 90)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        // Expected:
        //   5 3 1
        //   6 4 2
        assertEquals(listOf(5, 3, 1, 6, 4, 2), r.argb.toList())
    }

    @Test
    fun `270 clockwise moves last row to first column`() {
        val r = ImageRotation.rotate(src(), 2, 3, 270)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        // Expected:
        //   2 4 6
        //   1 3 5
        assertEquals(listOf(2, 4, 6, 1, 3, 5), r.argb.toList())
    }

    @Test
    fun `180 reverses both axes`() {
        val r = ImageRotation.rotate(src(), 2, 3, 180)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), r.argb.toList())
    }

    @Test
    fun `zero and full turn are identity`() {
        val zero = ImageRotation.rotate(src(), 2, 3, 0)
        assertEquals(src().toList(), zero.argb.toList())
        val turn = ImageRotation.rotate(src(), 2, 3, 360)
        assertEquals(src().toList(), turn.argb.toList())
    }

    @Test
    fun `negative degrees normalize clockwise`() {
        // −90° == 270° clockwise.
        assertEquals(
            ImageRotation.rotate(src(), 2, 3, 270).argb.toList(),
            ImageRotation.rotate(src(), 2, 3, -90).argb.toList(),
        )
    }

    /**
     * Tiled path (images larger than one 32px tile, non-multiple dimensions):
     * every pixel is checked against the documented index mapping.
     */
    @Test
    fun `tiled rotation maps every pixel of an odd sized image`() {
        val w = 70
        val h = 45
        val src = IntArray(w * h) { i -> (i / w) * 1000 + (i % w) }

        val cw90 = ImageRotation.rotate(src, w, h, 90)
        assertEquals(h, cw90.width)
        assertEquals(w, cw90.height)
        val cw270 = ImageRotation.rotate(src, w, h, 270)
        val cw180 = ImageRotation.rotate(src, w, h, 180)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val value = src[y * w + x]
                assertEquals(value, cw90.argb[(h - 1 - y) + x * h])
                assertEquals(value, cw270.argb[y + (w - 1 - x) * h])
                assertEquals(value, cw180.argb[(w - 1 - x) + (h - 1 - y) * w])
            }
        }
    }

    @Test
    fun `four quarter turns return to original`() {
        var pixels = src()
        var w = 2
        var h = 3
        repeat(4) {
            val r = ImageRotation.rotate(pixels, w, h, 90)
            pixels = r.argb
            w = r.width
            h = r.height
        }
        assertEquals(2, w)
        assertEquals(3, h)
        assertEquals(src().toList(), pixels.toList())
    }
}
