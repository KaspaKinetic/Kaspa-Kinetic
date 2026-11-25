package com.kaspakinetic.wallet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

// 1. Warm (signature espresso / cream palette)
val WarmLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF76513C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF2C160A),
    secondary = Color(0xFF705B4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE0D1),
    onSecondaryContainer = Color(0xFF271810),
    tertiary = Color(0xFF646032),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBE4AA),
    onTertiaryContainer = Color(0xFF1F1C00),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF211A16),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF211A16),
    surfaceVariant = Color(0xFFF3DED4),
    onSurfaceVariant = Color(0xFF51443D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2EC),
    surfaceContainer = Color(0xFFF7ECE5),
    surfaceContainerHigh = Color(0xFFF1E6DE),
    surfaceContainerHighest = Color(0xFFEBE0D8),
    outline = Color(0xFF83746C),
    outlineVariant = Color(0xFFD7C2B8),
)

val WarmDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE7BEA7),
    onPrimary = Color(0xFF44291A),
    primaryContainer = Color(0xFF5B3D2B),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFD9C2B5),
    onSecondary = Color(0xFF3E2D24),
    secondaryContainer = Color(0xFF554339),
    onSecondaryContainer = Color(0xFFFBE0D1),
    tertiary = Color(0xFFCEC890),
    onTertiary = Color(0xFF343108),
    tertiaryContainer = Color(0xFF4B481D),
    onTertiaryContainer = Color(0xFFEBE4AA),
    background = Color(0xFF17120F),
    onBackground = Color(0xFFEDE0DA),
    surface = Color(0xFF17120F),
    onSurface = Color(0xFFEDE0DA),
    surfaceVariant = Color(0xFF453933),
    onSurfaceVariant = Color(0xFFD7C2B8),
    surfaceContainerLowest = Color(0xFF110D0A),
    surfaceContainerLow = Color(0xFF1F1916),
    surfaceContainer = Color(0xFF241D1A),
    surfaceContainerHigh = Color(0xFF2F2824),
    surfaceContainerHighest = Color(0xFF3A322E),
    outline = Color(0xFFA08D84),
    outlineVariant = Color(0xFF51443D),
)

// 2. Ocean Blue
val BlueLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF255F8E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFF8F9FD),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FD),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Color(0xFFECEEF3),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
)

val BlueDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA2C9FE),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00487E),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF373B42),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
)

// 3. Forest Green
val GreenLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2D6A4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F0CD),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4F6356),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E8D7),
    onSecondaryContainer = Color(0xFF0C1F15),
    background = Color(0xFFF6FBF6),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404942),
    surfaceContainerLow = Color(0xFFF0F5F0),
    surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4EAE4),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1),
)

val GreenDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9BD4B2),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF125138),
    onPrimaryContainer = Color(0xFFB7F0CD),
    secondary = Color(0xFFB5CCBC),
    onSecondary = Color(0xFF213529),
    secondaryContainer = Color(0xFF374B3F),
    onSecondaryContainer = Color(0xFFD1E8D7),
    background = Color(0xFF101511),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF101511),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF343D36),
    onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceContainerLow = Color(0xFF181D19),
    surfaceContainer = Color(0xFF1C211D),
    surfaceContainerHigh = Color(0xFF262B27),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404942),
)

// 4. Violet
val PurpleLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF63518E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF1F0C46),
    secondary = Color(0xFF625B70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF48454E),
    surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF0ECF4),
    surfaceContainerHigh = Color(0xFFEBE6EE),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4D0),
)

val PurpleDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFCEB9FE),
    onPrimary = Color(0xFF34235C),
    primaryContainer = Color(0xFF4B3A74),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE7DEF8),
    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141318),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF3B3842),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2B2930),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF48454E),
)

// 5. Monochrome Neutral
val NeutralLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A2A2A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E4),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF1B1B1B),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF181818),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF181818),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainer = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFE6E6E6),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFCCCCCC),
)

val NeutralDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE2E2E2),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF343434),
    onPrimaryContainer = Color(0xFFF0F0F0),
    secondary = Color(0xFFC2C2C2),
    onSecondary = Color(0xFF2B2B2B),
    secondaryContainer = Color(0xFF3D3D3D),
    onSecondaryContainer = Color(0xFFE8E8E8),
    background = Color(0xFF111111),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF272727),
    outline = Color(0xFF8B8B8B),
    outlineVariant = Color(0xFF424242),
)

data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

fun colorToHsv(color: Color): HsvColor {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC

    val hue = if (delta <= 1e-5f) {
        0f
    } else {
        val raw = when (maxC) {
            r -> ((g - b) / delta) % 6f
            g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        } * 60f
        if (raw < 0f) raw + 360f else raw
    }
    val saturation = if (maxC <= 1e-5f) 0f else (delta / maxC).coerceIn(0f, 1f)
    return HsvColor(
        hue = hue.coerceIn(0f, 360f),
        saturation = saturation,
        value = maxC.coerceIn(0f, 1f),
    )
}

fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

fun contrastInkFor(background: Color, hue: Float = 0f, saturation: Float = 0f): Color {
    return if (background.luminance() > 0.36f) {
        hsvToColor(hue, (saturation * 0.45f).coerceIn(0f, 0.35f), 0.08f)
    } else {
        Color.White
    }
}

/**
 * Keeps accent section titles, chevrons, and slider readouts legible on dark or light translucent
 * canvases even when the user chooses an extreme dark or very bright custom accent color.
 */
fun readableAccentInk(accent: Color, isDarkBackground: Boolean): Color {
    val hsv = colorToHsv(accent)
    return if (isDarkBackground) {
        if (accent.luminance() >= 0.30f) return accent
        val vivid = hsvToColor(hsv.hue, hsv.saturation.coerceAtMost(0.85f), hsv.value.coerceAtLeast(0.88f))
        if (vivid.luminance() >= 0.30f) return vivid
        var t = 0.25f
        while (t <= 1f) {
            val candidate = lerp(vivid, Color.White, t)
            if (candidate.luminance() >= 0.30f) return candidate
            t += 0.15f
        }
        Color.White
    } else {
        if (accent.luminance() <= 0.30f) return accent
        val deepInk = hsvToColor(hsv.hue, hsv.saturation.coerceAtMost(0.85f), 0.18f)
        var t = 0.25f
        while (t <= 1f) {
            val candidate = lerp(accent, deepInk, t)
            if (candidate.luminance() <= 0.30f) return candidate
            t += 0.15f
        }
        deepInk
    }
}

fun argbLongToColor(argb: Long): Color {
    val opaque = (0xFF000000L or (argb and 0x00FFFFFFL)).toInt()
    return Color(opaque)
}

fun colorToArgbLong(color: Color): Long {
    val r = (color.red.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (color.green.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (color.blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

/**
 * Generates an accessible Material 3 [ColorScheme] from any arbitrary HSV wheel seed color
 * (including extreme dark shades, bright pastels, neons, and neutrals).
 */
fun generateCustomColorScheme(seedColor: Color, darkTheme: Boolean): ColorScheme {
    val hsv = colorToHsv(seedColor)
    val h = hsv.hue
    val s = hsv.saturation
    val v = hsv.value

    val primary = seedColor.copy(alpha = 1f)
    val onPrimary = contrastInkFor(primary, h, s)

    val tertiaryHue = (h + 38f) % 360f
    val neutralTintSat = (s * 0.14f).coerceIn(0f, 0.09f)

    return if (darkTheme) {
        val baseBg = lerp(Color(0xFF101218), hsvToColor(h, neutralTintSat, 0.14f), 0.45f)
        val primaryContainerTint = hsvToColor(h, (s * 0.85f).coerceIn(0f, 1f), v.coerceIn(0.32f, 0.84f))
        val primaryContainer = lerp(Color(0xFF171A22), primaryContainerTint, 0.38f)
        val onPrimaryContainer = lerp(Color.White, hsvToColor(h, (s * 0.30f), 0.96f), 0.45f)
        val secondary = hsvToColor(h, (s * 0.46f).coerceIn(0f, 0.65f), 0.82f)
        val onSecondary = contrastInkFor(secondary, h, s)
        val secondaryContainer = lerp(Color(0xFF1C2029), hsvToColor(h, s * 0.55f, 0.50f), 0.35f)
        val onSecondaryContainer = lerp(Color.White, hsvToColor(h, s * 0.25f, 0.94f), 0.40f)
        val tertiary = hsvToColor(tertiaryHue, (s * 0.62f).coerceIn(0f, 0.80f), 0.84f)
        val onTertiary = contrastInkFor(tertiary, tertiaryHue, s)
        val tertiaryContainer = lerp(Color(0xFF1C2028), hsvToColor(tertiaryHue, s * 0.60f, 0.52f), 0.36f)
        val onTertiaryContainer = lerp(Color.White, hsvToColor(tertiaryHue, s * 0.25f, 0.95f), 0.40f)

        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = baseBg,
            onBackground = Color(0xFFE7E9F0),
            surface = baseBg,
            onSurface = Color(0xFFE7E9F0),
            surfaceVariant = lerp(Color(0xFF282C35), hsvToColor(h, neutralTintSat * 1.6f, 0.28f), 0.45f),
            onSurfaceVariant = Color(0xFFC1C6D2),
            surfaceContainerLowest = lerp(Color(0xFF0A0C10), hsvToColor(h, neutralTintSat, 0.10f), 0.40f),
            surfaceContainerLow = lerp(Color(0xFF161820), hsvToColor(h, neutralTintSat, 0.18f), 0.40f),
            surfaceContainer = lerp(Color(0xFF1C1F28), hsvToColor(h, neutralTintSat, 0.22f), 0.40f),
            surfaceContainerHigh = lerp(Color(0xFF242832), hsvToColor(h, neutralTintSat, 0.27f), 0.40f),
            surfaceContainerHighest = lerp(Color(0xFF2E323D), hsvToColor(h, neutralTintSat, 0.33f), 0.40f),
            outline = Color(0xFF8C919E),
            outlineVariant = Color(0xFF414652),
        )
    } else {
        val baseBg = lerp(Color(0xFFF9FAFD), hsvToColor(h, neutralTintSat, 0.98f), 0.45f)
        val primaryContainerTint = hsvToColor(h, (s * 0.72f).coerceIn(0f, 1f), v.coerceIn(0.46f, 0.96f))
        val primaryContainer = lerp(Color(0xFFFFFFFF), primaryContainerTint, if (v < 0.25f) 0.14f else 0.22f)
        val onPrimaryContainer = hsvToColor(h, (s * 0.55f).coerceIn(0f, 0.70f), 0.13f)
        val secondary = hsvToColor(h, (s * 0.42f).coerceIn(0f, 0.58f), 0.40f)
        val onSecondary = contrastInkFor(secondary, h, s)
        val secondaryContainer = lerp(Color(0xFFFFFFFF), hsvToColor(h, s * 0.48f, 0.90f), 0.18f)
        val onSecondaryContainer = hsvToColor(h, (s * 0.45f).coerceIn(0f, 0.65f), 0.14f)
        val tertiary = hsvToColor(tertiaryHue, (s * 0.58f).coerceIn(0f, 0.72f), 0.42f)
        val onTertiary = contrastInkFor(tertiary, tertiaryHue, s)
        val tertiaryContainer = lerp(Color(0xFFFFFFFF), hsvToColor(tertiaryHue, s * 0.55f, 0.92f), 0.20f)
        val onTertiaryContainer = hsvToColor(tertiaryHue, (s * 0.45f).coerceIn(0f, 0.65f), 0.14f)

        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = baseBg,
            onBackground = Color(0xFF16181E),
            surface = baseBg,
            onSurface = Color(0xFF16181E),
            surfaceVariant = lerp(Color(0xFFE3E6EE), hsvToColor(h, neutralTintSat * 1.5f, 0.92f), 0.45f),
            onSurfaceVariant = Color(0xFF454954),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = lerp(Color(0xFFF3F5FA), hsvToColor(h, neutralTintSat, 0.96f), 0.45f),
            surfaceContainer = lerp(Color(0xFFECEFF6), hsvToColor(h, neutralTintSat, 0.93f), 0.45f),
            surfaceContainerHigh = lerp(Color(0xFFE5E9F1), hsvToColor(h, neutralTintSat, 0.90f), 0.45f),
            surfaceContainerHighest = lerp(Color(0xFFDEE2EB), hsvToColor(h, neutralTintSat, 0.87f), 0.45f),
            outline = Color(0xFF747882),
            outlineVariant = Color(0xFFC5C9D4),
        )
    }
}
