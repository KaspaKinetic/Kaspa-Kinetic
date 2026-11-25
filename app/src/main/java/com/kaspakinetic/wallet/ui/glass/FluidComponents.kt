package com.kaspakinetic.wallet.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Primary Liquid Glass capsule button with Living Flow parallel spring squash/stretch,
 * finger-tracking radial spotlight glow, subtle press drop shadow, and real-time refraction boost.
 */
@Composable
fun FluidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    destructive: Boolean = false,
    shape: CornerBasedShape = ContinuousCornerShape(50.dp),
    preset: GlassPreset = if (accent) GlassPreset.AccentPill else GlassPreset.Control,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = true,
    lockParentGestures: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val motion = rememberFluidMotionState()
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val effectivePreset = when {
        !enabled && preset == GlassPreset.AccentPill -> GlassPreset.Control
        enabled && destructive && preset == GlassPreset.Control -> GlassPreset.AccentPill
        else -> preset
    }
    val tint = when {
        !enabled -> when {
            reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
            isDark -> Color(0xFF1F2633)
            else -> Color(0xFFE5EAF0)
        }
        // High-contrast deep crimson in both Light & Dark modes (WCAG AA+ with Color.White text,
        // avoiding pale pastel M3 dark `colorScheme.error` when transparency is disabled!)
        destructive -> if (isDark) Color(0xFFB91C1C) else Color(0xFFC62828)
        accent -> MaterialTheme.colorScheme.primary
        else -> null
    }
    val contentColor = when {
        !enabled -> when {
            reduceTransparency -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            isDark -> Color(0xFFB8C2D1).copy(alpha = 0.78f)
            else -> Color(0xFF475060).copy(alpha = 0.78f)
        }
        destructive -> Color.White
        accent -> MaterialTheme.colorScheme.onPrimary
        else -> glassPrimaryContentColor()
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .fluidTouchPhysics(
                enabled = enabled,
                state = motion,
                pressedScale = 0.94f,
                lockParentGestures = lockParentGestures,
            )
            .liquidGlassSurface(
                shape = shape,
                preset = effectivePreset,
                backdrop = backdrop,
                surfaceTint = tint,
                motionState = motion,
                pressShadowEnabled = true,
                useBackdropCapture = useBackdropCapture,
            )
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Circular / squircle Liquid Glass icon button with finger-tracking spotlight and press shadow.
 */
@Composable
fun FluidGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 42.dp,
    shape: CornerBasedShape = CircleShape,
    preset: GlassPreset = GlassPreset.FloatingBar,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    surfaceTint: Color? = null,
    contentColor: Color? = null,
    useBackdropCapture: Boolean = true,
    lockParentGestures: Boolean = true,
    content: @Composable () -> Unit,
) {
    val motion = rememberFluidMotionState()
    val reduceTransparency = LocalReduceTransparency.current
    val resolvedTint = when {
        surfaceTint != null -> surfaceTint
        selected -> if (reduceTransparency) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        }
        else -> null
    }
    val resolvedContent = when {
        contentColor != null -> contentColor
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> glassPrimaryContentColor()
    }

    Box(
        modifier = modifier
            .size(size)
            .fluidTouchPhysics(
                enabled = enabled,
                state = motion,
                pressedScale = 0.90f,
                releaseStretchAmount = 0.06f,
                lockParentGestures = lockParentGestures,
            )
            .liquidGlassSurface(
                shape = shape,
                preset = if (selected && surfaceTint == null) GlassPreset.AccentPill else preset,
                backdrop = backdrop,
                surfaceTint = resolvedTint,
                motionState = motion,
                pressShadowEnabled = true,
                useBackdropCapture = useBackdropCapture,
            )
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContent) {
            content()
        }
    }
}

/**
 * Interactive Liquid Glass pill chip with Living Flow spring bounce, finger-tracking spotlight,
 * and subtle press drop shadow.
 */
@Composable
fun FluidGlassChip(
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = true,
    lockParentGestures: Boolean = true,
    shape: CornerBasedShape = ContinuousCornerShape(50.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val motion = rememberFluidMotionState()
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val targetTint = when {
        selected -> MaterialTheme.colorScheme.primary
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDark -> Color(0xFF1D2230)
        else -> Color(0xFFFBFCFE)
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f),
        label = "chip_tint",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        glassPrimaryContentColor()
    }

    Row(
        modifier = modifier
            .fluidTouchPhysics(
                enabled = enabled,
                state = motion,
                pressedScale = 0.93f,
                lockParentGestures = lockParentGestures,
            )
            .liquidGlassSurface(
                shape = shape,
                preset = if (selected) GlassPreset.AccentPill else GlassPreset.Control,
                backdrop = backdrop,
                surfaceTint = tint,
                motionState = motion,
                pressShadowEnabled = true,
                useBackdropCapture = useBackdropCapture,
            )
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Non-linear, interruptible, finger-draggable Living Flow toggle switch.
 * Supports both quick taps (smoothly reversing mid-flight if interrupted) and hold-and-scrub
 * swiping left/right across the track without lifting, with velocity-driven thumb elongation,
 * subtle rounded press drop shadow, midpoint haptic detent, and finger-tracking glow.
 */
@Composable
fun FluidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureCoordinator = LocalFluidGestureCoordinator.current
    val viewConfig = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()

    val latestChecked by rememberUpdatedState(checked)
    val latestOnCheckedChange by rememberUpdatedState(onCheckedChange)

    val thumbProgress = remember { Animatable(if (checked) 1f else 0f) }
    val thumbMotion = rememberFluidMotionState()
    val velocityStretch = remember { Animatable(0f) }
    val trackScale = remember { Animatable(1f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var activeRelease by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeRelease?.invoke()
            activeRelease = null
        }
    }

    LaunchedEffect(checked, isScrubbing) {
        if (!isScrubbing) {
            thumbProgress.animateTo(if (checked) 1f else 0f, FluidSprings.pillSlide())
        }
    }

    val trackWidth = 54.dp
    val trackHeight = 32.dp
    val thumbSize = 26.dp
    val pad = 3.dp
    val density = LocalDensity.current
    val travelPx = with(density) { (trackWidth - thumbSize - pad * 2).toPx() }
    val padPx = with(density) { pad.toPx() }
    val thumbPx = with(density) { thumbSize.toPx() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val offTrackColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color.Black.copy(alpha = 0.12f)
    }
    val offBorderColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.outline
        isDark -> Color.White.copy(alpha = 0.20f)
        else -> Color.White.copy(alpha = 0.80f)
    }
    val onBorderColor = Color.White.copy(alpha = if (reduceTransparency) 0.55f else 0.45f)

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .graphicsLayer {
                val sx = trackScale.value
                scaleX = sx
                scaleY = sx
                alpha = if (enabled) 1f else 0.45f
            }
            .fluidPressShadow(
                shape = CircleShape,
                motion = thumbMotion,
                maxAlpha = if (isDark) 0.36f else 0.16f,
                blurRadius = 9.dp,
                offsetY = 0.dp,
            )
            .clip(CircleShape)
            .drawBehind {
                val frac = thumbProgress.value.coerceIn(0f, 1f)
                val trackColor = lerp(offTrackColor, primaryColor, frac)
                val borderColor = lerp(offBorderColor, onBorderColor, frac)
                drawRect(color = trackColor, size = size)
                val strokePx = 0.9.dp.toPx()
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(width = strokePx),
                )
            }
            .pointerInput(enabled, hapticsEnabled, travelPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    down.consume()
                    activeRelease?.invoke()
                    activeRelease = null
                    var releaseLock: (() -> Unit)? = null

                    isScrubbing = true
                    thumbMotion.touchFractionX = ((down.position.x - (padPx + thumbProgress.value.coerceIn(0f, 1f) * travelPx)) / thumbPx)
                        .coerceIn(-0.15f, 1.15f)
                    thumbMotion.touchFractionY = (down.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)

                    scope.launch { thumbMotion.pressProgress.animateTo(1f, spring(dampingRatio = 0.90f, stiffness = 920f)) }
                    scope.launch { trackScale.animateTo(0.95f, FluidSprings.pressScale()) }
                    scope.launch { velocityStretch.animateTo(0.22f, spring(dampingRatio = 0.85f, stiffness = 900f)) }

                    var dragged = false
                    var lastSide = thumbProgress.value >= 0.5f
                    var targetProgress = thumbProgress.value
                    var targetStretch = 0.22f
                    var smoothVelX = 0f
                    var lastPos = down.position
                    var lastTimeMs = down.uptimeMillis

                    val followJob: Job = scope.launch {
                        var prevNanos = withFrameNanos { it }
                        while (isActive) {
                            val nowNanos = withFrameNanos { it }
                            val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                            prevNanos = nowNanos
                            smoothVelX *= exp(-14f * dt)
                            if (dragged) {
                                val pAlpha = 1f - exp(-34f * dt)
                                val sAlpha = 1f - exp(-22f * dt)
                                thumbProgress.snapTo(lerp(thumbProgress.value, targetProgress, pAlpha))
                                velocityStretch.snapTo(lerp(velocityStretch.value, targetStretch, sAlpha))
                            }
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) change.consume()

                            val dtSec = ((change.uptimeMillis - lastTimeMs).coerceAtLeast(4L)) / 1000f
                            val instVelX = ((change.position.x - lastPos.x) / dtSec).coerceIn(-2600f, 2600f)
                            smoothVelX = lerp(smoothVelX, instVelX, 0.30f)
                            lastPos = change.position
                            lastTimeMs = change.uptimeMillis

                            val dxFromDown = change.position.x - down.position.x
                            if (!dragged && abs(dxFromDown) > viewConfig.touchSlop * 0.55f) {
                                dragged = true
                                releaseLock = gestureCoordinator?.acquire()
                                activeRelease = releaseLock
                            }
                            if (dragged) {
                                val rawFrac = (change.position.x - padPx - thumbPx * 0.5f) / travelPx.coerceAtLeast(1f)
                                targetProgress = FluidMotionPhysics.elasticClampNormalized(rawFrac, overscrollLimit = 0.07f)

                                val currentSide = targetProgress >= 0.5f
                                if (currentSide != lastSide) {
                                    lastSide = currentSide
                                    if (hapticsEnabled) {
                                        runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                    }
                                }

                                targetStretch = (0.20f + (abs(smoothVelX) / 1400f).coerceIn(0f, 0.24f))
                            }
                            val activeThumbX = padPx + thumbProgress.value.coerceIn(0f, 1f) * travelPx
                            thumbMotion.touchFractionX = ((change.position.x - activeThumbX) / thumbPx).coerceIn(-0.15f, 1.15f)
                            thumbMotion.touchFractionY = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)
                        }
                    } finally {
                        followJob.cancel()
                        isScrubbing = false
                        releaseLock?.invoke()
                        if (activeRelease === releaseLock) {
                            activeRelease = null
                        }
                    }

                    val releaseVelNorm = (smoothVelX / travelPx.coerceAtLeast(1f)).coerceIn(-10f, 10f)

                    val finalChecked = if (dragged) {
                        val projected = thumbProgress.value + releaseVelNorm * 0.08f
                        projected >= 0.5f
                    } else {
                        if (hapticsEnabled) {
                            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        }
                        !latestChecked
                    }
                    if (finalChecked != latestChecked) {
                        latestOnCheckedChange(finalChecked)
                    }

                    val target = if (finalChecked) 1f else 0f
                    scope.launch {
                        thumbProgress.animateTo(
                            targetValue = target,
                            animationSpec = FluidSprings.pillSlide(),
                            initialVelocity = releaseVelNorm,
                        )
                    }
                    scope.launch { thumbMotion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                    scope.launch { trackScale.animateTo(1f, FluidSprings.pressScale()) }
                    scope.launch { velocityStretch.animateTo(0f, FluidSprings.pressStretch()) }
                }
            }
            .padding(pad),
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumbBrush = remember {
            Brush.verticalGradient(
                colors = listOf(Color.White, Color(0xFFEEF2F8)),
            )
        }
        Box(
            modifier = Modifier
                .size(thumbSize)
                .graphicsLayer {
                    val p = thumbProgress.value
                    translationX = travelPx * p
                    val speedStretch = (abs(thumbProgress.velocity) * 0.045f).coerceIn(0f, 0.22f)
                    val totalStretch = 1f + maxOf(velocityStretch.value, speedStretch)
                    scaleX = totalStretch
                    scaleY = (1f - ((totalStretch - 1f) * 0.16f)).coerceIn(0.86f, 1.06f)
                    transformOrigin = TransformOrigin(p.coerceIn(0.08f, 0.92f), 0.5f)
                }
                .fluidPressShadow(
                    shape = CircleShape,
                    motion = thumbMotion,
                    maxAlpha = 0.22f,
                    blurRadius = 7.dp,
                    offsetY = 0.dp,
                )
                .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(brush = thumbBrush, size = size)
                    drawFluidTouchSpotlight(
                        pressProgress = thumbMotion.pressProgress.value,
                        touchFractionX = thumbMotion.touchFractionX,
                        touchFractionY = thumbMotion.touchFractionY,
                        shape = CircleShape,
                        isDark = isDark,
                        isAccentSurface = false,
                    )
                }
                .border(0.75.dp, Color.White.copy(alpha = 0.92f), CircleShape),
        )
    }
}

/**
 * Elastic, non-linear, interruptible Living Flow slider pill with velocity-reactive bead
 * magnification, subtle rounded press drop shadow, haptic detents, and finger-tracking glow.
 */
@Composable
fun FluidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val gestureCoordinator = LocalFluidGestureCoordinator.current
    val viewConfig = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val clampedFraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latestRange by rememberUpdatedState(valueRange)

    var isDragging by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    var activeRelease by remember { mutableStateOf<(() -> Unit)?>(null) }

    val thumbFracAnim = remember { Animatable(clampedFraction) }
    val thumbMotion = rememberFluidMotionState()
    val velocityStretch = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        onDispose {
            activeRelease?.invoke()
            activeRelease = null
        }
    }

    LaunchedEffect(clampedFraction, isDragging) {
        if (!isDragging) {
            thumbFracAnim.animateTo(clampedFraction, FluidSprings.pillSlide())
        }
    }

    val thumbWidth = 28.dp
    val thumbHeight = 20.dp
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { thumbWidth.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.34f)
        else -> Color.Black.copy(alpha = 0.24f)
    }
    val trackBorderColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.42f)
        else -> Color.Black.copy(alpha = 0.18f)
    }
    val thumbShape = ContinuousCornerShape(50.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, hapticsEnabled, thumbWidthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    down.consume()
                    activeRelease?.invoke()
                    activeRelease = null
                    var releaseLock: (() -> Unit)? = null

                    isDragging = true
                    val usable = (trackWidthPx - thumbWidthPx).coerceAtLeast(1f)
                    val initialTarget = ((down.position.x - thumbWidthPx / 2f) / usable).coerceIn(0f, 1f)
                    scope.launch {
                        thumbFracAnim.animateTo(initialTarget, FluidSprings.pillSlide())
                    }
                    latestOnValueChange(latestRange.start + initialTarget * (latestRange.endInclusive - latestRange.start))

                    if (hapticsEnabled) {
                        runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    scope.launch { thumbMotion.pressProgress.animateTo(1f, spring(dampingRatio = 0.90f, stiffness = 920f)) }

                    var dragged = false
                    var lastHapticBucket = (initialTarget * 16f).roundToInt()
                    var targetFrac = initialTarget
                    var targetStretch = 0f
                    var smoothVelX = 0f
                    var lastPos = down.position
                    var lastTimeMs = down.uptimeMillis

                    val followJob: Job = scope.launch {
                        var prevNanos = withFrameNanos { it }
                        while (isActive) {
                            val nowNanos = withFrameNanos { it }
                            val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                            prevNanos = nowNanos
                            smoothVelX *= exp(-14f * dt)
                            if (dragged) {
                                val fAlpha = 1f - exp(-36f * dt)
                                val sAlpha = 1f - exp(-22f * dt)
                                thumbFracAnim.snapTo(lerp(thumbFracAnim.value, targetFrac, fAlpha))
                                velocityStretch.snapTo(lerp(velocityStretch.value, targetStretch, sAlpha))
                            }
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) change.consume()

                            val dtSec = ((change.uptimeMillis - lastTimeMs).coerceAtLeast(4L)) / 1000f
                            val instVelX = ((change.position.x - lastPos.x) / dtSec).coerceIn(-3000f, 3000f)
                            smoothVelX = lerp(smoothVelX, instVelX, 0.30f)
                            lastPos = change.position
                            lastTimeMs = change.uptimeMillis

                            if (!dragged && abs(change.position.x - down.position.x) > viewConfig.touchSlop * 0.4f) {
                                dragged = true
                                releaseLock = gestureCoordinator?.acquire()
                                activeRelease = releaseLock
                            }
                            if (dragged) {
                                val u = (trackWidthPx - thumbWidthPx).coerceAtLeast(1f)
                                val frac = ((change.position.x - thumbWidthPx / 2f) / u).coerceIn(0f, 1f)
                                targetFrac = frac

                                val v = latestRange.start + frac * (latestRange.endInclusive - latestRange.start)
                                latestOnValueChange(v)

                                val bucket = (frac * 16f).roundToInt()
                                if (bucket != lastHapticBucket && hapticsEnabled) {
                                    lastHapticBucket = bucket
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }

                                targetStretch = (abs(smoothVelX) / 2200f).coerceIn(0f, 0.24f)
                            }

                            val uNow = (trackWidthPx - thumbWidthPx).coerceAtLeast(1f)
                            val thumbStart = uNow * thumbFracAnim.value.coerceIn(0f, 1f)
                            thumbMotion.touchFractionX = ((change.position.x - thumbStart) / thumbWidthPx).coerceIn(-0.15f, 1.15f)
                            thumbMotion.touchFractionY = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)
                        }
                    } finally {
                        followJob.cancel()
                        isDragging = false
                        releaseLock?.invoke()
                        if (activeRelease === releaseLock) {
                            activeRelease = null
                        }
                    }

                    val normVel = (smoothVelX / (trackWidthPx - thumbWidthPx).coerceAtLeast(1f)).coerceIn(-6f, 6f)
                    val endTarget = thumbFracAnim.value.coerceIn(0f, 1f)
                    if (dragged && abs(normVel) > 0.5f) {
                        scope.launch {
                            thumbFracAnim.animateTo(endTarget, FluidSprings.pillSlide(), initialVelocity = normVel * 0.12f)
                        }
                    }
                    scope.launch { thumbMotion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                    scope.launch { velocityStretch.animateTo(0f, FluidSprings.pressStretch()) }
                    latestOnFinished?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val activeBrush = remember(primaryColor) {
            Brush.horizontalGradient(
                colors = listOf(primaryColor.copy(alpha = 0.78f), primaryColor),
            )
        }

        // Frosted / solid inactive track + live filled track drawn in Draw phase (zero recomposition)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .drawBehind {
                    val p = thumbMotion.pressProgress.value.coerceIn(0f, 1f)
                    val h = lerp(8.dp.toPx(), 11.dp.toPx(), p)
                    val top = (size.height - h) / 2f
                    val radius = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(
                        color = inactiveTrackColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                        cornerRadius = radius,
                    )
                    val uPx = (size.width - thumbWidthPx).coerceAtLeast(1f)
                    val offsetPx = uPx * thumbFracAnim.value.coerceIn(0f, 1f)
                    val fillW = (offsetPx + thumbWidthPx * 0.5f).coerceIn(h, size.width)
                    drawRoundRect(
                        brush = activeBrush,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(fillW, h),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = trackBorderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, h),
                        cornerRadius = radius,
                        style = Stroke(width = 0.55.dp.toPx()),
                    )
                },
        )

        // Elastic velocity-stretching Liquid Glass Pill Thumb
        Box(
            modifier = Modifier
                .offset {
                    val usablePx = (trackWidthPx - thumbWidthPx).coerceAtLeast(1f)
                    IntOffset((usablePx * thumbFracAnim.value.coerceIn(0f, 1f)).roundToInt(), 0)
                }
                .size(thumbWidth, thumbHeight)
                .graphicsLayer {
                    val p = thumbMotion.pressProgress.value.coerceIn(0f, 1f)
                    val velBoost = maxOf(
                        velocityStretch.value,
                        (abs(thumbFracAnim.velocity) * 0.04f).coerceIn(0f, 0.22f),
                    )
                    scaleX = lerp(1f, 1.26f, p) + velBoost
                    scaleY = (lerp(1f, 1.22f, p) - velBoost * 0.22f).coerceIn(0.95f, 1.25f)
                }
                .fluidPressShadow(
                    shape = thumbShape,
                    motion = thumbMotion,
                    shadowColor = Color.Black,
                    maxAlpha = if (isDark) 0.38f else 0.19f,
                    blurRadius = 9.dp,
                    offsetY = 0.dp,
                )
                .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
                .clip(thumbShape)
                .drawBehind {
                    val p = thumbMotion.pressProgress.value.coerceIn(0f, 1f)
                    val baseFill = if (reduceTransparency) {
                        Color.White
                    } else {
                        val restAlpha = if (isDark) 0.90f else 0.96f
                        val dragAlpha = if (isDark) 0.36f else 0.58f
                        Color.White.copy(alpha = lerp(restAlpha, dragAlpha, p))
                    }
                    drawRect(color = baseFill, size = size)
                    drawFluidTouchSpotlight(
                        pressProgress = p,
                        touchFractionX = thumbMotion.touchFractionX,
                        touchFractionY = thumbMotion.touchFractionY,
                        shape = thumbShape,
                        isDark = isDark,
                        isAccentSurface = false,
                    )
                }
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            primaryColor.copy(alpha = 0.40f),
                        ),
                    ),
                    shape = thumbShape,
                ),
        )
    }
}

/**
 * Non-linear, interruptible, finger-draggable sliding capsule segmented control.
 * Supports both quick tapping any option and holding anywhere on the bar to scrub left/right
 * continuously without lifting your finger, with velocity-driven dynamic elongation, live haptic
 * detents, moving finger spotlight inside the floating pill, and subtle rounded press shadow.
 */
@Composable
fun <T> FluidGlassSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    labelFor: (T) -> String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = false,
    preset: GlassPreset = GlassPreset.Control,
) {
    if (items.isEmpty()) return
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val gestureCoordinator = LocalFluidGestureCoordinator.current
    val viewConfig = LocalViewConfiguration.current

    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val count = items.size
    val latestItems by rememberUpdatedState(items)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    val pillIndexAnim = remember { Animatable(selectedIndex.toFloat()) }
    val velocityStretch = remember { Animatable(0f) }
    val pillMotion = rememberFluidMotionState()
    var isScrubbing by remember { mutableStateOf(false) }
    var activeRelease by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeRelease?.invoke()
            activeRelease = null
        }
    }

    LaunchedEffect(selectedIndex, isScrubbing) {
        if (!isScrubbing) {
            pillIndexAnim.animateTo(selectedIndex.toFloat(), FluidSprings.pillSlide())
        }
    }

    val highlightedIndex by remember(count) {
        derivedStateOf {
            pillIndexAnim.value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
        }
    }

    val containerShape = ContinuousCornerShape(50.dp)
    val pillShape = ContinuousCornerShape(50.dp)
    val density = LocalDensity.current
    val padDp = 3.dp
    val padPx = with(density) { padDp.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDark -> Color(0xFF141B24).copy(alpha = 0.56f)
        else -> Color.White.copy(alpha = 0.66f)
    }
    val containerBorder = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.White.copy(alpha = if (isDark) 0.26f else 0.85f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(
                if (!reduceTransparency && useBackdropCapture && backdrop != null) {
                    Modifier.liquidGlassSurface(
                        shape = containerShape,
                        preset = preset,
                        backdrop = backdrop,
                        shadowEnabled = false,
                    )
                } else {
                    Modifier
                        .clip(containerShape)
                        .background(containerColor)
                        .border(0.75.dp, containerBorder, containerShape)
                },
            )
            .padding(padDp),
    ) {
        val slotWidth = maxWidth / count
        val slotWidthPx = with(density) { slotWidth.toPx() }.coerceAtLeast(1f)

        val pillBrush = remember(isDark, reduceTransparency, primaryColor) {
            if (reduceTransparency) {
                Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            primaryColor.copy(alpha = 0.28f).compositeOverOpaque(Color(0xFF282D3A)),
                            Color(0xFF232834),
                        )
                    } else {
                        listOf(
                            Color.White,
                            primaryColor.copy(alpha = 0.12f).compositeOverOpaque(Color.White),
                        )
                    },
                )
            } else {
                Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            primaryColor.copy(alpha = 0.40f),
                            Color.White.copy(alpha = 0.18f),
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.96f),
                            primaryColor.copy(alpha = 0.18f),
                        )
                    },
                )
            }
        }
        val pillBorderBrush = remember(isDark, primaryColor) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.62f else 0.95f),
                    primaryColor.copy(alpha = 0.38f),
                ),
            )
        }

        // Travelling non-linear fluid glass capsule pill
        Box(
            modifier = Modifier
                .width(slotWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    val vSquash = maxOf(
                        velocityStretch.value,
                        (abs(pillIndexAnim.velocity) * 0.035f).coerceIn(0f, 0.24f),
                    )
                    val distFromRest = abs(pillIndexAnim.value - highlightedIndex.toFloat())
                    val pressLift = lerp(1f, 1.055f, pillMotion.pressProgress.value.coerceIn(0f, 1f))
                    val travelLift = lerp(1f, 1.045f, (distFromRest * 2f).coerceIn(0f, 1f))
                    val baseLift = maxOf(pressLift, travelLift)
                    translationX = pillIndexAnim.value * slotWidthPx
                    scaleX = baseLift + vSquash
                    scaleY = (baseLift - vSquash * 0.28f).coerceIn(0.86f, 1.08f)
                    val moveBias = when {
                        pillIndexAnim.velocity > 0.3f -> 0.32f
                        pillIndexAnim.velocity < -0.3f -> 0.68f
                        else -> 0.5f
                    }
                    transformOrigin = TransformOrigin(moveBias, 0.5f)
                }
                .fluidPressShadow(
                    shape = pillShape,
                    motion = pillMotion,
                    shadowColor = Color.Black,
                    maxAlpha = if (isDark) 0.36f else 0.16f,
                    blurRadius = 10.dp,
                    offsetY = 0.dp,
                )
                .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
                .clip(pillShape)
                .drawBehind {
                    drawRect(brush = pillBrush, size = size)
                    drawFluidTouchSpotlight(
                        pressProgress = pillMotion.pressProgress.value,
                        touchFractionX = pillMotion.touchFractionX,
                        touchFractionY = pillMotion.touchFractionY,
                        shape = pillShape,
                        isDark = isDark,
                        isAccentSurface = isDark,
                    )
                }
                .border(
                    width = 0.85.dp,
                    brush = pillBorderBrush,
                    shape = pillShape,
                ),
        )

        // Unified touch & drag surface over all segments
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(count, slotWidthPx, hapticsEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        down.consume()
                        activeRelease?.invoke()
                        activeRelease = null
                        var releaseLock: (() -> Unit)? = null

                        isScrubbing = true
                        var lastDetentSlot = pillIndexAnim.value.roundToInt().coerceIn(0, count - 1)
                        val pillLeft = pillIndexAnim.value * slotWidthPx
                        pillMotion.touchFractionX = ((down.position.x - pillLeft) / slotWidthPx).coerceIn(-0.15f, 1.15f)
                        pillMotion.touchFractionY = (down.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)

                        scope.launch { pillMotion.pressProgress.animateTo(1f, spring(dampingRatio = 0.90f, stiffness = 920f)) }

                        var dragged = false
                        var targetSlot = pillIndexAnim.value
                        var targetStretch = 0f
                        var smoothVelX = 0f
                        var lastPos = down.position
                        var lastTimeMs = down.uptimeMillis

                        val followJob: Job = scope.launch {
                            var prevNanos = withFrameNanos { it }
                            while (isActive) {
                                val nowNanos = withFrameNanos { it }
                                val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                                prevNanos = nowNanos
                                smoothVelX *= exp(-14f * dt)
                                if (dragged) {
                                    val iAlpha = 1f - exp(-34f * dt)
                                    val sAlpha = 1f - exp(-22f * dt)
                                    pillIndexAnim.snapTo(lerp(pillIndexAnim.value, targetSlot, iAlpha))
                                    velocityStretch.snapTo(lerp(velocityStretch.value, targetStretch, sAlpha))
                                }
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChanged()) change.consume()

                                val dtSec = ((change.uptimeMillis - lastTimeMs).coerceAtLeast(4L)) / 1000f
                                val instVelX = ((change.position.x - lastPos.x) / dtSec).coerceIn(-3000f, 3000f)
                                smoothVelX = lerp(smoothVelX, instVelX, 0.30f)
                                lastPos = change.position
                                lastTimeMs = change.uptimeMillis

                                if (!dragged && abs(change.position.x - down.position.x) > viewConfig.touchSlop * 0.5f) {
                                    dragged = true
                                    releaseLock = gestureCoordinator?.acquire()
                                    activeRelease = releaseLock
                                }
                                if (dragged) {
                                    val rawSlot = ((change.position.x - padPx) / slotWidthPx) - 0.5f
                                    targetSlot = FluidMotionPhysics.elasticClampRange(rawSlot, (count - 1).toFloat(), 0.14f)

                                    val nearest = rawSlot.roundToInt().coerceIn(0, count - 1)
                                    if (nearest != lastDetentSlot) {
                                        lastDetentSlot = nearest
                                        if (hapticsEnabled) {
                                            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                        }
                                    }

                                    val slotVel = smoothVelX / slotWidthPx
                                    targetStretch = (abs(slotVel) * 0.036f).coerceIn(0f, 0.28f)
                                }

                                val currentPillLeft = pillIndexAnim.value * slotWidthPx
                                pillMotion.touchFractionX = ((change.position.x - currentPillLeft) / slotWidthPx).coerceIn(-0.15f, 1.15f)
                                pillMotion.touchFractionY = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)
                            }
                        } finally {
                            followJob.cancel()
                            isScrubbing = false
                            releaseLock?.invoke()
                            if (activeRelease === releaseLock) {
                                activeRelease = null
                            }
                        }

                        val slotsVel = (smoothVelX / slotWidthPx).coerceIn(-12f, 12f)

                        val finalIndex = if (dragged) {
                            val projected = pillIndexAnim.value + slotsVel * 0.065f
                            projected.roundToInt().coerceIn(0, count - 1)
                        } else {
                            if (hapticsEnabled) {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                            (down.position.x / slotWidthPx).toInt().coerceIn(0, count - 1)
                        }

                        scope.launch {
                            pillIndexAnim.animateTo(
                                targetValue = finalIndex.toFloat(),
                                animationSpec = FluidSprings.pillSlide(),
                                initialVelocity = slotsVel,
                            )
                        }
                        scope.launch { pillMotion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                        scope.launch { velocityStretch.animateTo(0f, FluidSprings.pressStretch()) }

                        if (finalIndex != latestSelectedIndex) {
                            latestOnSelect(latestItems[finalIndex])
                        }
                    }
                },
        ) {
            items.forEachIndexed { index, item ->
                val isHighlighted = index == highlightedIndex
                val textColor by animateColorAsState(
                    targetValue = if (isHighlighted) {
                        if (isDark) {
                            Color.White
                        } else {
                            readableAccentInk(MaterialTheme.colorScheme.primary, isDarkBackground = false)
                        }
                    } else {
                        glassPrimaryContentColor().copy(alpha = 0.82f)
                    },
                    animationSpec = spring(stiffness = 500f),
                    label = "seg_txt_$index",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labelFor(item),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1_200,
                            velocity = 36.dp,
                        ),
                    )
                }
            }
        }
    }
}

private fun Color.compositeOverOpaque(background: Color): Color {
    val fgA = alpha.coerceIn(0f, 1f)
    val invA = 1f - fgA
    return Color(
        red = (red * fgA + background.red * invA).coerceIn(0f, 1f),
        green = (green * fgA + background.green * invA).coerceIn(0f, 1f),
        blue = (blue * fgA + background.blue * invA).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/**
 * Translucent Liquid Glass rounded card for grouped Settings items and hero tiles.
 */
@Composable
fun FluidGlassCard(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = ContinuousCornerShape(24.dp),
    preset: GlassPreset = GlassPreset.Card,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = true,
    surfaceTint: Color? = null,
    motionState: FluidMotionState? = null,
    pressShadowEnabled: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .liquidGlassSurface(
                shape = shape,
                preset = preset,
                backdrop = backdrop,
                surfaceTint = surfaceTint,
                motionState = motionState,
                pressShadowEnabled = pressShadowEnabled,
                useBackdropCapture = useBackdropCapture,
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Lightweight, zero-backdrop-blur Kinetic Aero interactive row modifier for high-FPS scrolling lists
 * (Recent Transactions, UTXOs, Covenants, Block Explorer rows, Rich List rows).
 *
 * Delivers elastic spring bounce, haptics, and draw-phase finger-tracking spotlight + rim glow
 * with `lockParentGestures = false` so vertical scrolling stays locked at 60–120 FPS.
 */
@Composable
fun Modifier.kineticInteractiveRow(
    shape: CornerBasedShape,
    backgroundColor: Color,
    borderColor: Color,
    borderWidth: Dp = 0.85.dp,
    isHighlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val motion = rememberFluidMotionState()
    val isDark = LocalGlassDarkTheme.current
    return this
        .fluidTouchPhysics(
            enabled = enabled,
            state = motion,
            pressedScale = 0.97f,
            maxDragPullPx = 6f,
            lockParentGestures = false,
        )
        .clip(shape)
        .drawBehind {
            drawRect(color = backgroundColor, size = size)
            drawFluidTouchSpotlight(
                pressProgress = motion.pressProgress.value,
                touchFractionX = motion.touchFractionX,
                touchFractionY = motion.touchFractionY,
                shape = shape,
                isDark = isDark,
                isAccentSurface = isHighlighted,
            )
        }
        .border(borderWidth, borderColor, shape)
        .clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
}

/**
 * Translucent Liquid Glass input box with crisp solid fallback under [LocalReduceTransparency].
 */
@Composable
fun FluidGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = false,
    nestedInGlassCard: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(20.dp)
    val ink = glassPrimaryContentColor()
    val placeholderColor = glassPrimaryContentColor().copy(alpha = 0.62f)
    val fill = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        nestedInGlassCard && isDark -> Color(0xFF0E141C).copy(alpha = 0.24f)
        nestedInGlassCard -> Color.White.copy(alpha = 0.34f)
        isDark -> Color(0xFF141B24).copy(alpha = 0.64f)
        else -> Color.White.copy(alpha = 0.78f)
    }
    val border = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.28f)
        else -> Color.White.copy(alpha = 0.92f)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = glassPrimaryContentColor().copy(alpha = 0.84f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        val boxSurfaceMod = if (!reduceTransparency && useBackdropCapture && backdrop != null) {
            Modifier.liquidGlassSurface(
                shape = shape,
                preset = GlassPreset.Card,
                backdrop = backdrop,
                shadowEnabled = false,
            )
        } else {
            Modifier
                .clip(shape)
                .background(fill, shape)
                .border(0.85.dp, border, shape)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 46.dp)
                .then(boxSurfaceMod)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingContent != null) {
                leadingContent()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = placeholderColor),
                        maxLines = if (singleLine) 1 else 2,
                        softWrap = !singleLine,
                        overflow = if (singleLine) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (singleLine) {
                            Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 900,
                                    velocity = 36.dp,
                                )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = textStyle.copy(color = ink),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * Kinetic Aero collapsible 2-column preset selector that replaces cramped horizontal chip carousels.
 *
 * Collapses to a single summary pill (`Choose` / `Hide` with Living Flow chevron rotation) and expands
 * downward with spring physics into balanced 2-column rows (`chunked(2)`). Inside scrollable screens
 * (such as Settings), combine with `FluidGlassChip(..., lockParentGestures = false, useBackdropCapture = false)`
 * so vertical page scrolling never gets hijacked when touching a dense option tile.
 */
@Composable
fun CollapsibleOptionGridSelector(
    title: String,
    summaryText: String,
    modifier: Modifier = Modifier,
    summaryLeading: (@Composable () -> Unit)? = null,
    initiallyExpanded: Boolean = false,
    chooseLabel: String = LocalKineticStrings.current.choose,
    hideLabel: String = LocalKineticStrings.current.hide,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = FluidSprings.menuOpen(),
        label = "kinetic_option_grid_chevron",
    )
    val headerShape = ContinuousCornerShape(18.dp)
    val headerBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.56f)
    }
    val headerBorder = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.85f)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(headerShape)
                .background(headerBg, headerShape)
                .border(0.85.dp, headerBorder, headerShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (summaryLeading != null) {
                        summaryLeading()
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = glassSecondaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val actionTint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (expanded) hideLabel else chooseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = actionTint,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) hideLabel else chooseLabel,
                    tint = actionTint,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(FluidSprings.sheetExpand()) + fadeIn(),
            exit = shrinkVertically(FluidSprings.menuClose()) + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/**
 * Convenience 2-column vertical preset selector backed by [CollapsibleOptionGridSelector] and
 * [FluidGlassChip] tiles with `lockParentGestures = false` and `useBackdropCapture = false`.
 */
@Composable
fun <T> FluidOptionGridSelector(
    title: String,
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    labelFor: (T) -> String,
    modifier: Modifier = Modifier,
    summaryLeading: (@Composable () -> Unit)? = null,
    itemLeading: (@Composable (T) -> Unit)? = null,
    initiallyExpanded: Boolean = false,
) {
    CollapsibleOptionGridSelector(
        title = title,
        summaryText = labelFor(selectedItem),
        modifier = modifier,
        summaryLeading = summaryLeading,
        initiallyExpanded = initiallyExpanded,
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        selected = item == selectedItem,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = { onSelect(item) },
                    ) {
                        if (itemLeading != null) {
                            itemLeading(item)
                        }
                        Text(
                            text = labelFor(item),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
