package com.adin.naturalcam.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neumorphic ("soft UI") surface treatment: two offset blurred-looking shadows
 * over a mid-tone base, so controls read as pressed from the same material as
 * the panel they sit on instead of as flat rectangles.
 *
 * Compose cannot blur a draw-time shadow on a hardware canvas, so the falloff is
 * faked with a short stack of offset rounded rects at decreasing alpha. Cheap,
 * deterministic, and good enough at UI scale.
 */

/** Extruded (raised) control: light catching top-left, shadow falling bottom-right. */
fun Modifier.neuRaised(
    corner: Dp = 24.dp,
    depth: Dp = 7.dp,
    surface: Color = NeumBase,
): Modifier = drawNeumorphic(corner, depth, surface, inverted = false)

/** Recessed control: used for pressed/active states and inset wells. */
fun Modifier.neuPressed(
    corner: Dp = 24.dp,
    depth: Dp = 5.dp,
    surface: Color = NeumBase,
): Modifier = drawNeumorphic(corner, depth, surface, inverted = true)

private fun Modifier.drawNeumorphic(
    corner: Dp,
    depth: Dp,
    surface: Color,
    inverted: Boolean,
): Modifier = drawBehind {
    val radius = corner.toPx().coerceAtMost(size.minDimension / 2f)
    val spread = depth.toPx()
    val steps = 7

    for (i in steps downTo 1) {
        val t = i.toFloat() / steps
        val offset = spread * t
        val alpha = 0.20f * (1f - t) + 0.02f
        val dark = if (inverted) NeumHighlight else NeumShadow
        val light = if (inverted) NeumShadow else NeumHighlight

        // Shadow side.
        drawRoundRect(
            color = dark.copy(alpha = alpha),
            topLeft = Offset(offset, offset),
            size = size,
            cornerRadius = CornerRadius(radius, radius),
        )
        // Highlight side.
        drawRoundRect(
            color = light.copy(alpha = alpha),
            topLeft = Offset(-offset, -offset),
            size = size,
            cornerRadius = CornerRadius(radius, radius),
        )
    }

    // Base fill on top of the shadow stack so the control reads as one material.
    drawRoundRect(
        color = surface,
        topLeft = Offset.Zero,
        size = size,
        cornerRadius = CornerRadius(radius, radius),
    )
}

/** Circle variants for shutter-style controls. */
fun Modifier.neuRaisedCircle(depth: Dp = 7.dp, surface: Color = NeumBase): Modifier = drawBehind {
    val radius = size.minDimension / 2f
    val spread = depth.toPx()
    val steps = 7
    for (i in steps downTo 1) {
        val t = i.toFloat() / steps
        val offset = spread * t
        val alpha = 0.22f * (1f - t) + 0.02f
        drawCircle(NeumShadow.copy(alpha = alpha), radius, Offset(radius + offset, radius + offset))
        drawCircle(NeumHighlight.copy(alpha = alpha), radius, Offset(radius - offset, radius - offset))
    }
    drawCircle(surface, radius, Offset(radius, radius))
}
