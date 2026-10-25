package com.adin.naturalcam.image.core

import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.StyleState

/**
 * Baseline strength of the half-resolution chroma smoother in NATURAL — the value
 * for a *clean* frame. [MAX_CHROMA_SMOOTH_STRENGTH] is where a noisy one lands.
 */
internal const val NATURAL_CHROMA_SMOOTH_STRENGTH = 0.45f

/**
 * Adaptive denoise (see `NoiseEstimator.shadowGrain`). A fixed strength is wrong
 * at both ends: it softens a clean frame for nothing and under-serves a dim one.
 * These are the grain values at which a frame counts as clean, and as noisy as
 * it gets; between them the smoothing scales linearly.
 */
internal const val GRAIN_CLEAN = 0.0050f
internal const val GRAIN_NOISY = 0.0140f

/** Smoothing at the noisy end. The clean end is each profile's own configured value. */
internal const val MAX_CHROMA_SMOOTH_STRENGTH = 0.85f
internal const val MAX_LUMA_SMOOTH_STRENGTH = 0.55f

/** 0 for a clean frame, 1 at [GRAIN_NOISY] or beyond. */
internal fun grainLevel(grain: Float): Float =
    ((grain - GRAIN_CLEAN) / (GRAIN_NOISY - GRAIN_CLEAN)).coerceIn(0f, 1f)

/**
 * Capture sharpening for NATURAL. The stage is cored against the image's own
 * noise floor, so the amount buys edge acutance without lifting flat-area
 * grain; this is well under the 0.5 ceiling that keeps halos structurally hard.
 */
internal const val NATURAL_SHARPEN_AMOUNT = 0.2f

/**
 * Smooth luma-only denoise for NATURAL, applied before the cored sharpener.
 *
 * This replaces the style chain's 2x2 luma pass, which v32 removed outright —
 * and removing it was a regression: on the same desk scene, dark-pixel luma
 * noise went 4.23 to 5.96. At 0.5 the trade is not worth taking (25-35% of the
 * 1-px detail for grain the filter only partly reaches) and it contours smooth
 * gradients, but at 0.2 it recovers what the 2x2 pass was achieving without
 * leaving a block grid behind.
 */
internal const val NATURAL_LUMA_SMOOTH_STRENGTH = 0.08f

/**
 * Style-chain chroma cleanup, scaled by style strength: the style workspace is a
 * creative layer, so it starts from a slightly cleaner base before bloom and
 * grain are added. Chroma only — luminance texture belongs to the base chain, so
 * a styled capture cannot go waxy.
 */
internal const val STYLE_CHROMA_DENOISE_STRENGTH = 0.6f

/**
 * Chroma multiplier span of the style Saturation control: the slider's -1..+1 maps
 * to a chroma scale of 1 ± this around luma, so 0 is exactly neutral and the
 * endpoints are a strong but bounded change (0.4x .. 1.6x).
 */
internal const val SATURATION_RANGE = 0.6f

/**
 * Extra chroma denoise per unit of *positive* Saturation, applied as a second 2x2
 * chroma pass on a shifted block grid. Boosting saturation scales the chroma offset,
 * and with it whatever chroma noise the pixel already carries, so the control pays
 * for its own amplification: at full boost the shift-grid pass cancels the noise the
 * 1.6x chroma scale would have magnified, while the extra smoothing stays inside the
 * chroma resolution JPEG 4:2:0 discards anyway. Reducing saturation needs no
 * compensation because it shrinks chroma noise along with the chroma.
 */
internal const val SATURATION_CHROMA_DENOISE = 1.0f

/**
 * Red/blue balance the temperature control contributes per unit of its -1..+1
 * range. The slider spans the whole range, so this sets its authority: at +1
 * the image is (1 + span) red against (1 - span) blue, and at -1 the reverse.
 * The previous 0.08 topped out at +9% red / -9% blue, which reads as "cranked
 * to max and still cold"; 0.20 was measured as still short of the ends a user
 * expects, so it carries a little further in both directions.
 */
internal const val TEMPERATURE_WARMTH_SPAN = 0.32f

/**
 * Ceiling on the combined NATURAL warmth, set just above base + span so the
 * slider never has a dead zone at the end of its travel. Past this the
 * red/blue balance stops reading as light and starts reading as a colour cast.
 */
internal const val MAX_NATURAL_WARMTH = 0.38f

/**
 * Red/blue balance NATURAL actually applies, from the recipe's own warmth plus
 * the user's temperature. Extracted so the "no dead zone at the end of the
 * slider" invariant is testable rather than implied by two constants that must
 * stay in step.
 */
internal fun naturalWarmth(tone: ToneConfig): Float =
    (tone.warmth + tone.temperature * TEMPERATURE_WARMTH_SPAN)
        .coerceIn(-MAX_NATURAL_WARMTH, MAX_NATURAL_WARMTH)

/**
 * Explicit, centralized tuning parameters (AGENTS 64). MVP values are the
 * restrained starting points from SPEC 130; they are empirical placeholders
 * until real-device evaluation (AGENTS 63) — see LIMITATIONS.md.
 */
data class ToneConfig(
    /**
     * NATURAL recipe exposure in stops. **Zero**: the delivered level is the RAW
     * development's own, so the black point, midtone level and highlight headroom
     * are the sensor's, not a recipe's. The recipe's brightness came from a fixed
     * lift (+0.75 EV at v36-v38) that had to be fought back down by a deepening
     * shadow toe — two tone moves pushing against each other, and still ~30%
     * brighter than a plain development with 12% of a bright frame's pixels
     * compressed by the shoulder instead of left where the sensor put them.
     * Measured on two device DNGs (bright outdoor, dim indoor) with the lift and
     * the toe removed: black point p05 18.3 -> 18.4 / 8.9 -> 9.0, p1 19.6 -> 19.7 /
     * 10.8 -> 10.8, median 104.1 -> 104.5 / 42.4 -> 42.5, p95 255 -> 250.3 / 103.7 ->
     * 104.1 — i.e. the RAW development itself, with only the shoulder acting on the
     * top end so clipped highlights stop being flat white. The toe and the small
     * contrast above the pivot (below) then add separation *around* that level.
     */
    val exposureStops: Float = 0f,
    /**
     * Sub-pivot tone exponent. A restrained toe: below mid-gray the frame is
     * deepened away from the veil a straight RAW development leaves (a linear
     * negative with no base curve reads as haze — the "fade" users report), while
     * the pivot is a touch under mid-gray and the value at 0 stays 0, so the black
     * point is only *deepened*, never lifted.
     *
     * Measured on three device DNGs against the v40 curve (delivered levels/255):
     * bright outdoor p0.5 14.2 -> 12.7, p5 25.5 -> 23.8, p10 32.5 -> 30.8,
     * median 103.3 -> 102.8, p75 168.7 -> 169.3; bright indoor p0.5 26.5 -> 24.8,
     * p5 42.5 -> 40.8, median 127.4 -> 127.5; dusk street p0.5 3.1 -> 2.6,
     * p5 9.4 -> 8.1, median 30.2 -> 28.5. The upper half gets the matching
     * small lift; the highlight shoulder stays in place, so bright-frame p95
     * remains 250.3 and clipping remains 0.10% instead of returning to the RAW-like
     * 12.57% hard-clipped pixels.
     */
    val midtoneGamma: Float = 1.14f,
    /**
     * Contrast above the mid-gray pivot. **0.06**: the upper mids gain a little
     * more separation while the existing shoulder keeps specular highlights smooth.
     */
    val contrast: Float = 0.06f,
    /** Linear level where highlight shoulder starts. */
    val highlightRollOffStart: Float = 0.75f,
    /**
     * Minimal shoulder. The restrained curve lifts upper mids, then this shoulder
     * keeps specular highlights smooth: on a bright frame the plain development
     * hard-clips 12.15% of pixels to 255, while NATURAL lands at p95 250.3 with
     * 0.10% clipped. RAW-like hard clipping remains an explicit tradeoff, not the
     * NATURAL default.
     */
    val highlightCompression: Float = 0.03f,
    /**
     * NATURAL's own red/blue balance toward warmth, independent of the user's
     * temperature. Sized to read as natural morning/evening light rather than a
     * cast; zero keeps neutral rendering.
     */
    val warmth: Float = 0.05f,
    /** User temperature adjustment; negative cools, positive warms, normalized [-1, 1]. */
    val temperature: Float = 0f,
)

data class ProcessingConfiguration(
    val profile: ProcessingProfile,
    val pipelineVersion: String,
    val tone: ToneConfig = ToneConfig(),
    /** Creative style state applied after NATURAL base processing (STYLE_PLAN 15). */
    val style: StyleState = StyleState(),
    /**
     * Aspect ratio of the delivered image. Geometry only — the crop keeps pixels of the
     * same rendering untouched — so it is not part of the processing recipe and does not
     * affect [pipelineVersion]. The default 4:3 value is the full sensor frame on
     * the supported 4:3 sensors.
     */
    val outputAspect: AspectRatio = AspectRatio.RATIO_4_3,
    /**
     * Strength of the 2x2 block chroma pass of `NoiseReducer` (the `reduce`
     * stage). 0 for every built-in profile — NATURAL cleans chroma with
     * [chromaSmoothStrength] instead — but the stage is a real, testable knob.
     */
    val chromaDenoiseStrength: Float = 0f,
    /** Base strength for the half-resolution chroma smoother; the shading gain adds to it. */
    val chromaSmoothStrength: Float = 0f,
    /** Luma strength of the same 2x2 block pass; 0 for every built-in profile. */
    val lumaDenoiseStrength: Float = 0f,
    /**
     * Smooth 3x3 luma-only denoise ([LumaDenoiser]). Distinct from
     * [lumaDenoiseStrength], whose 2x2 block kernel belongs to the `reduce`
     * stage and is block-periodic.
     */
    val lumaSmoothStrength: Float = 0f,
    /** Cored capture sharpening; see [Sharpener] for why the amount stays low. */
    val sharpenAmount: Float = 0f,
    val sharpenRadiusPx: Float = 1.0f,
    val jpegQuality: Int = 92,
) {
    companion object {
        const val PIPELINE_VERSION = "natural-v43"

        fun forProfile(
            profile: ProcessingProfile,
            jpegQuality: Int = 92,
            temperature: Float = 0f,
            style: StyleState = StyleState(),
            aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        ): ProcessingConfiguration =
            when (profile) {
                ProcessingProfile.NATURAL -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = PIPELINE_VERSION,
                    tone = ToneConfig(temperature = temperature.coerceIn(-1f, 1f)),
                    style = style,
                    outputAspect = aspectRatio,
                    jpegQuality = jpegQuality,
                    chromaSmoothStrength = NATURAL_CHROMA_SMOOTH_STRENGTH,
                    lumaSmoothStrength = NATURAL_LUMA_SMOOTH_STRENGTH,
                    sharpenAmount = NATURAL_SHARPEN_AMOUNT,
                    sharpenRadiusPx = 1f,
                )
                // PURE: only what is required for a viewable image (SPEC 131).
                ProcessingProfile.PURE -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "pure-v2",
                    // Explicit: PURE never takes the NATURAL recipe lift or its warmth.
                    tone = ToneConfig(exposureStops = 0f, contrast = 0f, highlightCompression = 0f, warmth = 0f),
                    outputAspect = aspectRatio,
                    chromaDenoiseStrength = 0f,
                    lumaDenoiseStrength = 0f,
                    sharpenAmount = 0f,
                    jpegQuality = jpegQuality,
                )
                // SYSTEM output is produced by the platform pipeline, not this configuration.
                ProcessingProfile.SYSTEM -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "system-passthrough",
                    // Explicit: SYSTEM never takes the NATURAL recipe lift or its warmth.
                    tone = ToneConfig(exposureStops = 0f, contrast = 0f, highlightCompression = 0f, warmth = 0f),
                    outputAspect = aspectRatio,
                    chromaDenoiseStrength = 0f,
                    lumaDenoiseStrength = 0f,
                    sharpenAmount = 0f,
                    jpegQuality = jpegQuality,
                )
            }
    }
}

/** Encoded final image bytes. Encoding stays separate from processing (PRD 24). */
data class EncodedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        other is EncodedImage && width == other.width && height == other.height && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = width * 31 + height
}

fun interface JpegEncoder {
    /** Encodes ARGB_8888 ints to JPEG. Must not modify [argb]. */
    fun encode(argb: IntArray, width: Int, height: Int, quality: Int): EncodedImage
}
