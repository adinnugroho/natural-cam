package com.adin.naturalcam.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import android.net.Uri
import android.util.Size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adin.naturalcam.R
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StylePresets
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.SATURATION_RANGE
import com.adin.naturalcam.image.processing.GrainStage
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraControl
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraOverlay
import com.adin.naturalcam.ui.theme.CameraWhite
import com.adin.naturalcam.ui.theme.NeumBase
import com.adin.naturalcam.ui.theme.neuPressed
import com.adin.naturalcam.ui.theme.neuRaised
import com.adin.naturalcam.ui.theme.neuRaisedCircle
import com.adin.naturalcam.ui.theme.cameraChoiceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CameraPopup { FLASH, OUTPUT, RATIO, RESOLUTION, PROFILE, LENS, TEMPERATURE, STYLE }
private fun rawModeLabel(mode: RawMode): String = when (mode) {
    RawMode.FINAL_ONLY -> "JPG"
    RawMode.RAW_AND_FINAL -> "JPG+RAW"
    RawMode.RAW_ONLY -> "RAW only"
}

@Composable
fun CameraScreen(
    state: CameraUiState,
    actions: CameraActions,
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
    var frozenLensFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
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
                TopControls(state, actions, caps, immersivePreview, popup) { popup = it }
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
        if (busy) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = CameraOrange)
                Spacer(Modifier.height(8.dp))
                Text("Processing…", color = CameraWhite)
            }
        }
    }
}

@Composable
private fun Preview(
    state: CameraUiState,
    actions: CameraActions,
    onPreviewViewCreated: (PreviewView) -> Unit,
    frozenLensFrame: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var tapFraction by remember { mutableStateOf(Offset.Zero) }
    var tapTick by remember { mutableStateOf(0) }
    val reticleAlpha = remember { Animatable(0f) }
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >=
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val frameRatio = previewFrameRatio(state.aspectRatio, portrait)

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
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (tap.x * previewSize.width).roundToInt() - 28.dp.toPx().roundToInt(),
                                    (tap.y * previewSize.height).roundToInt() - 28.dp.toPx().roundToInt(),
                                )
                            }
                            .size(56.dp)
                            .border(1.dp, CameraOrange, RoundedCornerShape(4.dp))
                            .alpha(reticleAlpha.value.coerceIn(0f, 1f)),
                    )
                }
            }
        }
    }
}

/**
 * Low-cost preview approximation of the same StyleState used by final capture.
 * Bloom and Grain are independent controls, so they render even at style
 * strength 0.
 */
@Composable
private fun StylePreviewOverlay(style: StyleState, modifier: Modifier) {
    val strength = style.strength.coerceIn(0f, 1f)
    val bloom = style.bloom.coerceIn(0f, 1f)
    val grain = style.grain.coerceIn(0f, 1f)
    if (strength <= 0f && bloom <= 0f && grain <= 0f) return
    val grainBrush = remember { ShaderBrush(ImageShader(GRAIN_TILE, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier) {
        val toneDepth = (-style.tone.y).coerceIn(-1f, 1f) * strength
        val warmth = style.color.x.coerceIn(-1f, 1f) * strength
        val richness = style.color.y.coerceIn(-1f, 1f) * strength
        val palette = style.palette.y.coerceIn(-1f, 1f) * strength
        if (toneDepth != 0f) drawRect(if (toneDepth > 0f) Color.Black else Color.White, alpha = kotlin.math.abs(toneDepth) * 0.08f)
        if (warmth != 0f) drawRect(if (warmth > 0f) Color(0xFFFFA16A) else Color(0xFF8A94A0), alpha = kotlin.math.abs(warmth) * 0.07f)
        if (richness != 0f) drawRect(if (richness > 0f) Color(0xFFFFD28A) else Color.White, alpha = kotlin.math.abs(richness) * 0.045f)
        if (palette != 0f) drawRect(if (palette > 0f) Color(0xFFFFC66D) else Color(0xFF8A94A0), alpha = kotlin.math.abs(palette) * 0.035f)
        if (bloom > 0f) {
            // Preview approximation: the capture thresholds and blurs real highlights in
            // linear light, which a live overlay cannot sample, so this reads as a soft
            // frame-wide glow whose intensity tracks the amount slider.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = bloom * 0.14f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.85f,
                ),
            )
        }
        if (grain > 0f) {
            // Preview approximation: capture adds deterministic linear-light monochrome
            // grain weighted to midtones; a tiled noise bitmap in Overlay blend keeps the
            // image mean unchanged and only moves local contrast.
            drawRect(brush = grainBrush, blendMode = BlendMode.Overlay, alpha = grain * 0.6f)
        }
    }
}

/**
 * Paints the Style workspace's Saturation onto the live feed.
 *
 * The capture applies saturation in linear light through the whole pipeline; the
 * preview cannot, so it recolours the camera view with a hue-preserving colour matrix
 * instead - close enough to compose with, and the only way to show a signed colour
 * change on a live feed without copying frames. API 31 is the floor for view-level
 * colour effects; below that the preview simply keeps the camera's own colour and the
 * slider only shows up in the saved photo.
 */
private fun View.applyPreviewSaturation(saturation: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val signed = saturation.coerceIn(-1f, 1f)
    val effect = if (signed == 0f) {
        null
    } else {
        RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(saturationColorMatrix(1f + SATURATION_RANGE * signed)),
        )
    }
    setRenderEffect(effect)
}

/**
 * Standard saturation matrix: the chroma of the pixel is scaled about its luminance,
 * which is what the capture's Saturation control does in linear light.
 */
private fun saturationColorMatrix(scale: Float): FloatArray {
    val lr = 0.2126f
    val lg = 0.7152f
    val lb = 0.0722f
    val inv = 1f - scale
    return floatArrayOf(
        lr * inv + scale, lg * inv, lb * inv, 0f, 0f,
        lr * inv, lg * inv + scale, lb * inv, 0f, 0f,
        lr * inv, lg * inv, lb * inv + scale, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * Fixed noise tile for the grain preview. Generated once from the same grain
 * field the capture stage uses, so the overlay shows the same clumped character;
 * a deterministic tile keeps the overlay allocation-free while dragging. The
 * tile is large enough to hold several density patches, which keeps the repeat
 * from reading as a pattern.
 */
private val GRAIN_TILE: ImageBitmap by lazy {
    val size = 512
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val v = ((GrainStage.sample(x, y) * 127f) + 128f).toInt().coerceIn(0, 255)
            pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    bitmap.asImageBitmap()
}

private fun previewFrameRatio(aspectRatio: AspectRatio, portrait: Boolean): Float? {
    val captureRatio = when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> 4f / 3f
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> return null
    }
    return if (portrait) 1f / captureRatio else captureRatio
}

@Composable
private fun GridOverlay(modifier: Modifier) { Canvas(modifier) { drawGrid() } }

private fun DrawScope.drawGrid() {
    val color = CameraWhite.copy(alpha = 0.35f)
    val stroke = 1.dp.toPx()
    for (i in 1..2) {
        val x = size.width * i / 3f
        val y = size.height * i / 3f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
    }
}

@Composable
private fun TopControls(
    state: CameraUiState,
    actions: CameraActions,
    caps: CameraCapabilities?,
    immersive: Boolean,
    popup: CameraPopup?,
    onPopupChange: (CameraPopup?) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .neuRaised(corner = 20.dp, depth = 5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (caps?.flashAvailable == true) {
                FlashButton(state.flashMode, popup == CameraPopup.FLASH) {
                    onPopupChange(if (popup == CameraPopup.FLASH) null else CameraPopup.FLASH)
                }
                if (popup == CameraPopup.FLASH) {
                    CameraPopupRow(
                        FlashMode.entries.map { PopupOption(it.name, state.flashMode == it) },
                        { actions.onSetFlash(FlashMode.entries[it]); onPopupChange(null) },
                        { onPopupChange(null) },
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            CameraTextButton(
                state.aspectRatio.label,
                "Pilih rasio foto, ${state.aspectRatio.label}",
                popup == CameraPopup.RATIO,
            ) {
                onPopupChange(if (popup == CameraPopup.RATIO) null else CameraPopup.RATIO)
            }
            if (popup == CameraPopup.RATIO) {
                CameraPopupRow(
                    AspectRatio.entries.map { PopupOption(it.label, state.aspectRatio == it) },
                    { actions.onSetAspectRatio(AspectRatio.entries[it]); onPopupChange(null) },
                    { onPopupChange(null) },
                )
            }
            CameraTextButton(
                rawModeLabel(state.rawMode),
                "Pilih output, ${rawModeLabel(state.rawMode)}",
                popup == CameraPopup.OUTPUT,
            ) {
                onPopupChange(if (popup == CameraPopup.OUTPUT) null else CameraPopup.OUTPUT)
            }
            if (popup == CameraPopup.OUTPUT) {
                CameraPopupRow(
                    RawMode.entries.map { PopupOption(rawModeLabel(it), state.rawMode == it) },
                    { actions.onSetRawMode(RawMode.entries[it]); onPopupChange(null) },
                    { onPopupChange(null) },
                )
            }
            CameraIconButton(R.drawable.ic_settings, "Pengaturan", CameraWhite, false, actions::onOpenSettings)
        }
    }
}

@Composable
private fun FlashButton(mode: FlashMode, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(34.dp)
            .then(if (active) Modifier.neuPressed(corner = 12.dp, depth = 2.dp) else Modifier.neuRaised(corner = 12.dp, depth = 3.dp))
            .semantics { contentDescription = "Pilih flash, ${mode.name}" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val flashOn = mode != FlashMode.OFF
        Icon(
            painterResource(R.drawable.ic_flash),
            null,
            if (flashOn) {
                Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(18.dp)
            } else {
                // Centred when there is no mode label under it.
                Modifier.align(Alignment.Center).size(19.dp)
            },
            if (flashOn) CameraOrange else CameraWhite,
        )
        if (mode != FlashMode.OFF) {
            Text(
                text = mode.name,
                color = CameraOrange,
                fontSize = 6.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun CameraIconButton(icon: Int, description: String, tint: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(34.dp)
            .then(if (active) Modifier.neuPressed(corner = 12.dp, depth = 2.dp) else Modifier.neuRaised(corner = 12.dp, depth = 3.dp))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(19.dp), tint)
    }
}

@Composable
private fun CameraTextButton(text: String, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .widthIn(min = 60.dp)
            .then(if (active) Modifier.neuPressed(corner = 12.dp, depth = 2.dp) else Modifier.neuRaised(corner = 12.dp, depth = 3.dp))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text = text, color = CameraWhite, style = MaterialTheme.typography.labelMedium) }
}

private data class PopupOption(val label: String, val selected: Boolean)

@Composable
private fun CameraPopupRow(
    options: List<PopupOption>,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    preferAbove: Boolean = false,
    itemWidth: Dp = 90.dp,
    itemHeight: Dp = 44.dp,
) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap, preferAbove) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                    val below = anchorBounds.bottom + gap
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (preferAbove && above >= 0) above else if (below + popupContentSize.height <= windowSize.height) below else above.coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            options.forEachIndexed { index, option ->
                Surface(
                    Modifier
                        .width(itemWidth)
                        .height(itemHeight)
                        .semantics { contentDescription = if (option.selected) "${option.label}, terpilih" else option.label }
                        .clickable { onSelect(index) },
                    RoundedCornerShape(8.dp),
                    Color(0x8C000000),
                    border = BorderStroke(1.dp, CameraWhite.copy(alpha = 0.28f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = option.label,
                            color = if (option.selected) CameraOrange else CameraWhite,
                            style = if (option.selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun evSteps(caps: CameraCapabilities): Int {
    val step = caps.exposureCompensationStepEv
    val range = caps.exposureCompensationEvRange ?: return 0
    if (step <= 0f) return 0
    return (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
}

@Composable
private fun BottomBar(
    state: CameraUiState,
    actions: CameraActions,
    shutterScale: Float,
    busy: Boolean,
    immersive: Boolean,
    popup: CameraPopup?,
    onLensSwitchStarted: () -> Unit,
    onOpenStyleMode: () -> Unit,
    onPopupChange: (CameraPopup?) -> Unit,
) {
    val currentFacing = state.lenses.firstOrNull { it.cameraId == state.selectedCameraId }?.lensFacing
    val switchFacing = when (currentFacing) {
        LensFacing.BACK -> LensFacing.FRONT
        LensFacing.FRONT -> LensFacing.BACK
        else -> null
    }
    val switchCameraId = switchFacing?.let { facing -> state.lenses.firstOrNull { it.lensFacing == facing }?.cameraId }
    val selectableLenses = when (currentFacing) {
        LensFacing.BACK -> state.lenses.filter { it.lensFacing == LensFacing.BACK }
        LensFacing.FRONT -> state.lenses.filter { it.lensFacing == LensFacing.FRONT }
        else -> state.lenses
    }
    val selectedLens = selectableLenses.firstOrNull { it.cameraId == state.selectedCameraId }
    val visibleLenses = selectableLenses.sortedByDescending { it.cameraId == state.selectedCameraId }.distinctBy { it.label.replace("×", "X").uppercase() }
    val exposureCaps = state.capabilities?.takeIf { it.exposureCompensationUsable }

    // Clear the system gesture bar, then leave a 2px margin above it.
    val bottomMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 2.toDp() }

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = bottomMargin)
            .neuRaised(corner = 22.dp, depth = 6.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StylePadButton(state.style, popup == CameraPopup.STYLE) {
                    onOpenStyleMode()
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    LensButton(
                        selectedLens?.label?.replace("×", "X")?.uppercase() ?: "—",
                        selectableLenses.isNotEmpty(),
                        popup == CameraPopup.LENS,
                    ) {
                        onPopupChange(if (popup == CameraPopup.LENS) null else CameraPopup.LENS)
                    }
                    if (popup == CameraPopup.LENS) {
                        CameraPopupRow(
                            visibleLenses.map { PopupOption(it.label.replace("×", "X").uppercase(), it.cameraId == state.selectedCameraId) },
                            { onLensSwitchStarted(); actions.onSelectLens(visibleLenses[it].cameraId); onPopupChange(null) },
                            { onPopupChange(null) },
                            preferAbove = true,
                        )
                    }
                }
                TemperatureButton(state.temperature, popup == CameraPopup.TEMPERATURE) {
                    onPopupChange(if (popup == CameraPopup.TEMPERATURE) null else CameraPopup.TEMPERATURE)
                }
                if (popup == CameraPopup.TEMPERATURE) {
                    TemperaturePopup(state.temperature, actions::onSetTemperature) { onPopupChange(null) }
                }
            }
        }
        if (exposureCaps != null) ExposureControl(exposureCaps, state.exposureCompensationEv, actions::onSetExposureCompensation, immersive)
        CaptureControls(state.lastCapture, switchCameraId, state.isShutterEnabled, busy, shutterScale, actions::onOpenGallery, actions::onShutter) { switchCameraId?.let(actions::onSelectLens) }
    }
        }
    }


@Composable
private fun StylePadButton(style: StyleState, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(34.dp)
            .then(if (active) Modifier.neuPressed(corner = 12.dp, depth = 2.dp) else Modifier.neuRaised(corner = 12.dp, depth = 3.dp))
            .semantics { contentDescription = "Buka panel gaya" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(Color(0xFFFFC93C), RoundedCornerShape(3.dp)))
            Box(Modifier.size(11.dp).background(Color(0xFFF2F2EE), RoundedCornerShape(3.dp)))
        }
    }
}

/** Top bar during style mode: back + preset list row (not a popup; STYLE_PLAN 22). */
@Composable
private fun StyleModeTopBar(
    state: CameraUiState,
    actions: CameraActions,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)
            .neuRaised(corner = 20.dp, depth = 5.dp)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
    ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterStart) {
                    CameraIconButton(R.drawable.ic_back, "Kembali", CameraWhite, false, onBack)
                }
                // Strength lives in the header so the pad dock keeps only Bloom + reset.
                StyleSliderRow(
                    label = "STYLE",
                    value = state.style.strength,
                    modifier = Modifier.weight(1f),
                ) { actions.onSetStyle(state.style.copy(strength = it)) }
            }
            // Inline preset list: one explicit row, no popup and no collapsed height.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StylePresets.entries.forEach { preset ->
                    val selected = preset.matches(state.style)
                    Box(
                        Modifier
                            .height(30.dp)
                            .then(
                                if (selected) {
                                    Modifier
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(CameraOrange)
                                } else {
                                    Modifier.neuRaised(corner = 15.dp, depth = 2.dp)
                                },
                            )
                            .clickable { actions.onSelectStylePreset(preset.state) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            preset.name,
                            color = if (selected) CameraBlack else CameraWhite.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
            // Explicit gap so chip shadows never bleed into the preview surface.
            Spacer(Modifier.height(12.dp))
    }
}

/**
 * Style-mode bottom editor: one big 2D pad where X = COLOR cool↔warm paired with
 * TONE soft↔hard influence lanes, and Y = TONE lift↔deepen crossed with PALETTE
 * gold↔blue shadow tint. The style engine's pads derive from this single point:
 * tone = (x soft/half of hard axis, y), color = (x, chromaFromY|lift), palette = (side, gold/blue).
 * Strength and Bloom use the same compact row as the EV slider. Back closes the mode.
 */
@Composable
private fun SinglePadEditor(
    style: StyleState,
    onStyleChange: (StyleState) -> Unit,
) {
    val point = singlePadPoint(style)
    val bottomMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 2.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = bottomMargin)
            .neuRaised(corner = 22.dp, depth = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Compact square pad inside the dock.
            SinglePad(point, onPointChange = { pt -> onStyleChange(singlePadToStyle(pt, style)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var amountControl by remember { mutableStateOf(StyleAmount.BLOOM) }
                var pickerOpen by remember { mutableStateOf(false) }
                Box {
                    StyleSliderRow(
                        label = amountControl.label,
                        value = amountControl.amountOf(style),
                        range = amountControl.range,
                        format = amountControl::format,
                        onLabelClick = { pickerOpen = true },
                    ) { onStyleChange(amountControl.withAmount(style, it)) }
                    if (pickerOpen) {
                        CameraPopupRow(
                            options = StyleAmount.entries.map { PopupOption(it.label, it == amountControl) },
                            onSelect = { index ->
                                amountControl = StyleAmount.entries[index]
                                pickerOpen = false
                            },
                            onDismissRequest = { pickerOpen = false },
                            preferAbove = true,
                            // Three entries have to fit one popup row.
                            itemWidth = 76.dp,
                        )
                    }
                }
                Box(Modifier.align(Alignment.End)) {
                    Box(
                        Modifier
                            .neuRaised(corner = 12.dp, depth = 3.dp)
                            // Reset the pads and strength; the independent amounts are kept.
                            .clickable {
                                onStyleChange(
                                    StyleState(
                                        bloom = style.bloom,
                                        grain = style.grain,
                                        saturation = style.saturation,
                                    ),
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("RESET", color = CameraWhite, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * The amount controls the dock's single slider can edit. Each is independent of
 * the pads and of style strength, so the list only swaps which amount the row
 * shows; every other field of the state is carried over untouched. Saturation is
 * signed (neutral at the middle of its track) because "no change" must be the
 * default for it, unlike Bloom and Grain where 0 already means off.
 */
private enum class StyleAmount(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
) {
    BLOOM("BLOOM", 0f..1f),
    GRAIN("GRAIN", 0f..1f),
    SATURATION("SATURATION", -1f..1f),
    ;

    fun amountOf(style: StyleState): Float = when (this) {
        BLOOM -> style.bloom
        GRAIN -> style.grain
        SATURATION -> style.saturation
    }

    fun withAmount(style: StyleState, amount: Float): StyleState = when (this) {
        BLOOM -> style.copy(bloom = amount.coerceIn(0f, 1f))
        GRAIN -> style.copy(grain = amount.coerceIn(0f, 1f))
        SATURATION -> style.copy(saturation = amount.coerceIn(-1f, 1f))
    }

    /** Signed for a bipolar control, plain percentage for an amount from zero. */
    fun format(amount: Float): String {
        val percent = (amount * 100f).roundToInt()
        return if (range.start < 0f && percent > 0) "+$percent%" else "$percent%"
    }
}

/**
 * Same compact shape as the EV control: short tag, track with the amount inside it.
 *
 * A one-sided amount (Bloom, Grain) fills its track from the left, which is what
 * "0 means off" looks like. A signed control (Saturation) must not: filling from
 * the left would read as 50% at rest. Bipolar rows therefore draw their own
 * centre-anchored fill and a neutral tick, and let the Material slider only own
 * the thumb, the drag, and the accessibility node.
 */
@Composable
private fun StyleSliderRow(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    onLabelClick: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    val amount = value.coerceIn(range.start, range.endInclusive)
    val bipolar = range.start < 0f
    Row(
        modifier
            .fillMaxWidth()
            .neuPressed(corner = 14.dp, depth = 3.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // The caret marks the tag as the control picker when it has a menu.
            if (onLabelClick == null) label else "$label ▾",
            color = CameraOrange,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 1,
            softWrap = false,
            modifier = if (onLabelClick == null) {
                Modifier
            } else {
                Modifier
                    .semantics { contentDescription = "Pilih kontrol jumlah, $label" }
                    .clickable(onClick = onLabelClick)
            },
        )
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (bipolar) {
                // Drawn under the slider: the slider's own track is transparent for
                // bipolar rows, so this is the only track the user sees.
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val trackStart = BIPOLAR_TRACK_INSET.toPx()
                    val trackEnd = size.width - BIPOLAR_TRACK_INSET.toPx()
                    val centerX = size.width / 2f
                    val thumbX = trackStart + (amount - range.start) / (range.endInclusive - range.start) * (trackEnd - trackStart)
                    val stroke = 5.dp.toPx()
                    val centerY = center.y
                    // Empty track: a dim line, so the control reads as a scale, not a bar.
                    drawLine(
                        Color.White.copy(alpha = 0.16f),
                        Offset(trackStart, centerY),
                        Offset(trackEnd, centerY),
                        stroke,
                        StrokeCap.Round,
                    )
                    // Deviation from neutral, growing out of the centre.
                    drawLine(
                        CameraOrange,
                        Offset(centerX, centerY),
                        Offset(thumbX, centerY),
                        stroke,
                        StrokeCap.Round,
                    )
                    // Neutral tick: the slider's thumb lands exactly on it at 0.
                    drawLine(
                        Color.White.copy(alpha = 0.45f),
                        Offset(centerX, centerY - 5.dp.toPx()),
                        Offset(centerX, centerY + 5.dp.toPx()),
                        1.5.dp.toPx(),
                    )
                }
            }
            Slider(
                value = amount,
                onValueChange = onValueChange,
                valueRange = range,
                // Snapping a signed control at 10% steps keeps exact neutral reachable.
                steps = if (bipolar) 20 else 0,
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CameraOrange,
                    activeTrackColor = if (bipolar) Color.Transparent else CameraOrange,
                    inactiveTrackColor = if (bipolar) Color.Transparent else CameraBlack,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = if (bipolar) Color.Transparent else CameraWhite.copy(alpha = 0.3f),
                ),
            )
            if (!bipolar) {
                // Inside the track, so the row needs no separate value column: white
                // stays readable over both the filled and the empty part of the track.
                Text(
                    format(amount),
                    color = CameraWhite,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (bipolar) {
            // A signed row keeps its value outside the track: inside, the neutral tick
            // and the fill growing out of the centre would run through the digits.
            Text(
                format(amount),
                color = CameraWhite,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(40.dp),
            )
        }
    }
}

/**
 * Half the Material slider's thumb travel at each end, so a hand-drawn bipolar
 * fill stops exactly where the thumb can reach.
 */
private val BIPOLAR_TRACK_INSET = 10.dp

/**
 * The one knob (STYLE_PLAN revision: single pad controls everything). Maps to
 * the engine's three pads as: tone = soft/hared left-right influence driven by X,
 * lifted/deepen from Y; color warmth from X; palette rose/green lean from X and
 * gold/blue from Y nearby. Center is a neutral state (STYLE_PLAN 47).
 */
private fun singlePadPoint(style: StyleState): StylePoint = StylePoint(
    ((style.color.x + style.tone.x) * 0.5f).coerceIn(-1f, 1f),
    ((style.tone.y + style.palette.y) * 0.5f).coerceIn(-1f, 1f),
)

private fun singlePadToStyle(point: StylePoint, base: StyleState): StyleState = base.copy(
    tone = StylePoint(point.x, point.y),
    color = StylePoint(point.x, base.color.y),
    palette = StylePoint(base.palette.x, point.y),
    strength = base.strength,
)

@Composable
private fun SinglePad(
    point: StylePoint,
    onPointChange: (StylePoint) -> Unit,
) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .width(112.dp)
            .aspectRatio(1f)
            .onSizeChanged { padSize = it }
            .neuPressed(corner = 18.dp, depth = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            // A 10×10 grid makes pad movement predictable and repeatable.
            .pointerInput(Unit) {
                fun snap(value: Float): Float = (value * 10f).roundToInt().coerceIn(-10, 10) / 10f
                fun clampPoint(offset: Offset): StylePoint = StylePoint(
                    snap((offset.x / size.width) * 2f - 1f),
                    snap(((size.height - offset.y) / size.height) * 2f - 1f),
                )
                detectDragGestures { change, _ -> onPointChange(change.position.let(::clampPoint)) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Color itself hints at direction: cool/soft blue → warm/hard amber,
            // with darker lower values and no textual axis tags.
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF4A4A50), Color(0xFF6B6660), Color(0xFFC69A4E)),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                ),
            )
            // Crosshair axes emphasize center = NATURAL baseline (STYLE_PLAN 47).
            drawLine(GRAY_LINE, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            drawLine(GRAY_LINE, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            for (i in 1 until 10) {
                val p = i / 10f
                drawLine(GRID_LINE, Offset(size.width * p, 0f), Offset(size.width * p, size.height))
                drawLine(GRID_LINE, Offset(0f, size.height * p), Offset(size.width, size.height * p))
            }
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val knobHalf = with(density) { 6.dp.toPx() }
        val knobX = ((point.x + 1f) / 2f * padSize.width - knobHalf).roundToInt().coerceAtLeast(0)
        val knobY = ((1f - (point.y + 1f) / 2f) * padSize.height - knobHalf).roundToInt().coerceAtLeast(0)
        Box(
            Modifier
                .absoluteOffset(x = with(density) { knobX.toFloat().toDp() }, y = with(density) { knobY.toFloat().toDp() })
                .size(12.dp)
                .background(CameraOrange, CircleShape),
        )
        // Direction is communicated by the color field, not text tags.
    }
}

private val GRAY_LINE = CameraWhite.copy(alpha = 0.18f)

@Composable
private fun TemperatureButton(value: Float, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(34.dp)
            .then(if (active) Modifier.neuPressed(corner = 12.dp, depth = 2.dp) else Modifier.neuRaised(corner = 12.dp, depth = 3.dp))
            .semantics { contentDescription = "Pilih temperatur warna" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(Color(0xFFFF8A5C), RoundedCornerShape(3.dp)))
            Box(Modifier.size(11.dp).background(Color(0xFF9CCBF5), RoundedCornerShape(3.dp)))
        }
    }
}

private val GRID_LINE = CameraWhite.copy(alpha = 0.09f)

@Composable
private fun TemperaturePopup(value: Float, onValueChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                    val below = anchorBounds.bottom + gap
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (below + popupContentSize.height <= windowSize.height) below else above.coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            Modifier.width(270.dp).padding(10.dp),
            RoundedCornerShape(10.dp),
            Color(0xD9000000),
            border = BorderStroke(1.dp, CameraWhite.copy(alpha = 0.28f)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("COOL", color = Color(0xFF8C99A6), style = MaterialTheme.typography.labelSmall)
                    Text("WARM", color = Color(0xFFD99A6C), style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = -1f..1f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = CameraOrange,
                        activeTrackColor = CameraOrange,
                        inactiveTrackColor = CameraWhite.copy(alpha = 0.35f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LensButton(label: String, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .then(if (active) Modifier.neuPressed(corner = 21.dp, depth = 3.dp) else Modifier.neuRaised(corner = 21.dp, depth = 4.dp))
            .semantics { contentDescription = "Pilih lensa, $label" }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = label, label = "lens-label") { selectedLabel ->
            Text(selectedLabel, color = if (active) CameraOrange else CameraWhite, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ExposureControl(caps: CameraCapabilities, value: Float, onValueChange: (Float) -> Unit, immersive: Boolean) {
    val range = caps.exposureCompensationEvRange ?: (0f..0f)
    Box(
        Modifier
            .fillMaxWidth()
            .neuPressed(corner = 14.dp, depth = 3.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EV", color = CameraOrange, style = MaterialTheme.typography.labelSmall)
            Slider(
                value.coerceIn(range),
                onValueChange,
                valueRange = range,
                steps = evSteps(caps),
                modifier = Modifier.weight(1f).height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CameraOrange,
                    activeTrackColor = CameraOrange,
                    inactiveTrackColor = CameraBlack,
                    activeTickColor = CameraBlack,
                    inactiveTickColor = CameraWhite.copy(alpha = 0.3f),
                ),
            )
            Text(
                value.toEvLabel(true),
                color = CameraOrange,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(min = 46.dp),
            )
        }
    }
}

private fun Float.toEvLabel(showPlus: Boolean = false): String {
    val rounded = (this * 10f).roundToInt() / 10f
    return if (showPlus) "%+.1f".format(rounded) else "%.1f".format(rounded)
}

@Composable
private fun CaptureControls(lastCapture: SavedPhoto?, switchCameraId: CameraId?, enabled: Boolean, busy: Boolean, shutterScale: Float, onGallery: () -> Unit, onShutter: () -> Unit, onSwitchCamera: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { GalleryShortcut(lastCapture, onGallery) }
        ShutterButton(enabled, busy, shutterScale, onShutter)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (switchCameraId != null) CameraSwitchButton(!busy, onSwitchCamera) }
    }
}

@Composable
private fun CameraSwitchButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .neuRaised(corner = 24.dp, depth = 4.dp)
            .semantics { contentDescription = "Ganti kamera" }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(R.drawable.ic_switch_camera), null, Modifier.size(22.dp), CameraWhite) }
}

@Composable
private fun GalleryShortcut(lastCapture: SavedPhoto?, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = lastCapture?.uri) {
        value = lastCapture?.uri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(Uri.parse(uri), Size(120, 120), null)
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(48.dp)
            .neuRaised(corner = 14.dp, depth = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = if (lastCapture == null) "Galeri, belum ada foto" else "Buka galeri"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, active: Boolean, scale: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .scale(scale)
            .size(74.dp)
            .neuRaisedCircle(depth = 6.dp)
            .semantics { contentDescription = "Ambil foto" }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        // Inset ring: the recess reads as a lens barrel rather than a flat button.
        Box(
            Modifier
                .size(56.dp)
                .neuPressed(corner = 28.dp, depth = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(if (active) CameraOrange else CameraWhite, CircleShape))
        }
    }
}

@Composable
private fun ErrorBar(failed: CaptureState.Failed, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFE64A35),
        contentColor = CameraBlack,
    ) {
        Text(
            text = failed.error.detail ?: "Capture gagal",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NoticeBar(notice: UiNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (notice.isError) Color(0xFFE64A35) else CameraControl,
        contentColor = CameraWhite,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = notice.message,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "×",
                modifier = Modifier
                    .semantics { contentDescription = "Tutup" }
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}