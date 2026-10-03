package com.adin.naturalcam.ui.theme

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.adin.naturalcam.R

val CameraBlack = Color(0xFF000000)
val CameraWhite = Color(0xFFFFFFFF)
val CameraOrange = Color(0xFFFF5A24)
val CameraControl = Color(0xFF1E1E1E)
val CameraOverlay = Color(0x94000000)
val CameraMuted = Color(0xFFB6B6B6)

private val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

private val NaturalCameraColors = darkColorScheme(
    primary = CameraOrange,
    onPrimary = CameraBlack,
    secondary = CameraWhite,
    onSecondary = CameraBlack,
    background = CameraBlack,
    onBackground = CameraWhite,
    surface = CameraControl,
    onSurface = CameraWhite,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = CameraMuted,
    error = Color(0xFFFF7961),
    onError = CameraBlack,
    outline = Color(0xFF6A6A6A),
)

private val NaturalTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceMono),
    displayMedium = TextStyle(fontFamily = SpaceMono),
    displaySmall = TextStyle(fontFamily = SpaceMono),
    headlineLarge = TextStyle(fontFamily = SpaceMono),
    headlineMedium = TextStyle(fontFamily = SpaceMono),
    headlineSmall = TextStyle(fontFamily = SpaceMono),
    titleLarge = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = SpaceMono),
    bodyMedium = TextStyle(fontFamily = SpaceMono),
    bodySmall = TextStyle(fontFamily = SpaceMono),
    labelLarge = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = SpaceMono),
    labelSmall = TextStyle(fontFamily = SpaceMono),
)

@Composable
fun NaturalCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NaturalCameraColors,
        typography = NaturalTypography,
        content = content,
    )
}

@Composable
fun cameraChoiceColors() = FilterChipDefaults.filterChipColors(
    containerColor = CameraControl,
    labelColor = CameraWhite,
    selectedContainerColor = CameraOrange,
    selectedLabelColor = CameraBlack,
    disabledContainerColor = CameraControl.copy(alpha = 0.45f),
    disabledLabelColor = CameraWhite.copy(alpha = 0.35f),
)
