package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.LUMA_B
import com.adin.naturalcam.image.core.LUMA_G
import com.adin.naturalcam.image.core.LUMA_R
import com.adin.naturalcam.image.core.LensShadingMap
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.bilinearGridGain
import com.adin.naturalcam.image.core.luminance
import com.adin.naturalcam.image.core.srgbEncode
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
                    val l0 = luminance(r0, g0, b0)
                    val l1 = luminance(r1, g1, b1)
                    val l2 = luminance(r2, g2, b2)
                    val l3 = luminance(r3, g3, b3)
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
}
/**
 * Edge-aware chroma smoothing for the noise the lens-shading correction amplifies,
 * done on the half-resolution chroma plane the JPEG actually keeps.
 *
 * Correcting the luminance vignette multiplies a pixel's signal *and its noise*
 * by the shading gain — up to ~5x at the frame corner of this lens. Chroma is
 * where that shows, and it is also the cheapest thing to clean: JPEG stores
 * chroma 4:2:0 (verified on device — luma 2x2, chroma 1x1 sampling), so every
 * capture already discards chroma detail below the 2x2 block. Filtering the
 * chroma plane at that resolution removes noise we could not have delivered
 * anyway, and costs a quarter of the pixels.
 *
 * Luma is carried through untouched: the blend moves only the chroma offsets,
 * and the luma it would have changed is subtracted back out, so the stage
 * cannot soften anything. A half-resolution luma guide reduces cross-edge
 * chroma bleed while keeping flat regions fully denoised.
 *
 * Strength per pixel is `1 - 1/gain`, so at the frame centre — where the
 * correction does nothing — it is an exact no-op, and where the correction
 * amplified by `gain` the residual chroma noise returns to the centre's level.
 *
 * Every pass is parallel: the planes are small, the horizontal pass is
 * row-independent, and the vertical pass reads one plane while writing another.
 * The previous full-resolution version was the pipeline's only sequential stage,
 * because a rolling three-row window was the only way to avoid a full-frame scratch.
 */
object ChromaSmoother {

    /** Ceiling on the per-pixel strength; replacing chroma outright reads as a smear. */
    const val MAX_STRENGTH = 0.85f

    fun apply(rgb: RgbImage, map: LensShadingMap?, baseStrength: Float = 0f): RgbImage {
        if (map != null && (map.imageWidth != rgb.width || map.imageHeight != rgb.height)) return rgb
        val grid = map?.meanGrid()
        if (grid == null && baseStrength <= 0f) return rgb
        val width = rgb.width
        val height = rgb.height
        val columns = map?.columns ?: 1
        val rows = map?.rows ?: 1

        val scaleX = if (width <= 1) 0f else (columns - 1).toFloat() / (width - 1)
        val columnLow = IntArray(width)
        val columnHigh = IntArray(width)
        val columnFrac = FloatArray(width)
        for (x in 0 until width) {
            val gx = x * scaleX
            val low = gx.toInt().coerceIn(0, columns - 1)
            val high = (low + 1).coerceAtMost(columns - 1)
            columnLow[x] = low
            columnHigh[x] = high
            columnFrac[x] = if (high == low) 0f else gx - low
        }
        val scaleY = if (height <= 1) 0f else (rows - 1).toFloat() / (height - 1)

        val planeWidth = (width + 1) / 2
        val planeHeight = (height + 1) / 2
        val red = rgb.r
        val green = rgb.g
        val blue = rgb.b

        // Pass 1: 2x2 average of R-G and B-G into the chroma planes, plus a
        // half-resolution luma guide for edge-aware blending.
        val crPlane = FloatArray(planeWidth * planeHeight)
        val cbPlane = FloatArray(planeWidth * planeHeight)
        val lumaPlane = FloatArray(planeWidth * planeHeight)
        CpuParallel.forEach(planeHeight, minItemsPerTask = 64) { start, end ->
            for (py in start until end) {
                val top = py * 2 * width
                val bottom = (py * 2 + 1).coerceAtMost(height - 1) * width
                for (px in 0 until planeWidth) {
                    val left = px * 2
                    val right = (left + 1).coerceAtMost(width - 1)
                    val r = (red[top + left] + red[top + right] + red[bottom + left] + red[bottom + right]) * 0.25f
                    val g = (green[top + left] + green[top + right] + green[bottom + left] + green[bottom + right]) * 0.25f
                    val b = (blue[top + left] + blue[top + right] + blue[bottom + left] + blue[bottom + right]) * 0.25f
                    val i = py * planeWidth + px
                    crPlane[i] = r - g
                    cbPlane[i] = b - g
                    lumaPlane[i] = luminance(r, g, b)
                }
            }
        }

        // Pass 2: edge-aware separable [1,2,1] per plane. The threshold sits above the
        // frame's own noise, so flat and merely noisy regions get the full blur
        // (bit-identical to an unconditional pass) and only real luma edges mix less.
        val edgeThreshold = CHROMA_EDGE_THRESHOLD
        val blurredCr = FloatArray(planeWidth * planeHeight)
        val blurredCb = FloatArray(planeWidth * planeHeight)
        edgeAwareBlur121Horizontal(crPlane, lumaPlane, crPlane, planeWidth, planeHeight, edgeThreshold)
        edgeAwareBlur121Vertical(crPlane, lumaPlane, blurredCr, planeWidth, planeHeight, edgeThreshold)
        edgeAwareBlur121Horizontal(cbPlane, lumaPlane, cbPlane, planeWidth, planeHeight, edgeThreshold)
        edgeAwareBlur121Vertical(cbPlane, lumaPlane, blurredCb, planeWidth, planeHeight, edgeThreshold)


        // Pass 3: rebuild, keeping each pixel's own luma exactly.
        CpuParallel.forEach(height, minItemsPerTask = 128) { startRow, endRow ->
            for (y in startRow until endRow) {
                val gy = y * scaleY
                val gainRowLow = gy.toInt().coerceIn(0, rows - 1)
                val gainRowHigh = (gainRowLow + 1).coerceAtMost(rows - 1)
                val gainRowFrac = if (gainRowHigh == gainRowLow) 0f else gy - gainRowLow
                val gainBaseLow = gainRowLow * columns
                val gainBaseHigh = gainRowHigh * columns

                val v = y * 0.5f
                val planeRow = v.toInt().coerceIn(0, planeHeight - 1)
                val planeRowNext = (planeRow + 1).coerceAtMost(planeHeight - 1)
                val fracY = v - planeRow
                val offHere = planeRow * planeWidth
                val offNext = planeRowNext * planeWidth

                val base = y * width
                for (x in 0 until width) {
                    val gain = if (grid == null) {
                        1f
                    } else {
                        bilinearGridGain(
                            grid, gainBaseLow, gainBaseHigh,
                            columnLow[x], columnHigh[x], columnFrac[x], gainRowFrac,
                        )
                    }
                    // Base strength covers the sensor's own chroma noise anywhere in
                    // the frame; the shading term adds back what the correction
                    // amplified. Without a base, the centre of the frame — where the
                    // gain is 1 — gets no effective chroma denoise at all.
                    val shading = if (gain > 1f) 1f - 1f / gain else 0f
                    val strength = (baseStrength + shading).coerceIn(0f, MAX_STRENGTH)
                    if (strength <= 0f) continue

                    val u = x * 0.5f
                    val planeColumn = u.toInt().coerceIn(0, planeWidth - 1)
                    val planeColumnNext = (planeColumn + 1).coerceAtMost(planeWidth - 1)
                    val fracU = u - planeColumn

                    val targetR = sample(
                        blurredCr[offHere + planeColumn], blurredCr[offHere + planeColumnNext],
                        blurredCr[offNext + planeColumn], blurredCr[offNext + planeColumnNext],
                        fracU, fracY,
                    )
                    val targetB = sample(
                        blurredCb[offHere + planeColumn], blurredCb[offHere + planeColumnNext],
                        blurredCb[offNext + planeColumn], blurredCb[offNext + planeColumnNext],
                        fracU, fracY,
                    )

                    val index = base + x
                    val r = red[index]
                    val g = green[index]
                    val b = blue[index]
                    val deltaR = strength * (targetR - (r - g))
                    val deltaB = strength * (targetB - (b - g))
                    // Keep luma exact: the chroma move shifts it by this much, so
                    // take it back off all three channels.
                    val deltaLuma = LUMA_R * deltaR + LUMA_B * deltaB
                    red[index] = r + deltaR - deltaLuma
                    green[index] = g - deltaLuma
                    blue[index] = b + deltaB - deltaLuma
                }
            }
        }
        return rgb
    }

    private fun sample(
        topLeft: Float,
        topRight: Float,
        bottomLeft: Float,
        bottomRight: Float,
        fracX: Float,
        fracY: Float,
    ): Float {
        val top = topLeft + (topRight - topLeft) * fracX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fracX
        return top + (bottom - top) * fracY
    }
}

/**
 * Estimates how grainy a frame is, so the denoise can be scaled to it.
 *
 * A fixed denoise strength is wrong in both directions at once: it softens a
 * clean, bright frame for nothing, and it is too weak for a dim one where sensor
 * gain has lifted the grain. Single-frame denoising cannot escape the
 * noise-versus-detail trade, but it can spend that trade only where the frame
 * needs it.
 *
 * The measurement is taken in the *delivered* domain, not in linear light. Linear
 * grain is what the sensor produced, but not what you see: the output transfer
 * curve expands the shadows, so the same absolute noise is far more visible
 * there. Reading it linearly made a dim indoor frame look cleaner than a bright
 * daylight one, which is exactly backwards. Both the median gate and the
 * high-pass therefore run on sRGB-encoded values.
 *
 * Grain is then measured where noise is least confusable with detail: the
 * high-pass amplitude over the *darker half* of the frame, where shadow detail is
 * sparse and what is left is mostly sensor noise. Everything runs on a 1-in-4
 * subsample in two cheap passes over a precomputed transfer curve, so the
 * estimate costs no measurable time.
 */
object NoiseEstimator {

    private const val STEP = 4
    private const val BINS = 256

    /** Roughly the standard deviation of the horizontal high-pass for white noise. */
    private const val HIGH_PASS_GAIN = 1.22f

    fun shadowGrain(rgb: RgbImage): Float {
        val width = rgb.width
        val height = rgb.height
        if (width < 3 || height < 3) return 0f
        val red = rgb.r
        val green = rgb.g
        val blue = rgb.b

        val histogram = IntArray(BINS)
        var count = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val encoded = srgbEncode(lumaAt(red, green, blue, y * width + x))
                val bin = (encoded * BINS).toInt()
                if (bin in 0 until BINS) {
                    histogram[bin]++
                    count++
                }
                x += STEP
            }
            y += STEP
        }
        if (count == 0) return 0f
        var running = 0
        var medianBin = 0
        for (bin in 0 until BINS) {
            running += histogram[bin]
            if (running * 2 >= count) {
                medianBin = bin
                break
            }
        }
        val threshold = (medianBin + 0.5f) / BINS

        var sum = 0.0
        var samples = 0
        y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val i = y * width + x
                val encoded = srgbEncode(lumaAt(red, green, blue, i))
                if (encoded <= threshold) {
                    val horizontal =
                        0.5f * (srgbEncode(lumaAt(red, green, blue, i - 1)) + srgbEncode(lumaAt(red, green, blue, i + 1)))
                    sum += abs(encoded - horizontal)
                    samples++
                }
                x += STEP
            }
            y += STEP
        }
        if (samples == 0) return 0f
        return (sum / samples).toFloat() / HIGH_PASS_GAIN
    }

    private fun lumaAt(red: FloatArray, green: FloatArray, blue: FloatArray, index: Int): Float =
        luminance(red[index], green[index], blue[index])
}

/**
 * Smooth, luma-only denoising of the grain the shading correction amplifies.
 *
 * [NoiseReducer]'s 2x2 luma path is deliberately not used for this: a 2x2 block
 * average is block-periodic, and a block grid measures *worse* at pixel scale
 * than no filtering at all. Denoising through it and then sharpening left more
 * flat-area noise (+48%) than either alone, which is why a separable [1,2,1]
 * kernel is used instead.
 *
 * Only luminance moves: the same offset is added to all three channels, so R-G
 * and B-G are preserved exactly and no colour is touched. It is meant to run
 * before [Sharpener], whose coring then restores edge contrast without lifting
 * the reduced grain back up.
 *
 * Memory is a single frame buffer: the blur is built in one plane, and the
 * final pass reads that plane while writing the image itself. Each pixel owns
 * its own update and reads nothing another pixel writes, so every pass
 * parallelizes. Strength 0 is an exact no-op.
 */
object LumaDenoiser {

    fun apply(rgb: RgbImage, strength: Float): RgbImage {
        val amount = strength.coerceIn(0f, 1f)
        if (amount == 0f) return rgb
        val width = rgb.width
        val height = rgb.height
        val red = rgb.r
        val green = rgb.g
        val blue = rgb.b
        val plane = FloatArray(width * height)

        CpuParallel.forEach(height, minItemsPerTask = 128) { start, end ->
            for (y in start until end) {
                val base = y * width
                for (x in 0 until width) {
                    val i = base + x
                    plane[i] = LUMA_R * red[i] + LUMA_G * green[i] + LUMA_B * blue[i]
                }
            }
        }

        // Horizontal [1,2,1] in place, via the shared blur helper so the kernel is
        // identical to the sharpener/bloom blur. Rows are independent, so it parallelizes.
        blur121Horizontal(plane, plane, width, height)

        // Vertical [1,2,1] stays fused with the blend below rather than calling
        // blur121Vertical into a scratch plane: this stage deliberately owns a single
        // full-frame buffer, and a shared vertical pass would need a second one.
        CpuParallel.forEach(height, minItemsPerTask = 128) { start, end ->
            for (y in start until end) {
                val up = (y - 1).coerceAtLeast(0) * width
                val mid = y * width
                val down = (y + 1).coerceAtMost(height - 1) * width
                for (x in 0 until width) {
                    val i = mid + x
                    val blurred = (plane[up + x] + 2f * plane[mid + x] + plane[down + x]) * 0.25f
                    val luma = LUMA_R * red[i] + LUMA_G * green[i] + LUMA_B * blue[i]
                    val delta = amount * (blurred - luma)
                    red[i] += delta
                    green[i] += delta
                    blue[i] += delta
                }
            }
        }
        return rgb
    }
}

/**
 * Conservative unsharp masking (PRD 20): real detail over perceived sharpness,
 * amount clamped to 0.5 to make halos structurally hard to produce. The blur
 * is a separable [1,2,1]/4 two-pass filter (radius ≈ 1 px at the default).
 * Amount 0 is an exact no-op (AGENTS 27).
 *
 * The high-pass is *cored*: only detail above [NOISE_REJECT] times the image's
 * own measured noise floor is boosted. Sharpening and luma noise occupy the same
 * spatial band, so an uncored unsharp simply buys acutance and noise in equal
 * measure — measured on a device DNG, +7% acutance cost +7% flat-area noise.
 * Thresholding at the noise amplitude passes the edges and leaves the grain
 * alone, which is the only way to get crisper without getting noisier.
 */
object Sharpener {

    const val MAX_AMOUNT = 0.5f

    /** Detail must exceed this multiple of the measured noise floor before it is boosted. */
    const val NOISE_REJECT = 2f

    /** Coarse-histogram ceiling for the residual; larger than this is scene edge, not noise. */
    private const val FLOOR_CEILING = 0.25f

    fun sharpen(rgb: RgbImage, amount: Float, radiusPx: Float): RgbImage {
        val a = amount.coerceIn(0f, MAX_AMOUNT)
        if (a == 0f) return rgb
        val passes = radiusPx.coerceAtLeast(1f).toInt().coerceIn(1, 3)
        val n = rgb.r.size
        val work = FloatArray(n)
        val blurredA = FloatArray(n)
        val blurredB = if (passes > 1) FloatArray(n) else blurredA
        // Green first: it carries most of the luma and the least white-balance
        // gain, so its residual is the best stand-in for luminance noise. One cut
        // for all three channels keeps the sharpening from reacting to a change
        // in *chroma* noise (the smoother's) as though it were detail.
        var cut = 0f
        for ((index, channel) in arrayOf(rgb.g, rgb.r, rgb.b).withIndex()) {
            var source = channel
            var target = blurredA
            repeat(passes) {
                blur3x3Into(source, target, rgb.width, rgb.height, work)
                source = target
                target = if (target === blurredA) blurredB else blurredA
            }
            if (index == 0) cut = NOISE_REJECT * noiseFloor(channel, source)
            CpuParallel.forEach(n) { start, end ->
                for (i in start until end) {
                    val detail = channel[i] - source[i]
                    val magnitude = abs(detail) - cut
                    if (magnitude > 0f) {
                        channel[i] += if (detail < 0f) -a * magnitude else a * magnitude
                    }
                }
            }
        }
        return rgb
    }

    /**
     * Noise amplitude of the high-frequency residual, as a histogram median over
     * a subsample. A median rather than a mean because scene edges drag the mean
     * towards the picture instead of the grain.
     */
    private fun noiseFloor(channel: FloatArray, blurred: FloatArray): Float {
        val bins = 256
        val histogram = IntArray(bins)
        var count = 0
        var i = 0
        while (i < channel.size) {
            val bin = (abs(channel[i] - blurred[i]) * bins / FLOOR_CEILING).toInt()
            if (bin < bins) {
                histogram[bin]++
                count++
            }
            i += 4
        }
        if (count == 0) return 0f
        val target = count / 2
        var running = 0
        for (b in 0 until bins) {
            running += histogram[b]
            if (running >= target) return (b + 0.5f) * FLOOR_CEILING / bins
        }
        return 0f
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
        // Mask rows are independent and each cell writes only its own entry. This ran
        // serially over every pixel of every channel and was the bloom control's cost.
        CpuParallel.forEach(maskHeight, minItemsPerTask = 8) { startRow, endRow ->
            for (my in startRow until endRow) {
                val yStart = my * height / maskHeight
                val yEnd = maxOf(yStart + 1, (my + 1) * height / maskHeight)
                val maskRow = my * maskWidth
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
                    mask[maskRow + mx] = peak
                }
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
                    val luma = luminance(r, g, b)
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
        // Independent mask rows, same as the bloom mask: this was a serial full-frame scan.
        CpuParallel.forEach(maskHeight, minItemsPerTask = 8) { startRow, endRow ->
            for (my in startRow until endRow) {
                val yStart = my * height / maskHeight
                val yEnd = maxOf(yStart + 1, (my + 1) * height / maskHeight)
                val maskRow = my * maskWidth
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
                    detail[maskRow + mx] = peak / 255f
                }
            }
        }
    }

    private fun lumaAt(argb: IntArray, index: Int): Float {
        val pixel = argb[index]
        return luminance(
            ((pixel shr 16) and 0xFF).toFloat(),
            ((pixel shr 8) and 0xFF).toFloat(),
            (pixel and 0xFF).toFloat(),
        )
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
 * Horizontal pass of a separable binomial [1,2,1] blur with edge replication.
 * Each row is independent, so it parallelizes. Safe with `src === dst`: each
 * sample is read before its destination slot is written, and the left neighbour
 * is carried in a local rather than re-read.
 */
internal fun blur121Horizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val row = y * w
            var left = src[row]
            for (x in 0 until w) {
                val mid = src[row + x]
                val right = src[row + (x + 1).coerceAtMost(w - 1)]
                dst[row + x] = (left + 2f * mid + right) * 0.25f
                left = mid
            }
        }
    }
}

/**
 * Vertical pass of the same [1,2,1] blur; [src] and [dst] must differ, since a
 * row is read as the neighbour of the rows it is written among.
 */
internal fun blur121Vertical(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val up = (y - 1).coerceAtLeast(0) * w
            val mid = y * w
            val down = (y + 1).coerceAtMost(h - 1) * w
            for (x in 0 until w) {
                dst[mid + x] = (src[up + x] + 2f * src[mid + x] + src[down + x]) * 0.25f
            }
        }
    }
}

/**
 * Luma difference below which two neighbouring half-resolution guide samples are
 * treated as the same surface, so the chroma blur runs across them unchanged.
 *
 * Calibrated by measurement, not guessed: the guide's own 1-px step reads a median
 * of 0.002 linear on a dim frame and 0.010 on an indoor one, and the lens-shading
 * correction lifts corner noise by up to ~5x on top of that — so a threshold of
 * 0.12 sits an order of magnitude above the noisiest corner the app ships, while a
 * real edge (a window frame against a wall, a silhouette) clears it by several
 * times. A threshold derived from the frame's *median* step does not work: the
 * noise is not uniform, so the corners still tripped it and lost their denoise
 * (measured +15% residual midtone chroma HF on a dusk frame, against 0% here).
 *
 * If a future sensor or ISO range exceeds this, the fix is a spatially varying
 * threshold, not a larger constant: at some point every edge would be suppressed too.
 */
private const val CHROMA_EDGE_THRESHOLD = 0.12f

/**
 * Guide-difference weight: at or below [threshold] the difference is surface, not
 * an edge, so the pixel gets the full blur; above it the weight falls off as
 * `threshold / difference`, so a real edge mixes progressively less across itself.
 */
private fun chromaEdgeWeight(difference: Float, threshold: Float): Float =
    if (difference <= threshold) 1f else threshold / difference

/** Luma-guided binomial pass; safe in-place because neighbours are read first. */
internal fun edgeAwareBlur121Horizontal(
    src: FloatArray,
    guide: FloatArray,
    dst: FloatArray,
    w: Int,
    h: Int,
    threshold: Float,
) {
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val row = y * w
            var left = src[row]
            var leftGuide = guide[row]
            for (x in 0 until w) {
                val index = row + x
                val mid = src[index]
                val centerGuide = guide[index]
                val rightIndex = row + (x + 1).coerceAtMost(w - 1)
                val right = src[rightIndex]
                val rightGuide = guide[rightIndex]
                val leftWeight = chromaEdgeWeight(abs(centerGuide - leftGuide), threshold)
                val rightWeight = chromaEdgeWeight(abs(centerGuide - rightGuide), threshold)
                val weighted = leftWeight * left + 2f * mid + rightWeight * right
                // Ungated samples (the overwhelming majority) reduce to the plain [1,2,1]
                // exactly: skipping the normalising divide keeps the stage's cost where it
                // was, and makes flat regions bit-identical to an unconditional pass.
                dst[index] =
                    if (leftWeight == 1f && rightWeight == 1f) weighted * 0.25f
                    else weighted / (leftWeight + 2f + rightWeight)
                left = mid
                leftGuide = centerGuide
            }
        }
    }
}

/** Luma-guided vertical pass; source and destination must differ. */
internal fun edgeAwareBlur121Vertical(
    src: FloatArray,
    guide: FloatArray,
    dst: FloatArray,
    w: Int,
    h: Int,
    threshold: Float,
) {
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val up = (y - 1).coerceAtLeast(0) * w
            val mid = y * w
            val down = (y + 1).coerceAtMost(h - 1) * w
            for (x in 0 until w) {
                val index = mid + x
                val centerGuide = guide[index]
                val upWeight = chromaEdgeWeight(abs(centerGuide - guide[up + x]), threshold)
                val downWeight = chromaEdgeWeight(abs(centerGuide - guide[down + x]), threshold)
                val weighted = upWeight * src[up + x] + 2f * src[index] + downWeight * src[down + x]
                dst[index] =
                    if (upWeight == 1f && downWeight == 1f) weighted * 0.25f
                    else weighted / (upWeight + 2f + downWeight)
            }
        }
    }
}

/**
 * Separable binomial blur [1,2,1] with edge replication, into a caller-owned
 * output buffer. Shared by the sharpener and the bloom mask so their spatial
 * footprint stays identical; [work] is an n-sized scratch buffer the caller owns.
 * Composed from [blur121Horizontal]/[blur121Vertical], the same primitives the
 * chroma smoother and the luma denoiser use.
 */
internal fun blur3x3Into(src: FloatArray, out: FloatArray, w: Int, h: Int, work: FloatArray) {
    blur121Horizontal(src, work, w, h)
    blur121Vertical(work, out, w, h)
}
