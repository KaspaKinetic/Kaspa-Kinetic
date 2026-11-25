package com.kaspakinetic.wallet.ui.locale

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.kaspakinetic.wallet.data.preferences.ColorPalette
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import java.util.Locale

/**
 * App-agnostic localized UI strings for all mandatory Kinetic Aero surfaces:
 * - First-launch 3-Step Welcome / Setup flow (`KineticAeroOnboardingScreen`)
 * - Interactive glass, progressive blur & physics preview sandbox
 * - Searchable All-Languages picker
 * - Mandatory Settings screen (`KineticAeroSettingsScreen`) & Custom HSV Color Studio
 *
 * Naming rule enforced: zero user-facing occurrences of competitor trademark names.
 * Host apps can also define their own domain `AppStrings` resolved via [LocalActiveLocale]
 * alongside [LocalKineticStrings].
 */
@Immutable
data class KineticAeroStrings(
    // Common Actions
    val continueLabel: String = "Continue",
    val getStarted: String = "Get started",
    val back: String = "Back",
    val next: String = "Next",
    val close: String = "Close",
    val cancel: String = "Cancel",
    val save: String = "Save",
    val delete: String = "Delete",
    val edit: String = "Edit",
    val copy: String = "Copy",
    val retry: String = "Retry",
    val undo: String = "Undo",
    val choose: String = "Choose",
    val hide: String = "Hide",
    val reset: String = "Reset",
    val resetToDefault: String = "Reset to default",
    val gotIt: String = "Got it",

    // First-Launch 3-Step Welcome / Setup Flow
    val welcomeChooseLanguage: String = "Choose your language",
    val systemDefault: String = "System default",
    val searchLanguages: String = "Search all languages...",
    val onboardingAppearanceTitle: String = "Appearance & Feel",
    val onboardingAppearanceSubtitle: String = "Tune your look and test the interactive surface effects live below.",
    val tryButtonsPrompt: String = "Try it: tap, hold, and drag the buttons above to feel the glow, stretch physics, and haptics!",
    val demoPressPull: String = "Hold & Drag Me",
    val demoAeroPill: String = "Aero Glass",
    val transparencyOption: String = "Transparency",
    val transparencyOptionDesc: String = "Real-time optical glass blur and refraction.",
    val progressiveBlurOption: String = "Progressive blur",
    val progressiveBlurOptionDesc: String = "Smooth top & bottom edge blur feathering.",
    val hapticsOption: String = "Haptics",
    val hapticsOptionDesc: String = "Tactile spring feedback on touches and sliders.",
    val makeItYoursTitle: String = "Make the app yours",
    val makeItYoursBody: String = "You can build any theme with a photo/GIF background and custom color palettes.",
    val lightExampleLabel: String = "Light theme",
    val darkExampleLabel: String = "Dark theme",

    // Mandatory Kinetic Aero Settings & Color Wheel Studio
    val settingsTitle: String = "Settings",
    val languageSectionTitle: String = "Language",
    val appLanguageLabel: String = "App language",
    val appearanceSectionTitle: String = "Appearance & Accessibility",
    val disableTransparency: String = "Disable transparency",
    val disableTransparencyDesc: String = "Use solid high-contrast surfaces instead of glass blur for easier reading & lower GPU load. Keeps all elastic spring physics, spotlight glow, and haptics.",
    val disableProgressiveBlur: String = "Disable progressive blur",
    val disableProgressiveBlurDesc: String = "Turn off the soft gradient blur feather behind the top and bottom bars.",
    val hapticFeedback: String = "Haptic feedback",
    val hapticFeedbackDesc: String = "Subtle tactile spring feedback on button presses, toggles, and sliders.",
    val themeLabel: String = "Theme",
    val themeSystem: String = "System",
    val themeLight: String = "Light",
    val themeDark: String = "Dark",
    val colorPaletteLabel: String = "Color palette",
    val paletteWarm: String = "Warm",
    val paletteOcean: String = "Ocean",
    val paletteForest: String = "Forest",
    val paletteViolet: String = "Violet",
    val paletteMono: String = "Mono",
    val paletteDynamic: String = "Dynamic",
    val paletteCustom: String = "Custom",
    val colorStudioSample: String = "Accent Aa",
    val colorStudioDarkness: String = "Darkness",
    val colorStudioBrightness: String = "Brightness",
    val colorStudioQuickShades: String = "Quick shades",
    val wallpaperLabel: String = "Background wallpaper (Photo or GIF)",
    val customWallpaperActive: String = "Custom background active — live-refracted across Kinetic Aero glass",
    val defaultAuroraActive: String = "Using ambient Kinetic Aero aurora gradient",
    val wallpaperBrightnessPreview: String = "Background brightness preview",
    val solidContrastPreview: String = "Solid high-contrast preview over background",
    val uploadImageOrGif: String = "Upload image or GIF",
    val changeWallpaper: String = "Change background",
    val wallpaperBrightness: String = "Background brightness",
    val appFontSize: String = "App font size",
    val typographyPreviewTitleFormat: String = "Kinetic Aero Typography (%d%%)",
    val typographyPreviewBody: String = "Headlines, body copy, lists, buttons, and menus scale uniformly across every screen while optical borders stay crisp.",
    val replayWelcomeFlow: String = "Replay Welcome Setup",
)

val LocalKineticStrings = compositionLocalOf { KineticAeroStrings() }

fun KineticAeroStrings.themeLabelFor(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> themeSystem
    ThemeMode.Light -> themeLight
    ThemeMode.Dark -> themeDark
}

fun KineticAeroStrings.paletteLabelFor(palette: ColorPalette): String = when (palette) {
    ColorPalette.Warm -> paletteWarm
    ColorPalette.Blue -> paletteOcean
    ColorPalette.Green -> paletteForest
    ColorPalette.Purple -> paletteViolet
    ColorPalette.Neutral -> paletteMono
    ColorPalette.Dynamic -> paletteDynamic
    ColorPalette.Custom -> paletteCustom
}

fun resolveKineticAeroStrings(locale: Locale): KineticAeroStrings {
    val lang = when (val raw = locale.language.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "no", "nn" -> "nb"
        "tl" -> "fil"
        else -> raw
    }
    val isTraditionalZh = lang == "zh" && (
        locale.country.equals("TW", ignoreCase = true) ||
            locale.country.equals("HK", ignoreCase = true) ||
            locale.country.equals("MO", ignoreCase = true) ||
            locale.script.equals("Hant", ignoreCase = true)
        )
    val key = if (isTraditionalZh) "zh-tw" else if (lang == "zh") "zh-cn" else lang
    return KineticAeroTranslationsWest[key]
        ?: KineticAeroTranslationsEast[key]
        ?: KineticAeroStrings()
}
