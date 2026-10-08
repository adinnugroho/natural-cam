package com.adin.naturalcam.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.SATURATION_RANGE
import com.adin.naturalcam.image.processing.GrainStage
import com.adin.naturalcam.ui.theme.CameraWhite

@Composable
internal fun GridOverlay(modifier: Modifier) { Canvas(modifier) { drawGrid() } }

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

/**
 * Low-cost preview approximation of the same StyleState used by final capture.
 * Bloom and Grain are independent controls, so they render even at style
 * strength 0.
 */
@Composable
internal fun StylePreviewOverlay(style: StyleState, modifier: Modifier) {
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
internal fun View.applyPreviewSaturation(saturation: Float) {
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
