package com.adin.naturalcam.image.core

import kotlin.math.pow

/**
 * Shared color math (AGENTS 21: color logic lives in one place). The Rec.709
 * luma weights were retyped across several stages, and the linear→sRGB output
 * transfer was built identically in two; both live here now so the stages cannot
 * drift apart on the coefficients or the curve.
 */

/** Rec.709 luma weights (ITU-R BT.709 primaries, the same ones sRGB uses). */
internal const val LUMA_R = 0.2126f
internal const val LUMA_G = 0.7152f
internal const val LUMA_B = 0.0722f

/** Rec.709 luma of a linear-light RGB triple. */
internal fun luminance(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

/** Resolution of the sRGB output-transfer table (buckets across linear [0,1]). */
private const val SRGB_LUT_SIZE = 4096

/**
 * Linear → sRGB transfer (IEC 61966-2-1) sampled at [SRGB_LUT_SIZE] buckets. The
 * top bucket is pinned to exactly 1 so the white endpoint is exact; the transfer
 * itself evaluates to one ulp below 1 there.
 */
private val srgbTransfer = FloatArray(SRGB_LUT_SIZE + 1) { index ->
    val linear = index / SRGB_LUT_SIZE.toFloat()
    if (linear <= 0.0031308f) 12.92f * linear else 1.055f * linear.pow(1f / 2.4f) - 0.055f
}.also { it[SRGB_LUT_SIZE] = 1f }

/**
 * sRGB-encoded value for a linear-light level, linearly interpolated between
 * buckets; levels at or below 0 map to 0 and at or above 1 map to 1.
 */
internal fun srgbEncode(linear: Float): Float {
    val scaled = linear * SRGB_LUT_SIZE
    if (scaled <= 0f) return 0f
    if (scaled >= SRGB_LUT_SIZE) return 1f
    val index = scaled.toInt()
    val fraction = scaled - index
    return srgbTransfer[index] + (srgbTransfer[index + 1] - srgbTransfer[index]) * fraction
}
