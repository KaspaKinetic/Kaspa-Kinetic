package com.kaspakinetic.wallet.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Provides app-wide haptic preference to interactive Kinetic Aero controls.
 */
val LocalFluidHapticsEnabled = compositionLocalOf { true }

/**
 * Tracks when a user is actively holding/dragging an interactive Kinetic Aero button, chip,
 * switch, slider, or segmented pill so parent navigation drawers and scrollable containers
 * yield gesture ownership cleanly instead of stealing the touch.
 */
@Stable
class FluidGestureCoordinator {
    private var activeLocks by mutableIntStateOf(0)

    val isLocked: Boolean
        get() = activeLocks > 0

    fun acquire(): () -> Unit {
        activeLocks++
        var released = false
        return {
            if (!released) {
                released = true
                activeLocks = (activeLocks - 1).coerceAtLeast(0)
            }
        }
    }
}

val LocalFluidGestureCoordinator = compositionLocalOf<FluidGestureCoordinator?> { null }

/**
 * Pure non-linear math helpers for rubber-band resistance and velocity-reactive fluid deformation.
 */
object FluidMotionPhysics {
    fun rubberBandOffset(rawDeltaPx: Float, maxPullPx: Float, responsiveness: Float = 0.22f): Float {
        if (maxPullPx <= 0f) return 0f
        return maxPullPx * tanh((rawDeltaPx * responsiveness) / maxPullPx)
    }

    fun elasticClampNormalized(value: Float, overscrollLimit: Float = 0.08f): Float {
        return when {
            value < 0f -> -overscrollLimit * tanh(abs(value) / (overscrollLimit * 2.5f).coerceAtLeast(0.01f))
            value > 1f -> 1f + overscrollLimit * tanh((value - 1f) / (overscrollLimit * 2.5f).coerceAtLeast(0.01f))
            else -> value
        }
    }

    fun elasticClampRange(value: Float, maxIndex: Float, overscrollSlots: Float = 0.14f): Float {
        return when {
            value < 0f -> -overscrollSlots * tanh(abs(value) / (overscrollSlots * 2.5f).coerceAtLeast(0.01f))
            value > maxIndex -> maxIndex + overscrollSlots * tanh((value - maxIndex) / (overscrollSlots * 2.5f).coerceAtLeast(0.01f))
            else -> value
        }
    }
}

/**
 * Spring physics presets tuned for the Kinetic Aero design language:
 * parallel, interruptible, velocity-preserving springs that stretch, squeeze, and bounce.
 */
object FluidSprings {
    fun <T> pressScale(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.56f,
        stiffness = 780f,
    )

    fun <T> pressStretch(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.52f,
        stiffness = 620f,
    )

    fun <T> pressDrift(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = 540f,
    )

    fun <T> menuOpen(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = 460f,
    )

    fun <T> menuClose(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.88f,
        stiffness = 680f,
    )

    fun <T> pillSlide(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.66f,
        stiffness = 460f,
    )

    fun <T> sheetExpand(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.78f,
        stiffness = 380f,
    )

    fun <T> floatGentle(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 360f,
    )
}

@Stable
class FluidMotionState {
    val pressProgress = Animatable(0f)
    val scaleX = Animatable(1f)
    val scaleY = Animatable(1f)
    val driftX = Animatable(0f)
    val driftY = Animatable(0f)
    var pivotFractionX by mutableFloatStateOf(0.5f)
    var pivotFractionY by mutableFloatStateOf(0.5f)
    var touchFractionX by mutableFloatStateOf(0.5f)
    var touchFractionY by mutableFloatStateOf(0.5f)
}

@Composable
fun rememberFluidMotionState(): FluidMotionState = remember { FluidMotionState() }

/**
 * Applies Living Flow parallel interruptible spring squeeze, directional rubber-band stretch,
 * magnetic pull toward touch position, live finger coordinates ([FluidMotionState.touchFractionX]/[Y]),
 * and elastic release bounce.
 *
 * When [lockParentGestures] is true (default for buttons, chips, pills, and toggles), active pointer
 * movement is claimed cleanly after inner [Modifier.clickable] sees press/bounds, preventing parent
 * navigation drawers or scroll containers from stealing the touch while admiring button physics.
 */
fun Modifier.fluidTouchPhysics(
    enabled: Boolean = true,
    state: FluidMotionState? = null,
    pressedScale: Float = 0.945f,
    releaseStretchAmount: Float = 0.045f,
    maxDragPullPx: Float = 14f,
    enableHapticsOnDown: Boolean = true,
    lockParentGestures: Boolean = true,
): Modifier = composed {
    val motion = state ?: rememberFluidMotionState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val gestureCoordinator = LocalFluidGestureCoordinator.current
    val viewConfig = LocalViewConfiguration.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var activeRelease by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeRelease?.invoke()
            activeRelease = null
        }
    }

    this
        .onSizeChanged { size = it }
        .pointerInput(enabled, pressedScale, releaseStretchAmount, maxDragPullPx, hapticsEnabled, lockParentGestures) {
            if (!enabled) {
                activeRelease?.invoke()
                activeRelease = null
                scope.launch { motion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                scope.launch { motion.scaleX.animateTo(1f, FluidSprings.pressScale()) }
                scope.launch { motion.scaleY.animateTo(1f, FluidSprings.pressScale()) }
                scope.launch { motion.driftX.animateTo(0f, FluidSprings.pressDrift()) }
                scope.launch { motion.driftY.animateTo(0f, FluidSprings.pressDrift()) }
                return@pointerInput
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                activeRelease?.invoke()
                activeRelease = null
                var releaseLock: (() -> Unit)? = null

                val w = max(size.width.toFloat(), 1f)
                val h = max(size.height.toFloat(), 1f)
                val downPos = down.position
                val normX = ((downPos.x / w) - 0.5f) * 2f // -1..1
                val normY = ((downPos.y / h) - 0.5f) * 2f

                motion.touchFractionX = (downPos.x / w).coerceIn(-0.12f, 1.12f)
                motion.touchFractionY = (downPos.y / h).coerceIn(-0.12f, 1.12f)
                motion.pivotFractionX = (0.5f + normX * 0.14f).coerceIn(0.25f, 0.75f)
                motion.pivotFractionY = (0.5f + normY * 0.14f).coerceIn(0.25f, 0.75f)

                if (enableHapticsOnDown && hapticsEnabled) {
                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }

                // Parallel squeeze: push slightly flatter along tapped axis without fighting drag follower
                val squeezeBiasX = pressedScale + abs(normX) * 0.015f
                val squeezeBiasY = pressedScale + abs(normY) * 0.015f
                scope.launch { motion.pressProgress.animateTo(1f, spring(dampingRatio = 0.90f, stiffness = 920f)) }

                var targetDriftX = 0f
                var targetDriftY = 0f
                var targetScaleX = squeezeBiasX
                var targetScaleY = squeezeBiasY
                var smoothVelX = 0f
                var smoothVelY = 0f
                var lastMoveTimeMs = down.uptimeMillis
                var lastPos = downPos
                var lastDragDelta = Offset.Zero
                var draggedPastTap = false
                // Strictly below parent Drawer/Scroll 1.0 * touchSlop so intentional holds lock parents,
                // while normal taps (0..14px micro-wiggle on high-DPI digitizers) remain completely unconsumed.
                val dragLockThresholdPx = max(viewConfig.touchSlop * 0.72f, 12f)
                val pullNormDenominator = max(maxDragPullPx, 1f)

                // Single display-vsync critically-damped exponential follower while held:
                // smoothly glides scale & rubber-band pull with zero 240Hz spring restarts or velocity zoom pulsing.
                val followJob: Job = scope.launch {
                    var prevFrameNanos = withFrameNanos { it }
                    while (isActive) {
                        val nowNanos = withFrameNanos { it }
                        val dt = ((nowNanos - prevFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                        prevFrameNanos = nowNanos

                        // Gently bleed smoothed velocity toward 0 when finger pauses before lifting
                        val velDecay = exp(-14f * dt)
                        smoothVelX *= velDecay
                        smoothVelY *= velDecay

                        val driftAlpha = 1f - exp(-30f * dt)
                        val scaleAlpha = 1f - exp(-26f * dt)
                        motion.driftX.snapTo(lerp(motion.driftX.value, targetDriftX, driftAlpha))
                        motion.driftY.snapTo(lerp(motion.driftY.value, targetDriftY, driftAlpha))
                        motion.scaleX.snapTo(lerp(motion.scaleX.value, targetScaleX, scaleAlpha))
                        motion.scaleY.snapTo(lerp(motion.scaleY.value, targetScaleY, scaleAlpha))
                    }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            break
                        }

                        val totalDelta = change.position - downPos
                        lastDragDelta = totalDelta

                        motion.touchFractionX = (change.position.x / w).coerceIn(-0.12f, 1.12f)
                        motion.touchFractionY = (change.position.y / h).coerceIn(-0.12f, 1.12f)

                        if (lockParentGestures && !draggedPastTap) {
                            val maxAxis = max(abs(totalDelta.x), abs(totalDelta.y))
                            if (maxAxis > dragLockThresholdPx || totalDelta.getDistance() > viewConfig.touchSlop * 0.80f) {
                                draggedPastTap = true
                                releaseLock = gestureCoordinator?.acquire()
                                activeRelease = releaseLock
                            }
                        }

                        // Consume ONLY once user intentionally drags past tap tolerance (Initial pass runs
                        // before parent drawer / scroll Main-pass slop, keeping clicks 100% responsive).
                        if (draggedPastTap && lockParentGestures && change.positionChanged()) {
                            change.consume()
                        }

                        val dtSec = ((change.uptimeMillis - lastMoveTimeMs).coerceAtLeast(4L)) / 1000f
                        val stepDelta = change.position - lastPos
                        val instVelX = (stepDelta.x / dtSec).coerceIn(-2600f, 2600f)
                        val instVelY = (stepDelta.y / dtSec).coerceIn(-2600f, 2600f)
                        smoothVelX = lerp(smoothVelX, instVelX, 0.25f)
                        smoothVelY = lerp(smoothVelY, instVelY, 0.25f)
                        lastMoveTimeMs = change.uptimeMillis
                        lastPos = change.position

                        // Pure monotonic displacement rubber-band pull & directional taffy stretch:
                        // intentionally avoids speed-based zoom spikes while holding/dragging a button.
                        val nextDriftX = FluidMotionPhysics.rubberBandOffset(totalDelta.x, maxDragPullPx)
                        val nextDriftY = FluidMotionPhysics.rubberBandOffset(totalDelta.y, maxDragPullPx)
                        targetDriftX = nextDriftX
                        targetDriftY = nextDriftY
                        val stretchRatioX = (abs(nextDriftX) / pullNormDenominator).coerceIn(0f, 1f)
                        val stretchRatioY = (abs(nextDriftY) / pullNormDenominator).coerceIn(0f, 1f)

                        targetScaleX = (squeezeBiasX + stretchRatioX * 0.055f - stretchRatioY * 0.025f).coerceIn(0.89f, 1.02f)
                        targetScaleY = (squeezeBiasY + stretchRatioY * 0.055f - stretchRatioX * 0.025f).coerceIn(0.89f, 1.02f)
                    }
                } finally {
                    followJob.cancel()
                    releaseLock?.invoke()
                    if (activeRelease === releaseLock) {
                        activeRelease = null
                    }
                }

                // Velocity-seeded elastic spring rebound back to resting geometry
                val vxNorm = (-smoothVelX / w).coerceIn(-5f, 5f)
                val vyNorm = (-smoothVelY / h).coerceIn(-5f, 5f)
                val bounceBoostX = 1f + releaseStretchAmount + (abs(lastDragDelta.x) / w).coerceAtMost(0.055f)
                val bounceBoostY = 1f + releaseStretchAmount * 0.7f + (abs(lastDragDelta.y) / h).coerceAtMost(0.055f)

                scope.launch { motion.pressProgress.animateTo(0f, FluidSprings.pressScale()) }
                scope.launch { motion.driftX.animateTo(0f, FluidSprings.pressDrift(), initialVelocity = vxNorm * 16f) }
                scope.launch { motion.driftY.animateTo(0f, FluidSprings.pressDrift(), initialVelocity = vyNorm * 16f) }
                scope.launch {
                    motion.scaleX.animateTo(bounceBoostX, spring(dampingRatio = 0.65f, stiffness = 1400f))
                    motion.scaleX.animateTo(1f, FluidSprings.pressScale())
                }
                scope.launch {
                    motion.scaleY.animateTo(bounceBoostY, spring(dampingRatio = 0.65f, stiffness = 1400f))
                    motion.scaleY.animateTo(1f, FluidSprings.pressScale())
                }
            }
        }
        // Coordinate isolation boundary: keeps .pointerInput in un-transformed parent space so
        // graphicsLayer translation/scale below never creates an inverse-transform jitter loop.
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }
        .graphicsLayer {
            scaleX = motion.scaleX.value
            scaleY = motion.scaleY.value
            translationX = motion.driftX.value
            translationY = motion.driftY.value
            transformOrigin = TransformOrigin(motion.pivotFractionX, motion.pivotFractionY)
        }
}
