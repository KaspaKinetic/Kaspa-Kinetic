package com.kaspakinetic.wallet.data.preferences

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kineticAeroDataStore by preferencesDataStore(name = "kinetic_aero_preferences")

object KineticAeroDefaults {
    const val MIN_WALLPAPER_BRIGHTNESS = 0.10f
    const val MAX_WALLPAPER_BRIGHTNESS = 1.00f
    const val DEFAULT_WALLPAPER_BRIGHTNESS = 0.75f

    const val MIN_FONT_SCALE = 0.85f
    const val MAX_FONT_SCALE = 1.40f
    const val DEFAULT_FONT_SCALE = 1.00f

    const val DEFAULT_CUSTOM_ACCENT_ARGB = 0xFF2D6A4FL

    /** Transparency is OFF by default on fresh installs (`reduceTransparency = true`) for instant crisp readability & zero-lag baseline; users can turn it ON anytime in Welcome Step 2 or Settings. */
    const val DEFAULT_REDUCE_TRANSPARENCY = true

    /**
     * Progressive blur at top/bottom bars is **OFF by default** (`disableProgressiveBlur = true`).
     * Users can turn it ON during Welcome Step 2 or in Settings -> Appearance & Accessibility.
     */
    const val DEFAULT_DISABLE_PROGRESSIVE_BLUR = true

    /** Tactile spring haptics are ON by default (`hapticsEnabled = true`). */
    const val DEFAULT_HAPTICS_ENABLED = true

    /** Empty string (`""`) follows the Android OS primary language by default. */
    const val DEFAULT_APP_LANGUAGE_TAG = ""
}

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

enum class ColorPalette(val label: String) {
    Warm("Warm"),
    Blue("Ocean"),
    Green("Forest"),
    Purple("Violet"),
    Neutral("Mono"),
    Dynamic("Dynamic"),
    Custom("Custom"),
}

/**
 * Minimum mandatory appearance, localization, onboarding & accessibility settings required by
 * every Kinetic Aero application.
 */
@Immutable
data class KineticAeroAppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorPalette: ColorPalette = ColorPalette.Green,
    val customAccentArgb: Long = KineticAeroDefaults.DEFAULT_CUSTOM_ACCENT_ARGB,
    val wallpaperPath: String? = null,
    val wallpaperBrightness: Float = KineticAeroDefaults.DEFAULT_WALLPAPER_BRIGHTNESS,
    val fontScale: Float = KineticAeroDefaults.DEFAULT_FONT_SCALE,
    val reduceTransparency: Boolean = KineticAeroDefaults.DEFAULT_REDUCE_TRANSPARENCY,
    val disableProgressiveBlur: Boolean = KineticAeroDefaults.DEFAULT_DISABLE_PROGRESSIVE_BLUR,
    val hapticsEnabled: Boolean = KineticAeroDefaults.DEFAULT_HAPTICS_ENABLED,
    val appLanguageTag: String = KineticAeroDefaults.DEFAULT_APP_LANGUAGE_TAG,
    val hasCompletedOnboarding: Boolean = false,
)

/**
 * Standalone DataStore repository managing all mandatory Kinetic Aero appearance, language, and
 * first-launch onboarding preferences.
 */
class KineticAeroPreferencesRepository(
    private val context: Context,
) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val colorPalette = stringPreferencesKey("color_palette")
        val customAccentArgb = longPreferencesKey("custom_accent_argb")
        val wallpaperPath = stringPreferencesKey("wallpaper_path")
        val wallpaperBrightness = floatPreferencesKey("wallpaper_brightness")
        val fontScale = floatPreferencesKey("font_scale")
        val reduceTransparency = booleanPreferencesKey("reduce_transparency")
        val disableProgressiveBlur = booleanPreferencesKey("disable_progressive_blur")
        val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
        val appLanguageTag = stringPreferencesKey("app_language_tag")
        val hasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
    }

    val settings: Flow<KineticAeroAppearanceSettings> = context.kineticAeroDataStore.data.map { prefs ->
        KineticAeroAppearanceSettings(
            themeMode = prefs[Keys.themeMode]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.System,
            colorPalette = prefs[Keys.colorPalette]?.let { name ->
                ColorPalette.entries.firstOrNull { it.name == name }
            } ?: ColorPalette.Green,
            customAccentArgb = prefs[Keys.customAccentArgb]
                ?: KineticAeroDefaults.DEFAULT_CUSTOM_ACCENT_ARGB,
            wallpaperPath = prefs[Keys.wallpaperPath]?.takeIf { it.isNotBlank() },
            wallpaperBrightness = (prefs[Keys.wallpaperBrightness]
                ?: KineticAeroDefaults.DEFAULT_WALLPAPER_BRIGHTNESS)
                .coerceIn(
                    KineticAeroDefaults.MIN_WALLPAPER_BRIGHTNESS,
                    KineticAeroDefaults.MAX_WALLPAPER_BRIGHTNESS,
                ),
            fontScale = (prefs[Keys.fontScale] ?: KineticAeroDefaults.DEFAULT_FONT_SCALE)
                .coerceIn(
                    KineticAeroDefaults.MIN_FONT_SCALE,
                    KineticAeroDefaults.MAX_FONT_SCALE,
                ),
            reduceTransparency = prefs[Keys.reduceTransparency]
                ?: KineticAeroDefaults.DEFAULT_REDUCE_TRANSPARENCY,
            disableProgressiveBlur = prefs[Keys.disableProgressiveBlur]
                ?: KineticAeroDefaults.DEFAULT_DISABLE_PROGRESSIVE_BLUR,
            hapticsEnabled = prefs[Keys.hapticsEnabled]
                ?: KineticAeroDefaults.DEFAULT_HAPTICS_ENABLED,
            appLanguageTag = prefs[Keys.appLanguageTag]?.trim().orEmpty(),
            hasCompletedOnboarding = prefs[Keys.hasCompletedOnboarding] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.kineticAeroDataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setColorPalette(palette: ColorPalette) {
        context.kineticAeroDataStore.edit { it[Keys.colorPalette] = palette.name }
    }

    suspend fun setCustomAccentArgb(argb: Long) {
        context.kineticAeroDataStore.edit {
            it[Keys.customAccentArgb] = argb
            it[Keys.colorPalette] = ColorPalette.Custom.name
        }
    }

    suspend fun setWallpaperPath(path: String?) {
        context.kineticAeroDataStore.edit { prefs ->
            if (path.isNullOrBlank()) {
                prefs.remove(Keys.wallpaperPath)
            } else {
                prefs[Keys.wallpaperPath] = path
            }
        }
    }

    suspend fun setWallpaperBrightness(brightness: Float) {
        context.kineticAeroDataStore.edit {
            it[Keys.wallpaperBrightness] = brightness.coerceIn(
                KineticAeroDefaults.MIN_WALLPAPER_BRIGHTNESS,
                KineticAeroDefaults.MAX_WALLPAPER_BRIGHTNESS,
            )
        }
    }

    suspend fun setFontScale(scale: Float) {
        context.kineticAeroDataStore.edit {
            it[Keys.fontScale] = scale.coerceIn(
                KineticAeroDefaults.MIN_FONT_SCALE,
                KineticAeroDefaults.MAX_FONT_SCALE,
            )
        }
    }

    suspend fun setReduceTransparency(enabled: Boolean) {
        context.kineticAeroDataStore.edit { it[Keys.reduceTransparency] = enabled }
    }

    suspend fun setDisableProgressiveBlur(disabled: Boolean) {
        context.kineticAeroDataStore.edit { it[Keys.disableProgressiveBlur] = disabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.kineticAeroDataStore.edit { it[Keys.hapticsEnabled] = enabled }
    }

    suspend fun setAppLanguageTag(languageTag: String) {
        context.kineticAeroDataStore.edit { prefs ->
            val trimmed = languageTag.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.appLanguageTag)
            } else {
                prefs[Keys.appLanguageTag] = trimmed
            }
        }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        context.kineticAeroDataStore.edit { it[Keys.hasCompletedOnboarding] = completed }
    }
}
