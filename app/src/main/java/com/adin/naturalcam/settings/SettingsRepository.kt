package com.adin.naturalcam.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adin.naturalcam.domain.AppSettings
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StyleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Settings persistence (SPEC 92). Every field has an explicit default (SPEC 128). */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            profile = p[KEY_PROFILE].enumOrNull<ProcessingProfile>() ?: ProcessingProfile.NATURAL,
            rawMode = p[KEY_RAW_MODE].enumOrNull<RawMode>() ?: RawMode.FINAL_ONLY,
            flashMode = p[KEY_FLASH_MODE].enumOrNull<FlashMode>() ?: FlashMode.OFF,
            aspectRatio = p[KEY_ASPECT_RATIO].enumOrNull<AspectRatio>() ?: AspectRatio.RATIO_4_3,
            highestResolution = p[KEY_HIGHEST_RESOLUTION] ?: false,
            timerSeconds = p[KEY_TIMER_SECONDS] ?: 0,
            geotagging = p[KEY_GEOTAGGING] ?: false,
            gridEnabled = p[KEY_GRID] ?: false,
            temperature = p[KEY_TEMPERATURE] ?: 0f,
            style = p[KEY_STYLE].let(::decodeStyle),
        )
    }

    suspend fun setProfile(profile: ProcessingProfile) = set(KEY_PROFILE, profile.name)

    suspend fun setRawMode(rawMode: RawMode) = set(KEY_RAW_MODE, rawMode.name)

    suspend fun setFlashMode(flashMode: FlashMode) = set(KEY_FLASH_MODE, flashMode.name)

    suspend fun setAspectRatio(aspectRatio: AspectRatio) = set(KEY_ASPECT_RATIO, aspectRatio.name)
    suspend fun setHighestResolution(enabled: Boolean) = set(KEY_HIGHEST_RESOLUTION, enabled)
    suspend fun setTimerSeconds(seconds: Int) = set(KEY_TIMER_SECONDS, seconds)

    suspend fun setGeotagging(enabled: Boolean) = set(KEY_GEOTAGGING, enabled)

    suspend fun setGrid(enabled: Boolean) = set(KEY_GRID, enabled)

    suspend fun setStyle(style: StyleState) = set(KEY_STYLE, encodeStyle(style))

    suspend fun setTemperature(temperature: Float) = set(KEY_TEMPERATURE, temperature.coerceIn(-1f, 1f))

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}

private inline fun <reified T : Enum<T>> String?.enumOrNull(): T? =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } }

private val KEY_PROFILE = stringPreferencesKey("profile")
private val KEY_RAW_MODE = stringPreferencesKey("raw_mode")
private val KEY_FLASH_MODE = stringPreferencesKey("flash_mode")
private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
private val KEY_HIGHEST_RESOLUTION = booleanPreferencesKey("highest_resolution")
private val KEY_TIMER_SECONDS = intPreferencesKey("timer_seconds")
private val KEY_GEOTAGGING = booleanPreferencesKey("geotagging")
private val KEY_GRID = booleanPreferencesKey("grid")
private val KEY_STYLE = stringPreferencesKey("style")
private val KEY_TEMPERATURE = androidx.datastore.preferences.core.floatPreferencesKey("temperature")

/** Encoded as `version|toneX,toneY|colorX,colorY|paletteX,paletteY|strength`. */
internal fun encodeStyle(style: StyleState): String =
    listOf(
        style.version,
        style.tone.x, style.tone.y,
        style.color.x, style.color.y,
        style.palette.x, style.palette.y,
        style.strength,
    ).joinToString("|")

internal fun decodeStyle(raw: String?): StyleState {
    val parts = raw?.split("|") ?: return StyleState()
    return runCatching {
        StyleState(
            version = parts[0].toInt(),
            tone = StylePoint(parts[1].toFloat(), parts[2].toFloat()),
            color = StylePoint(parts[3].toFloat(), parts[4].toFloat()),
            palette = StylePoint(parts[5].toFloat(), parts[6].toFloat()),
            strength = parts[7].toFloat(),
        )
    }.getOrNull() ?: StyleState()
}
