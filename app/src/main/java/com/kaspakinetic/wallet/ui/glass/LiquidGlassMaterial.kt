package com.kaspakinetic.wallet.ui.glass

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.BackdropEffectScope
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.LayerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.drawBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.drawPlainBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.effects.blur
import com.kaspakinetic.wallet.ui.glass.backdrop.effects.colorControls
import com.kaspakinetic.wallet.ui.glass.backdrop.effects.lens
import com.kaspakinetic.wallet.ui.glass.backdrop.effects.progressiveVerticalBlur
import com.kaspakinetic.wallet.ui.glass.backdrop.highlight.Highlight
import com.kaspakinetic.wallet.ui.glass.backdrop.highlight.HighlightElement
import com.kaspakinetic.wallet.ui.glass.backdrop.highlight.HighlightStyle
import com.kaspakinetic.wallet.ui.glass.backdrop.internal.ShapeProvider
import com.kaspakinetic.wallet.ui.glass.backdrop.isRenderEffectSupported
import com.kaspakinetic.wallet.ui.glass.backdrop.isRuntimeShaderSupported
import com.kaspakinetic.wallet.ui.glass.backdrop.shadow.InnerShadow
import com.kaspakinetic.wallet.ui.glass.backdrop.shadow.Shadow
import kotlin.math.ceil
import kotlin.math.max

/**
 * Tier 1 backdrop (wallpaper / animated GIF / Living Flow ambient aurora behind scrolling content).
 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Tier 2 backdrop (Tier 1 + main scrollable viewport), sampled by floating chrome, top bars,
 * bottom composer pills, popovers, sheets, drawer, and dialogs so real scrolling text refracts
 * underneath.
 */
val LocalOverlayBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Optional exported glass surface layer for sliding selection pills over existing glass bars.
 */
val LocalGlassExportLayer = compositionLocalOf<LayerBackdrop?> { null }

val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }
val LocalGlassDarkTheme = compositionLocalOf { false }

/**
 * Disables glass translucency and backdrop capture across the app in favor of crisp opaque
 * high-contrast surfaces while keeping all elastic spring physics, finger glow, shadows, and haptics.
 */
val LocalReduceTransparency = compositionLocalOf { false }

/**
 * Controls whether the progressive vertical edge blur band ([ProgressiveGlassEdgeBand]) behind
 * floating top/bottom bars is disabled.
 *
 * Kinetic Aero specification rule: **Progressive blur is OFF by default** (`true` here means disabled
 * by default unless the user turns Progressive Blur ON during Welcome Step 2 or in Settings).
 */
val LocalDisableProgressiveBlur = compositionLocalOf { true }

enum class GlassPreset {
    /** Floating interactive pills, circular icon buttons, tiles, quick-action capsules. */
    Control,
    /** Floating top/bottom chrome capsules and navigation bars — maximum refraction + chromatic edge. */
    FloatingBar,
    /** Content cards, grouped surfaces, sheets — readable frost + glass rim. */
    Card,
    /** Emphasized content card or primary container surface — tinted liquid glass with crisp border. */
    HighlightSurface,
    /** High-readability frosted reader surface so long prose, code, tables, and dense typography stay sharp. */
    ReaderSurface,
    /** Floating origin-growing popover menus (`+`, overflow menus, inline pickers). */
    MenuPopover,
    /** Sliding navigation drawer — optimized vibrant blurred sheet without per-frame lens shaders. */
    Drawer,
    /** Modal dialogs, confirmations, and focused overlay forms. */
    Dialog,
    /** Accent-tinted active pill / primary CTA. */
    AccentPill,
}

private data class ResolvedGlassSpec(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val shaderHighlight: Boolean = true,
    val resolutionScale: Float,
    val surfaceAlpha: Float,
    val highlightAlpha: Float,
    val shadowAlpha: Float,
    val innerShadowAlpha: Float,
    val vibrancy: Float,
)

private fun GlassPreset.resolveSpec(isDark: Boolean): ResolvedGlassSpec = when (this) {
    GlassPreset.Control -> ResolvedGlassSpec(
        blurDp = 12f,
        refractionHeightDp = 22f,
        refractionAmountDp = 18f,
        depthEffect = true,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.36f,
        surfaceAlpha = if (isDark) 0.36f else 0.48f,
        highlightAlpha = if (isDark) 0.72f else 0.92f,
        shadowAlpha = if (isDark) 0.24f else 0.11f,
        innerShadowAlpha = if (isDark) 0.16f else 0.08f,
        vibrancy = 1.45f,
    )
    GlassPreset.FloatingBar -> ResolvedGlassSpec(
        blurDp = 14f,
        refractionHeightDp = 28f,
        refractionAmountDp = 24f,
        depthEffect = true,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.40f,
        surfaceAlpha = if (isDark) 0.38f else 0.46f,
        highlightAlpha = if (isDark) 0.85f else 0.95f,
        shadowAlpha = if (isDark) 0.28f else 0.14f,
        innerShadowAlpha = if (isDark) 0.18f else 0.09f,
        vibrancy = 1.50f,
    )
    GlassPreset.Card -> ResolvedGlassSpec(
        blurDp = 14f,
        refractionHeightDp = 0f,
        refractionAmountDp = 0f,
        depthEffect = false,
        chromaticAberration = false,
        shaderHighlight = false,
        resolutionScale = 0.24f,
        surfaceAlpha = if (isDark) 0.56f else 0.68f,
        highlightAlpha = if (isDark) 0.55f else 0.82f,
        shadowAlpha = 0f,
        innerShadowAlpha = 0f,
        vibrancy = 1.30f,
    )
    GlassPreset.HighlightSurface -> ResolvedGlassSpec(
        blurDp = 14f,
        refractionHeightDp = 18f,
        refractionAmountDp = 14f,
        depthEffect = true,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.32f,
        surfaceAlpha = if (isDark) 0.58f else 0.72f,
        highlightAlpha = if (isDark) 0.70f else 0.88f,
        shadowAlpha = if (isDark) 0.20f else 0.09f,
        innerShadowAlpha = if (isDark) 0.14f else 0.06f,
        vibrancy = 1.40f,
    )
    GlassPreset.ReaderSurface -> ResolvedGlassSpec(
        blurDp = 16f,
        refractionHeightDp = 0f,
        refractionAmountDp = 0f,
        depthEffect = false,
        chromaticAberration = false,
        shaderHighlight = false,
        resolutionScale = 0.24f,
        surfaceAlpha = if (isDark) 0.68f else 0.78f,
        highlightAlpha = if (isDark) 0.50f else 0.78f,
        shadowAlpha = 0f,
        innerShadowAlpha = 0f,
        vibrancy = 1.25f,
    )
    GlassPreset.MenuPopover -> ResolvedGlassSpec(
        blurDp = 22f,
        refractionHeightDp = 28f,
        refractionAmountDp = 24f,
        depthEffect = true,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.42f,
        surfaceAlpha = if (isDark) 0.60f else 0.72f,
        highlightAlpha = if (isDark) 0.88f else 0.96f,
        shadowAlpha = if (isDark) 0.38f else 0.18f,
        innerShadowAlpha = if (isDark) 0.20f else 0.09f,
        vibrancy = 1.52f,
    )
    GlassPreset.Drawer -> ResolvedGlassSpec(
        blurDp = 18f,
        refractionHeightDp = 0f,
        refractionAmountDp = 0f,
        depthEffect = false,
        chromaticAberration = false,
        shaderHighlight = false,
        resolutionScale = 0.25f,
        surfaceAlpha = if (isDark) 0.70f else 0.78f,
        highlightAlpha = if (isDark) 0.72f else 0.90f,
        shadowAlpha = if (isDark) 0.30f else 0.14f,
        innerShadowAlpha = 0f,
        vibrancy = 1.42f,
    )
    GlassPreset.Dialog -> ResolvedGlassSpec(
        blurDp = 24f,
        refractionHeightDp = 22f,
        refractionAmountDp = 18f,
        depthEffect = false,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.36f,
        surfaceAlpha = if (isDark) 0.72f else 0.82f,
        highlightAlpha = if (isDark) 0.80f else 0.92f,
        shadowAlpha = if (isDark) 0.42f else 0.20f,
        innerShadowAlpha = if (isDark) 0.16f else 0.08f,
        vibrancy = 1.40f,
    )
    GlassPreset.AccentPill -> ResolvedGlassSpec(
        blurDp = 10f,
        refractionHeightDp = 20f,
        refractionAmountDp = 16f,
        depthEffect = true,
        chromaticAberration = true,
        shaderHighlight = true,
        resolutionScale = 0.36f,
        surfaceAlpha = if (isDark) 0.78f else 0.84f,
        highlightAlpha = 0.92f,
        shadowAlpha = if (isDark) 0.26f else 0.15f,
        innerShadowAlpha = 0.14f,
        vibrancy = 1.45f,
    )
}

@Composable
fun glassPrimaryContentColor(): Color {
    val reduceTransparency = LocalReduceTransparency.current
    if (reduceTransparency) {
        return MaterialTheme.colorScheme.onSurface
    }
    val isDark = LocalGlassDarkTheme.current
    return if (isDark) Color(0xFFF5F7FB) else Color(0xFF14171F)
}

@Composable
fun glassSecondaryContentColor(): Color {
    val reduceTransparency = LocalReduceTransparency.current
    if (reduceTransparency) {
        return MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isDark = LocalGlassDarkTheme.current
    return if (isDark) Color(0xFFA9B2C3) else Color(0xFF565E6E)
}

@Composable
fun defaultGlassSurfaceTint(preset: GlassPreset, isDark: Boolean = LocalGlassDarkTheme.current): Color {
    val surface = MaterialTheme.colorScheme.surface
    return when (preset) {
        GlassPreset.AccentPill -> MaterialTheme.colorScheme.primary
        GlassPreset.HighlightSurface -> MaterialTheme.colorScheme.primaryContainer
        GlassPreset.Control,
        GlassPreset.FloatingBar,
        -> if (isDark) Color(0xFF171A23) else Color(0xFFFBFCFE)
        GlassPreset.MenuPopover,
        GlassPreset.Drawer,
        GlassPreset.Dialog,
        -> if (isDark) Color(0xFF191D28) else Color(0xFFFCFDFF)
        GlassPreset.Card,
        GlassPreset.ReaderSurface,
        -> surface
    }
}

@Composable
private fun solidOpaqueSurfaceColor(
    preset: GlassPreset,
    surfaceTint: Color?,
): Color {
    val scheme = MaterialTheme.colorScheme
    val baseContainer = when (preset) {
        GlassPreset.AccentPill -> scheme.primary
        GlassPreset.HighlightSurface -> scheme.primaryContainer
        GlassPreset.FloatingBar,
        GlassPreset.MenuPopover,
        GlassPreset.Drawer,
        GlassPreset.Dialog,
        -> scheme.surfaceContainerHigh
        GlassPreset.Control -> scheme.surfaceContainer
        GlassPreset.Card,
        GlassPreset.ReaderSurface,
        -> scheme.surfaceContainerLow
    }
    if (surfaceTint == null) return baseContainer.copy(alpha = 1f)
    return if (surfaceTint.alpha >= 0.999f) {
        surfaceTint.copy(alpha = 1f)
    } else {
        surfaceTint.compositeOver(baseContainer).copy(alpha = 1f)
    }
}

/**
 * Draws an iOS 26 / ColorOS 17 finger-tracking radial spotlight bloom + directional rim catchlight
 * clipped inside [shape] and driven strictly in the draw phase for zero recomposition cost.
 */
fun DrawScope.drawFluidTouchSpotlight(
    pressProgress: Float,
    touchFractionX: Float,
    touchFractionY: Float,
    shape: Shape,
    isDark: Boolean,
    isAccentSurface: Boolean,
    cachedOutline: Outline? = null,
    cachedOutlinePath: Path? = null,
) {
    val p = pressProgress.coerceIn(0f, 1.15f)
    if (p <= 0.005f || !size.width.isFinite() || !size.height.isFinite() || size.width < 1f || size.height < 1f) {
        return
    }
    val cx = (size.width * touchFractionX).coerceIn(-size.width * 0.15f, size.width * 1.15f)
    val cy = (size.height * touchFractionY).coerceIn(-size.height * 0.25f, size.height * 1.25f)
    val center = Offset(cx, cy)
    val bloomRadius = max(size.width * 0.66f, size.height * 2.25f).coerceAtLeast(1f)

    val colors = when {
        isAccentSurface -> listOf(
            Color(0xFFFFFFFF).copy(alpha = (0.40f * p).coerceIn(0f, 0.55f)),
            Color(0xFFDCF6FF).copy(alpha = (0.22f * p).coerceIn(0f, 0.35f)),
            Color.White.copy(alpha = (0.06f * p).coerceIn(0f, 0.12f)),
            Color.Transparent,
        )
        isDark -> listOf(
            Color.White.copy(alpha = (0.25f * p).coerceIn(0f, 0.34f)),
            Color(0xFFD6E8FF).copy(alpha = (0.10f * p).coerceIn(0f, 0.16f)),
            Color.Transparent,
        )
        else -> listOf(
            Color.White.copy(alpha = (0.64f * p).coerceIn(0f, 0.78f)),
            Color(0xFFEAF3FF).copy(alpha = (0.26f * p).coerceIn(0f, 0.34f)),
            Color.Transparent,
        )
    }
    drawRect(
        brush = Brush.radialGradient(
            colors = colors,
            center = center,
            radius = bloomRadius,
        ),
        size = size,
    )

    // Directional rim catchlight focused around the finger edge
    val outline = cachedOutline ?: shape.createOutline(size, layoutDirection, this)
    val rimRadius = max(size.maxDimension * 0.56f, 36f)
    val rimBrush = Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = (if (isAccentSurface || !isDark) 0.58f else 0.34f) * p),
            Color.Transparent,
        ),
        center = center,
        radius = rimRadius,
    )
    drawOutline(
        outline = outline,
        brush = rimBrush,
        style = Stroke(width = 1.6.dp.toPx()),
    )
}

/**
 * Adds a subtle, Gaussian-feathered rounded ambient drop shadow behind a pressed button, driven
 * purely by draw-phase [FluidMotionState.pressProgress] with a cached [GraphicsLayer] so holding
 * a button pays zero offscreen FBO re-recording per frame.
 */
fun Modifier.fluidPressShadow(
    shape: Shape,
    motion: FluidMotionState,
    shadowColor: Color = Color.Black,
    maxAlpha: Float = 0.22f,
    blurRadius: Dp = 11.dp,
    offsetY: Dp = 0.dp,
): Modifier = this then FluidPressShadowElement(
    shape = shape,
    motion = motion,
    shadowColor = shadowColor,
    maxAlpha = maxAlpha,
    blurRadius = blurRadius,
    offsetY = offsetY,
)

private class FluidPressShadowElement(
    val shape: Shape,
    val motion: FluidMotionState,
    val shadowColor: Color,
    val maxAlpha: Float,
    val blurRadius: Dp,
    val offsetY: Dp,
) : ModifierNodeElement<FluidPressShadowNode>() {

    override fun create(): FluidPressShadowNode = FluidPressShadowNode(
        shape = shape,
        motion = motion,
        shadowColor = shadowColor,
        maxAlpha = maxAlpha,
        blurRadius = blurRadius,
        offsetY = offsetY,
    )

    override fun update(node: FluidPressShadowNode) {
        node.shape = shape
        node.motion = motion
        node.shadowColor = shadowColor
        node.maxAlpha = maxAlpha
        node.blurRadius = blurRadius
        node.offsetY = offsetY
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fluidPressShadow"
        properties["shape"] = shape
        properties["maxAlpha"] = maxAlpha
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FluidPressShadowElement) return false
        return shape == other.shape &&
            motion === other.motion &&
            shadowColor == other.shadowColor &&
            maxAlpha == other.maxAlpha &&
            blurRadius == other.blurRadius &&
            offsetY == other.offsetY
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + motion.hashCode()
        result = 31 * result + shadowColor.hashCode()
        result = 31 * result + maxAlpha.hashCode()
        result = 31 * result + blurRadius.hashCode()
        result = 31 * result + offsetY.hashCode()
        return result
    }
}

private class FluidPressShadowNode(
    var shape: Shape,
    var motion: FluidMotionState,
    var shadowColor: Color,
    var maxAlpha: Float,
    var blurRadius: Dp,
    var offsetY: Dp,
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null
    private val paint = Paint()

    private var cachedSize: Size = Size.Unspecified
    private var cachedDensity: Float = Float.NaN
    private var cachedLayoutDirection: LayoutDirection? = null
    private var cachedShape: Shape? = null
    private var cachedBlurRadius: Dp = Dp.Unspecified
    private var cachedColor: Color = Color.Unspecified

    override fun ContentDrawScope.draw() {
        val press = motion.pressProgress.value.coerceIn(0f, 1.15f)
        if (press <= 0.005f ||
            !size.width.isFinite() ||
            !size.height.isFinite() ||
            size.width < 1f ||
            size.height < 1f
        ) {
            drawContent()
            return
        }

        val layer = shadowLayer
        if (layer != null) {
            val radiusPx = blurRadius.toPx().coerceAtLeast(1f)
            val needsRecord = cachedSize != size ||
                cachedDensity != density ||
                cachedLayoutDirection != layoutDirection ||
                cachedShape != shape ||
                cachedBlurRadius != blurRadius ||
                cachedColor != shadowColor

            if (needsRecord) {
                val densityObj: Density = this
                val pad = radiusPx * 2f
                val shadowIntSize = IntSize(
                    ceil(size.width + pad * 2f).toInt().coerceAtLeast(1),
                    ceil(size.height + pad * 2f).toInt().coerceAtLeast(1),
                )
                val outline = shape.createOutline(size, layoutDirection, densityObj)
                paint.color = shadowColor
                paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)

                layer.record(shadowIntSize) {
                    translate(pad, pad) {
                        val canvas = drawContext.canvas
                        canvas.drawOutline(outline, paint)
                        canvas.drawOutline(outline, PressShadowCutoutPaint)
                    }
                }

                cachedSize = size
                cachedDensity = density
                cachedLayoutDirection = layoutDirection
                cachedShape = shape
                cachedBlurRadius = blurRadius
                cachedColor = shadowColor
            }

            layer.alpha = (press * maxAlpha).coerceIn(0f, 1f)
            val pad = radiusPx * 2f
            val liftPx = offsetY.toPx() * press
            val haloScale = lerp(0.985f, 1.018f, press)
            val centerPivot = Offset(size.width * 0.5f, size.height * 0.5f)
            scale(scaleX = haloScale, scaleY = haloScale, pivot = centerPivot) {
                translate(-pad, -pad + liftPx) {
                    drawLayer(layer)
                }
            }
        }

        drawContent()
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer = graphicsContext.createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
        }
        cachedSize = Size.Unspecified
        cachedDensity = Float.NaN
        cachedLayoutDirection = null
        cachedShape = null
        cachedBlurRadius = Dp.Unspecified
        cachedColor = Color.Unspecified
    }
}

private val PressShadowCutoutPaint = Paint().apply {
    blendMode = BlendMode.Clear
}

/**
 * Renders a genuine dynamic Liquid Glass surface using AGSL RuntimeShader lens refraction,
 * 7-band chromatic aberration, Gaussian backdrop blur, vibrancy color controls, directional
 * specular highlight rim, inner edge depth shadow, ambient drop shadow, subtle held press shadow,
 * and a live finger-tracking spotlight glow.
 *
 * When [motionState] is supplied, press refraction boost, subtle drop shadow, and finger-tracking
 * spotlight glow are observed strictly in the draw/effect scopes — zero Compose UI recompositions
 * during 120Hz touch springs.
 *
 * When [LocalReduceTransparency] is true, switches cleanly to crisp 100% opaque high-contrast
 * surfaces with zero backdrop capture/blur while preserving finger glow, press shadows, and all
 * touch spring physics.
 */
@Composable
fun Modifier.liquidGlassSurface(
    shape: CornerBasedShape,
    preset: GlassPreset = GlassPreset.Control,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    surfaceTint: Color? = null,
    pressProgress: Float = 0f,
    motionState: FluidMotionState? = null,
    shadowEnabled: Boolean = true,
    pressShadowEnabled: Boolean = false,
    useBackdropCapture: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val spec = remember(preset, isDark) { preset.resolveSpec(isDark) }
    val resolvedTint = surfaceTint ?: defaultGlassSurfaceTint(preset, isDark)
    val rimHighlightColor = Color.White
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    val isAccentSurface = preset == GlassPreset.AccentPill || (surfaceTint != null && !surfaceTint.isLightSurface())

    val pressShadowModifier = if (pressShadowEnabled && motionState != null) {
        val shadowTint = if (isAccentSurface && !isDark) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f).compositeOver(Color.Black)
        } else {
            Color.Black
        }
        Modifier.fluidPressShadow(
            shape = shape,
            motion = motionState,
            shadowColor = shadowTint,
            maxAlpha = if (isDark) 0.38f else 0.17f,
            blurRadius = 11.dp,
            offsetY = 0.dp,
        )
    } else {
        Modifier
    }

    if (reduceTransparency) {
        val solidColor = solidOpaqueSurfaceColor(preset = preset, surfaceTint = surfaceTint)
        val borderColor = when {
            preset == GlassPreset.AccentPill -> Color.White.copy(alpha = if (isDark) 0.28f else 0.36f)
            isDark -> MaterialTheme.colorScheme.outlineVariant
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        }
        val surfaceDrawBlock = remember(solidColor, motionState, pressProgress, shape, isDark, isAccentSurface) {
            val block: DrawScope.() -> Unit = {
                drawRect(color = solidColor, size = size)
                val p = motionState?.pressProgress?.value ?: pressProgress
                val tx = motionState?.touchFractionX ?: 0.5f
                val ty = motionState?.touchFractionY ?: 0.5f
                drawFluidTouchSpotlight(
                    pressProgress = p,
                    touchFractionX = tx,
                    touchFractionY = ty,
                    shape = shape,
                    isDark = isDark,
                    isAccentSurface = isAccentSurface,
                )
            }
            block
        }
        val solidElevationShadow = if (!pressShadowEnabled && shadowEnabled &&
            (preset == GlassPreset.FloatingBar || preset == GlassPreset.MenuPopover || preset == GlassPreset.Dialog)
        ) {
            Modifier.shadow(
                elevation = if (preset == GlassPreset.FloatingBar) 4.dp else 10.dp,
                shape = RoundedCornerShape(
                    topStart = shape.topStart,
                    topEnd = shape.topEnd,
                    bottomEnd = shape.bottomEnd,
                    bottomStart = shape.bottomStart,
                ),
                clip = false,
            )
        } else {
            Modifier
        }
        return then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
            .then(pressShadowModifier)
            .then(solidElevationShadow)
            .clip(shape)
            .drawBehind(surfaceDrawBlock)
            .border(width = 0.9.dp, color = borderColor, shape = shape)
    }

    if (!LocalLiquidGlassEnabled.current ||
        !useBackdropCapture ||
        backdrop == null ||
        !isRenderEffectSupported()
    ) {
        val fallbackTint = resolvedTint.copy(
            alpha = (spec.surfaceAlpha + if (isRenderEffectSupported()) 0.14f else 0.24f)
                .coerceAtMost(0.95f),
        )
        val borderBrush = remember(isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.28f else 0.85f),
                    Color.White.copy(alpha = if (isDark) 0.08f else 0.26f),
                    Color.White.copy(alpha = if (isDark) 0.16f else 0.55f),
                ),
            )
        }
        val fallbackSurfaceDraw = remember(fallbackTint, motionState, pressProgress, shape, isDark, isAccentSurface) {
            val block: DrawScope.() -> Unit = {
                drawRect(color = fallbackTint, size = size)
                val p = motionState?.pressProgress?.value ?: pressProgress
                val tx = motionState?.touchFractionX ?: 0.5f
                val ty = motionState?.touchFractionY ?: 0.5f
                drawFluidTouchSpotlight(
                    pressProgress = p,
                    touchFractionX = tx,
                    touchFractionY = ty,
                    shape = shape,
                    isDark = isDark,
                    isAccentSurface = isAccentSurface,
                )
            }
            block
        }
        val highlightStyle = remember(isDark, spec.shaderHighlight) {
            if (spec.shaderHighlight) {
                HighlightStyle.Default(
                    color = Color.White.copy(alpha = if (isDark) 0.55f else 0.82f),
                )
            } else {
                HighlightStyle.Plain(
                    color = Color.White.copy(alpha = if (isDark) 0.42f else 0.72f),
                )
            }
        }
        val highlightProvider = remember(spec, motionState, pressProgress, highlightStyle) {
            {
                val p = (motionState?.pressProgress?.value ?: pressProgress).coerceIn(0f, 1f)
                Highlight(
                    width = 0.85.dp,
                    blurRadius = 0.4.dp,
                    alpha = (spec.highlightAlpha + p * 0.18f).coerceAtMost(1f),
                    style = highlightStyle,
                )
            }
        }
        return then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
            .then(pressShadowModifier)
            .clip(shape)
            .drawBehind(fallbackSurfaceDraw)
            .then(
                if (spec.shaderHighlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HighlightElement(
                        shapeProvider = shapeProvider,
                        highlight = highlightProvider,
                    )
                } else {
                    Modifier.border(width = 0.85.dp, brush = borderBrush, shape = shape)
                },
            )
    }

    val plainRimBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDark) 0.30f else 0.88f),
                Color.White.copy(alpha = if (isDark) 0.12f else 0.36f),
            ),
        )
    }

    val density = LocalDensity.current
    val scale = spec.resolutionScale
    val blurPx = with(density) { spec.blurDp.dp.toPx() } * scale
    val baseLensHeightPx = with(density) { spec.refractionHeightDp.dp.toPx() } * scale
    val baseLensAmountPx = with(density) { spec.refractionAmountDp.dp.toPx() } * scale

    val shapeLambda = remember(shape) { { shape } }
    val effectsLambda = remember(spec, blurPx, baseLensHeightPx, baseLensAmountPx, motionState, pressProgress) {
        val block: BackdropEffectScope.() -> Unit = {
            colorControls(saturation = spec.vibrancy)
            blur(blurPx, edgeTreatment = TileMode.Clamp)
            if (isRuntimeShaderSupported() &&
                spec.refractionHeightDp > 0f &&
                spec.refractionAmountDp > 0f
            ) {
                val p = (motionState?.pressProgress?.value ?: pressProgress).coerceIn(0f, 1f)
                val pressBoost = 1f + p * 0.32f
                lens(
                    refractionHeight = baseLensHeightPx * pressBoost,
                    refractionAmount = baseLensAmountPx * pressBoost,
                    depthEffect = spec.depthEffect,
                    chromaticAberration = spec.chromaticAberration,
                )
            }
        }
        block
    }

    val highlightStyle = remember(isDark) {
        HighlightStyle.Default(
            color = rimHighlightColor.copy(alpha = if (isDark) 0.62f else 0.86f),
            angle = 45f,
            falloff = 1.15f,
        )
    }
    val highlightLambda: (() -> Highlight?)? = remember(spec, highlightStyle, motionState, pressProgress) {
        if (spec.shaderHighlight) {
            {
                val p = (motionState?.pressProgress?.value ?: pressProgress).coerceIn(0f, 1f)
                Highlight(
                    width = 0.9.dp,
                    blurRadius = 0.4.dp,
                    alpha = (spec.highlightAlpha + p * 0.2f).coerceIn(0f, 1f),
                    style = highlightStyle,
                )
            }
        } else {
            null
        }
    }

    val shadowLambda = remember(shadowEnabled, spec, preset) {
        if (shadowEnabled && spec.shadowAlpha > 0f) {
            val s = Shadow(
                radius = when (preset) {
                    GlassPreset.MenuPopover,
                    GlassPreset.Dialog,
                    -> 26.dp
                    GlassPreset.Drawer -> 18.dp
                    else -> 16.dp
                },
                offset = DpOffset(0.dp, 6.dp),
                color = Color.Black.copy(alpha = spec.shadowAlpha),
                alpha = 1f,
            )
            val provider: () -> Shadow? = { s }
            provider
        } else {
            null
        }
    }

    val innerShadowLambda = remember(spec) {
        if (spec.innerShadowAlpha > 0f) {
            val s = InnerShadow(
                radius = 8.dp,
                offset = DpOffset(0.dp, 2.dp),
                color = Color.White.copy(alpha = spec.innerShadowAlpha),
                alpha = 1f,
            )
            val provider: () -> InnerShadow? = { s }
            provider
        } else {
            null
        }
    }

    val onDrawSurfaceLambda = remember(resolvedTint, spec, motionState, pressProgress, shape, isDark, isAccentSurface) {
        val block: DrawScope.() -> Unit = {
            val p = (motionState?.pressProgress?.value ?: pressProgress).coerceIn(0f, 1.15f)
            val fillColor = resolvedTint.copy(
                alpha = (spec.surfaceAlpha + p * 0.08f).coerceIn(0.12f, 0.94f),
            )
            drawRect(color = fillColor, size = size)
            if (motionState != null) {
                drawFluidTouchSpotlight(
                    pressProgress = p,
                    touchFractionX = motionState.touchFractionX,
                    touchFractionY = motionState.touchFractionY,
                    shape = shape,
                    isDark = isDark,
                    isAccentSurface = isAccentSurface,
                )
            } else if (p > 0.01f) {
                val shimmerAlpha = if (isDark) 0.08f * p else 0.16f * p
                drawRect(color = Color.White.copy(alpha = shimmerAlpha), size = size)
            }
        }
        block
    }

    return then(pressShadowModifier)
        .drawBackdrop(
            backdrop = backdrop,
            shape = shapeLambda,
            effects = effectsLambda,
            highlight = highlightLambda,
            shadow = shadowLambda,
            innerShadow = innerShadowLambda,
            onDrawSurface = onDrawSurfaceLambda,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            backdropScale = scale,
        )
        .then(
            if (!spec.shaderHighlight) {
                Modifier.border(width = 0.85.dp, brush = plainRimBrush, shape = shape)
            } else {
                Modifier
            },
        )
}

/**
 * Progressive vertical backdrop blur & soft gradient feather for top/bottom floating chrome bands.
 *
 * Runs the progressive AGSL blur at `0.42x` downsampled resolution for low GPU overhead while
 * softening scrolling content behind floating glass pills when enabled.
 *
 * Strict Kinetic Aero rules:
 * 1. **OFF by default** ([LocalDisableProgressiveBlur] defaults to `true`).
 * 2. Returns immediately (`0` draw calls) whenever [LocalReduceTransparency] OR
 *    [LocalDisableProgressiveBlur] is `true` — never render a solid-to-transparent gradient smear
 *    when transparency is disabled!
 */
@Composable
fun ProgressiveGlassEdgeBand(
    backdrop: Backdrop?,
    fromTop: Boolean,
    modifier: Modifier = Modifier,
    maxBlurRadius: Dp = 18.dp,
    tintColor: Color = MaterialTheme.colorScheme.background,
    tintMaxAlpha: Float = 0.72f,
) {
    val reduceTransparency = LocalReduceTransparency.current
    val disableProgressiveBlur = LocalDisableProgressiveBlur.current
    if (reduceTransparency || disableProgressiveBlur) {
        return
    }

    val isDark = LocalGlassDarkTheme.current
    val density = LocalDensity.current
    val edgeScale = 0.42f
    val maxBlurPx = with(density) { maxBlurRadius.toPx() } * edgeScale

    val edgeBrush = remember(fromTop, tintColor, tintMaxAlpha, isDark) {
        val peak = tintColor.copy(alpha = if (isDark) tintMaxAlpha else tintMaxAlpha * 0.92f)
        val mid = tintColor.copy(alpha = tintMaxAlpha * 0.42f)
        val clear = Color.Transparent
        if (fromTop) {
            Brush.verticalGradient(colors = listOf(peak, mid, clear))
        } else {
            Brush.verticalGradient(colors = listOf(clear, mid, peak))
        }
    }

    if (backdrop != null && LocalLiquidGlassEnabled.current && isRuntimeShaderSupported()) {
        val rectShape = remember { { RectangleShape } }
        val effectsBlock = remember(maxBlurPx, fromTop) {
            val block: BackdropEffectScope.() -> Unit = {
                progressiveVerticalBlur(
                    maxRadiusPx = maxBlurPx,
                    fromTop = fromTop,
                    startFraction = 0.04f,
                    endFraction = 0.88f,
                    tintColor = Color.Transparent,
                )
            }
            block
        }
        val surfaceBlock = remember(edgeBrush) {
            val block: DrawScope.() -> Unit = {
                drawRect(brush = edgeBrush, size = size)
            }
            block
        }
        Box(
            modifier = modifier.drawPlainBackdrop(
                backdrop = backdrop,
                shape = rectShape,
                effects = effectsBlock,
                onDrawSurface = surfaceBlock,
                backdropScale = edgeScale,
            ),
        )
    } else {
        Box(modifier = modifier.background(brush = edgeBrush))
    }
}

/**
 * Utility luminance helper so tinted glass picks crisp contrasting ink/icon colors.
 */
fun Color.isLightSurface(): Boolean = luminance() > 0.5f
