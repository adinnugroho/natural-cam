package com.adin.naturalcam.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    state: CameraUiState,
    actions: CameraActions,
    onOpenSettings: () -> Unit,
    onPreviewViewCreated: (PreviewView) -> Unit,
) {
    val caps = state.capabilities
    val immersivePreview = state.aspectRatio != AspectRatio.RATIO_4_3
    val busy = state.captureState is CaptureState.Capturing ||
        state.captureState is CaptureState.Processing ||
        state.captureState is CaptureState.Saving
    val shutterScale = remember { Animatable(1f) }
    val screenFlash = remember { Animatable(0f) }
    var popup by remember { mutableStateOf<CameraPopup?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var frozenLensFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val previewCreated: (PreviewView) -> Unit = { view ->
        previewView = view
        onPreviewViewCreated(view)
    }
    val freezePreviewForLensSwitch = {
        frozenLensFrame = previewView?.bitmap
            ?.copy(Bitmap.Config.ARGB_8888, false)
            ?.asImageBitmap()
    }

    LaunchedEffect(state.selectedCameraId) {
        if (frozenLensFrame != null) {
            delay(300)
            frozenLensFrame = null
        }
    }

    LaunchedEffect(state.captureState) {
        if (state.captureState is CaptureState.Capturing) {
            shutterScale.animateTo(1.08f, tween(90))
            shutterScale.animateTo(1f, tween(180))
            screenFlash.snapTo(0.3f)
            screenFlash.animateTo(0f, tween(220))
        }
    }

    val styleMode = state.styleMode

    Box(Modifier.fillMaxSize().background(CameraBlack)) {
        // Full-bleed ratios keep the original look: preview behind the chrome.
        if (immersivePreview) {
            Preview(state, actions, previewCreated, frozenLensFrame, Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize()) {
            if (styleMode) {
                StyleModeTopBar(state, actions) { actions.onSetStyleMode(false) }
            } else {
                TopControls(state, actions, caps, immersivePreview, popup, onOpenSettings) { popup = it }
            }
            if (immersivePreview) {
                Spacer(Modifier.weight(1f))
            } else {
                // 4:3 viewfinder fits exactly between the two control bars.
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Preview(state, actions, previewCreated, frozenLensFrame, Modifier.fillMaxSize())
                }
            }
            val failed = state.captureState as? CaptureState.Failed
            if (failed != null) ErrorBar(failed, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            state.notice?.let { notice ->
                NoticeBar(notice, actions::onNoticeShown, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (styleMode) {
                SinglePadEditor(state.style, actions::onSetStyle)
            } else {
                BottomBar(state, actions, shutterScale.value, busy, immersivePreview, popup, freezePreviewForLensSwitch, { actions.onSetStyleMode(true) }) { popup = it }
            }
        }
        if (screenFlash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = screenFlash.value)))
        if (state.captureState is CaptureState.Capturing) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = CameraOrange)
                Spacer(Modifier.height(8.dp))
                Text("Processing…", color = CameraWhite)
            }
        } else if (state.jobsInFlight > 0) {
            // A photo is developing in the background. Keep the viewfinder clear and the
            // shutter live (a 12 MP develop takes seconds; a point-and-shoot must not
            // stop taking shots for that long) — just say that work is happening.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    // Above the control cluster (EV row ~124-150dp up, lens row ~152-208dp).
                    .padding(bottom = 224.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = CameraOrange,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.jobsInFlight > 1) "Processing ${state.jobsInFlight} photos…" else "Processing…",
                    color = CameraWhite,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Preview(
    state: CameraUiState,
    actions: CameraActions,
    onPreviewViewCreated: (PreviewView) -> Unit,
    frozenLensFrame: ImageBitmap?,
    modifier: Modifier,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var tapFraction by remember { mutableStateOf(Offset.Zero) }
    var tapTick by remember { mutableStateOf(0) }
    val reticleAlpha = remember { Animatable(0f) }
    val portrait = LocalConfiguration.current.screenHeightDp >=
        LocalConfiguration.current.screenWidthDp
    val frameRatio = previewFrameRatio(state.aspectRatio, portrait)

    // Flash the focus reticle on every tap: snap visible, then fade out. The
    // tick re-triggers the effect even when the tap lands on the same point.
    LaunchedEffect(tapTick) {
        if (tapTick > 0) {
            reticleAlpha.snapTo(1f)
            reticleAlpha.animateTo(0f, tween(700))
        }
    }

    // Pinch-to-zoom → dynamic lens switching. Each pinch gesture accumulates
    // zoom; crossing a lens boundary switches the physical camera (AGENTS 10).
    val backLenses = state.lenses
        .filter { it.lensFacing == LensFacing.BACK }
        .sortedBy { it.focalLengthMm }
    val pinchZoom = remember { mutableStateOf(1f) }
    val gestureStartZoom = remember { mutableStateOf(1f) }

    BoxWithConstraints(modifier) {
        // Fit the capture ratio *inside* the available area. Using
        // fillMaxWidth().aspectRatio() forces the height from the width, which
        // overflows the box and drew the preview over the control bars.
        val frame = if (frameRatio == null) {
            Modifier.fillMaxSize()
        } else {
            val fitWidth = minOf(maxWidth, maxHeight * frameRatio)
            Modifier.size(fitWidth, fitWidth / frameRatio)
        }
        Box(frame.align(Alignment.Center)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onPreviewViewCreated(this)
                    }
                },
                // Runs on every recomposition, so dragging the Saturation slider repaints
                // the live feed instead of waiting for the shot.
                update = { view -> view.applyPreviewSaturation(state.style.saturation) },
            )
            frozenLensFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            StylePreviewOverlay(state.style, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { previewSize = it }
                    .pointerInput(state.selectedCameraId, backLenses.size) {
                        fun focusPoint(offset: Offset) = Offset(
                            (offset.x / size.width.toFloat()).coerceIn(0f, 1f),
                            (offset.y / size.height.toFloat()).coerceIn(0f, 1f),
                        )
                        fun showFocusBox(point: Offset) {
                            tapFraction = point
                            tapTick++
                        }
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val point = focusPoint(offset)
                                showFocusBox(point)
                                actions.onLockFocus(point.x, point.y)
                            },
                            onTap = { offset ->
                                val point = focusPoint(offset)
                                showFocusBox(point)
                                actions.onTapToFocus(point.x, point.y)
                            },
                        )
                    }
                    .pointerInput(state.selectedCameraId, backLenses.size) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var zoomAccumulated = 1f
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    // Skip zoom during capture (AGENTS 42).
                                    if (state.captureState is CaptureState.Capturing ||
                                        state.captureState is CaptureState.Processing ||
                                        state.captureState is CaptureState.Saving
                                    ) continue
                                    // Moderate damping keeps the pinch responsive
                                    // without restoring the previous jumpiness.
                                    zoomAccumulated *= 1f + (zoomChange - 1f) * 0.35f
                                    val base = gestureStartZoom.value
                                    val target = (base * zoomAccumulated).coerceIn(0.5f, 10f)
                                    pinchZoom.value = target
                                    // Continuous smooth zoom within the current lens.
                                    actions.onPinchZoom(target)
                                    // Dynamic lens switching: only cross a focal
                                    // boundary when the target zoom is far enough
                                    // from the current lens's ratio to be intentional.
                                    if (backLenses.size > 1) {
                                        val mainFocal = backLenses.firstOrNull()?.focalLengthMm ?: 1f
                                        val currentLensFocal = backLenses
                                            .firstOrNull { it.cameraId == state.selectedCameraId }
                                            ?.focalLengthMm ?: mainFocal
                                        val currentRatio = currentLensFocal / mainFocal
                                        val targetLens = backLenses.minByOrNull { lens ->
                                            kotlin.math.abs(lens.focalLengthMm / mainFocal - target)
                                        }
                                        val targetRatio = targetLens?.let { it.focalLengthMm / mainFocal } ?: currentRatio
                                        val movingToLongerLens = targetRatio > currentRatio
                                        val crossedOpticalBoundary = if (movingToLongerLens) {
                                            target >= targetRatio
                                        } else {
                                            target <= targetRatio
                                        }
                                        // Switch exactly at the optical boundary.
                                        // The new camera receives the equivalent
                                        // zoom ratio, so 1× ↔ 3× remains continuous.
                                        if (targetLens != null &&
                                            targetLens.cameraId != state.selectedCameraId &&
                                            crossedOpticalBoundary
                                        ) {
                                            val effectiveTarget = target.coerceAtLeast(1f) * currentRatio
                                            val targetCameraZoom = (effectiveTarget / targetRatio).coerceIn(1f, 10f)
                                            actions.onSelectLens(targetLens.cameraId, targetCameraZoom)
                                            gestureStartZoom.value = targetCameraZoom
                                            pinchZoom.value = targetCameraZoom
                                            zoomAccumulated = 1f
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            gestureStartZoom.value = pinchZoom.value
                        }
                    },
            ) {
                if (state.gridEnabled) GridOverlay(Modifier.fillMaxSize())
                if (tapTick > 0) {
                    val tap = tapFraction
                    // Fade the border colour itself. `Modifier.alpha` placed after `.border`
                    // wraps only the (empty) content, so the outline drew at full opacity and
                    // the box never disappeared — measured on device, the box was still solid
                    // 6 s after the fade had already reached alpha 0.
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (tap.x * previewSize.width).roundToInt() - 28.dp.toPx().roundToInt(),
                                    (tap.y * previewSize.height).roundToInt() - 28.dp.toPx().roundToInt(),
                                )
                            }
                            .size(56.dp)
                            .border(
                                width = 1.dp,
                                color = CameraOrange.copy(alpha = reticleAlpha.value.coerceIn(0f, 1f)),
                                shape = RoundedCornerShape(4.dp),
                            ),
                    )
                }
            }
        }
    }
}

private fun previewFrameRatio(aspectRatio: AspectRatio, portrait: Boolean): Float? {
    val captureRatio = when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> 4f / 3f
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> return null
    }
    return if (portrait) 1f / captureRatio else captureRatio
}
