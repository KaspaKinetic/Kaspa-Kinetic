package com.kaspakinetic.wallet.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaspakinetic.wallet.data.preferences.ColorPalette
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import com.kaspakinetic.wallet.data.repository.WallpaperStore
import com.kaspakinetic.wallet.ui.components.FluidColorWheelStudio
import com.kaspakinetic.wallet.ui.components.LocalIsTestnetNetwork
import com.kaspakinetic.wallet.ui.components.SmallStatusTag
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.CollapsibleOptionGridSelector
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSegmentedControl
import com.kaspakinetic.wallet.ui.glass.FluidGlassSlider
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidSprings
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalOverlayBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.ProgressiveGlassEdgeBand
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.layerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.kaspakinetic.wallet.ui.glass.fluidTouchPhysics
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import com.kaspakinetic.wallet.ui.glass.rememberFluidMotionState
import com.kaspakinetic.wallet.ui.locale.KineticAeroLocaleManager
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.paletteLabelFor
import com.kaspakinetic.wallet.ui.locale.themeLabelFor
import com.kaspakinetic.wallet.ui.onboarding.KineticAeroLanguageRowItem
import com.kaspakinetic.wallet.ui.theme.argbLongToColor
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Canonical Kinetic Aero Settings screen.
 *
 * Architecture & Readability rules enforced:
 * 1. Live full-screen blurred wallpaper/GIF background (`Modifier.blur(28.dp)`) + soft atmosphere scrim
 *    instead of stacking dozens of `FluidGlassCard` RenderEffect FBOs over a `LazyColumn` (eliminates
 *    Settings scroll stutter and keeps animated GIF reflections alive at 120Hz).
 * 2. When `LocalReduceTransparency` is `true`, switches Sub-layer A1 to a 100% solid
 *    `MaterialTheme.colorScheme.background` canvas so bare section headers and subtitles NEVER
 *    collide with an unblurred custom photo/GIF wallpaper.
 * 3. Floating Aero Glass top bar (`GlassPreset.FloatingBar`) + optional [ProgressiveGlassEdgeBand]
 *    (OFF by default; returns early with zero draw when disabled or when transparency is disabled).
 * 4. Every mandatory Kinetic Aero preference included out-of-the-box:
 *    - Searchable App Language picker (**System default** + all Android languages)
 *    - Disable transparency (`reduceTransparency`)
 *    - Disable progressive blur (`disableProgressiveBlur`, **checked / OFF by default**)
 *    - Haptic feedback (`hapticsEnabled`)
 *    - Theme mode (`System` / `Light` / `Dark`)
 *    - Color palette presets (`2x2` collapsible grid) + Custom HSV wheel with Darkness/Brightness
 *    - Background Photo / Animated GIF picker + live brightness preview & slider (`0.10..1.00`)
 *    - App-wide font size slider (`0.85x..1.40x`) + live multi-style preview
 */
@Composable
fun KineticAeroSettingsScreen(
    appearance: KineticAeroAppearanceSettings,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onColorPaletteChanged: (ColorPalette) -> Unit,
    onCustomAccentColorChanged: (Long) -> Unit,
    onWallpaperPathChanged: (String?) -> Unit,
    onWallpaperBrightnessChanged: (Float) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onDisableProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onAppLanguageChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = LocalKineticStrings.current.settingsTitle,
    onNavigateBack: (() -> Unit)? = null,
    onReplayOnboarding: (() -> Unit)? = null,
    headerActions: (@Composable () -> Unit)? = null,
    extraSections: (LazyListScope.() -> Unit)? = null,
    footerContent: (LazyListScope.() -> Unit)? = null,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val strings = LocalKineticStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallpaperStore = remember(context) { WallpaperStore(context.applicationContext) }
    var isUpdatingWallpaper by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUpdatingWallpaper = true
            try {
                wallpaperStore.importFromUri(uri).onSuccess { savedPath ->
                    onWallpaperPathChanged(savedPath)
                }
            } finally {
                isUpdatingWallpaper = false
            }
        }
    }

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 100.dp.roundToPx() }) }

    val tier2ViewportBackdrop = rememberLayerBackdrop()
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val effectiveTier2Backdrop: Backdrop? = if (reduceTransparency) null else tier2ViewportBackdrop

    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // LAYER A: Recorded into tier2ViewportBackdrop for floating header & modal dialogs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(tier2ViewportBackdrop) else Modifier),
        ) {
            // Sub-layer A1: Solid theme background when transparency is disabled (prevents unblurred
            // custom wallpapers from destroying text contrast), or live 28.dp blurred wallpaper/GIF
            if (reduceTransparency) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AmbientLiquidBackdrop(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Modifier.blur(radius = 28.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                                } else {
                                    Modifier
                                },
                            ),
                        wallpaperPath = appearance.wallpaperPath,
                        wallpaperBrightness = appearance.wallpaperBrightness,
                    )
                    val scrimAlpha = when {
                        hasCustomWallpaper && isDark -> 0.66f
                        hasCustomWallpaper && !isDark -> 0.76f
                        isDark -> 0.56f
                        else -> 0.64f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (isDark) {
                                    Color(0xFF0A0D15).copy(alpha = scrimAlpha)
                                } else {
                                    Color(0xFFF3F6FC).copy(alpha = scrimAlpha)
                                },
                            ),
                    )
                }
            }

            // Sub-layer A2: Native flat scrollable settings list (zero nested scroll-time glass FBOs)
            CompositionLocalProvider(LocalOverlayBackdrop provides effectiveTier2Backdrop) {
                val topPadDp = with(density) { topChromeHeightPx.toDp() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding(),
                    userScrollEnabled = !gestureLocked,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topPadDp + 10.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Mandatory App-Wide Language Section
                    item(key = "kinetic_aero_language") {
                        SettingsGroupSection(title = strings.languageSectionTitle) {
                            KineticAeroLanguageSelectorRow(
                                selectedLanguageTag = appearance.appLanguageTag,
                                onOpenLanguagePicker = { showLanguageDialog = true },
                            )
                        }
                    }

                    // Optional app-specific sections
                    if (extraSections != null) {
                        extraSections()
                    }

                    // Mandatory Kinetic Aero Appearance, Background, Typography & Accessibility
                    item(key = "kinetic_aero_appearance") {
                        KineticAeroAppearanceSettingsSection(
                            appearance = appearance,
                            isUpdatingWallpaper = isUpdatingWallpaper,
                            onThemeModeChanged = onThemeModeChanged,
                            onColorPaletteChanged = onColorPaletteChanged,
                            onCustomAccentColorChanged = onCustomAccentColorChanged,
                            onPickWallpaper = {
                                mediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onClearWallpaper = {
                                onWallpaperPathChanged(null)
                                scope.launch { wallpaperStore.clearAll() }
                            },
                            onWallpaperBrightnessChanged = onWallpaperBrightnessChanged,
                            onFontScaleChanged = onFontScaleChanged,
                            onReduceTransparencyChanged = onReduceTransparencyChanged,
                            onDisableProgressiveBlurChanged = onDisableProgressiveBlurChanged,
                            onHapticsChanged = onHapticsChanged,
                            onReplayOnboarding = onReplayOnboarding,
                        )
                    }

                    if (footerContent != null) {
                        footerContent()
                    }
                }
            }
        }

        // LAYER B: Floating Aero Glass Header Bar + Status-Bar Legibility Veil + Optional Progressive Top Blur + Dialogs
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveTier2Backdrop,
            LocalOverlayBackdrop provides effectiveTier2Backdrop,
        ) {
            val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            // Subtle top veil over the status bar inset so scrolling section headers never clash with the clock
            val statusVeilColor = if (isDark) Color(0xFF0A0D15) else Color(0xFFF3F6FC)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusTopDp + 18.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                statusVeilColor.copy(alpha = if (reduceTransparency) 1.0f else 0.92f),
                                statusVeilColor.copy(alpha = if (reduceTransparency) 0.95f else 0.60f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            ProgressiveGlassEdgeBand(
                backdrop = effectiveTier2Backdrop,
                fromTop = true,
                maxBlurRadius = 16.dp,
                tintMaxAlpha = if (isDark) 0.56f else 0.62f,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusTopDp + 86.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topChromeHeightPx = it.height }
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onNavigateBack != null) {
                    FluidGlassIconButton(
                        onClick = onNavigateBack,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveTier2Backdrop,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                val headerShape = ContinuousCornerShape(50.dp)
                val isTestnetNet = LocalIsTestnetNetwork.current
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .liquidGlassSurface(
                            shape = headerShape,
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectiveTier2Backdrop,
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1_200,
                                repeatDelayMillis = 1_500,
                                velocity = 36.dp,
                            ),
                    )
                    if (isTestnetNet) {
                        SmallStatusTag(text = "TN10", color = MaterialTheme.colorScheme.tertiary)
                    }
                }

                if (headerActions != null) {
                    headerActions()
                }
            }

            if (showLanguageDialog) {
                KineticAeroLanguagePickerDialog(
                    selectedLanguageTag = appearance.appLanguageTag,
                    onSelectLanguage = onAppLanguageChanged,
                    onDismiss = { showLanguageDialog = false },
                )
            }

            if (overlayContent != null) {
                overlayContent()
            }
        }
    }
}

/**
 * Reusable Kinetic Aero Appearance, Accessibility, Background & Font Size section that satisfies
 * every minimum design-system requirement.
 */
@Composable
fun KineticAeroAppearanceSettingsSection(
    appearance: KineticAeroAppearanceSettings,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onColorPaletteChanged: (ColorPalette) -> Unit,
    onCustomAccentColorChanged: (Long) -> Unit,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onWallpaperBrightnessChanged: (Float) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onDisableProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    isUpdatingWallpaper: Boolean = false,
    onReplayOnboarding: (() -> Unit)? = null,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val dividerColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.outlineVariant
    } else if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    SettingsGroupSection(title = strings.appearanceSectionTitle) {
        // 1. Mandatory Accessibility / Performance Transparency & Progressive Blur Toggles
        KineticSettingSwitchRow(
            title = strings.disableTransparency,
            subtitle = strings.disableTransparencyDesc,
            checked = appearance.reduceTransparency,
            onCheckedChange = onReduceTransparencyChanged,
        )

        KineticSettingSwitchRow(
            title = strings.disableProgressiveBlur,
            subtitle = strings.disableProgressiveBlurDesc,
            checked = appearance.disableProgressiveBlur || appearance.reduceTransparency,
            enabled = !appearance.reduceTransparency,
            onCheckedChange = onDisableProgressiveBlurChanged,
        )

        KineticSettingSwitchRow(
            title = strings.hapticFeedback,
            subtitle = strings.hapticFeedbackDesc,
            checked = appearance.hapticsEnabled,
            onCheckedChange = onHapticsChanged,
        )

        HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

        // 2. Mandatory Theme Mode Selector (System / Light / Dark)
        Text(
            text = strings.themeLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
        FluidGlassSegmentedControl(
            items = ThemeMode.entries,
            selectedItem = appearance.themeMode,
            onSelect = onThemeModeChanged,
            labelFor = { strings.themeLabelFor(it) },
        )

        // 3. Mandatory Color Palette Selector (2-column collapsible grid + Custom HSV Wheel)
        val rainbowRingBrush = remember {
            Brush.sweepGradient(
                colors = listOf(
                    Color(0xFFFF3B30),
                    Color(0xFFFFCC00),
                    Color(0xFF34C759),
                    Color(0xFF00C7BE),
                    Color(0xFF0A84FF),
                    Color(0xFFAF52DE),
                    Color(0xFFFF3B30),
                ),
            )
        }
        val availablePalettes = remember {
            ColorPalette.entries.filter {
                it != ColorPalette.Dynamic || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }
        }
        CollapsibleOptionGridSelector(
            title = strings.colorPaletteLabel,
            summaryText = strings.paletteLabelFor(appearance.colorPalette),
            summaryLeading = {
                PaletteSwatchDot(
                    palette = appearance.colorPalette,
                    customAccentArgb = appearance.customAccentArgb,
                    rainbowRingBrush = rainbowRingBrush,
                    sizeDp = 12,
                )
            },
        ) {
            availablePalettes.chunked(2).forEach { rowPalettes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPalettes.forEach { palette ->
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            selected = appearance.colorPalette == palette,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { onColorPaletteChanged(palette) },
                        ) {
                            PaletteSwatchDot(
                                palette = palette,
                                customAccentArgb = appearance.customAccentArgb,
                                rainbowRingBrush = rainbowRingBrush,
                                sizeDp = 14,
                            )
                            Text(
                                text = strings.paletteLabelFor(palette),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (rowPalettes.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = appearance.colorPalette == ColorPalette.Custom,
            enter = expandVertically(FluidSprings.sheetExpand()) + fadeIn(),
            exit = shrinkVertically(FluidSprings.menuClose()) + fadeOut(),
        ) {
            FluidColorWheelStudio(
                selectedColorArgb = appearance.customAccentArgb,
                onColorChanged = onCustomAccentColorChanged,
            )
        }

        HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

        // 4. Mandatory Background Photo / Animated GIF + Brightness Control
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = strings.wallpaperLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = glassPrimaryContentColor(),
            )
            Text(
                text = if (!appearance.wallpaperPath.isNullOrBlank() && File(appearance.wallpaperPath).exists()) {
                    strings.customWallpaperActive
                } else {
                    strings.defaultAuroraActive
                },
                style = MaterialTheme.typography.bodySmall,
                color = glassSecondaryContentColor(),
            )

            val previewShape = ContinuousCornerShape(22.dp)
            val previewSurface = when {
                reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
                isDark -> Color(0xFF161A25).copy(alpha = 0.76f)
                else -> Color.White.copy(alpha = 0.80f)
            }
            val previewBorder = if (reduceTransparency) {
                MaterialTheme.colorScheme.outlineVariant
            } else {
                Color.White.copy(alpha = if (isDark) 0.26f else 0.86f)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(previewShape)
                    .border(0.9.dp, previewBorder, previewShape),
            ) {
                AmbientLiquidBackdrop(
                    modifier = Modifier.fillMaxSize(),
                    wallpaperPath = appearance.wallpaperPath,
                    wallpaperBrightness = appearance.wallpaperBrightness,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(ContinuousCornerShape(18.dp))
                        .background(previewSurface)
                        .border(0.8.dp, previewBorder, ContinuousCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (reduceTransparency) {
                            strings.solidContrastPreview
                        } else {
                            strings.wallpaperBrightnessPreview
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = glassPrimaryContentColor(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FluidGlassButton(
                    modifier = Modifier.weight(1f),
                    accent = true,
                    enabled = !isUpdatingWallpaper,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = onPickWallpaper,
                ) {
                    if (isUpdatingWallpaper) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (appearance.wallpaperPath == null) strings.uploadImageOrGif else strings.changeWallpaper,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (appearance.wallpaperPath != null) {
                    FluidGlassButton(
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = onClearWallpaper,
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.reset)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.wallpaperBrightness,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = glassPrimaryContentColor(),
                )
                Text(
                    text = "${(appearance.wallpaperBrightness * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentInk,
                )
            }
            FluidGlassSlider(
                value = appearance.wallpaperBrightness,
                onValueChange = onWallpaperBrightnessChanged,
                valueRange = KineticAeroDefaults.MIN_WALLPAPER_BRIGHTNESS..KineticAeroDefaults.MAX_WALLPAPER_BRIGHTNESS,
            )
        }

        HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

        // 5. Mandatory App-Wide Font Size Changer + Live Scaled Preview
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fontPercent = (appearance.fontScale * 100f).roundToInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.appFontSize,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (abs(appearance.fontScale - KineticAeroDefaults.DEFAULT_FONT_SCALE) > 0.02f) {
                        Text(
                            text = strings.reset,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = glassSecondaryContentColor(),
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onFontScaleChanged(KineticAeroDefaults.DEFAULT_FONT_SCALE)
                            },
                        )
                    }
                    Text(
                        text = "$fontPercent%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentInk,
                    )
                }
            }

            FluidGlassSlider(
                value = appearance.fontScale,
                onValueChange = onFontScaleChanged,
                valueRange = KineticAeroDefaults.MIN_FONT_SCALE..KineticAeroDefaults.MAX_FONT_SCALE,
            )

            val fontPreviewShape = ContinuousCornerShape(18.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(fontPreviewShape)
                    .background(
                        when {
                            reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
                            isDark -> Color.White.copy(alpha = 0.06f)
                            else -> Color.Black.copy(alpha = 0.04f)
                        },
                    )
                    .border(
                        0.75.dp,
                        if (reduceTransparency) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            Color.White.copy(alpha = if (isDark) 0.15f else 0.70f)
                        },
                        fontPreviewShape,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val formattedPreviewTitle = runCatching {
                    String.format(strings.typographyPreviewTitleFormat, fontPercent)
                }.getOrDefault("Kinetic Aero Typography ($fontPercent%)")
                Text(
                    text = formattedPreviewTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
                Text(
                    text = strings.typographyPreviewBody,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = glassSecondaryContentColor(),
                )
            }
        }

        if (onReplayOnboarding != null) {
            HorizontalDivider(thickness = 0.6.dp, color = dividerColor)
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = onReplayOnboarding,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.replayWelcomeFlow, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Summary card opening the searchable All-Languages dialog from Settings.
 */
@Composable
fun KineticAeroLanguageSelectorRow(
    selectedLanguageTag: String,
    onOpenLanguagePicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val motion = rememberFluidMotionState()
    val shape = ContinuousCornerShape(18.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.56f)
    }
    val border = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.85f)
    }
    val summary = remember(selectedLanguageTag, strings.systemDefault) {
        KineticAeroLocaleManager.displaySummaryForSelection(selectedLanguageTag, strings.systemDefault)
    }
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .fluidTouchPhysics(state = motion, pressedScale = 0.97f, lockParentGestures = false)
            .clip(shape)
            .background(bg, shape)
            .border(0.85.dp, border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenLanguagePicker,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = accentInk,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = strings.appLanguageLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = glassPrimaryContentColor(),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = glassSecondaryContentColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = strings.choose,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accentInk,
        )
    }
}

/**
 * Searchable modal All-Languages picker dialog rendered inside the window backdrop tree.
 */
@Composable
fun KineticAeroLanguagePickerDialog(
    selectedLanguageTag: String,
    onSelectLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalKineticStrings.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    var searchQuery by remember { mutableStateOf("") }
    val allLanguages = remember { KineticAeroLocaleManager.getAllLanguages() }
    val filtered = remember(allLanguages, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            allLanguages
        } else {
            allLanguages.filter {
                it.nativeName.contains(q, ignoreCase = true) ||
                    it.englishName.contains(q, ignoreCase = true) ||
                    it.tag.contains(q, ignoreCase = true)
            }
        }
    }

    FluidGlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.appLanguageLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
            )
        },
        actions = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FluidGlassButton(useBackdropCapture = false, accent = true, onClick = onDismiss) {
                    Text(strings.close, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        FluidGlassTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = strings.searchLanguages,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = glassSecondaryContentColor(),
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingContent = {
                if (searchQuery.isNotEmpty()) {
                    FluidGlassIconButton(
                        onClick = { searchQuery = "" },
                        size = 26.dp,
                        useBackdropCapture = false,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = strings.close,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            userScrollEnabled = !gestureLocked,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (searchQuery.isBlank()) {
                item(key = "__system__") {
                    val sys = remember { KineticAeroLocaleManager.systemDefaultLocale() }
                    val sysNative = remember(sys) {
                        sys.getDisplayName(sys).replaceFirstChar { it.titlecase(sys) }
                    }
                    KineticAeroLanguageRowItem(
                        title = strings.systemDefault,
                        subtitle = sysNative,
                        selected = selectedLanguageTag.isBlank(),
                        onClick = {
                            onSelectLanguage("")
                            onDismiss()
                        },
                    )
                }
            }
            items(filtered, key = { it.tag.lowercase() }) { lang ->
                KineticAeroLanguageRowItem(
                    title = lang.nativeName,
                    subtitle = lang.englishName,
                    selected = selectedLanguageTag.equals(lang.tag, ignoreCase = true),
                    onClick = {
                        onSelectLanguage(lang.tag)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * Clean, flat native phone Settings group header + vertical item column + divider.
 * Never wrap Settings groups in `FluidGlassCard` or extra backdrop blurs.
 */
@Composable
fun SettingsGroupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val titleColor = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val dividerColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.Black.copy(alpha = 0.08f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = titleColor,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(thickness = 0.75.dp, color = dividerColor)
    }
}

@Composable
fun KineticSettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.52f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = glassPrimaryContentColor(),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = glassSecondaryContentColor(),
            )
        }
        FluidGlassSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PaletteSwatchDot(
    palette: ColorPalette,
    customAccentArgb: Long,
    rainbowRingBrush: Brush,
    sizeDp: Int,
) {
    if (palette == ColorPalette.Custom) {
        Box(
            modifier = Modifier
                .size((sizeDp + 2).dp)
                .clip(CircleShape)
                .background(rainbowRingBrush)
                .padding(2.5.dp)
                .clip(CircleShape)
                .background(argbLongToColor(customAccentArgb)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(palettePreviewSwatch(palette, customAccentArgb))
                .border(0.75.dp, Color.White.copy(alpha = 0.8f), CircleShape),
        )
    }
}

@Composable
private fun palettePreviewSwatch(palette: ColorPalette, customAccentArgb: Long): Color = when (palette) {
    ColorPalette.Warm -> Color(0xFF76513C)
    ColorPalette.Blue -> Color(0xFF255F8E)
    ColorPalette.Green -> Color(0xFF2D6A4F)
    ColorPalette.Purple -> Color(0xFF63518E)
    ColorPalette.Neutral -> Color(0xFF444444)
    ColorPalette.Dynamic -> MaterialTheme.colorScheme.primary
    ColorPalette.Custom -> argbLongToColor(customAccentArgb)
}
