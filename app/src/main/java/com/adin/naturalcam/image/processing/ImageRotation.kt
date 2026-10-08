package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
/**
 * Physical output rotation (SPEC 89 — physically correct output preferred over
 * EXIF orientation). Camera rotations are always multiples of 90°; other
 * values are normalized to the nearest multiple and 0° is an identity copy.
 */
object ImageRotation {

    class RotatedPixels(val argb: IntArray, val width: Int, val height: Int)

    /**
     * Pure copy — every index mapping below is the same one the row scatter used,
     * only the loop nesting changed. 90°/270° are transposes, and the naive form
     * wrote (or read) one pixel per destination *column*, i.e. one cache line per
     * pixel: at 12 MP that is ~12.6M cache misses for a stage that copies 50 MB.
     * Walking [TILE]-sized tiles keeps both sides of the transpose inside L1, and
     * the tasks split on tiles whose destinations do not overlap.
     */
    private const val TILE = 32

    fun rotate(argb: IntArray, width: Int, height: Int, degrees: Int): RotatedPixels {
        require(argb.size == width * height)
        return when (normalize(degrees)) {
            90 -> {
                // Destination is height wide: source row y becomes destination column height-1-y.
                val out = IntArray(width * height)
                val rowTiles = (height + TILE - 1) / TILE
                CpuParallel.forEach(rowTiles, minItemsPerTask = 2) { startTile, endTile ->
                    for (tile in startTile until endTile) {
                        val y0 = tile * TILE
                        val y1 = minOf(y0 + TILE, height)
                        var x0 = 0
                        while (x0 < width) {
                            val x1 = minOf(x0 + TILE, width)
                            // Destination index stays in L1 for one tile of source columns.
                            for (x in x0 until x1) {
                                var dst = (height - 1 - y0) + x * height
                                for (y in y0 until y1) {
                                    out[dst] = argb[y * width + x]
                                    dst--
                                }
                            }
                            x0 = x1
                        }
                    }
                }
                RotatedPixels(out, height, width)
            }
            180 -> {
                val out = IntArray(width * height)
                CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
                    for (y in startRow until endRow) {
                        // Reversed destination row: forward source reads, backward destination writes.
                        var dst = (height - 1 - y) * width + width - 1
                        val src = y * width
                        for (x in 0 until width) {
                            out[dst] = argb[src + x]
                            dst--
                        }
                    }
                }
                RotatedPixels(out, width, height)
            }
            270 -> {
                // Destination is height wide: source column x becomes destination column width-1-x.
                val out = IntArray(width * height)
                val colTiles = (width + TILE - 1) / TILE
                CpuParallel.forEach(colTiles, minItemsPerTask = 2) { startTile, endTile ->
                    for (tile in startTile until endTile) {
                        val x0 = tile * TILE
                        val x1 = minOf(x0 + TILE, width)
                        var y0 = 0
                        while (y0 < height) {
                            val y1 = minOf(y0 + TILE, height)
                            for (x in x0 until x1) {
                                val dst = (width - 1 - x) * height
                                for (y in y0 until y1) {
                                    out[dst + y] = argb[y * width + x]
                                }
                            }
                            y0 = y1
                        }
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
