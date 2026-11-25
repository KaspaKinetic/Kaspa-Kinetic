package com.kaspakinetic.wallet.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Floating anchored Liquid Glass popover menu rendered inside the same window tree so
 * [LocalOverlayBackdrop] refracts live scrolling content and wallpaper underneath.
 *
 * Uses parallel interruptible springs from an origin corner ([transformOrigin]) for ColorOS 17
 * Living Flow / iOS 26 fluid emergence.
 */
@Composable
fun FluidGlassPopoverOverlay(
    visible: Boolean,
    anchorBoundsInRoot: Rect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    preferAbove: Boolean = false,
    alignEndToAnchor: Boolean = true,
    menuWidth: Dp = 248.dp,
    backdrop: Backdrop? = LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) {
            shouldRender = true
            progress.animateTo(1f, FluidSprings.menuOpen())
        } else if (shouldRender) {
            progress.animateTo(0f, FluidSprings.menuClose())
            shouldRender = false
        }
    }

    if (!shouldRender && !visible) return

    BackHandler(enabled = visible, onBack = onDismiss)

    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val effectiveBackdrop = if (reduceTransparency) null else backdrop
    val density = LocalDensity.current
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(50f),
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val menuWidthPx = with(density) { menuWidth.toPx() }
        val edgePadPx = with(density) { 12.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
        val anchor = anchorBoundsInRoot ?: Rect(
            left = containerWidthPx - edgePadPx - 44f,
            top = with(density) { 80.dp.toPx() },
            right = containerWidthPx - edgePadPx,
            bottom = with(density) { 124.dp.toPx() },
        )

        var measuredMenuHeightPx by remember { mutableStateOf(with(density) { 160.dp.toPx() }) }
        val desiredX = if (alignEndToAnchor) {
            anchor.right - menuWidthPx
        } else {
            anchor.left
        }
        val clampedX = desiredX.coerceIn(edgePadPx, (containerWidthPx - menuWidthPx - edgePadPx).coerceAtLeast(edgePadPx))

        val canPlaceAbove = anchor.top - gapPx - measuredMenuHeightPx > edgePadPx
        val canPlaceBelow = anchor.bottom + gapPx + measuredMenuHeightPx < containerHeightPx - edgePadPx
        val placeAbove = when {
            preferAbove && canPlaceAbove -> true
            !preferAbove && canPlaceBelow -> false
            canPlaceAbove -> true
            else -> false
        }

        val desiredY = if (placeAbove) {
            anchor.top - gapPx - measuredMenuHeightPx
        } else {
            anchor.bottom + gapPx
        }
        val clampedY = desiredY.coerceIn(
            with(density) { safePadding.calculateTopPadding().toPx() + 8f },
            (containerHeightPx - measuredMenuHeightPx - edgePadPx).coerceAtLeast(edgePadPx),
        )

        val originX = ((anchor.center.x - clampedX) / menuWidthPx).coerceIn(0.08f, 0.92f)
        val originY = if (placeAbove) 0.96f else 0.04f

        val p = progress.value.coerceIn(0f, 1.08f)
        val scrimAlpha = (0.14f * p).coerceIn(0f, 0.2f)

        // Tap-outside scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (isDark) scrimAlpha * 1.4f else scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        val menuShape = ContinuousCornerShape(24.dp)
        Column(
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .width(menuWidth)
                .liquidGlassSurface(
                    shape = menuShape,
                    preset = GlassPreset.MenuPopover,
                    backdrop = effectiveBackdrop,
                    shadowEnabled = true,
                    layerBlock = {
                        val s = 0.36f + 0.64f * p
                        scaleX = s
                        scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(originX, originY)
                    },
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(vertical = 6.dp),
        ) {
            androidx.compose.ui.layout.Layout(
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        content()
                    }
                },
            ) { measurables, constraints ->
                val placeable = measurables.first().measure(constraints)
                measuredMenuHeightPx = placeable.height.toFloat()
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
        }
    }
}

@Composable
fun FluidPopoverMenuRow(
    text: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val motion = rememberFluidMotionState()
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val tint = when {
        !enabled -> glassSecondaryContentColor().copy(alpha = 0.45f)
        destructive -> if (isDark) Color(0xFFFF6B6B) else Color(0xFFC62828)
        selected && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
        selected -> readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
        else -> glassPrimaryContentColor()
    }
    val subtitleColor = if (selected && reduceTransparency) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.80f)
    } else {
        glassSecondaryContentColor()
    }
    val rowBg = if (selected) {
        if (reduceTransparency) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
        }
    } else {
        Color.Transparent
    }
    val rowShape = ContinuousCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .fluidTouchPhysics(enabled = enabled, state = motion, pressedScale = 0.96f, maxDragPullPx = 6f)
            .clip(rowShape)
            .drawBehind {
                if (rowBg != Color.Transparent) {
                    drawRect(color = rowBg, size = size)
                }
                drawFluidTouchSpotlight(
                    pressProgress = motion.pressProgress.value,
                    touchFractionX = motion.touchFractionX,
                    touchFractionY = motion.touchFractionY,
                    shape = rowShape,
                    isDark = isDark,
                    isAccentSurface = selected,
                )
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = tint,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                )
            }
        }
    }
}

@Composable
fun FluidPopoverMenuDivider() {
    val isDark = LocalGlassDarkTheme.current
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 0.6.dp,
        color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
    )
}

/**
 * Full-screen Kinetic Aero modal dialog rendered either in-tree over [LocalOverlayBackdrop] OR
 * inside a full-screen edge-to-edge window-level [Dialog] ([windowOverlay] = true) so modals
 * launched from padded sub-steps still dim the entire screen edge-to-edge behind status bars,
 * headers, and bottom actions.
 */
@Composable
fun FluidGlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current,
    maxWidth: Dp = 420.dp,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    windowOverlay: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(22.dp),
    title: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable (Backdrop?) -> Unit = { resolvedBackdrop ->
        val scope = rememberCoroutineScope()
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, FluidSprings.sheetExpand())
        }

        val isDark = LocalGlassDarkTheme.current
        val effectiveBackdrop = if (LocalReduceTransparency.current) null else resolvedBackdrop
        val density = LocalDensity.current
        val slideOffsetPx = with(density) { 26.dp.toPx() }
        val p = progress.value.coerceIn(0f, 1.06f)
        val dialogShape = ContinuousCornerShape(30.dp)

        fun dismissAnimated() {
            scope.launch {
                progress.animateTo(0f, FluidSprings.menuClose())
                onDismissRequest()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(60f)
                .background(
                    Color.Black.copy(
                        alpha = (if (isDark) 0.56f else 0.34f) * p.coerceIn(0f, 1f),
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (dismissOnClickOutside) dismissAnimated() },
                )
                .then(if (windowOverlay) Modifier.systemBarsPadding() else Modifier)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .heightIn(max = 660.dp)
                    .liquidGlassSurface(
                        shape = dialogShape,
                        preset = GlassPreset.Dialog,
                        backdrop = effectiveBackdrop,
                        shadowEnabled = true,
                        layerBlock = {
                            val s = 0.88f + 0.12f * p
                            scaleX = s
                            scaleY = s
                            translationY = slideOffsetPx * (1f - p)
                            alpha = p.coerceIn(0f, 1f)
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (title != null) {
                    title()
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                if (actions != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        actions()
                    }
                }
            }
        }
    }

    if (windowOverlay) {
        Dialog(
            onDismissRequest = { if (dismissOnBackPress) onDismissRequest() },
            properties = DialogProperties(
                dismissOnBackPress = dismissOnBackPress,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.let { w ->
                    w.setDimAmount(0f)
                    w.setBackgroundDrawableResource(android.R.color.transparent)
                    w.setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    WindowCompat.setDecorFitsSystemWindows(w, false)
                }
            }
            // Separate Dialog windows cannot sample the host Activity's GraphicsLayer backdrop;
            // null backdrop keeps GlassPreset.Dialog crisp and artifact-free over the full-screen scrim.
            body(null)
        }
    } else {
        BackHandler(enabled = dismissOnBackPress) {
            onDismissRequest()
        }
        body(backdrop)
    }
}

/**
 * Floating translucent Liquid Glass bottom sheet with interactive drag-to-dismiss spring physics.
 */
@Composable
fun FluidGlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler {
        onDismissRequest()
    }

    val isDark = LocalGlassDarkTheme.current
    val effectiveBackdrop = if (LocalReduceTransparency.current) null else backdrop
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val openProgress = remember { Animatable(0f) }
    val dragOffsetPx = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        openProgress.animateTo(1f, FluidSprings.sheetExpand())
    }

    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val initialSlidePx = with(density) { 260.dp.toPx() }
    val p = openProgress.value.coerceIn(0f, 1.05f)
    val sheetShape = ContinuousCornerShape(
        topStart = 32.dp,
        topEnd = 32.dp,
        bottomStart = 26.dp,
        bottomEnd = 26.dp,
    )

    fun closeWithSpring() {
        scope.launch {
            openProgress.animateTo(0f, FluidSprings.menuClose())
            onDismissRequest()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(55f)
            .background(
                Color.Black.copy(alpha = (if (isDark) 0.52f else 0.30f) * p.coerceIn(0f, 1f)),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = ::closeWithSpring,
            )
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .heightIn(max = 620.dp)
                .graphicsLayer {
                    val baseY = initialSlidePx * (1f - p)
                    translationY = baseY + dragOffsetPx.value.coerceAtLeast(0f)
                    alpha = p.coerceIn(0f, 1f)
                }
                .liquidGlassSurface(
                    shape = sheetShape,
                    preset = GlassPreset.MenuPopover,
                    backdrop = effectiveBackdrop,
                    shadowEnabled = true,
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (dragOffsetPx.value + dragAmount).coerceAtLeast(-24f)
                            scope.launch { dragOffsetPx.snapTo(next) }
                        },
                        onDragEnd = {
                            if (dragOffsetPx.value > dismissThresholdPx) {
                                closeWithSpring()
                            } else {
                                scope.launch { dragOffsetPx.animateTo(0f, FluidSprings.sheetExpand()) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { dragOffsetPx.animateTo(0f, FluidSprings.sheetExpand()) }
                        },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Luminous pill drag indicator
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.18f),
                    ),
            )
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = glassPrimaryContentColor(),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}
