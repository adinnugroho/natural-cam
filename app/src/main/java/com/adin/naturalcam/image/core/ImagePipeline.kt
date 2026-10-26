package com.adin.naturalcam.image.core

import android.graphics.BitmapFactory

import com.adin.naturalcam.BuildConfig
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.image.processing.AspectCropper
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
import com.adin.naturalcam.image.raw.GpuRawPipeline
import com.adin.naturalcam.image.raw.GpuPipelineConfig
import com.adin.naturalcam.image.raw.RawNormalizer
import com.adin.naturalcam.image.style.StyleEngine
import com.adin.naturalcam.image.style.FastJpegStyleProcessor
import com.adin.naturalcam.image.yuv.YuvToRgbConverter
import java.util.Locale
import kotlin.math.pow

/**
 * Processing boundary (SPEC 50). Profiles are product intent; this pipeline is
 * one implementation of them (SPEC 19). Stages never mutate their inputs.
 */
interface ImagePipeline {
    val version: String

    /** [timings], when given, collects per-stage durations for the SPEC 109 debug log. */
    fun processRaw(
        raw: RawImage,
        config: ProcessingConfiguration,
        timings: ProcessingTimings? = null,
    ): EncodedImage

    /** [orientationDegrees] is the clockwise display rotation, baked into pixels (SPEC 89). */
    fun processYuv(
        yuv: YuvImage,
        config: ProcessingConfiguration,
        orientationDegrees: Int,
        timings: ProcessingTimings? = null,
    ): EncodedImage
    /** Fast processed-JPEG path used when NATURAL prioritizes shutter-to-gallery latency. */
    fun processJpeg(
        jpeg: ByteArray,
        config: ProcessingConfiguration,
        timings: ProcessingTimings? = null,
    ): EncodedImage
}

/**
 * Reference CPU pipeline for NATURAL (SPEC 130):
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
    override fun processJpeg(
        jpeg: ByteArray,
        config: ProcessingConfiguration,
        timings: ProcessingTimings?,
    ): EncodedImage {
        val bitmap = timings.measureStage("jpegDecode") {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: throw IllegalArgumentException("processed JPEG decode failed")
        }
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        timings.measureStage("jpegPixels") { bitmap.getPixels(argb, 0, width, 0, 0, width, height) }
        bitmap.recycle()

        timings.measureStage("style") {
            FastJpegStyleProcessor.apply(argb, width, height, config.style)
        }
        timings.measureStage("grain") {
            GrainStage.apply(argb, width, height, config.style.grain)
        }
        val cropped = timings.measureStage("crop") {
            AspectCropper.crop(argb, width, height, config.outputAspect)
        }
        return timings.measureStage("encode") {
            encoder.encode(cropped.argb, cropped.width, cropped.height, config.jpegQuality)
        }
    }

    override fun processRaw(
        raw: RawImage,
        config: ProcessingConfiguration,
        timings: ProcessingTimings?,
    ): EncodedImage {
        if (
            (config.profile == ProcessingProfile.PURE || config.profile == ProcessingProfile.NATURAL) &&
            BuildConfig.EXPERIMENTAL_GPU_PIPELINE
        ) {
            val meta = raw.metadata
            val neutral = meta.asShotNeutral
            val gains = neutral?.let { RgbGains.fromAsShotNeutral(it) }
            val matrix = runCatching { ColorMatrixFactory.cameraToWorkingRgbMatrix(meta) }.getOrNull()
            if (gains != null && matrix != null) {
                val natural = config.profile == ProcessingProfile.NATURAL
                val style = if (natural) StyleEngine.resolve(config.style) else null
                val gpuConfig = GpuPipelineConfig(
                    natural = natural,
                    wbGains = gains,
                    colorMatrix = matrix,
                    warmth = if (natural) naturalWarmth(config.tone) else 0f,
                    exposureGain = if (natural) 2f.pow(config.tone.exposureStops) else 1f,
                    midtoneGamma = if (natural) config.tone.midtoneGamma else 1f,
                    contrast = if (natural) config.tone.contrast else 0f,
                    highlightRollOffStart = if (natural) config.tone.highlightRollOffStart else 1f,
                    highlightCompression = if (natural) config.tone.highlightCompression else 0f,
                    chromaStrength = if (natural) config.chromaSmoothStrength else 0f,
                    lumaStrength = if (natural) config.lumaSmoothStrength else 0f,
                    sharpenAmount = if (natural) config.sharpenAmount else 0f,
                    styleStrength = if (natural) config.style.strength.coerceIn(0f, 1f) else 0f,
                    saturation = if (natural) config.style.saturation.coerceIn(-1f, 1f) else 0f,
                    bloom = if (natural) config.style.bloom.coerceIn(0f, 1f) else 0f,
                    toneContrast = style?.toneContrast ?: 0f,
                    midtoneLift = style?.midtoneLift ?: 0f,
                    shoulderStrength = style?.shoulderStrength ?: 0f,
                    temperature = style?.temperature ?: 0f,
                    chromaScale = style?.chromaScale ?: 1f,
                    chromaCompression = style?.chromaCompression ?: 0f,
                    paletteHueShift = style?.paletteHueShift ?: 0f,
                    paletteChromaBoost = style?.paletteChromaBoost ?: 0f,
                    shadowHueTint = style?.shadowHueTint ?: 0f,
                )
                val gpu = timings.measureStage("gpuPipeline") {
                    GpuRawPipeline.tryRender(raw, gpuConfig)
                }
                if (gpu != null) {
                    timings?.fact("gpuPipelineBackend", "gpu-native-${config.profile.name.lowercase()}")
                    timings?.fact("style", StyleEngine.describe(config.style))
                    // Grain belongs to the delivered pixels: an equal whole-level shift on
                    // all three channels is exactly colour-free (same as the CPU path).
                    if (natural) {
                        timings.measureStage("grain") {
                            GrainStage.apply(gpu.argb, gpu.width, gpu.height, config.style.grain)
                        }
                    }
                    val rotated = timings.measureStage("rotate") {
                        ImageRotation.rotate(gpu.argb, gpu.width, gpu.height, raw.metadata.orientationDegrees)
                    }
                    val cropped = timings.measureStage("crop") {
                        AspectCropper.crop(rotated.argb, rotated.width, rotated.height, config.outputAspect)
                    }
                    return timings.measureStage("encode") {
                        encoder.encode(cropped.argb, cropped.width, cropped.height, config.jpegQuality)
                    }
                }
                timings?.fact("gpuPipelineBackend", "cpu-reference")
            } else {
                timings?.fact("gpuPipelineBackend", "cpu-metadata-fallback")
            }
        }
        var bayer: BayerImage? = null
        timings.measureStage("normalize") { bayer = RawNormalizer.normalize(raw) }
        // Lens-shading colour correction first: the GainMap is per CFA position and
        // must be undone before demosaic blends neighbouring samples (SPEC 31).
        timings.measureStage("lensShading") {
            bayer = raw.metadata.lensShading?.let { LensShadingCorrector.correct(bayer!!, it) } ?: bayer
        }
        var rgb: RgbImage? = null
        timings.measureStage("demosaic") { rgb = Demosaicer.demosaic(bayer!!) }
        // Demosaic is the last reader of the mosaic: drop its ~46 MB before the rest of
        // the develop allocates (see BayerImage.releasePixels).
        bayer?.releasePixels()

        val meta = raw.metadata
        val neutral = meta.asShotNeutral
        if (neutral != null) {
            timings.measureStage("wbGains") {
                rgb = WhiteBalanceStage.apply(rgb!!, RgbGains.fromAsShotNeutral(neutral))
            }
        }

        // Color matrices are metadata; when a producer omits them we develop
        val matrix = runCatching { ColorMatrixFactory.cameraToWorkingRgbMatrix(meta) }.getOrNull()
        if (matrix != null) {
            timings.measureStage("colorMatrix") { rgb = ColorTransformStage.transform(rgb!!, matrix) }
        }
        if (neutral == null) {
            // Iterate once: the first correction makes neutral pixels easier to
            // identify when the sensor starts with a strong channel cast.
            timings.measureStage("autoWb") {
                rgb = WhiteBalanceStage.apply(rgb!!, WhiteBalanceStage.auto(rgb!!))
                rgb = WhiteBalanceStage.apply(rgb!!, WhiteBalanceStage.auto(rgb!!))
            }
        } else {
            // Metadata is the primary WB source, but a real HAL can report a
            // stale neutral. Apply one conservative residual correction from
            // bright, low-chroma working-RGB samples instead of a fixed RGB bias.
            timings.measureStage("autoWb") { rgb = WhiteBalanceStage.apply(rgb!!, WhiteBalanceStage.auto(rgb!!)) }
        }
        timings.measureStage("clipNeutralize") { rgb = ClippedHighlightNeutralizer.apply(rgb!!) }

        return finish(rgb!!, config, raw.metadata.orientationDegrees, raw.metadata.lensShading, timings)
    }

    override fun processYuv(
        yuv: YuvImage,
        config: ProcessingConfiguration,
        orientationDegrees: Int,
        timings: ProcessingTimings?,
    ): EncodedImage {
        var rgb: RgbImage? = null
        timings.measureStage("yuvToRgb") { rgb = YuvToRgbConverter.toRgb(yuv) }
        return finish(rgb!!, config, orientationDegrees, null, timings)
    }

    private fun finish(
        rgb: RgbImage,
        config: ProcessingConfiguration,
        orientationDegrees: Int,
        lensShading: LensShadingMap? = null,
        timings: ProcessingTimings? = null,
    ): EncodedImage {
        var out = rgb
        if (config.profile == ProcessingProfile.NATURAL) {
            val warmth = naturalWarmth(config.tone)
            timings.measureStage("warmth") { out = WhiteBalanceStage.apply(out, RgbGains(1f + warmth, 1f, 1f - warmth)) }
            timings.measureStage("exposure") { out = ExposureProcessor.apply(out, config.tone.exposureStops) }
            timings.measureStage("tone") { out = NaturalToneMapper.map(out, config.tone) }
            timings.measureStage("rollOff") {
                out = HighlightRollOff.apply(out, config.tone.highlightRollOffStart, config.tone.highlightCompression)
            }
            timings.measureStage("reduce") {
                out = NoiseReducer.reduce(out, config.chromaDenoiseStrength, config.lumaDenoiseStrength)
            }
            // Grain decides how much denoise this frame actually needs, so a clean bright
            // frame keeps its 1-px texture and a dim one gets the grain taken out.
            var grain = 0f
            timings.measureStage("grainEstimate") {
                val measured = NoiseEstimator.shadowGrain(out)
                timings?.fact("grainMeasured", format("%.5f", measured))
                grain = grainLevel(measured)
            }
            val chromaStrength =
                config.chromaSmoothStrength + (MAX_CHROMA_SMOOTH_STRENGTH - config.chromaSmoothStrength) * grain
            val lumaStrength =
                config.lumaSmoothStrength + (MAX_LUMA_SMOOTH_STRENGTH - config.lumaSmoothStrength) * grain
            timings?.fact("grainLevel", format("%.3f", grain))
            timings?.fact("chromaStrength", format("%.3f", chromaStrength))
            timings?.fact("lumaStrength", format("%.3f", lumaStrength))
            // Absorbs what the shading correction amplified, plus the chroma strength
            // everywhere; a null map just means "no shading term".
            timings.measureStage("chromaSmooth") { out = ChromaSmoother.apply(out, lensShading, chromaStrength) }
            timings.measureStage("lumaSmooth") { out = LumaDenoiser.apply(out, lumaStrength) }
            // Sharpen harder where the denoise worked harder. Denoising and sharpening
            // share a band, so this is not free — but the cored sharpener only lifts what
            // sits above the *new*, lower noise floor, which is mostly real structure.
            // Measured, it roughly halves the detail a given grain reduction costs, and
            // the edge overshoot still lands *below* the lightly-denoised rendering,
            // because the denoise softened the edges the sharpen then works on.
            val sharpen = config.sharpenAmount + (Sharpener.MAX_AMOUNT - config.sharpenAmount) * grain
            timings?.fact("sharpenAmount", format("%.3f", sharpen))
            timings.measureStage("sharpen") { out = Sharpener.sharpen(out, sharpen, config.sharpenRadiusPx) }
            timings?.fact("style", StyleEngine.describe(config.style))
            timings.measureStage("style") { out = StyleEngine.apply(out, config.style) }
        }
        // PURE and SYSTEM: only what makes the image viewable (SPEC 131).
        timings.measureStage("gamut") { out = GamutMapper.clampToSrgbGamut(out) }
        val argb = timings.measureStage("output") { OutputTransformer.toArgb8888(out) }
        // The float frame is finished with: drop 151 MB before the rotation buffer and
        // the encoder's bitmap are allocated (see RgbImage.releasePixels).
        out.releasePixels()
        // Grain belongs to the delivered pixels: an equal whole-level shift on all three
        // channels is exactly colour-free, which an earlier linear-light stage could not
        // be. NATURAL only, like the rest of the style chain.
        if (config.profile == ProcessingProfile.NATURAL) {
            timings.measureStage("grain") { GrainStage.apply(argb, out.width, out.height, config.style.grain) }
        }
        val rotated = timings.measureStage("rotate") {
            ImageRotation.rotate(argb, out.width, out.height, orientationDegrees)
        }
        // The selected aspect ratio is applied here, in display orientation, after rotation:
        // RAW develop had no crop stage, so without this the 16:9 control did nothing
        // while the platform JPEG path honoured the setting.
        val cropped = timings.measureStage("crop") {
            AspectCropper.crop(rotated.argb, rotated.width, rotated.height, config.outputAspect)
        }
        val encoded = timings.measureStage("encode") {
            encoder.encode(cropped.argb, cropped.width, cropped.height, config.jpegQuality)
        }
        return encoded
    }
}

/** Debug-log numbers stay dot-decimal wherever the device's locale puts its comma. */
private fun format(pattern: String, value: Float): String = String.format(Locale.US, pattern, value)
