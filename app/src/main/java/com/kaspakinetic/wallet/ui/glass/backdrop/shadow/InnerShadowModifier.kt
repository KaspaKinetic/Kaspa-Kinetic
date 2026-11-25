/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.kaspakinetic.wallet.ui.glass.backdrop.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import com.kaspakinetic.wallet.ui.glass.backdrop.internal.ShapeProvider
import com.kaspakinetic.wallet.ui.glass.backdrop.internal.clipOutline
import com.kaspakinetic.wallet.ui.glass.backdrop.isRenderEffectSupported

internal class InnerShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> InnerShadow?
) : ModifierNodeElement<InnerShadowNode>() {

    override fun create(): InnerShadowNode {
        return InnerShadowNode(shapeProvider, shadow)
    }

    override fun update(node: InnerShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "innerShadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class InnerShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> InnerShadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private val paint = Paint()
    private var clipPath: Path? = null

    private var prevRadius = Float.NaN
    private var cachedSize: Size = Size.Unspecified
    private var cachedDensity: Float = Float.NaN
    private var cachedLayoutDirection: LayoutDirection? = null
    private var cachedShape: Shape? = null
    private var cachedRadius: Dp = Dp.Unspecified
    private var cachedOffset: DpOffset = DpOffset.Unspecified
    private var cachedColor: Color = Color.Unspecified

    override fun ContentDrawScope.draw() {
        drawContent()

        if (!isRenderEffectSupported()) return
        if (!size.width.isFinite() || !size.height.isFinite() || size.width < 1f || size.height < 1f) return

        val shadow = shadow() ?: return
        if (shadow.alpha <= 0.005f || shadow.color.alpha <= 0.005f) return

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val densityObj: Density = this
            val layoutDirection = layoutDirection
            val outline = shapeProvider.shape.createOutline(size, layoutDirection, densityObj)
            val resolvedClipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            val innerShape = shapeProvider.innerShape
            val needsRecord = cachedSize != size ||
                cachedDensity != density ||
                cachedLayoutDirection != layoutDirection ||
                cachedShape != innerShape ||
                cachedRadius != shadow.radius ||
                cachedOffset != shadow.offset ||
                cachedColor != shadow.color

            if (needsRecord) {
                val radius = shadow.radius.toPx()
                val offsetX = shadow.offset.x.toPx()
                val offsetY = shadow.offset.y.toPx()

                configurePaint(shadow)

                if (prevRadius != radius) {
                    shadowLayer.renderEffect =
                        if (radius > 0f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    prevRadius = radius
                }
                shadowLayer.record {
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.clipOutline(outline, resolvedClipPath)
                    canvas.drawOutline(outline, paint)
                    canvas.translate(offsetX, offsetY)
                    canvas.drawOutline(outline, ShadowMaskPaint)
                    canvas.translate(-offsetX, -offsetY)
                    canvas.restore()
                }

                cachedSize = size
                cachedDensity = density
                cachedLayoutDirection = layoutDirection
                cachedShape = innerShape
                cachedRadius = shadow.radius
                cachedOffset = shadow.offset
                cachedColor = shadow.color
            }

            shadowLayer.alpha = shadow.alpha
            shadowLayer.blendMode = shadow.blendMode

            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, resolvedClipPath)
            drawLayer(shadowLayer)
            canvas.restore()
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer =
            graphicsContext.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
        }
        clipPath = null
        prevRadius = Float.NaN
        cachedSize = Size.Unspecified
        cachedDensity = Float.NaN
        cachedLayoutDirection = null
        cachedShape = null
        cachedRadius = Dp.Unspecified
        cachedOffset = DpOffset.Unspecified
        cachedColor = Color.Unspecified
    }

    private fun DrawScope.configurePaint(shadow: InnerShadow) {
        paint.color = shadow.color
    }
}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
