package com.adin.naturalcam.domain

enum class AspectRatio(val label: String) {
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9"),
    RATIO_FULL("Full"),
}

/** User settings; every field has an explicit default (SPEC 128). */
data class AppSettings(
    val profile: ProcessingProfile = ProcessingProfile.NATURAL,
    val rawMode: RawMode = RawMode.FINAL_ONLY,
    val flashMode: FlashMode = FlashMode.OFF,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val highestResolution: Boolean = false,
    val timerSeconds: Int = 0,
    val geotagging: Boolean = false,
    val gridEnabled: Boolean = false,
    /** Normalized white-balance temperature adjustment in [-1, 1]. */
    val temperature: Float = 0f,
    /** Creative style state (STYLE_PLAN 13). */
    val style: StyleState = StyleState(),
)
