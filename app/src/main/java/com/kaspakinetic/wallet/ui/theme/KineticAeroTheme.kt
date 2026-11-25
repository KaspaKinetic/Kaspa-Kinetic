package com.kaspakinetic.wallet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.kaspakinetic.wallet.data.preferences.ColorPalette
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import com.kaspakinetic.wallet.ui.glass.FluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalDisableProgressiveBlur
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.locale.LocalActiveLocale
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.locale.ProvideKineticAeroLocale
import com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings

val KineticAeroTypography = Typography()

/**
 * Exposes the active user-chosen app-wide font multiplier (`0.85f..1.40f`).
 */
val LocalKineticFontScale = compositionLocalOf { KineticAeroDefaults.DEFAULT_FONT_SCALE }

/**
 * Root theme wrapper for every Kinetic Aero application.
 *
 * Provides:
 * - Resolved Light / Dark / System & Palette / Material You Dynamic / Custom HSV ColorScheme
 * - App-wide multi-language resolution ([ProvideKineticAeroLocale]: `LocalKineticStrings`,
 *   `LocalActiveLocale`, and automatic RTL/LTR `LocalLayoutDirection` without wrapping `LocalContext`)
 * - App-wide live font scaling (`LocalDensity.fontScale * appearance.fontScale`) without scaling `.dp`
 *   component geometry, icon bounds, or spring physics thresholds
 * - Root [LocalGlassDarkTheme], [LocalReduceTransparency], [LocalDisableProgressiveBlur],
 *   [LocalFluidHapticsEnabled], and [LocalFluidGestureCoordinator]
 */
@Composable
fun KineticAeroTheme(
    appearance: KineticAeroAppearanceSettings,
    typography: Typography = KineticAeroTypography,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemDark
    }

    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    val customSeedColor = remember(appearance.customAccentArgb) {
        argbLongToColor(appearance.customAccentArgb)
    }

    val colorScheme = remember(
        darkTheme,
        appearance.colorPalette,
        appearance.customAccentArgb,
        context,
    ) {
        when (appearance.colorPalette) {
            ColorPalette.Dynamic -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    if (darkTheme) GreenDarkScheme else GreenLightScheme
                }
            }
            ColorPalette.Warm -> if (darkTheme) WarmDarkScheme else WarmLightScheme
            ColorPalette.Blue -> if (darkTheme) BlueDarkScheme else BlueLightScheme
            ColorPalette.Green -> if (darkTheme) GreenDarkScheme else GreenLightScheme
            ColorPalette.Purple -> if (darkTheme) PurpleDarkScheme else PurpleLightScheme
            ColorPalette.Neutral -> if (darkTheme) NeutralDarkScheme else NeutralLightScheme
            ColorPalette.Custom -> generateCustomColorScheme(
                seedColor = customSeedColor,
                darkTheme = darkTheme,
            )
        }
    }

    val baseDensity = LocalDensity.current
    val coercedFontScale = appearance.fontScale.coerceIn(
        KineticAeroDefaults.MIN_FONT_SCALE,
        KineticAeroDefaults.MAX_FONT_SCALE,
    )
    val scaledDensity = remember(baseDensity.density, baseDensity.fontScale, coercedFontScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * coercedFontScale,
        )
    }
    val gestureCoordinator = remember { FluidGestureCoordinator() }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        ProvideKineticAeroLocale(languageTag = appearance.appLanguageTag) {
            val activeLocale = LocalActiveLocale.current
            val walletStrings = remember(activeLocale) { resolveKaspaWalletStrings(activeLocale) }
            CompositionLocalProvider(
                LocalWalletStrings provides walletStrings,
                LocalDensity provides scaledDensity,
                LocalKineticFontScale provides coercedFontScale,
                LocalGlassDarkTheme provides darkTheme,
                LocalReduceTransparency provides appearance.reduceTransparency,
                LocalDisableProgressiveBlur provides appearance.disableProgressiveBlur,
                LocalFluidHapticsEnabled provides appearance.hapticsEnabled,
                LocalFluidGestureCoordinator provides gestureCoordinator,
                content = content,
            )
        }
    }
}
