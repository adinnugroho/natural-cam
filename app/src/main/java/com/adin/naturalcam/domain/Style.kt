package com.adin.naturalcam.domain

/** Style pad coordinate in [-1, 1]; (0, 0) is exactly NATURAL baseline (STYLE_PLAN 13). */
data class StylePoint(val x: Float, val y: Float) {
    init {
        require(x in -1f..1f && y in -1f..1f) { "style point must be normalized, got ($x, $y)" }
    }

    companion object {
        val NEUTRAL = StylePoint(0f, 0f)
    }
}

/** v1 style interpretation version; bump when pad→parameter mapping changes (STYLE_PLAN 24). */
enum class StyleVersion(val code: Int) { V1(1) }

/** Complete creative style state applied between NATURAL base tone and gamut mapping (STYLE_PLAN 15). */
data class StyleState(
    val version: Int = StyleVersion.V1.code,
    val tone: StylePoint = StylePoint.NEUTRAL,
    val color: StylePoint = StylePoint.NEUTRAL,
    val palette: StylePoint = StylePoint.NEUTRAL,
    /** 0 = exactly NATURAL, 1 = full style. Never leaves side effects at zero (STYLE_PLAN 12.3). */
    val strength: Float = 1f,
)

/** Named starting points; a preset is only a starting pad position (STYLE_PLAN 14). */
data class StylePreset(
    val id: String,
    val name: String,
    val state: StyleState,
)

object StylePresets {
    private fun preset(id: String, name: String, tone: StylePoint, color: StylePoint, palette: StylePoint) =
        StylePreset(id, name, StyleState(tone = tone, color = color, palette = palette))

    val entries: List<StylePreset> = listOf(
        preset("natural", "Natural", StylePoint.NEUTRAL, StylePoint.NEUTRAL, StylePoint.NEUTRAL),
        preset("soft", "Soft", StylePoint(-0.4f, 0.2f), StylePoint.NEUTRAL, StylePoint.NEUTRAL),
        preset("warm", "Warm", StylePoint.NEUTRAL, StylePoint(0.6f, 0.1f), StylePoint.NEUTRAL),
        preset("gold", "Gold", StylePoint.NEUTRAL, StylePoint(0.3f, 0.3f), StylePoint(0f, 0.8f)),
        preset("cool", "Cool", StylePoint.NEUTRAL, StylePoint(-0.6f, 0.0f), StylePoint.NEUTRAL),
        preset("muted", "Muted", StylePoint.NEUTRAL, StylePoint(0f, -0.6f), StylePoint.NEUTRAL),
        preset("rich", "Rich", StylePoint(0.3f, 0f), StylePoint(0.2f, 0.7f), StylePoint.NEUTRAL),
        preset("deep", "Deep", StylePoint(0.4f, -0.6f), StylePoint.NEUTRAL, StylePoint.NEUTRAL),
    )
}
