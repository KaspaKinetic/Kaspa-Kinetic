package com.kaspakinetic.wallet.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSegmentedControl
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidSprings
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalDisableProgressiveBlur
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
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
import com.kaspakinetic.wallet.ui.locale.themeLabelFor
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Mandatory Kinetic Aero 3-Step First-Launch Welcome / Setup Screen (Settings-backed overload).
 *
 * Copy the bundled showcase images from the `Kinetic Aero/images/` folder into your app module:
 * - `Kinetic Aero/images/light mode.jpg` -> `app/src/main/res/drawable-nodpi/onboarding_example_light.jpg`
 * - `Kinetic Aero/images/dark mode.jpg`  -> `app/src/main/res/drawable-nodpi/onboarding_example_dark.jpg`
 * and pass `R.drawable.onboarding_example_light` and `R.drawable.onboarding_example_dark`.
 */
@Composable
fun KineticAeroOnboardingScreen(
    appearance: KineticAeroAppearanceSettings,
    @DrawableRes lightExampleDrawableRes: Int,
    @DrawableRes darkExampleDrawableRes: Int,
    onSelectLanguage: (String) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onDisableProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    extraStepCount: Int = 0,
    canContinueExtraStep: (Int) -> Boolean = { true },
    hideDefaultBottomNavForExtraStep: (Int) -> Boolean = { false },
    finalActionLabel: String? = null,
    onExtraStepContinue: ((Int, advance: () -> Unit) -> Unit)? = null,
    extraStepContent: (@Composable (extraIndex: Int, goBack: () -> Unit, goNext: () -> Unit) -> Unit)? = null,
) {
    KineticAeroOnboardingScreen(
        selectedLanguageTag = appearance.appLanguageTag,
        themeMode = appearance.themeMode,
        transparencyEnabled = !appearance.reduceTransparency,
        progressiveBlurEnabled = !appearance.disableProgressiveBlur,
        hapticsEnabled = appearance.hapticsEnabled,
        lightExampleDrawableRes = lightExampleDrawableRes,
        darkExampleDrawableRes = darkExampleDrawableRes,
        onSelectLanguage = onSelectLanguage,
        onSelectThemeMode = onSelectThemeMode,
        onTransparencyChanged = { enabled -> onReduceTransparencyChanged(!enabled) },
        onProgressiveBlurChanged = { enabled -> onDisableProgressiveBlurChanged(!enabled) },
        onHapticsChanged = onHapticsChanged,
        onFinish = onFinish,
        modifier = modifier,
        extraStepCount = extraStepCount,
        canContinueExtraStep = canContinueExtraStep,
        hideDefaultBottomNavForExtraStep = hideDefaultBottomNavForExtraStep,
        finalActionLabel = finalActionLabel,
        onExtraStepContinue = onExtraStepContinue,
        extraStepContent = extraStepContent,
    )
}

/**
 * Mandatory Kinetic Aero 3-Step First-Launch Welcome / Setup Screen:
 *
 * - **Step 0 (Language & Greeting)**: Smooth typewriter typing `"hello"` (adapting live to native
 *   greetings when tapping languages) + searchable All-Languages list (**System default** selected by default).
 * - **Step 1 (Appearance & Interactive Sandbox)**: Theme (`System` default), Transparency (`ON` default),
 *   Progressive blur (**`OFF` by default**), and Haptics (`ON` default), paired with a live scrolling
 *   vector/SVG preview viewport where users can tap, hold, and drag Aero Glass buttons with live
 *   finger-tracking spotlight glow, centered press shadow, elastic stretch physics, and haptics.
 * - **Step 2 ("Make the app yours")**: Showcases custom photo/GIF backgrounds & custom color palettes
 *   with the bundled Light & Dark example theme cards (`images/light mode.jpg` & `images/dark mode.jpg`)
 *   and tap-to-zoom modal preview.
 */
@Composable
fun KineticAeroOnboardingScreen(
    selectedLanguageTag: String,
    themeMode: ThemeMode,
    transparencyEnabled: Boolean = true,
    progressiveBlurEnabled: Boolean = false,
    hapticsEnabled: Boolean = true,
    @DrawableRes lightExampleDrawableRes: Int,
    @DrawableRes darkExampleDrawableRes: Int,
    onSelectLanguage: (String) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onTransparencyChanged: (Boolean) -> Unit,
    onProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    extraStepCount: Int = 0,
    canContinueExtraStep: (Int) -> Boolean = { true },
    hideDefaultBottomNavForExtraStep: (Int) -> Boolean = { false },
    finalActionLabel: String? = null,
    onExtraStepContinue: ((Int, advance: () -> Unit) -> Unit)? = null,
    extraStepContent: (@Composable (extraIndex: Int, goBack: () -> Unit, goNext: () -> Unit) -> Unit)? = null,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val totalSteps = 2 + extraStepCount.coerceAtLeast(0)
    val lastStep = totalSteps - 1
    var step by rememberSaveable { mutableIntStateOf(0) }
    if (step > lastStep) step = lastStep

    BackHandler(enabled = step > 0) {
        step = (step - 1).coerceAtLeast(0)
    }

    val ambientBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else ambientBackdrop

    Box(modifier = modifier.fillMaxSize()) {
        // Background layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(ambientBackdrop) else Modifier),
        ) {
            if (reduceTransparency) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            } else {
                AmbientLiquidBackdrop(
                    wallpaperPath = null,
                    wallpaperBrightness = 0.80f,
                )
            }
        }

        CompositionLocalProvider(LocalGlassBackdrop provides effectiveBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                // Step progress pill dots at top
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(totalSteps) { idx ->
                        val active = idx == step
                        val dotW by animateDpAsState(
                            targetValue = if (active) (if (totalSteps > 5) 22.dp else 28.dp) else 7.dp,
                            animationSpec = FluidSprings.pillSlide(),
                            label = "kinetic_onboard_dot_$idx",
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(7.dp)
                                .width(dotW)
                                .clip(CircleShape)
                                .background(
                                    if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else if (isDark) {
                                        Color.White.copy(alpha = 0.22f)
                                    } else {
                                        Color.Black.copy(alpha = 0.14f)
                                    },
                                ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val slideSpring = spring<IntOffset>(dampingRatio = 0.84f, stiffness = 400f)
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(slideSpring) { if (forward) it / 2 else -it / 2 } + fadeIn()) togetherWith
                            (slideOutHorizontally(slideSpring) { if (forward) -it / 3 else it / 3 } + fadeOut())
                    },
                    label = "kinetic_onboarding_step",
                ) { targetStep ->
                    when (targetStep) {
                        0 -> WelcomeLanguageStep(
                            selectedLanguageTag = selectedLanguageTag,
                            hapticsEnabled = hapticsEnabled,
                            onSelectLanguage = onSelectLanguage,
                        )
                        1 -> AppearanceInteractiveStep(
                            themeMode = themeMode,
                            transparencyEnabled = transparencyEnabled,
                            progressiveBlurEnabled = progressiveBlurEnabled,
                            hapticsEnabled = hapticsEnabled,
                            onSelectThemeMode = onSelectThemeMode,
                            onTransparencyChanged = onTransparencyChanged,
                            onProgressiveBlurChanged = onProgressiveBlurChanged,
                            onHapticsChanged = onHapticsChanged,
                        )
                        else -> {
                            val extraIdx = targetStep - 2
                            extraStepContent?.invoke(
                                extraIdx,
                                { step = (step - 1).coerceAtLeast(0) },
                                {
                                    if (step < lastStep) step++ else onFinish()
                                },
                            )
                        }
                    }
                }

                val isExtra = step >= 2
                val hideNav = isExtra && hideDefaultBottomNavForExtraStep(step - 2)
                if (!hideNav) {
                    Spacer(modifier = Modifier.height(12.dp))

                    val continueEnabled = !isExtra || canContinueExtraStep(step - 2)
                    // Bottom navigation controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (step > 0) {
                            FluidGlassButton(
                                onClick = { step = (step - 1).coerceAtLeast(0) },
                                backdrop = effectiveBackdrop,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.back, fontWeight = FontWeight.Medium)
                            }
                        }

                        FluidGlassButton(
                            modifier = Modifier.weight(1f),
                            accent = true,
                            enabled = continueEnabled,
                            backdrop = effectiveBackdrop,
                            onClick = {
                                val advance: () -> Unit = {
                                    if (step < lastStep) {
                                        step++
                                    } else {
                                        onFinish()
                                    }
                                }
                                if (isExtra && onExtraStepContinue != null) {
                                    onExtraStepContinue(step - 2, advance)
                                } else {
                                    advance()
                                }
                            },
                        ) {
                            Text(
                                text = if (step < lastStep) strings.continueLabel else (finalActionLabel ?: strings.getStarted),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = if (step < lastStep) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeLanguageStep(
    selectedLanguageTag: String,
    hapticsEnabled: Boolean,
    onSelectLanguage: (String) -> Unit,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val haptics = LocalHapticFeedback.current

    // Initial launch greeting always starts by typing "hello" cleanly, and adapts if user picks a language
    val targetGreeting = remember(selectedLanguageTag) {
        if (selectedLanguageTag.isBlank()) "hello" else KineticAeroLocaleManager.greetingForTag(selectedLanguageTag)
    }
    var typedText by remember { mutableStateOf("") }

    LaunchedEffect(targetGreeting) {
        while (typedText.isNotEmpty() && !targetGreeting.startsWith(typedText)) {
            typedText = typedText.dropLast(1)
            delay(32L)
        }
        if (typedText == targetGreeting) {
            typedText = ""
            delay(180L)
        } else {
            delay(200L)
        }
        for (i in 1..targetGreeting.length) {
            typedText = targetGreeting.substring(0, i)
            if (hapticsEnabled) {
                runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
            delay(95L)
        }
    }

    val cursorTransition = rememberInfiniteTransition(label = "kinetic_hello_cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor_blink",
    )

    var searchQuery by remember { mutableStateOf("") }
    val allLanguages = remember { KineticAeroLocaleManager.getAllLanguages() }
    val filteredLanguages = remember(allLanguages, searchQuery) {
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

    val listCardShape = ContinuousCornerShape(24.dp)
    val listCardBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
        isDark -> Color(0xFF141824).copy(alpha = 0.66f)
        else -> Color.White.copy(alpha = 0.72f)
    }
    val listCardBorder = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.88f)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Animated typing "hello" display header
        Row(
            modifier = Modifier.height(74.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = typedText,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                color = glassPrimaryContentColor(),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(44.dp)
                    .alpha(cursorAlpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = strings.welcomeChooseLanguage,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = glassSecondaryContentColor(),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

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

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(listCardShape)
                .background(listCardBg, listCardShape)
                .border(0.85.dp, listCardBorder, listCardShape),
            userScrollEnabled = !gestureLocked,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (searchQuery.isBlank()) {
                item(key = "__system_default__") {
                    val sysLocale = remember { KineticAeroLocaleManager.systemDefaultLocale() }
                    val sysSubtitle = remember(sysLocale) {
                        sysLocale.getDisplayName(sysLocale).replaceFirstChar { it.titlecase(sysLocale) }
                    }
                    KineticAeroLanguageRowItem(
                        title = strings.systemDefault,
                        subtitle = sysSubtitle,
                        selected = selectedLanguageTag.isBlank(),
                        onClick = { onSelectLanguage("") },
                    )
                }
            }

            items(
                items = filteredLanguages,
                key = { it.tag.lowercase() },
            ) { option ->
                KineticAeroLanguageRowItem(
                    title = option.nativeName,
                    subtitle = option.englishName,
                    selected = selectedLanguageTag.equals(option.tag, ignoreCase = true),
                    onClick = { onSelectLanguage(option.tag) },
                )
            }
        }
    }
}

/**
 * Reusable Kinetic Aero language list row shared between Welcome Step 0 and Settings -> Language.
 * Enforces `primaryContainer` + `onPrimaryContainer` contrast when `LocalReduceTransparency` is active.
 */
@Composable
fun KineticAeroLanguageRowItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val motion = rememberFluidMotionState()
    val shape = ContinuousCornerShape(16.dp)
    val rowBg = when {
        selected && reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.16f)
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDark -> Color.White.copy(alpha = 0.05f)
        else -> Color.Black.copy(alpha = 0.035f)
    }
    val rowInk = if (selected && reduceTransparency) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        glassPrimaryContentColor()
    }
    val subInk = if (selected && reduceTransparency) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    } else {
        glassSecondaryContentColor()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fluidTouchPhysics(state = motion, pressedScale = 0.97f, lockParentGestures = false)
            .clip(shape)
            .background(rowBg, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = rowInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = subInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (reduceTransparency) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
                },
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun AppearanceInteractiveStep(
    themeMode: ThemeMode,
    transparencyEnabled: Boolean,
    progressiveBlurEnabled: Boolean,
    hapticsEnabled: Boolean,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onTransparencyChanged: (Boolean) -> Unit,
    onProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = !transparencyEnabled
    val disableProgressiveBlur = !progressiveBlurEnabled
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    val dividerColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.outlineVariant
    } else if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = strings.onboardingAppearanceTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = strings.onboardingAppearanceSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = glassSecondaryContentColor(),
        )

        // LIVE INTERACTIVE SCROLLING SVG + GLASS + PROGRESSIVE BLUR + PHYSICS SANDBOX WINDOW
        InteractiveAeroPreviewSandbox(
            isDark = isDark,
            reduceTransparency = reduceTransparency,
            disableProgressiveBlur = disableProgressiveBlur,
            hapticsEnabled = hapticsEnabled,
        )

        // Friendly prompt encouraging user to press & drag the example buttons
        val hintShape = ContinuousCornerShape(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(hintShape)
                .background(
                    when {
                        reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
                        isDark -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    },
                    hintShape,
                )
                .border(
                    0.75.dp,
                    if (reduceTransparency) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    },
                    hintShape,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = if (reduceTransparency) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
                },
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = strings.tryButtonsPrompt,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (reduceTransparency) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    glassPrimaryContentColor()
                },
            )
        }

        // Theme Mode (System / Light / Dark)
        Text(
            text = strings.themeLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
        FluidGlassSegmentedControl(
            items = ThemeMode.entries,
            selectedItem = themeMode,
            onSelect = onSelectThemeMode,
            labelFor = { strings.themeLabelFor(it) },
        )

        HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

        OnboardingToggleRow(
            title = strings.transparencyOption,
            subtitle = strings.transparencyOptionDesc,
            checked = transparencyEnabled,
            onCheckedChange = onTransparencyChanged,
        )

        OnboardingToggleRow(
            title = strings.progressiveBlurOption,
            subtitle = strings.progressiveBlurOptionDesc,
            checked = progressiveBlurEnabled && transparencyEnabled,
            enabled = transparencyEnabled,
            onCheckedChange = onProgressiveBlurChanged,
        )

        OnboardingToggleRow(
            title = strings.hapticsOption,
            subtitle = strings.hapticsOptionDesc,
            checked = hapticsEnabled,
            onCheckedChange = onHapticsChanged,
        )

        Spacer(modifier = Modifier.height(36.dp))
    }
}

/**
 * Self-contained live preview viewport showcasing:
 * 1. Continuously downward-scrolling vector/SVG geometric & botanical artwork
 * 2. Top progressive blur feather ([ProgressiveGlassEdgeBand]) reacting live to the toggle
 * 3. Interactive Kinetic Aero buttons ([FluidGlassButton], [FluidGlassIconButton]) that users can
 *    tap, hold, pull, and stretch with live spotlight glow, centered press shadow, and haptics,
 *    reacting immediately when Transparency is toggled on/off.
 */
@Composable
private fun InteractiveAeroPreviewSandbox(
    isDark: Boolean,
    reduceTransparency: Boolean,
    disableProgressiveBlur: Boolean,
    hapticsEnabled: Boolean,
) {
    val strings = LocalKineticStrings.current
    val previewShape = ContinuousCornerShape(26.dp)
    val previewBackdrop = rememberLayerBackdrop()
    val effectivePreviewBackdrop: Backdrop? = if (reduceTransparency) null else previewBackdrop

    val infinite = rememberInfiniteTransition(label = "kinetic_svg_preview_scroll")
    val scrollPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "svg_scroll_phase",
    )

    var tapSparkCount by remember { mutableIntStateOf(0) }

    val borderColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        Color.White.copy(alpha = if (isDark) 0.26f else 0.88f)
    }

    CompositionLocalProvider(
        LocalReduceTransparency provides reduceTransparency,
        LocalDisableProgressiveBlur provides disableProgressiveBlur,
        LocalFluidHapticsEnabled provides hapticsEnabled,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(224.dp)
                .clip(previewShape)
                .border(1.dp, borderColor, previewShape),
        ) {
            // LAYER 1: Scrolling colorful vector/SVG scene recorded into previewBackdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!reduceTransparency) Modifier.layerBackdrop(previewBackdrop) else Modifier),
            ) {
                ScrollingVectorArtworkCanvas(
                    scrollPhase = scrollPhase,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // LAYER 2: Top Progressive Blur Band + Floating Mini Header Pill
            CompositionLocalProvider(LocalGlassBackdrop provides effectivePreviewBackdrop) {
                ProgressiveGlassEdgeBand(
                    backdrop = effectivePreviewBackdrop,
                    fromTop = true,
                    maxBlurRadius = 16.dp,
                    tintColor = if (isDark) Color(0xFF0E1320) else Color(0xFFF4F7FD),
                    tintMaxAlpha = if (isDark) 0.68f else 0.72f,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(66.dp),
                )

                val topPillShape = ContinuousCornerShape(50.dp)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                        .liquidGlassSurface(
                            shape = topPillShape,
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectivePreviewBackdrop,
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Kinetic Aero",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    if (tapSparkCount > 0) {
                        Text(
                            text = "• ×$tapSparkCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // LAYER 3: Interactive touch-physics example buttons floating over the scrolling scene
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FluidGlassButton(
                            accent = true,
                            backdrop = effectivePreviewBackdrop,
                            lockParentGestures = true,
                            onClick = { tapSparkCount++ },
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.demoPressPull, fontWeight = FontWeight.SemiBold)
                        }

                        FluidGlassIconButton(
                            backdrop = effectivePreviewBackdrop,
                            lockParentGestures = true,
                            onClick = { tapSparkCount++ },
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }

                    FluidGlassButton(
                        accent = false,
                        backdrop = effectivePreviewBackdrop,
                        lockParentGestures = true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        onClick = { tapSparkCount++ },
                    ) {
                        Text(
                            text = strings.demoAeroPill,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollingVectorArtworkCanvas(
    scrollPhase: Float,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (!w.isFinite() || !h.isFinite() || w < 2f || h < 2f) return@Canvas

        drawRect(
            brush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF0F292E))
                } else {
                    listOf(Color(0xFFE0F2FE), Color(0xFFF3E8FF), Color(0xFFDCFCE7))
                },
                start = Offset.Zero,
                end = Offset(w, h),
            ),
        )

        val tileHeight = h * 1.35f
        val yShift = scrollPhase * tileHeight

        val palette = if (isDark) {
            listOf(
                Color(0xFF38BDF8),
                Color(0xFFA855F7),
                Color(0xFF34D399),
                Color(0xFFFB7185),
                Color(0xFFFBBF24),
            )
        } else {
            listOf(
                Color(0xFF0284C7),
                Color(0xFF7C3AED),
                Color(0xFF059669),
                Color(0xFFE11D48),
                Color(0xFFD97706),
            )
        }

        for (tile in -1..1) {
            val baseY = tile * tileHeight + yShift

            // 1. Flowing vector sine ribbon
            val wavePath = Path().apply {
                moveTo(0f, baseY + tileHeight * 0.18f)
                cubicTo(
                    w * 0.33f, baseY + tileHeight * 0.04f,
                    w * 0.66f, baseY + tileHeight * 0.32f,
                    w, baseY + tileHeight * 0.16f,
                )
            }
            drawPath(
                path = wavePath,
                color = palette[0].copy(alpha = 0.55f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )

            // 2. Colorful SVG-style floating cards & badges scrolling behind glass
            val cardH = 44.dp.toPx()
            val cardR = CornerRadius(14.dp.toPx(), 14.dp.toPx())

            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(palette[1], palette[0])),
                topLeft = Offset(w * 0.08f, baseY + tileHeight * 0.10f),
                size = Size(w * 0.48f, cardH),
                cornerRadius = cardR,
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(palette[2], palette[4])),
                topLeft = Offset(w * 0.42f, baseY + tileHeight * 0.38f),
                size = Size(w * 0.50f, cardH),
                cornerRadius = cardR,
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(palette[3], palette[1])),
                topLeft = Offset(w * 0.12f, baseY + tileHeight * 0.68f),
                size = Size(w * 0.54f, cardH),
                cornerRadius = cardR,
            )

            // 3. Crisp vector botanical / starburst rings
            val orbCenter1 = Offset(w * 0.80f, baseY + tileHeight * 0.18f)
            drawCircle(color = palette[3].copy(alpha = 0.78f), radius = 22.dp.toPx(), center = orbCenter1)
            drawCircle(
                color = Color.White.copy(alpha = 0.70f),
                radius = 30.dp.toPx(),
                center = orbCenter1,
                style = Stroke(width = 2.dp.toPx()),
            )

            val starCenter = Offset(w * 0.22f, baseY + tileHeight * 0.48f)
            val outerR = 24.dp.toPx()
            for (k in 0 until 6) {
                val angle = (k * PI / 3.0).toFloat()
                drawLine(
                    color = palette[4].copy(alpha = 0.85f),
                    start = Offset(
                        starCenter.x - cos(angle) * outerR,
                        starCenter.y - sin(angle) * outerR,
                    ),
                    end = Offset(
                        starCenter.x + cos(angle) * outerR,
                        starCenter.y + sin(angle) * outerR,
                    ),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val orbCenter2 = Offset(w * 0.78f, baseY + tileHeight * 0.82f)
            drawCircle(color = palette[0].copy(alpha = 0.82f), radius = 26.dp.toPx(), center = orbCenter2)
        }
    }
}

@Composable
private fun OnboardingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
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
private fun MakeItYoursShowcaseStep(
    @DrawableRes lightExampleDrawableRes: Int,
    @DrawableRes darkExampleDrawableRes: Int,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    var zoomedRes by remember { mutableStateOf<Pair<Int, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = strings.makeItYoursTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
            textAlign = TextAlign.Center,
        )

        Text(
            text = strings.makeItYoursBody,
            style = MaterialTheme.typography.bodyLarge,
            color = glassSecondaryContentColor(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExampleThemeCard(
                drawableRes = lightExampleDrawableRes,
                caption = strings.lightExampleLabel,
                isDark = isDark,
                reduceTransparency = reduceTransparency,
                modifier = Modifier.weight(1f),
                onClick = { zoomedRes = lightExampleDrawableRes to strings.lightExampleLabel },
            )
            ExampleThemeCard(
                drawableRes = darkExampleDrawableRes,
                caption = strings.darkExampleLabel,
                isDark = isDark,
                reduceTransparency = reduceTransparency,
                modifier = Modifier.weight(1f),
                onClick = { zoomedRes = darkExampleDrawableRes to strings.darkExampleLabel },
            )
        }
    }

    zoomedRes?.let { (resId, label) ->
        FluidGlassDialog(
            onDismissRequest = { zoomedRes = null },
            windowOverlay = true,
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
            },
            actions = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FluidGlassButton(useBackdropCapture = false, accent = true, onClick = { zoomedRes = null }) {
                        Text(strings.close, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
        ) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousCornerShape(20.dp)),
            )
        }
    }
}

@Composable
private fun ExampleThemeCard(
    @DrawableRes drawableRes: Int,
    caption: String,
    isDark: Boolean,
    reduceTransparency: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberFluidMotionState()
    val cardShape = ContinuousCornerShape(22.dp)
    val borderColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        Color.White.copy(alpha = if (isDark) 0.25f else 0.85f)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.56f)
                .fluidTouchPhysics(state = motion, pressedScale = 0.965f, lockParentGestures = false)
                .clip(cardShape)
                .border(1.dp, borderColor, cardShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = caption,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
    }
}
