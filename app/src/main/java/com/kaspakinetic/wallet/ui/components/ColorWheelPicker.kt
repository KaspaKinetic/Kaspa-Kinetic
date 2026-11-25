package com.kaspakinetic.wallet.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidSprings
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.drawFluidTouchSpotlight
import com.kaspakinetic.wallet.ui.glass.fluidPressShadow
import com.kaspakinetic.wallet.ui.glass.fluidTouchPhysics
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.rememberFluidMotionState
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.theme.argbLongToColor
import com.kaspakinetic.wallet.ui.theme.colorToArgbLong
import com.kaspakinetic.wallet.ui.theme.colorToHsv
import com.kaspakinetic.wallet.ui.theme.contrastInkFor
import com.kaspakinetic.wallet.ui.theme.hsvToColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val WheelSweepColors = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)

private val QuickCustomSwatches = listOf(
    0xFF0A84FFL,
    0xFFFF453AL,
    0xFF30D158L,
    0xFFFF9F0AL,
    0xFFBF5AF2L,
    0xFF1F2A44L,
    0xFFECEEF3L,
)

/**
 * Interactive Kinetic Aero HSV color studio pairing an unrestricted 360-degree Hue/Saturation
 * wheel with a dedicated Brightness / Darkness (Value) scrubber.
 *
 * Convenience overload for callers that handle every ARGB update uniformly.
 */
@Composable
fun FluidColorWheelStudio(
    selectedColorArgb: Long,
    onColorChanged: (argb: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    FluidColorWheelStudio(
        selectedColorArgb = selectedColorArgb,
        onColorChanged = { argb, _ -> onColorChanged(argb) },
        modifier = modifier,
    )
}

/**
 * Interactive Kinetic Aero HSV color studio pairing an unrestricted 360-degree Hue/Saturation
 * wheel with a dedicated Brightness / Darkness (Value) scrubber.
 */
@Composable
fun FluidColorWheelStudio(
    selectedColorArgb: Long,
    onColorChanged: (argb: Long, immediate: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val gestureCoordinator = LocalFluidGestureCoordinator.current
    val scope = rememberCoroutineScope()

    val maskedInput = remember(selectedColorArgb) { 0xFF000000L or (selectedColorArgb and 0x00FFFFFFL) }
    val initialHsv = remember { colorToHsv(argbLongToColor(maskedInput)) }

    var hue by remember { mutableFloatStateOf(initialHsv.hue) }
    var saturation by remember { mutableFloatStateOf(initialHsv.saturation) }
    var brightness by remember { mutableFloatStateOf(initialHsv.value) }
    var isDraggingWheel by remember { mutableStateOf(false) }
    var isDraggingBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(maskedInput, isDraggingWheel, isDraggingBrightness) {
        if (!isDraggingWheel && !isDraggingBrightness) {
            val currentArgb = colorToArgbLong(hsvToColor(hue, saturation, brightness))
            if (currentArgb != maskedInput) {
                val parsed = colorToHsv(argbLongToColor(maskedInput))
                if (parsed.saturation > 0.01f) {
                    hue = parsed.hue
                }
                if (parsed.value > 0.01f) {
                    saturation = parsed.saturation
                }
                brightness = parsed.value
            }
        }
    }

    val latestOnColorChanged by rememberUpdatedState(onColorChanged)
    val wheelPuckMotion = rememberFluidMotionState()
    val brightnessMotion = rememberFluidMotionState()
    var wheelActiveRelease by remember { mutableStateOf<(() -> Unit)?>(null) }
    var sliderActiveRelease by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            wheelActiveRelease?.invoke()
            wheelActiveRelease = null
            sliderActiveRelease?.invoke()
            sliderActiveRelease = null
        }
    }

    val liveColor = remember(hue, saturation, brightness) { hsvToColor(hue, saturation, brightness) }
    val fullBrightColor = remember(hue, saturation) { hsvToColor(hue, saturation, 1f) }
    val liveArgb = remember(liveColor) { colorToArgbLong(liveColor) }
    val hexText = remember(liveArgb) {
        "#%06X".format((liveArgb and 0x00FFFFFFL).toInt())
    }
    val previewInk = remember(liveColor, hue, saturation) { contrastInkFor(liveColor, hue, saturation) }

    val panelShape = ContinuousCornerShape(22.dp)
    val panelBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainer
        isDark -> Color.White.copy(alpha = 0.06f)
        else -> Color.White.copy(alpha = 0.56f)
    }
    val panelBorder = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.85f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(panelBg, panelShape)
            .border(0.9.dp, panelBorder, panelShape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header: live pill preview + #HEX code + percentage brightness badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(ContinuousCornerShape(14.dp))
                        .background(liveColor)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = if (isDark) 0.35f else 0.75f),
                            shape = ContinuousCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = strings.colorStudioSample,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = previewInk,
                    )
                }
                Column {
                    Text(
                        text = hexText,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = "${hue.roundToInt()}° • Sat ${(saturation * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                }
            }
            Text(
                text = "Lum ${(brightness * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = glassSecondaryContentColor(),
            )
        }

        // Hue & Saturation Wheel (live brightness/darkness shaded)
        val wheelSize = 196.dp
        val puckRadiusDp = 13.dp
        val density = LocalDensity.current
        var wheelDiameterPx by remember { mutableFloatStateOf(with(density) { wheelSize.toPx() }) }
        var lastSectorBucket by remember { mutableStateOf((hue / 30f).toInt()) }

        Box(
            modifier = Modifier
                .size(wheelSize)
                .onSizeChanged { wheelDiameterPx = minOf(it.width, it.height).toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        down.consume()
                        val releaseLock = gestureCoordinator?.acquire()
                        wheelActiveRelease?.invoke()
                        wheelActiveRelease = releaseLock
                        isDraggingWheel = true

                        scope.launch { wheelPuckMotion.pressProgress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 900f)) }

                        fun sampleWheel(pos: Offset, immediateCommit: Boolean) {
                            val cx = wheelDiameterPx * 0.5f
                            val cy = wheelDiameterPx * 0.5f
                            val maxRadius = (wheelDiameterPx * 0.5f - with(density) { 4.dp.toPx() }).coerceAtLeast(1f)
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val dist = hypot(dx, dy)
                            val newSat = (dist / maxRadius).coerceIn(0f, 1f)
                            val angleDeg = ((atan2(dy, dx) * (180.0 / PI)).toFloat() + 360f) % 360f

                            hue = angleDeg
                            saturation = newSat
                            val bucket = (angleDeg / 30f).toInt()
                            if (bucket != lastSectorBucket) {
                                lastSectorBucket = bucket
                                if (hapticsEnabled) {
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                            }
                            val argb = colorToArgbLong(hsvToColor(angleDeg, newSat, brightness))
                            latestOnColorChanged(argb, immediateCommit)
                        }

                        sampleWheel(down.position, false)
                        var lastPos = down.position

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChanged()) {
                                    change.consume()
                                    lastPos = change.position
                                    sampleWheel(change.position, false)
                                }
                            }
                        } finally {
                            isDraggingWheel = false
                            releaseLock?.invoke()
                            if (wheelActiveRelease === releaseLock) {
                                wheelActiveRelease = null
                            }
                        }

                        sampleWheel(lastPos, true)
                        scope.launch { wheelPuckMotion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                if (!size.width.isFinite() || !size.height.isFinite() || size.minDimension <= 2f) {
                    return@Canvas
                }
                val r = size.minDimension * 0.5f
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val innerR = (r - 4.dp.toPx()).coerceAtLeast(1f)

                drawCircle(
                    brush = Brush.sweepGradient(colors = WheelSweepColors, center = center),
                    radius = innerR,
                    center = center,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        center = center,
                        radius = innerR,
                    ),
                    radius = innerR,
                    center = center,
                )
                // Live brightness/darkness shading directly across the wheel
                val darknessOverlay = (1f - brightness).coerceIn(0f, 1f)
                if (darknessOverlay > 0.005f) {
                    drawCircle(
                        color = Color.Black.copy(alpha = darknessOverlay),
                        radius = innerR,
                        center = center,
                    )
                }
                // Crisp outer glass rim ring so dark wheel states stay clearly bounded
                drawCircle(
                    color = if (isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.14f),
                    radius = innerR,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )

                // Living Flow magnifier puck
                val rad = Math.toRadians(hue.toDouble())
                val puckCenter = Offset(
                    x = center.x + (cos(rad) * innerR * saturation).toFloat(),
                    y = center.y + (sin(rad) * innerR * saturation).toFloat(),
                )
                val p = wheelPuckMotion.pressProgress.value.coerceIn(0f, 1f)
                val puckRadius = lerp(puckRadiusDp.toPx(), (puckRadiusDp + 4.dp).toPx(), p)

                // Soft centered halo under held puck
                if (p > 0.01f) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.28f * p),
                        radius = puckRadius + 4.dp.toPx() * p,
                        center = puckCenter,
                    )
                }
                drawCircle(
                    color = liveColor,
                    radius = puckRadius,
                    center = puckCenter,
                )
                drawCircle(
                    color = Color.White,
                    radius = puckRadius,
                    center = puckCenter,
                    style = Stroke(width = 2.6.dp.toPx()),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.24f),
                    radius = puckRadius + 1.2.dp.toPx(),
                    center = puckCenter,
                    style = Stroke(width = 0.9.dp.toPx()),
                )
            }
        }

        // Brightness / Darkness (Value) Scrubbing Track
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.colorStudioDarkness,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
                Text(
                    text = strings.colorStudioBrightness,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
            }

            var barWidthPx by remember { mutableFloatStateOf(1f) }
            val thumbWidth = 30.dp
            val thumbHeight = 22.dp
            val thumbWidthPx = with(density) { thumbWidth.toPx() }
            val thumbValueAnim = remember { Animatable(brightness) }
            val thumbStretch = remember { Animatable(0f) }

            LaunchedEffect(brightness, isDraggingBrightness) {
                if (!isDraggingBrightness) {
                    thumbValueAnim.animateTo(brightness, FluidSprings.pillSlide())
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(fullBrightColor, thumbWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            down.consume()
                            val releaseLock = gestureCoordinator?.acquire()
                            sliderActiveRelease?.invoke()
                            sliderActiveRelease = releaseLock
                            isDraggingBrightness = true

                            val usable = (barWidthPx - thumbWidthPx).coerceAtLeast(1f)
                            var targetValue = ((down.position.x - thumbWidthPx * 0.5f) / usable).coerceIn(0f, 1f)
                            var targetStretchVal = 0f
                            var smoothVelX = 0f
                            var lastPos = down.position
                            var lastTimeMs = down.uptimeMillis
                            var lastBucket = (targetValue * 16f).roundToInt()

                            brightness = targetValue
                            latestOnColorChanged(colorToArgbLong(hsvToColor(hue, saturation, targetValue)), false)
                            scope.launch { brightnessMotion.pressProgress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 920f)) }

                            val followJob: Job = scope.launch {
                                var prevNanos = withFrameNanos { it }
                                while (isActive) {
                                    val nowNanos = withFrameNanos { it }
                                    val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                                    prevNanos = nowNanos
                                    smoothVelX *= exp(-14f * dt)
                                    thumbValueAnim.snapTo(lerp(thumbValueAnim.value, targetValue, 1f - exp(-36f * dt)))
                                    thumbStretch.snapTo(lerp(thumbStretch.value, targetStretchVal, 1f - exp(-22f * dt)))
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

                                    val v = ((change.position.x - thumbWidthPx * 0.5f) / usable).coerceIn(0f, 1f)
                                    targetValue = v
                                    brightness = v
                                    latestOnColorChanged(colorToArgbLong(hsvToColor(hue, saturation, v)), false)

                                    val bucket = (v * 16f).roundToInt()
                                    if (bucket != lastBucket && hapticsEnabled) {
                                        lastBucket = bucket
                                        runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                    }
                                    targetStretchVal = (abs(smoothVelX) / 2200f).coerceIn(0f, 0.22f)

                                    val thumbX = usable * thumbValueAnim.value.coerceIn(0f, 1f)
                                    brightnessMotion.touchFractionX = ((change.position.x - thumbX) / thumbWidthPx).coerceIn(-0.15f, 1.15f)
                                    brightnessMotion.touchFractionY = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(-0.15f, 1.15f)
                                }
                            } finally {
                                followJob.cancel()
                                isDraggingBrightness = false
                                releaseLock?.invoke()
                                if (sliderActiveRelease === releaseLock) {
                                    sliderActiveRelease = null
                                }
                            }

                            latestOnColorChanged(colorToArgbLong(hsvToColor(hue, saturation, targetValue)), true)
                            scope.launch { brightnessMotion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                            scope.launch { thumbStretch.animateTo(0f, FluidSprings.pressStretch()) }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Full Black -> Full Brightness gradient track
                val gradientBrush = remember(fullBrightColor) {
                    Brush.horizontalGradient(colors = listOf(Color.Black, fullBrightColor))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(ContinuousCornerShape(50.dp))
                        .drawBehind {
                            drawRoundRect(
                                brush = gradientBrush,
                                cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f),
                            )
                            drawRoundRect(
                                color = if (isDark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.16f),
                                cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f),
                                style = Stroke(width = 0.85.dp.toPx()),
                            )
                        },
                )

                val thumbShape = ContinuousCornerShape(50.dp)
                Box(
                    modifier = Modifier
                        .offset {
                            val usablePx = (barWidthPx - thumbWidthPx).coerceAtLeast(1f)
                            IntOffset((usablePx * thumbValueAnim.value.coerceIn(0f, 1f)).roundToInt(), 0)
                        }
                        .size(thumbWidth, thumbHeight)
                        .graphicsLayer {
                            val p = brightnessMotion.pressProgress.value.coerceIn(0f, 1f)
                            val velBoost = thumbStretch.value
                            scaleX = lerp(1f, 1.24f, p) + velBoost
                            scaleY = (lerp(1f, 1.18f, p) - velBoost * 0.2f).coerceIn(0.95f, 1.24f)
                        }
                        .fluidPressShadow(
                            shape = thumbShape,
                            motion = brightnessMotion,
                            shadowColor = Color.Black,
                            maxAlpha = if (isDark) 0.36f else 0.18f,
                            blurRadius = 8.dp,
                            offsetY = 0.dp,
                        )
                        .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                        .clip(thumbShape)
                        .drawBehind {
                            drawRect(color = liveColor, size = size)
                            drawFluidTouchSpotlight(
                                pressProgress = brightnessMotion.pressProgress.value,
                                touchFractionX = brightnessMotion.touchFractionX,
                                touchFractionY = brightnessMotion.touchFractionY,
                                shape = thumbShape,
                                isDark = isDark,
                                isAccentSurface = false,
                            )
                        }
                        .border(1.8.dp, Color.White, thumbShape),
                )
            }
        }

        // Quick jump dots (light, vibrant, & deep dark presets for instant 1-tap starting points)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.colorStudioQuickShades,
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickCustomSwatches.forEach { swatchArgb ->
                    val swatchColor = argbLongToColor(swatchArgb)
                    val isMatch = (liveArgb and 0x00FFFFFFL) == (swatchArgb and 0x00FFFFFFL)
                    val dotMotion = rememberFluidMotionState()
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .fluidTouchPhysics(
                                state = dotMotion,
                                pressedScale = 0.86f,
                                maxDragPullPx = 6f,
                            )
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = if (isMatch) 2.2.dp else 0.9.dp,
                                color = if (isMatch) Color.White else panelBorder,
                                shape = CircleShape,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                val parsed = colorToHsv(swatchColor)
                                hue = parsed.hue
                                saturation = parsed.saturation
                                brightness = parsed.value
                                latestOnColorChanged(swatchArgb, true)
                            },
                    )
                }
            }
        }
    }
}
