package com.adin.naturalcam.image.core

import com.adin.naturalcam.domain.ProcessingProfile

/** Mild chroma-only cleanup; luminance grain remains untouched. */
internal const val NATURAL_CHROMA_DENOISE_STRENGTH = 0.18f

/**
 * Explicit, centralized tuning parameters (AGENTS 64). MVP values are the
 * restrained starting points from SPEC 130; they are empirical placeholders
 * until real-device evaluation (AGENTS 63) — see LIMITATIONS.md.
 */
data class ToneConfig(
    /** Exposure offset in stops applied in linear light. */
    val exposureStops: Float = 0f,
    /** Above-unity toe exponent deepens NATURAL shadows instead of lifting the black floor. */
    val midtoneGamma: Float = 1.06f,
    /** Slightly stronger global contrast keeps the scene from looking faded. */
    val contrast: Float = 0.04f,
    /** Linear level where highlight shoulder starts. */
    val highlightRollOffStart: Float = 0.75f,
    /** Minimal shoulder preserves highlight contrast without a washed/faded look. */
    val highlightCompression: Float = 0.03f,
    /** Very small NATURAL-only red/blue balance toward warmth; zero keeps neutral rendering. */
    val warmth: Float = 0.01f,
)

data class ProcessingConfiguration(
    val profile: ProcessingProfile,
    val pipelineVersion: String,
    val tone: ToneConfig = ToneConfig(),
    /** NATURAL-v10 preserves luminance grain; only chroma denoise is enabled. */
    val chromaDenoiseStrength: Float = 0f,
    val lumaDenoiseStrength: Float = 0f,
    /** NATURAL-v10 avoids full-frame unsharp masking on the capture path. */
    val sharpenAmount: Float = 0f,
    val sharpenRadiusPx: Float = 1.0f,
    val jpegQuality: Int = 92,
) {
    companion object {
        const val PIPELINE_VERSION = "natural-v10"

        fun forProfile(profile: ProcessingProfile, jpegQuality: Int = 92): ProcessingConfiguration =
            when (profile) {
                ProcessingProfile.NATURAL -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = PIPELINE_VERSION,
                    jpegQuality = jpegQuality,
                    chromaDenoiseStrength = NATURAL_CHROMA_DENOISE_STRENGTH,
                )
                // PURE: only what is required for a viewable image (SPEC 131).
                ProcessingProfile.PURE -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "pure-v2",
                    tone = ToneConfig(contrast = 0f, highlightCompression = 0f),
                    chromaDenoiseStrength = 0f,
                    lumaDenoiseStrength = 0f,
                    sharpenAmount = 0f,
                    jpegQuality = jpegQuality,
                )
                // SYSTEM output is produced by the platform pipeline, not this configuration.
                ProcessingProfile.SYSTEM -> ProcessingConfiguration(
                    profile = profile,
                    pipelineVersion = "system-passthrough",
                    tone = ToneConfig(contrast = 0f, highlightCompression = 0f),
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
