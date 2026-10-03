package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
/**
 * Physical output rotation (SPEC 89 — physically correct output preferred over
 * EXIF orientation). Camera rotations are always multiples of 90°; other
 * values are normalized to the nearest multiple and 0° is an identity copy.
 */
object ImageRotation {

    class RotatedPixels(val argb: IntArray, val width: Int, val height: Int)

    fun rotate(argb: IntArray, width: Int, height: Int, degrees: Int): RotatedPixels {
        require(argb.size == width * height)
        return when (normalize(degrees)) {
            90 -> {
                // First source row becomes the last destination column.
                val out = IntArray(width * height)
                CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
                    for (y in startRow until endRow) for (x in 0 until width) {
                        out[(height - 1 - y) + x * height] = argb[x + y * width]
                    }
                }
                RotatedPixels(out, height, width)
            }
            180 -> {
                val out = IntArray(width * height)
                CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
                    for (y in startRow until endRow) for (x in 0 until width) {
                        out[(width - 1 - x) + (height - 1 - y) * width] = argb[x + y * width]
                    }
                }
                RotatedPixels(out, width, height)
            }
            270 -> {
                // Last source row becomes the first destination column.
                val out = IntArray(width * height)
                CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
                    for (y in startRow until endRow) for (x in 0 until width) {
                        out[y + (width - 1 - x) * height] = argb[x + y * width]
                    }
                }
                RotatedPixels(out, height, width)
            }
            else -> RotatedPixels(argb, width, height)
        }
    }

    /** Clockwise display rotation normalized to 0/90/180/270. */
    internal fun normalize(degrees: Int): Int {
        val d = ((degrees % 360) + 360) % 360
        return ((d + 45) / 90) % 4 * 90
    }
}
