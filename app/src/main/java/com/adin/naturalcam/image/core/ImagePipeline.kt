package com.adin.naturalcam.image.core

import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.image.processing.ColorTransformStage
import com.adin.naturalcam.image.processing.ClippedHighlightNeutralizer
import com.adin.naturalcam.image.processing.ChromaSmoother
import com.adin.naturalcam.image.processing.ExposureProcessor
import com.adin.naturalcam.image.processing.GamutMapper
import com.adin.naturalcam.image.processing.GrainStage
import com.adin.naturalcam.image.processing.HighlightRollOff
import com.adin.naturalcam.image.processing.ImageRotation
import com.adin.naturalcam.image.processing.LumaDenoiser
import com.adin.naturalcam.image.processing.NaturalToneMapper
import com.adin.naturalcam.image.processing.NoiseEstimator
import com.adin.naturalcam.image.processing.NoiseReducer
import com.adin.naturalcam.image.processing.OutputTransformer
import com.adin.naturalcam.image.processing.Sharpener
import com.adin.naturalcam.image.processing.WhiteBalanceStage
import com.adin.naturalcam.image.raw.ColorMatrixFactory
import com.adin.naturalcam.image.raw.Demosaicer
import com.adin.naturalcam.image.raw.LensShadingCorrector
import com.adin.naturalcam.image.raw.RawNormalizer
import com.adin.naturalcam.image.style.StyleEngine
import com.adin.naturalcam.image.yuv.YuvToRgbConverter

/**
 * Processing boundary (SPEC 50). Profiles are product intent; this pipeline is
 * one implementation of them (SPEC 19). Stages never mutate their inputs.
 */
interface ImagePipeline {
    val version: String
    fun processRaw(raw: RawImage, config: ProcessingConfiguration): EncodedImage

    /** [orientationDegrees] is the clockwise display rotation, baked into pixels (SPEC 89). */
    fun processYuv(yuv: YuvImage, config: ProcessingConfiguration, orientationDegrees: Int): EncodedImage
}

/**
 * Reference CPU pipeline, natural-v26 (SPEC 130):
 * normalize → lens-shading correction → metadata WB → calibration-selected
 * color transform → residual neutral correction → clipped-highlight
 * neutralization → exposure → hue-preserving tone/roll-off → chroma denoise →
 * shading-scaled chroma smoothing → gamut → sRGB → encode.
 *
 * PURE/SYSTEM inputs take only the transformations required for a viewable
 * image (SPEC 131). YUV input arrives already color-rendered by the ISP, so
 * white balance, the camera matrix and lens shading are skipped for it.
 */
class DefaultImagePipeline(private val encoder: JpegEncoder) : ImagePipeline {

    override val version: String = ProcessingConfiguration.PIPELINE_VERSION

    override fun processRaw(raw: RawImage, config: ProcessingConfiguration): EncodedImage {
        val bayer = RawNormalizer.normalize(raw)
        // Lens-shading colour correction first: the GainMap is per CFA position and
        // must be undone before demosaic blends neighbouring samples (SPEC 31).
        val shaded = raw.metadata.lensShading?.let { LensShadingCorrector.correct(bayer, it) } ?: bayer
        var rgb = Demosaicer.demosaic(shaded)

        val meta = raw.metadata
        val neutral = meta.asShotNeutral
        if (neutral != null) {
            rgb = WhiteBalanceStage.apply(rgb, RgbGains.fromAsShotNeutral(neutral))
        }

        // Color matrices are metadata; when a producer omits them we develop
        val matrix = runCatching { ColorMatrixFactory.cameraToWorkingRgbMatrix(meta) }.getOrNull()
        if (matrix != null) {
            rgb = ColorTransformStage.transform(rgb, matrix)
        }
        if (neutral == null) {
            // Iterate once: the first correction makes neutral pixels easier to
            // identify when the sensor starts with a strong channel cast.
            rgb = WhiteBalanceStage.apply(rgb, WhiteBalanceStage.auto(rgb))
            rgb = WhiteBalanceStage.apply(rgb, WhiteBalanceStage.auto(rgb))
        } else {
            // Metadata is the primary WB source, but a real HAL can report a
            // stale neutral. Apply one conservative residual correction from
            // bright, low-chroma working-RGB samples instead of a fixed RGB bias.
            rgb = WhiteBalanceStage.apply(rgb, WhiteBalanceStage.auto(rgb))
        }
        rgb = ClippedHighlightNeutralizer.apply(rgb)

        return finish(rgb, config, raw.metadata.orientationDegrees, raw.metadata.lensShading)
    }

    override fun processYuv(yuv: YuvImage, config: ProcessingConfiguration, orientationDegrees: Int): EncodedImage {
        val rgb = YuvToRgbConverter.toRgb(yuv)
        return finish(rgb, config, orientationDegrees)
    }

    private fun finish(
        rgb: RgbImage,
        config: ProcessingConfiguration,
        orientationDegrees: Int,
        lensShading: LensShadingMap? = null,
    ): EncodedImage {
        var out = rgb
        if (config.profile == ProcessingProfile.NATURAL) {
            val warmth = naturalWarmth(config.tone)
            out = WhiteBalanceStage.apply(out, RgbGains(1f + warmth, 1f, 1f - warmth))
            out = ExposureProcessor.apply(out, config.tone.exposureStops)
            out = NaturalToneMapper.map(out, config.tone)
            out = HighlightRollOff.apply(out, config.tone.highlightRollOffStart, config.tone.highlightCompression)
            out = NoiseReducer.reduce(out, config.chromaDenoiseStrength, config.lumaDenoiseStrength)
            // Grain decides how much denoise this frame actually needs, so a clean bright
            // frame keeps its 1-px texture and a dim one gets the grain taken out.
            val grain = grainLevel(NoiseEstimator.shadowGrain(out))
            val chromaStrength =
                config.chromaSmoothStrength + (MAX_CHROMA_SMOOTH_STRENGTH - config.chromaSmoothStrength) * grain
            val lumaStrength =
                config.lumaSmoothStrength + (MAX_LUMA_SMOOTH_STRENGTH - config.lumaSmoothStrength) * grain
            // Absorbs what the shading correction amplified, plus the chroma strength
            // everywhere; a null map just means "no shading term".
            out = ChromaSmoother.apply(out, lensShading, chromaStrength)
            out = LumaDenoiser.apply(out, lumaStrength)
            // Sharpen harder where the denoise worked harder. Denoising and sharpening
            // share a band, so this is not free — but the cored sharpener only lifts what
            // sits above the *new*, lower noise floor, which is mostly real structure.
            // Measured, it roughly halves the detail a given grain reduction costs, and
            // the edge overshoot still lands *below* the lightly-denoised rendering,
            // because the denoise softened the edges the sharpen then works on.
            val sharpen = config.sharpenAmount + (Sharpener.MAX_AMOUNT - config.sharpenAmount) * grain
            out = Sharpener.sharpen(out, sharpen, config.sharpenRadiusPx)
            out = StyleEngine.apply(out, config.style)
        }
        // PURE and SYSTEM: only what makes the image viewable (SPEC 131).
        out = GamutMapper.clampToSrgbGamut(out)
        val argb = OutputTransformer.toArgb8888(out)
        // Grain belongs to the delivered pixels: an equal whole-level shift on all three
        // channels is exactly colour-free, which an earlier linear-light stage could not
        // be. NATURAL only, like the rest of the style chain.
        if (config.profile == ProcessingProfile.NATURAL) {
            GrainStage.apply(argb, out.width, out.height, config.style.grain)
        }
        val rotated = ImageRotation.rotate(argb, out.width, out.height, orientationDegrees)
        return encoder.encode(rotated.argb, rotated.width, rotated.height, config.jpegQuality)
    }
}
