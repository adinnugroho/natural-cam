package com.adin.naturalcam.image.core

import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.StyleState

/** Mild chroma-only cleanup; luminance grain remains untouched. */
internal const val NATURAL_CHROMA_DENOISE_STRENGTH = 0.18f

/**
 * Style-chain cleanup, scaled by style strength: the style workspace is a
 * creative layer, so it starts from a slightly cleaner base before bloom and
 * grain are added. Chroma is treated more strongly than luminance (AGENTS 26)
 * to keep real texture and avoid waxy rendering.
 */
internal const val STYLE_CHROMA_DENOISE_STRENGTH = 0.6f
internal const val STYLE_LUMA_DENOISE_STRENGTH = 0.20f

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
 * Explicit, centralized tuning parameters (AGENTS 64). MVP values are the
 * restrained starting points from SPEC 130; they are empirical placeholders
 * until real-device evaluation (AGENTS 63) — see LIMITATIONS.md.
 */
data class ToneConfig(
    /**
     * NATURAL recipe exposure lift in stops, applied in linear light inside the
     * pipeline. This is a fixed part of the NATURAL look, independent of the
     * camera EV control the user drives from the shutter bar.
     */
    val exposureStops: Float = 1.5f,
    /** Above-unity toe exponent keeps shadows deeper while the lift restores overall brightness. */
    val midtoneGamma: Float = 1.04f,
    /** Slightly stronger global contrast keeps the lifted image from looking faded. */
    val contrast: Float = 0.05f,
    /** Linear level where highlight shoulder starts. */
    val highlightRollOffStart: Float = 0.75f,
    /** Minimal shoulder preserves highlight contrast without a washed/faded look. */
    val highlightCompression: Float = 0.03f,
    /** Very small NATURAL-only red/blue balance toward warmth; zero keeps neutral rendering. */
    val warmth: Float = 0.01f,
    /** User temperature adjustment; negative cools, positive warms, normalized [-1, 1]. */
    val temperature: Float = 0f,
)

data class ProcessingConfiguration(
    val profile: ProcessingProfile,
    val pipelineVersion: String,
    val tone: ToneConfig = ToneConfig(),
    /** Creative style state applied after NATURAL base processing (STYLE_PLAN 15). */
    val style: StyleState = StyleState(),
    /** NATURAL-v13 preserves luminance grain; only chroma denoise is enabled. */
    val chromaDenoiseStrength: Float = 0f,
    val lumaDenoiseStrength: Float = 0f,
    /** NATURAL-v13 avoids full-frame unsharp masking on the capture path. */
    val sharpenAmount: Float = 0f,
    val sharpenRadiusPx: Float = 1.0f,
    val jpegQuality: Int = 92,
) {
    companion object {
        const val PIPELINE_VERSION = "natural-v26"

        fun forProfile(
            profile: ProcessingProfile,
            jpegQuality: Int = 92,
            temperature: Float = 0f,
            style: StyleState = StyleState(),
        ): ProcessingConfiguration =
            when (profile) {
                ProcessingProfile.NATURAL -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = PIPELINE_VERSION,
                    tone = ToneConfig(temperature = temperature.coerceIn(-1f, 1f)),
                    style = style,
                    jpegQuality = jpegQuality,
                    chromaDenoiseStrength = NATURAL_CHROMA_DENOISE_STRENGTH,
                )
                // PURE: only what is required for a viewable image (SPEC 131).
                ProcessingProfile.PURE -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "pure-v2",
                    // Explicit: PURE never takes the NATURAL recipe lift.
                    tone = ToneConfig(exposureStops = 0f, contrast = 0f, highlightCompression = 0f),
                    chromaDenoiseStrength = 0f,
                    lumaDenoiseStrength = 0f,
                    sharpenAmount = 0f,
                    jpegQuality = jpegQuality,
                )
                // SYSTEM output is produced by the platform pipeline, not this configuration.
                ProcessingProfile.SYSTEM -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "system-passthrough",
                    // Explicit: SYSTEM never takes the NATURAL recipe lift.
                    tone = ToneConfig(exposureStops = 0f, contrast = 0f, highlightCompression = 0f),
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
