package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.RgbImage
import kotlin.math.abs
import kotlin.math.floor
/**
 * Restrained 2×2 chroma denoising. Each pixel keeps its own linear luminance;
 * only color differences are blended toward the block mean. Real luminance
 * grain and texture therefore remain, with no synthetic grain added.
 *
 * The block filter is intentionally small and allocation-bounded for 12 MP
 * captures. ponytail: replace with an edge-aware chroma filter only if 2×2
 * color blocking becomes visible at a stronger measured tuning.
 */
object NoiseReducer {

    /**
     * [rowOffset]/[colOffset] shift the 2x2 block grid by one pixel. A second pass on
     * the shifted grid therefore averages a different pairing of neighbours, which is
     * how the style chain buys extra chroma smoothing from the same allocation-free
     * kernel (used when a saturation boost would otherwise amplify chroma noise).
     */
    fun reduce(
        rgb: RgbImage,
        chromaStrength: Float,
        lumaStrength: Float,
        rowOffset: Int = 0,
        colOffset: Int = 0,
    ): RgbImage {
        val chroma = chromaStrength.coerceIn(0f, 1f)
        val luma = lumaStrength.coerceIn(0f, 1f)
        if (chroma == 0f && luma == 0f) return rgb

        val blockRows = (rgb.height - rowOffset) / 2
        CpuParallel.forEach(blockRows, minItemsPerTask = 128) { startBlockRow, endBlockRow ->
            for (blockRow in startBlockRow until endBlockRow) {
                val y = rowOffset + blockRow * 2
                val top = y * rgb.width
                val bottom = top + rgb.width
                var x = colOffset
                while (x + 1 < rgb.width) {
                    val i0 = top + x
                    val i1 = i0 + 1
                    val i2 = bottom + x
                    val i3 = i2 + 1

                    val r0 = rgb.r[i0]; val g0 = rgb.g[i0]; val b0 = rgb.b[i0]
                    val r1 = rgb.r[i1]; val g1 = rgb.g[i1]; val b1 = rgb.b[i1]
                    val r2 = rgb.r[i2]; val g2 = rgb.g[i2]; val b2 = rgb.b[i2]
                    val r3 = rgb.r[i3]; val g3 = rgb.g[i3]; val b3 = rgb.b[i3]
                    val l0 = linearLuminance(r0, g0, b0)
                    val l1 = linearLuminance(r1, g1, b1)
                    val l2 = linearLuminance(r2, g2, b2)
                    val l3 = linearLuminance(r3, g3, b3)
                    val meanLuminance = (l0 + l1 + l2 + l3) * 0.25f
                    val meanChromaR = (r0 - l0 + r1 - l1 + r2 - l2 + r3 - l3) * 0.25f
                    val meanChromaG = (g0 - l0 + g1 - l1 + g2 - l2 + g3 - l3) * 0.25f
                    val meanChromaB = (b0 - l0 + b1 - l1 + b2 - l2 + b3 - l3) * 0.25f

                    writePixel(rgb, i0, r0, g0, b0, l0, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i1, r1, g1, b1, l1, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i2, r2, g2, b2, l2, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i3, r3, g3, b3, l3, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    x += 2
                }
            }
        }
        return rgb
    }

    private fun writePixel(
        rgb: RgbImage,
        index: Int,
        r: Float,
        g: Float,
        b: Float,
        sourceLuminance: Float,
        meanLuminance: Float,
        meanChromaR: Float,
        meanChromaG: Float,
        meanChromaB: Float,
        chromaStrength: Float,
        lumaStrength: Float,
    ) {
        val outputLuminance = sourceLuminance +
            lumaStrength * (meanLuminance - sourceLuminance)
        val sourceChromaR = r - sourceLuminance
        val sourceChromaG = g - sourceLuminance
        val sourceChromaB = b - sourceLuminance
        rgb.r[index] = outputLuminance + sourceChromaR +
            chromaStrength * (meanChromaR - sourceChromaR)
        rgb.g[index] = outputLuminance + sourceChromaG +
            chromaStrength * (meanChromaG - sourceChromaG)
        rgb.b[index] = outputLuminance + sourceChromaB +
            chromaStrength * (meanChromaB - sourceChromaB)
    }

    private fun linearLuminance(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * Conservative unsharp masking (PRD 20): real detail over perceived sharpness,
 * amount clamped to 0.5 to make halos structurally hard to produce. The blur
 * is a separable [1,2,1]/4 two-pass filter (radius ≈ 1 px at the default).
 * Amount 0 is an exact no-op (AGENTS 27).
 */
object Sharpener {

    const val MAX_AMOUNT = 0.5f

    fun sharpen(rgb: RgbImage, amount: Float, radiusPx: Float): RgbImage {
        val a = amount.coerceIn(0f, MAX_AMOUNT)
        if (a == 0f) return rgb
        val passes = radiusPx.coerceAtLeast(1f).toInt().coerceIn(1, 3)
        val n = rgb.r.size
        val work = FloatArray(n)
        val blurredA = FloatArray(n)
        val blurredB = if (passes > 1) FloatArray(n) else blurredA
        for (channel in arrayOf(rgb.r, rgb.g, rgb.b)) {
            var source = channel
            var target = blurredA
            repeat(passes) {
                blur3x3Into(source, target, rgb.width, rgb.height, work)
                source = target
                target = if (target === blurredA) blurredB else blurredA
            }
            CpuParallel.forEach(n) { start, end ->
                for (i in start until end) {
                    channel[i] = channel[i] + a * (channel[i] - source[i])
                }
            }
        }
        return rgb
    }
}

/**
 * Linear-light highlight bloom for the explicit Bloom style control.
 *
 * The glow has to span a visible fraction of the frame, so the highlight mask is
 * built directly at [DOWNSAMPLE] linear resolution, blurred there (cheap, and
 * equivalent to a wide full-resolution blur), then bilinearly upsampled back.
 * A full-resolution blur of the same radius would cost ~[DOWNSAMPLE]² more work.
 *
 * Pixels below [THRESHOLD] contribute nothing, so amount 0 — and any image
 * without highlights — is an exact no-op.
 */
object BloomStage {

    /** Full-resolution pixels per mask pixel; also the glow's minimum scale. */
    private const val DOWNSAMPLE = 16

    /** Blur passes at mask resolution; joint radius ≈ DOWNSAMPLE × √(passes/2) px. */
    private const val BLUR_PASSES = 6

    /** Linear-light level where bloom starts picking up (≈ sRGB 0.8). */
    private const val THRESHOLD = 0.6f

    /** Soft knee width above [THRESHOLD]; avoids a hard glow edge on soft gradients. */
    private const val KNEE = 0.4f

    /** Peak glow energy added at amount 1 relative to the thresholded highlight. */
    private const val MIX = 0.45f

    fun apply(rgb: RgbImage, strength: Float): RgbImage {
        val amount = strength.coerceIn(0f, 1f)
        if (amount == 0f || rgb.r.isEmpty()) return rgb

        val maskWidth = maxOf(1, rgb.width / DOWNSAMPLE)
        val maskHeight = maxOf(1, rgb.height / DOWNSAMPLE)
        val maskSize = maskWidth * maskHeight

        val mask = FloatArray(maskSize)
        val blurred = FloatArray(maskSize)
        val work = FloatArray(maskSize)
        for (channel in arrayOf(rgb.r, rgb.g, rgb.b)) {
            accumulateHighlights(channel, rgb.width, rgb.height, mask, maskWidth, maskHeight)
            var source = mask
            var target = blurred
            repeat(BLUR_PASSES) {
                blur3x3Into(source, target, maskWidth, maskHeight, work)
                source = target
                target = if (target === blurred) mask else blurred
            }
            CpuParallel.forEach(rgb.height) { startRow, endRow ->
                for (y in startRow until endRow) {
                    val row = y * rgb.width
                    val gy = (y + 0.5f) * maskHeight / rgb.height
                    for (x in 0 until rgb.width) {
                        val glow = sampleBilinear(
                            source,
                            maskWidth,
                            maskHeight,
                            (x + 0.5f) * maskWidth / rgb.width,
                            gy,
                        )
                        val i = row + x
                        channel[i] = (channel[i] + glow * MIX * amount).coerceAtLeast(0f)
                    }
                }
            }
        }
        return rgb
    }

    /**
     * Peak thresholded highlight per mask cell.
     *
     * Averaging the cell would divide a highlight's energy by the cell area, so
     * a sparse specular or a small lamp (a few pixels) contributed ~1/256 of its
     * value and the glow became invisible. Bloom is about the *presence* of bright
     * light, so the peak is pooled instead and the soft knee still suppresses
     * values that only just cross [THRESHOLD].
     */
    private fun accumulateHighlights(
        channel: FloatArray,
        width: Int,
        height: Int,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
    ) {
        for (my in 0 until maskHeight) {
            val yStart = my * height / maskHeight
            val yEnd = maxOf(yStart + 1, (my + 1) * height / maskHeight)
            for (mx in 0 until maskWidth) {
                val xStart = mx * width / maskWidth
                val xEnd = maxOf(xStart + 1, (mx + 1) * width / maskWidth)
                var peak = 0f
                for (y in yStart until yEnd) {
                    val row = y * width
                    for (x in xStart until xEnd) {
                        val value = channel[row + x].coerceAtLeast(0f)
                        if (value <= THRESHOLD) continue
                        val knee = ((value - THRESHOLD) / KNEE).coerceIn(0f, 1f)
                        val highlight = value * knee
                        if (highlight > peak) peak = highlight
                    }
                }
                mask[my * maskWidth + mx] = peak
            }
        }
    }
}

/**
 * Bilinear sample with half-texel centering; clamped at the border. Shared by the
 * bloom glow and the grain detail mask, which both build a small field at reduced
 * resolution and upsample it per output pixel.
 */
internal fun sampleBilinear(src: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
    val gx = (x - 0.5f).coerceIn(0f, (width - 1).toFloat())
    val gy = (y - 0.5f).coerceIn(0f, (height - 1).toFloat())
    val x0 = gx.toInt()
    val y0 = gy.toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = gx - x0
    val fy = gy - y0
    val top = src[y0 * width + x0] * (1f - fx) + src[y0 * width + x1] * fx
    val bottom = src[y1 * width + x0] * (1f - fx) + src[y1 * width + x1] * fx
    return top * (1f - fy) + bottom * fy
}

/**
 * Film-like grain for the explicit Grain control, applied to the *delivered*
 * pixels — the ARGB image the encoder is about to receive, after tone mapping,
 * gamut mapping, and the output transfer curve.
 *
 * That placement is the whole point. Adding grain earlier (in linear working
 * light) looks neutral in theory but is not: the output transfer curve compresses
 * the channels differently, so an equal linear delta lands as unequal encoded
 * deltas, and it clips unevenly against black. Measured through the real pipeline,
 * a linear-light offset grew chroma noise by 29% in the black band, a uniform RGB
 * gain by 20% in midtones, and a luma-scaled linear delta by 75% of its own luma
 * energy in chroma — i.e. all three arrived as colour speckle. Here every channel
 * receives the *same integer* shift, bounded so the pixel cannot leave 0..255, so
 * channel differences (R−G, B−G) are preserved exactly and grain can never be
 * coloured. Rounding the shift to whole levels costs at most half a level, far
 * below JPEG's own precision, and buys that exactness.
 *
 * The field itself is deterministic and built from three layers: per-pixel speckle,
 * a [CLUMP_STEP]-pixel lattice octave whose lattice noise is interpolated *linearly*
 * (a smoothstep profile softened the clumps into blobs and read as blur), and a
 * [DENSITY_STEP]-pixel field that only nudges the amplitude so the sheet is not
 * perfectly even. Everything is a hash of the coordinate, so repeated develops of one
 * capture match and no random state has to be threaded through the pipeline.
 *
 * The integer shift is dithered with a per-pixel hash before rounding. Rounding alone
 * quantises the effect in whole levels, which at small amounts leaves a sparse
 * salt-and-pepper pattern instead of the intended fine texture; the dither keeps the
 * average amplitude and stays colour-neutral because the same integer still lands on
 * all three channels.
 *
 * Amplitude is dynamic — it follows the image instead of being a fixed overlay:
 * it scales with encoded luma (film density, so black stays clean and grain never
 * clips), a midtone bell with a floor, and a local-detail mask that backs the grain
 * off where the picture already has texture or edges, which is where real grain
 * stops being visible anyway. The detail measure is peak-pooled at
 * [DETAIL_DOWNSAMPLE] resolution, so the mask costs a small field instead of a
 * full-resolution buffer. Amount 0 is an exact no-op.
 */
object GrainStage {

    /** Peak relative luma delta at amount 1, before the midtone and detail weights. */
    private const val MAX_GAIN = 0.32f

    /** Weight floor for shadows and highlights; midtones use the full weight. */
    private const val MIDTONE_FLOOR = 0.25f

    /** Lattice step of the clumped octave, in pixels; ~1 px grain at capture scale. */
    private const val CLUMP_STEP = 1.6f

    /** Share of the field from the per-pixel octave; the rest comes from the clump octave. */
    private const val FINE_SHARE = 0.55f

    /** Lattice step of the density field, in pixels. */
    private const val DENSITY_STEP = 24f

    /** ± swing of the density field around the base amplitude. */
    private const val DENSITY_SWING = 0.25f

    /** Lattice offsets that decorrelate the density field from the clump lattice. */
    private const val DENSITY_OFFSET_X = 51.7f
    private const val DENSITY_OFFSET_Y = 93.1f

    /** Local-detail mask cell size in pixels. */
    private const val DETAIL_DOWNSAMPLE = 4

    /** Sum of both axis luma gradients (as a share of full scale) where grain halves. */
    private const val DETAIL_HALF = 0.10f

    /** Applies grain in place to the packed ARGB output image. */
    fun apply(argb: IntArray, width: Int, height: Int, strength: Float) {
        val amount = strength.coerceIn(0f, 1f)
        if (amount == 0f || argb.isEmpty() || width <= 0 || height <= 0) return

        val maskWidth = maxOf(1, width / DETAIL_DOWNSAMPLE)
        val maskHeight = maxOf(1, height / DETAIL_DOWNSAMPLE)
        val detail = FloatArray(maskWidth * maskHeight)
        accumulateDetail(argb, width, height, detail, maskWidth, maskHeight)

        val peak = MAX_GAIN * amount
        CpuParallel.forEach(height) { startRow, endRow ->
            for (y in startRow until endRow) {
                val row = y * width
                val maskY = (y + 0.5f) * maskHeight / height
                for (x in 0 until width) {
                    val i = row + x
                    val pixel = argb[i]
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    val b = (pixel and 0xFF).toFloat()
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val normalized = luma / 255f
                    val midtone = MIDTONE_FLOOR +
                        (1f - MIDTONE_FLOOR) * 4f * normalized * (1f - normalized)
                    // Dynamic part: grain fades out where the image already carries detail.
                    val localDetail = sampleBilinear(
                        detail,
                        maskWidth,
                        maskHeight,
                        (x + 0.5f) * maskWidth / width,
                        maskY,
                    )
                    val detailWeight = DETAIL_HALF / (DETAIL_HALF + localDetail)
                    val delta = luma * sample(x, y) * peak * midtone * detailWeight
                    // Same whole-level shift on every channel, so R−G and B−G survive
                    // exactly; the bound keeps the pixel inside 0..255 without clamping
                    // one channel before another.
                    val lower = -minOf(r, g, b)
                    val upper = 255f - maxOf(r, g, b)
                    // Dithered rounding: one integer for all three channels (colour stays
                    // exact) but the fractional part is spent probabilistically, so small
                    // amounts keep a fine texture instead of a sparse +/-1 salt pattern.
                    val shift = floor(delta.coerceIn(lower, upper) + hash01(x, y)).toInt()
                    if (shift == 0) continue
                    val red = ((pixel shr 16) and 0xFF) + shift
                    val green = ((pixel shr 8) and 0xFF) + shift
                    val blue = (pixel and 0xFF) + shift
                    argb[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
    }

    /**
     * Peak local-detail energy per mask cell: the sum of both axis luma gradients,
     * peak-pooled so one edge inside a cell protects the whole cell. Peak pooling
     * (rather than averaging) is what makes the mask track structure instead of
     * fading out over busy regions.
     *
     * Gradients are measured between *adjacent* pixels, not as a central difference:
     * a central difference samples every other pixel and is therefore blind to
     * detail sitting at the sampling limit, which is exactly the fine texture and
     * noise the mask has to notice.
     */
    private fun accumulateDetail(
        argb: IntArray,
        width: Int,
        height: Int,
        detail: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
    ) {
        for (my in 0 until maskHeight) {
            val yStart = my * height / maskHeight
            val yEnd = maxOf(yStart + 1, (my + 1) * height / maskHeight)
            for (mx in 0 until maskWidth) {
                val xStart = mx * width / maskWidth
                val xEnd = maxOf(xStart + 1, (mx + 1) * width / maskWidth)
                var peak = 0f
                for (y in yStart until yEnd) {
                    val row = y * width
                    val down = if (y < height - 1) row + width else row
                    for (x in xStart until xEnd) {
                        val i = row + x
                        val right = if (x < width - 1) i + 1 else i
                        val luma = lumaAt(argb, i)
                        val dx = abs(lumaAt(argb, right) - luma)
                        val dy = abs(lumaAt(argb, down + x) - luma)
                        val energy = dx + dy
                        if (energy > peak) peak = energy
                    }
                }
                detail[my * maskWidth + mx] = peak / 255f
            }
        }
    }

    private fun lumaAt(argb: IntArray, index: Int): Float {
        val pixel = argb[index]
        return 0.2126f * ((pixel shr 16) and 0xFF) +
            0.7152f * ((pixel shr 8) and 0xFF) +
            0.0722f * (pixel and 0xFF)
    }

    /**
     * Amount-independent grain field for one pixel, normalized to [-1, 1].
     * Shared by the capture stage and the live preview's noise tile so both show
     * the same grain character.
     */
    internal fun sample(x: Int, y: Int): Float {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fine = hash(x, y)
        val clump = valueNoise(fx / CLUMP_STEP, fy / CLUMP_STEP)
        val density = valueNoise(
            fx / DENSITY_STEP + DENSITY_OFFSET_X,
            fy / DENSITY_STEP + DENSITY_OFFSET_Y,
        )
        // Normalizing by the peak swing keeps the documented gain bound exact.
        val field = FINE_SHARE * fine + (1f - FINE_SHARE) * clump
        return field * (1f + DENSITY_SWING * density) / (1f + DENSITY_SWING)
    }

    /**
     * Bilinear value noise on a hash lattice. Interpolation is linear, not smoothstep:
     * a smoothed profile rounds the lattice peaks into soft blobs, which is what makes
     * grain look blurred rather than granular.
     */
    private fun valueNoise(u: Float, v: Float): Float {
        val x0 = floor(u)
        val y0 = floor(v)
        val fu = u - x0
        val fv = v - y0
        val ix = x0.toInt()
        val iy = y0.toInt()
        val top = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * fu
        val bottom = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * fu
        return top + (bottom - top) * fv
    }

    /** Deterministic dither value in [0, 1) for one pixel. */
    private fun hash01(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return ((h ushr 8) and 0xFFFF) / 65536f
    }

    /** Deterministic white-noise value in [-1, 1] for one lattice point. */
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }
}

/**
 * Separable binomial blur [1,2,1] with edge replication. Shared by denoise and
 * sharpen so their spatial footprint stays identical. Returns a freshly
 * blurred array; [work] is an n-sized scratch buffer the caller owns.
 */
internal fun blur3x3(src: FloatArray, w: Int, h: Int, work: FloatArray): FloatArray {
    val out = FloatArray(w * h)
    blur3x3Into(src, out, w, h, work)
    return out
}

internal fun blur3x3Into(src: FloatArray, out: FloatArray, w: Int, h: Int, work: FloatArray) {
    // Horizontal pass into scratch.
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val row = y * w
            for (x in 0 until w) {
                val left = src[row + if (x > 0) x - 1 else 0]
                val mid = src[row + x]
                val right = src[row + if (x < w - 1) x + 1 else w - 1]
                work[row + x] = (left + 2f * mid + right) * 0.25f
            }
        }
    }
    // Vertical pass into caller-owned output.
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val upRow = if (y > 0) y - 1 else 0
            val downRow = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                out[y * w + x] = (work[upRow * w + x] + 2f * work[y * w + x] + work[downRow * w + x]) * 0.25f
            }
        }
    }
}
