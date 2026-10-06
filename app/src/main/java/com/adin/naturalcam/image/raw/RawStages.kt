package com.adin.naturalcam.image.raw

import com.adin.naturalcam.image.core.BayerImage
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.LensShadingMap
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.RgbGains
import com.adin.naturalcam.image.core.RgbImage
/**
 * Black-level subtraction and white-level normalization to [0, 1] floats
 * (AGENTS 20 — no early 8-bit). Values above 1.0 are preserved (overexposed
 * sensor samples must survive into tone mapping).
 */
object RawNormalizer {

    fun normalize(raw: RawImage): BayerImage {
        val cfa = raw.metadata.cfa
        val black = raw.metadata.blackLevelPerChannel
        val white = raw.metadata.whiteLevel.toFloat()
        val values = FloatArray(raw.pixelData.size)
        CpuParallel.forEach(raw.height, minItemsPerTask = 128) { startRow, endRow ->
            for (y in startRow until endRow) {
                for (x in 0 until raw.width) {
                    val i = y * raw.width + x
                    val b = blackFor(black, cfa.channelAt(x, y))
                    val normalized = (raw.pixelData[i].toInt() and 0xFFFF) - b
                    values[i] = if (white - b > 0f) normalized / (white - b) else 0f
                }
            }
        }
        return BayerImage(raw.width, raw.height, cfa, values)
    }

    /** Per-color black level; size-1 metadata acts as scalar (DngReader canonicalizes). */
    internal fun blackFor(black: FloatArray, channel: Int): Float =
        if (black.size == 1) black[0] else black[channel]
}

/**
 * Applies the DNG GainMap lens-shading correction to a normalized Bayer mosaic
 * (SPEC 31). The map is per CFA position, so it runs before demosaic; gains are
 * bilinearly interpolated because the stored grid is coarse (17x17 on the
 * Oppo CPH2737). Mutates [bayer] and returns it (ownership transfer, AGENTS 45):
 * a full-frame copy would cost ~50 MB per 12 MP frame for no benefit.
 *
 * Only the *colour* part of the map is applied: every grid point is divided by
 * the mean gain across the CFA positions present, which makes the correction
 * luminance-neutral. A full shading correction multiplies sensor noise by the
 * gain — up to ~5x at the corner of this lens — and that lands as coloured
 * speckle in the shadows: measured on the Oppo CPH2737, dark pixels gained 46%
 * chroma noise overall and 117% at the frame corner. The colour cast is what
 * reads as a defect; the luminance vignette is a real property of the lens and
 * is left in place rather than paid for with shadow noise.
 *
 * A map for a different frame, a single-position map (which cannot be split
 * into colour and luminance), or one with no entry for a CFA position leaves
 * that sample at its original value — no invented correction (AGENTS 18).
 */
object LensShadingCorrector {

    fun correct(bayer: BayerImage, map: LensShadingMap): BayerImage {
        if (map.imageWidth != bayer.width || map.imageHeight != bayer.height) return bayer
        val grids = colourOnly(map.grids)
        val width = bayer.width
        val height = bayer.height
        val columns = map.columns
        val rows = map.rows
        val scaleX = if (width <= 1) 0f else (columns - 1).toFloat() / (width - 1)
        val columnLow = IntArray(width)
        val columnHigh = IntArray(width)
        val columnFrac = FloatArray(width)
        for (x in 0 until width) {
            val g = x * scaleX
            val low = g.toInt().coerceIn(0, columns - 1)
            val high = (low + 1).coerceAtMost(columns - 1)
            columnLow[x] = low
            columnHigh[x] = high
            columnFrac[x] = if (high == low) 0f else g - low
        }
        val scaleY = if (height <= 1) 0f else (rows - 1).toFloat() / (height - 1)
        val values = bayer.values
        CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
            for (y in startRow until endRow) {
                val gy = y * scaleY
                val rowLow = gy.toInt().coerceIn(0, rows - 1)
                val rowHigh = (rowLow + 1).coerceAtMost(rows - 1)
                val fracY = if (rowHigh == rowLow) 0f else gy - rowLow
                val baseLow = rowLow * columns
                val baseHigh = rowHigh * columns
                for (x in 0 until width) {
                    val grid = grids[(y and 1) * 2 + (x and 1)] ?: continue
                    val low = columnLow[x]
                    val high = columnHigh[x]
                    val fracX = columnFrac[x]
                    val topLeft = grid[baseLow + low]
                    val top = topLeft + (grid[baseLow + high] - topLeft) * fracX
                    val bottomLeft = grid[baseHigh + low]
                    val bottom = bottomLeft + (grid[baseHigh + high] - bottomLeft) * fracX
                    values[y * width + x] *= top + (bottom - top) * fracY
                }
            }
        }
        return bayer
    }

    /**
     * Splits the map into colour only: every grid point is divided by the mean
     * gain of the CFA positions present. With fewer than two positions there is
     * nothing to split, so the map is used unchanged.
     */
    private fun colourOnly(grids: Array<FloatArray?>): Array<FloatArray?> {
        val present = grids.filterNotNull()
        if (present.size < 2) return grids
        return Array(grids.size) { index ->
            grids[index]?.let { grid ->
                FloatArray(grid.size) { i ->
                    var sum = 0f
                    for (other in present) sum += other[i]
                    grid[i] / (sum / present.size)
                }
            }
        }
    }
}

/**
 * Bilinear demosaicing, CFA-aware (AGENTS 19). Missing channels at a site are
 * averaged from the nearest same-channel samples: 4-neighbors first (green at
 * an R/B site), 4 diagonals otherwise (R/B at a green site or cross-color),
 * replicating at borders. Deterministic reference implementation; GPU or
 * better algorithms may replace it later (AGENTS 48).
 */
object Demosaicer {

    fun demosaic(bayer: BayerImage): RgbImage {
        val out = RgbImage(bayer.width, bayer.height)
        val cfa = bayer.cfa
        val v = bayer.values
        fun sample(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, bayer.width - 1)
            val cy = y.coerceIn(0, bayer.height - 1)
            return v[cy * bayer.width + cx]
        }

        CpuParallel.forEach(bayer.height, minItemsPerTask = 128) { startRow, endRow ->
            for (y in startRow until endRow) {
                for (x in 0 until bayer.width) {
                    val i = y * bayer.width + x
                    val own = cfa.channelAt(x, y)
                    val r: Float
                    val g: Float
                    val b: Float
                    if (own == 0) {
                        r = v[i]
                        g = cross(bayer, x, y, 1, ::sample)
                        b = diag(bayer, x, y, 2, ::sample)
                    } else if (own == 2) {
                        b = v[i]
                        g = cross(bayer, x, y, 1, ::sample)
                        r = diag(bayer, x, y, 0, ::sample)
                    } else {
                        g = v[i]
                        r = chromaAtGreen(bayer, x, y, 0, ::sample)
                        b = chromaAtGreen(bayer, x, y, 2, ::sample)
                    }
                    out.r[i] = r
                    out.g[i] = g
                    out.b[i] = b
                }
            }
        }
        return out
    }

    private fun cross(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float {
        var sum = 0f
        var n = 0
        if (x > 0 && bayer.cfa.channelAt(x - 1, y) == channel) { sum += sample(x - 1, y); n++ }
        if (x < bayer.width - 1 && bayer.cfa.channelAt(x + 1, y) == channel) { sum += sample(x + 1, y); n++ }
        if (y > 0 && bayer.cfa.channelAt(x, y - 1) == channel) { sum += sample(x, y - 1); n++ }
        if (y < bayer.height - 1 && bayer.cfa.channelAt(x, y + 1) == channel) { sum += sample(x, y + 1); n++ }
        return if (n == 0) fallback(bayer, x, y, channel, sample) else sum / n
    }

    private fun diag(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float {
        val sum = diagSum(bayer, x, y, channel, sample)
        return if (sum.count == 0) fallback(bayer, x, y, channel, sample) else sum.total / sum.count
    }

    private fun diagSum(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Sum {
        var total = 0f
        var count = 0
        if (x > 0 && y > 0 && bayer.cfa.channelAt(x - 1, y - 1) == channel) { total += sample(x - 1, y - 1); count++ }
        if (x < bayer.width - 1 && y > 0 && bayer.cfa.channelAt(x + 1, y - 1) == channel) { total += sample(x + 1, y - 1); count++ }
        if (x > 0 && y < bayer.height - 1 && bayer.cfa.channelAt(x - 1, y + 1) == channel) { total += sample(x - 1, y + 1); count++ }
        if (x < bayer.width - 1 && y < bayer.height - 1 && bayer.cfa.channelAt(x + 1, y + 1) == channel) { total += sample(x + 1, y + 1); count++ }
        return Sum(total, count)
    }

    private class Sum(val total: Float, val count: Int)

    private fun chromaAtGreen(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float {
        var total = 0f
        var count = 0
        horizontalNearest(bayer, x, y, channel, sample)?.let { total += it; count++ }
        verticalNearest(bayer, x, y, channel, sample)?.let { total += it; count++ }
        val diag = diagSum(bayer, x, y, channel, sample)
        if (diag.count > 0) { total += diag.total / diag.count; count++ }
        return if (count == 0) sample(x, y) else total / count
    }

    private fun horizontalNearest(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float? {
        val l = probe(bayer, x, y, channel, -1, 0, sample)
        val r = probe(bayer, x, y, channel, 1, 0, sample)
        return when {
            l != null && r != null -> (l + r) / 2f
            l != null -> l
            else -> r
        }
    }

    private fun verticalNearest(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float? {
        val u = probe(bayer, x, y, channel, 0, -1, sample)
        val d = probe(bayer, x, y, channel, 0, 1, sample)
        return when {
            u != null && d != null -> (u + d) / 2f
            u != null -> u
            else -> d
        }
    }

    private fun probe(
        bayer: BayerImage, x: Int, y: Int, channel: Int, dx: Int, dy: Int,
        sample: (Int, Int) -> Float,
    ): Float? {
        for (d in 1..2) {
            val cx = x + dx * d
            val cy = y + dy * d
            if (cx !in 0 until bayer.width || cy !in 0 until bayer.height) return null
            if (bayer.cfa.channelAt(cx, cy) == channel) return sample(cx, cy)
        }
        return null
    }

    private fun fallback(bayer: BayerImage, x: Int, y: Int, channel: Int, sample: (Int, Int) -> Float): Float =
        horizontalNearest(bayer, x, y, channel, sample)
            ?: verticalNearest(bayer, x, y, channel, sample)
            ?: sample(x, y)
}

/**
 * Builds the camera → linear sRGB matrix from DNG matrices (AGENTS 21:
 * centralized color logic). Prefers ForwardMatrix (already white-balanced
 * camera → XYZ D50); falls back to inverting ColorMatrix (XYZ → camera).
 * Composition with the standard XYZ D50 → sRGB matrix:
 *
 *   sRGB = M_xyz→srgb · M_cam→xyz · camera
 *
 * XYZ D50 → sRGB values (Bradford D50→D65 adapted, IEC 61966-2-1 primaries):
 */
object ColorMatrixFactory {

    private val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )

    fun cameraToWorkingRgbMatrix(meta: com.adin.naturalcam.image.core.RawCaptureMetadata): FloatArray =
        multiply3x3(XYZ_D50_TO_SRGB, selectCandidate(meta).cameraToXyz)

    /** Exposed for debug diagnostics; never includes pixel data. */
    internal fun matrixSelectionName(meta: com.adin.naturalcam.image.core.RawCaptureMetadata): String =
        selectCandidate(meta).name

    private fun selectCandidate(meta: com.adin.naturalcam.image.core.RawCaptureMetadata): MatrixCandidate {
        val candidates = listOfNotNull(
            candidate("ColorMatrix1/ForwardMatrix1", meta.forwardMatrix1, meta.colorMatrix1, meta.calibrationIlluminant1),
            candidate("ColorMatrix2/ForwardMatrix2", meta.forwardMatrix2, meta.colorMatrix2, meta.calibrationIlluminant2),
        )
        require(candidates.isNotEmpty()) { "RAW metadata carries no usable color matrix" }
        val neutral = meta.asShotNeutral?.takeIf { it.size == 3 && it.all { value -> value > 0f } }
        // Without AsShotNeutral there is no honest illuminant estimate; retain
        // the prior fallback order rather than inventing a scene temperature.
        if (neutral != null && candidates.size == 2) {
            val firstRatio = expectedNeutralRatio(candidates[0])
            val secondRatio = expectedNeutralRatio(candidates[1])
            val actualRatio = neutral[0] / neutral[2]
            if (firstRatio != null && secondRatio != null && actualRatio > 0f) {
                val span = kotlin.math.ln(secondRatio / firstRatio)
                if (span.isFinite() && kotlin.math.abs(span) > 1e-6f) {
                    val weight = (kotlin.math.ln(actualRatio / firstRatio) / span).coerceIn(0f, 1f)
                    if (weight <= 0.01f) return candidates[0]
                    if (weight >= 0.99f) return candidates[1]
                    val blended = FloatArray(9) { i ->
                        candidates[0].cameraToXyz[i] * (1f - weight) +
                            candidates[1].cameraToXyz[i] * weight
                    }
                    return MatrixCandidate(
                        name = "Interpolated(${candidates[0].name},${candidates[1].name})",
                        cameraToXyz = blended,
                        xyzToCamera = invert3x3(blended),
                        illuminant = null,
                    )
                }
            }
        }
        // Without a usable illuminant estimate, retain the prior fallback
        // order rather than inventing a scene temperature.
        return neutral?.let { actual ->
            candidates.minByOrNull { candidate -> neutralError(actual, candidate) }
        } ?: candidates.last()
    }

    private fun candidate(
        name: String,
        forward: FloatArray?,
        color: FloatArray?,
        illuminant: Int?,
    ): MatrixCandidate? {
        val validForward = validMatrix(forward)
        val validColor = validMatrix(color)
        val cameraToXyz = validForward ?: validColor?.let(::invert3x3) ?: return null
        val xyzToCamera = validColor ?: invert3x3(cameraToXyz)
        return MatrixCandidate(name, cameraToXyz, xyzToCamera, illuminant)
    }

    private fun expectedNeutralRatio(candidate: MatrixCandidate): Float? {
        val illuminant = whitePoint(candidate.illuminant) ?: return null
        val expected = multiply3x3Vector(candidate.xyzToCamera, illuminant)
        if (expected.any { it <= 0f || !it.isFinite() }) return null
        return expected[0] / expected[2]
    }

    private fun neutralError(actual: FloatArray, candidate: MatrixCandidate): Float {
        val expectedRatio = expectedNeutralRatio(candidate) ?: return Float.POSITIVE_INFINITY
        val actualRatio = actual[0] / actual[2]
        val ratioError = kotlin.math.ln(actualRatio / expectedRatio)
        return ratioError * ratioError
    }

    private fun multiply3x3Vector(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /** CIE 1931 2° white points for DNG CalibrationIlluminant codes. */
    private fun whitePoint(code: Int?): FloatArray? {
        val xy = when (code) {
            17, 24 -> 0.44757f to 0.40745f // Standard light A / ISO studio tungsten
            18 -> 0.34842f to 0.35161f // Standard light B
            19 -> 0.31006f to 0.31616f // Standard light C
            20 -> 0.33242f to 0.34743f // D55
            21 -> 0.31271f to 0.32902f // D65
            22 -> 0.29902f to 0.31485f // D75
            23 -> 0.34567f to 0.35850f // D50
            else -> return null
        }
        val (x, y) = xy
        return floatArrayOf(x / y, 1f, (1f - x - y) / y)
    }

    private data class MatrixCandidate(
        val name: String,
        val cameraToXyz: FloatArray,
        val xyzToCamera: FloatArray,
        val illuminant: Int?,
    )

    /**
     * A matrix is usable only if it is 3x3, finite, non-zero and non-singular.
     * Real producers ship degenerate tags (e.g. a zero ForwardMatrix1 on the
     * Oppo CPH2737) which would otherwise black out the whole image.
     */
    private fun validMatrix(m: FloatArray?): FloatArray? {
        if (m == null || m.size != 9) return null
        if (m.any { !it.isFinite() }) return null
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        return if (kotlin.math.abs(det) > 1e-6f) m else null
    }

    /** Row-major 3x3 multiply. */
    internal fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 9 && b.size == 9)
        val out = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                out[row * 3 + col] = a[row * 3] * b[col] +
                    a[row * 3 + 1] * b[3 + col] +
                    a[row * 3 + 2] * b[6 + col]
            }
        }
        return out
    }

    internal fun invert3x3(m: FloatArray): FloatArray {
        require(m.size == 9)
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(det) > 1e-12f) { "Singular color matrix" }
        val inv = 1f / det
        return floatArrayOf(
            (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv,
        )
    }
}
