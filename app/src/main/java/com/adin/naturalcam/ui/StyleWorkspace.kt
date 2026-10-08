package com.adin.naturalcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adin.naturalcam.R
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StylePresets
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite
import com.adin.naturalcam.ui.theme.neuPressed
import com.adin.naturalcam.ui.theme.neuRaised
import kotlin.math.roundToInt

/** Top bar during style mode: back + preset list row (not a popup; STYLE_PLAN 22). */
@Composable
internal fun StyleModeTopBar(
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
internal fun SinglePadEditor(
    style: StyleState,
    onStyleChange: (StyleState) -> Unit,
) {
    val point = singlePadPoint(style)
    val bottomMargin = with(LocalDensity.current) { 2.toDp() }
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
        val density = LocalDensity.current
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

private val GRID_LINE = CameraWhite.copy(alpha = 0.09f)
