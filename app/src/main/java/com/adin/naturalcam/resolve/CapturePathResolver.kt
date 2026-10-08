package com.adin.naturalcam.resolve

import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.CaptureLimitation
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureSource
import com.adin.naturalcam.domain.IspConfiguration
import com.adin.naturalcam.domain.NoiseReductionMode
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.PipelineType
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode

/**
 * Single place where capture-path decisions are made (AGENTS 33). Inputs:
 * real per-camera capabilities + requested profile/output (SPEC 20). Priority
 * for custom processing: RAW_SENSOR → YUV → processed JPEG (SPEC 22), unless a
 * path is objectively unusable.
 */
object CapturePathResolver {

    fun resolve(
        capabilities: CameraCapabilities,
        profile: ProcessingProfile,
        rawMode: RawMode,
        zoomRatio: Float = 1f,
    ): CapturePlan {
        val saveRaw = rawMode != RawMode.FINAL_ONLY
        // Below 1x the logical camera engages a *wider* physical camera. RAW is not exposed
        // for those (SPEC 96 states the case), so a RAW plan would quietly deliver the main
        // sensor's frame — narrower than what the viewfinder shows. The YUV stream carries the
        // same crop region, so it keeps the composed framing *and* stays under our pipeline.
        val wideFraming = zoomRatio < 1f
        // RAW_ONLY keeps the RAW plan: the user asked for the RAW *file*, which exists only for
        // the main camera, and the DNG's own framing is already recorded as PREVIEW_MAY_DIFFER.
        val raw = capabilities.rawUsable && (!wideFraming || rawMode == RawMode.RAW_ONLY)
        val yuv = capabilities.yuvUsable
        val jpeg = capabilities.jpegUsable

        // Least-aggressive ISP request the hardware reports (SPEC 23). Honesty
        // about what actually got disabled is recorded as limitations below.
        // SYSTEM does not use it: the backend leaves the device's own defaults alone.
        val isp = resolveIspConfiguration(capabilities)

        val plan = when (profile) {
            ProcessingProfile.SYSTEM -> resolveSystem(raw, yuv, jpeg, saveRaw, isp)
            ProcessingProfile.NATURAL -> resolveNatural(raw, yuv, jpeg, saveRaw, isp)
            ProcessingProfile.PURE -> resolvePure(raw, yuv, jpeg, saveRaw, isp)
        }

        // RAW-only mode must never synthesize a final image from an unwanted path.
        val resolved = when {
            rawMode == RawMode.RAW_ONLY && plan.source == CaptureSource.RAW_SENSOR -> plan.copy(saveRaw = true)
            // A framing wider than 1x cannot come from RAW even though this camera has it:
            // say so instead of delivering the narrower main-sensor frame unexplained.
            wideFraming && rawMode != RawMode.RAW_ONLY && capabilities.rawUsable ->
                plan.copy(limitations = plan.limitations + CaptureLimitation.RAW_NOT_AVAILABLE)
            else -> plan
        }
        return resolved
    }

    private fun resolveSystem(
        raw: Boolean,
        yuv: Boolean,
        jpeg: Boolean,
        saveRaw: Boolean,
        isp: IspConfiguration,
    ): CapturePlan {
        // SYSTEM = the device's own processing; that means platform JPEG output.
        return CapturePlan(
            source = CaptureSource.PROCESSED_JPEG,
            pipeline = PipelineType.HARDWARE_PROCESSED,
            saveRaw = saveRaw && raw,
            ispConfiguration = isp,
            limitations = buildSet {
                add(CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE)
                add(CaptureLimitation.PREVIEW_MAY_DIFFER)
                if (saveRaw && !raw) add(CaptureLimitation.RAW_NOT_AVAILABLE)
                if (saveRaw && raw) add(CaptureLimitation.RAW_NOT_AVAILABLE) // SYSTEM JPEG stays primary; RAW capture still uses a separate raw stream request
            },
        ).let {
            // RAW companion capture in SYSTEM mode is a secondary request, not a
            // development source; keep the plan honest by tracking availability.
            if (saveRaw && raw) it.copy(limitations = it.limitations - CaptureLimitation.RAW_NOT_AVAILABLE)
            else it
        }
    }

    private fun resolveNatural(
        raw: Boolean,
        yuv: Boolean,
        jpeg: Boolean,
        saveRaw: Boolean,
        isp: IspConfiguration,
    ): CapturePlan = when {
        raw -> CapturePlan(
            source = CaptureSource.RAW_SENSOR,
            pipeline = PipelineType.RAW_NATURAL,
            saveRaw = saveRaw,
            ispConfiguration = isp,
            limitations = ispLimitations(isp) + CaptureLimitation.PREVIEW_MAY_DIFFER,
        )
        yuv -> CapturePlan(
            source = CaptureSource.YUV,
            pipeline = PipelineType.YUV_NATURAL,
            saveRaw = false,
            ispConfiguration = isp,
            limitations = ispLimitations(isp) +
                CaptureLimitation.FALLBACK_TO_YUV +
                CaptureLimitation.PREVIEW_MAY_DIFFER +
                if (saveRaw) setOf(CaptureLimitation.RAW_NOT_AVAILABLE) else emptySet(),
        )
        else -> CapturePlan(
            source = CaptureSource.PROCESSED_JPEG,
            pipeline = PipelineType.HARDWARE_PROCESSED,
            saveRaw = false,
            ispConfiguration = isp,
            limitations = ispLimitations(isp) +
                CaptureLimitation.FALLBACK_TO_JPEG +
                CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE +
                CaptureLimitation.PREVIEW_MAY_DIFFER +
                if (saveRaw) setOf(CaptureLimitation.RAW_NOT_AVAILABLE) else emptySet(),
        )
    }

    private fun resolvePure(
        raw: Boolean,
        yuv: Boolean,
        jpeg: Boolean,
        saveRaw: Boolean,
        isp: IspConfiguration,
    ): CapturePlan = when {
        // Strategy A: RAW → minimal development → JPEG (SPEC 24).
        raw -> CapturePlan(
            source = CaptureSource.RAW_SENSOR,
            pipeline = PipelineType.RAW_PURE_MINIMAL,
            saveRaw = saveRaw,
            ispConfiguration = isp,
            limitations = ispLimitations(isp) + CaptureLimitation.PREVIEW_MAY_DIFFER,
        )
        // Strategy B: YUV → minimal transformation → JPEG.
        yuv -> CapturePlan(
            source = CaptureSource.YUV,
            pipeline = PipelineType.YUV_PURE_MINIMAL,
            saveRaw = false,
            ispConfiguration = isp,
            limitations = ispLimitations(isp) +
                CaptureLimitation.FALLBACK_TO_YUV +
                CaptureLimitation.PREVIEW_MAY_DIFFER +
                if (saveRaw) setOf(CaptureLimitation.RAW_NOT_AVAILABLE) else emptySet(),
        )
        // Strategy C: least-processed hardware output. PURE semantics are no
        // longer fully satisfiable — say so instead of pretending (AGENTS 34).
        else -> CapturePlan(
            source = CaptureSource.PROCESSED_JPEG,
            pipeline = PipelineType.HARDWARE_PROCESSED,
            saveRaw = false,
            ispConfiguration = isp,
            limitations = setOf(
                CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE,
                CaptureLimitation.FALLBACK_TO_JPEG,
                CaptureLimitation.PREVIEW_MAY_DIFFER,
            ) + if (saveRaw) setOf(CaptureLimitation.RAW_NOT_AVAILABLE) else emptySet(),
        )
    }

    private fun resolveIspConfiguration(
        capabilities: CameraCapabilities,
    ): IspConfiguration {
        val nrModes = capabilities.noiseReductionModes
        val edgeModes = capabilities.edgeModes

        val nr = when {
            NoiseReductionMode.OFF in nrModes -> NoiseReductionMode.OFF
            NoiseReductionMode.MINIMAL in nrModes -> NoiseReductionMode.MINIMAL
            else -> leastAggressive(nrModes)
        }
        val edge = when {
            EdgeMode.OFF in edgeModes -> EdgeMode.OFF
            else -> leastAggressiveEdge(edgeModes)
        }
        return IspConfiguration(
            noiseReduction = nr,
            edgeMode = edge,
        )
    }

    private fun leastAggressive(modes: Set<NoiseReductionMode>): NoiseReductionMode = when {
        modes.isEmpty() -> NoiseReductionMode.UNKNOWN
        NoiseReductionMode.MINIMAL in modes -> NoiseReductionMode.MINIMAL
        NoiseReductionMode.FAST in modes -> NoiseReductionMode.FAST
        NoiseReductionMode.ZERO_SHUTTER_LAG in modes -> NoiseReductionMode.ZERO_SHUTTER_LAG
        NoiseReductionMode.HIGH_QUALITY in modes -> NoiseReductionMode.HIGH_QUALITY
        else -> NoiseReductionMode.UNKNOWN
    }

    private fun leastAggressiveEdge(modes: Set<EdgeMode>): EdgeMode = when {
        modes.isEmpty() -> EdgeMode.UNKNOWN
        EdgeMode.FAST in modes -> EdgeMode.FAST
        EdgeMode.ZERO_SHUTTER_LAG in modes -> EdgeMode.ZERO_SHUTTER_LAG
        EdgeMode.HIGH_QUALITY in modes -> EdgeMode.HIGH_QUALITY
        else -> EdgeMode.UNKNOWN
    }

    /** Honest recording when we could not request OFF and settled for MINIMAL (PRD 11). */
    private fun ispLimitations(isp: IspConfiguration): Set<CaptureLimitation> = buildSet {
        if (isp.noiseReduction != NoiseReductionMode.OFF && isp.noiseReduction != NoiseReductionMode.UNKNOWN) {
            add(CaptureLimitation.MINIMAL_DENOISE_ONLY)
        }
        if (isp.edgeMode != EdgeMode.OFF && isp.edgeMode != EdgeMode.UNKNOWN) {
            add(CaptureLimitation.MINIMAL_SHARPENING_ONLY)
        }
        // Vendor auto-HDR toggles are not exposed through public capture controls;
        // "HDR disabled" is requested, not confirmed (AGENTS 12).
        add(CaptureLimitation.HDR_CONTROL_UNAVAILABLE)
    }
}
