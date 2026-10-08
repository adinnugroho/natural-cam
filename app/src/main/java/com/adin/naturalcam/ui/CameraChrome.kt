package com.adin.naturalcam.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adin.naturalcam.R
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraControl
import com.adin.naturalcam.ui.theme.CameraError
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite
import com.adin.naturalcam.ui.theme.neuPressed
import com.adin.naturalcam.ui.theme.neuRaised
import com.adin.naturalcam.ui.theme.neuRaisedCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal enum class CameraPopup { FLASH, OUTPUT, RATIO, LENS, TEMPERATURE, STYLE }

private fun rawModeLabel(mode: RawMode): String = when (mode) {
    RawMode.FINAL_ONLY -> "JPG"
    RawMode.RAW_AND_FINAL -> "JPG+RAW"
    RawMode.RAW_ONLY -> "RAW only"
}

/** Neumorphic toggle shared by every chrome button: raised at rest, one step shallower while active. */
private fun Modifier.neuToggle(active: Boolean, corner: Dp, depth: Dp): Modifier =
    then(
        if (active) Modifier.neuPressed(corner = corner, depth = depth - 1.dp)
        else Modifier.neuRaised(corner = corner, depth = depth),
    )

@Composable
private fun rememberPopupPositionProvider(preferAbove: Boolean): PopupPositionProvider {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    return remember(gap, preferAbove) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                val below = anchorBounds.bottom + gap
                val above = anchorBounds.top - popupContentSize.height - gap
                val y = if (preferAbove && above >= 0) {
                    above
                } else if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    above.coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
}

@Composable
internal fun TopControls(
    state: CameraUiState,
    actions: CameraActions,
    caps: CameraCapabilities?,
    immersive: Boolean,
    popup: CameraPopup?,
    onOpenSettings: () -> Unit,
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
            CameraIconButton(R.drawable.ic_settings, "Pengaturan", CameraWhite, false, onOpenSettings)
        }
    }
}

@Composable
private fun FlashButton(mode: FlashMode, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(34.dp)
            .neuToggle(active, corner = 12.dp, depth = 3.dp)
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
internal fun CameraIconButton(icon: Int, description: String, tint: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(34.dp)
            .neuToggle(active, corner = 12.dp, depth = 3.dp)
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
            .neuToggle(active, corner = 12.dp, depth = 3.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text = text, color = CameraWhite, style = MaterialTheme.typography.labelMedium) }
}

internal data class PopupOption(val label: String, val selected: Boolean)

@Composable
internal fun CameraPopupRow(
    options: List<PopupOption>,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    preferAbove: Boolean = false,
    itemWidth: Dp = 90.dp,
    itemHeight: Dp = 44.dp,
) {
    Popup(
        popupPositionProvider = rememberPopupPositionProvider(preferAbove),
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
internal fun BottomBar(
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
    // Widest first when the camera can zoom out below 1x (the wide lens lives behind the
    // logical camera); see lensChoices for why this is a preset and not another camera.
    val choices = lensChoices(state.lenses, state.capabilities, state.selectedCameraId, state.wideAngleActive)
    val selectedChoice = choices.firstOrNull { it.selected }
    val exposureCaps = state.capabilities?.takeIf { it.exposureCompensationUsable }

    // Clear the system gesture bar, then leave a 2px margin above it.
    val bottomMargin = with(LocalDensity.current) { 2.toDp() }

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
                        selectedChoice?.label ?: "—",
                        choices.isNotEmpty(),
                        popup == CameraPopup.LENS,
                    ) {
                        onPopupChange(if (popup == CameraPopup.LENS) null else CameraPopup.LENS)
                    }
                    if (popup == CameraPopup.LENS) {
                        CameraPopupRow(
                            choices.map { PopupOption(it.label, it.selected) },
                            { index ->
                                onLensSwitchStarted()
                                actions.onSelectLens(choices[index].cameraId, choices[index].zoomRatio)
                                onPopupChange(null)
                            },
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
            .neuToggle(active, corner = 12.dp, depth = 3.dp)
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

@Composable
private fun TemperatureButton(value: Float, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(34.dp)
            .neuToggle(active, corner = 12.dp, depth = 3.dp)
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

@Composable
private fun TemperaturePopup(value: Float, onValueChange: (Float) -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = rememberPopupPositionProvider(preferAbove = false),
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
            .neuToggle(active, corner = 21.dp, depth = 4.dp)
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
internal fun ErrorBar(failed: CaptureState.Failed, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = CameraError,
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
internal fun NoticeBar(notice: UiNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (notice.isError) CameraError else CameraControl,
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
