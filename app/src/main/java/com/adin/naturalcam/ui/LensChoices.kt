package com.adin.naturalcam.ui

import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.LensOption
import java.util.Locale

/**
 * One entry of the lens picker: a physical camera, or the wide-angle preset of the camera
 * that is already selected.
 *
 * The wide entry is not a camera — the wide lens lives *behind* the logical camera on a
 * multi-camera device and is reached with a zoom ratio below 1 (PRD 9 asks for a selectable
 * ultrawide; [LensOption] can only describe a camera id, so the preset carries a zoom).
 */
data class LensChoice(
    val label: String,
    val cameraId: CameraId,
    val zoomRatio: Float,
    val selected: Boolean,
)

/** "0.5×" → "0.5X": the compact lens row has no room for the multiplication sign. */
internal fun String.asLensLabel(): String = replace("×", "X").uppercase()

/**
 * Lens picker contents for the current camera. Kept out of the composable so the ordering and
 * the selected flags are testable, and so pinching never rebuilds this on the UI thread.
 *
 * The wide entry appears only when the camera reports a zoom range starting below 1, i.e. when
 * a wider lens actually exists to engage (AGENTS 10: from metadata, never assumed).
 */
internal fun lensChoices(
    lenses: List<LensOption>,
    capabilities: CameraCapabilities?,
    selectedCameraId: CameraId?,
    wideAngleActive: Boolean,
): List<LensChoice> {
    val currentFacing = lenses.firstOrNull { it.cameraId == selectedCameraId }?.lensFacing
    val selectable = when (currentFacing) {
        LensFacing.BACK -> lenses.filter { it.lensFacing == LensFacing.BACK }
        LensFacing.FRONT -> lenses.filter { it.lensFacing == LensFacing.FRONT }
        else -> lenses
    }
    // Stable order, widest first: the row must not reshuffle when a lens is picked, or the
    // entry under the finger changes between taps.
    val cameras = selectable
        .sortedBy { it.focalLengthMm }
        .distinctBy { it.label.asLensLabel() }
    val zoomRange = capabilities?.zoomRatioRange
    val selectedFocal = selectable.firstOrNull { it.cameraId == selectedCameraId }?.focalLengthMm
    // A wider *camera* being selectable is the better answer than digitally zooming this one out
    // — on this phone the 0.3x lens exposes RAW (12 MP) while the 1x camera's 0.6x zoom framing
    // can only be served from the YUV stream (1.9 MP). So the preset exists only where no wider
    // lens can be selected at all (a logical camera hiding its wide lens).
    val widerCameraExists = selectedFocal != null && selectable.any { it.focalLengthMm < selectedFocal }
    val wideZoom = zoomRange?.start?.takeIf { it < 1f && !widerCameraExists }
    val wide = if (wideZoom == null || selectedCameraId == null) {
        emptyList()
    } else {
        listOf(
            LensChoice(
                label = String.format(Locale.US, "%.1fX", wideZoom),
                cameraId = selectedCameraId,
                zoomRatio = wideZoom,
                selected = wideAngleActive,
            ),
        )
    }
    return wide + cameras.map {
        LensChoice(
            // A camera entry returns to its own 1x framing, so picking the camera that is
            // already selected is a way back out of the wide preset.
            label = it.label.asLensLabel(),
            cameraId = it.cameraId,
            zoomRatio = 1f,
            selected = !wideAngleActive && it.cameraId == selectedCameraId,
        )
    }
}
