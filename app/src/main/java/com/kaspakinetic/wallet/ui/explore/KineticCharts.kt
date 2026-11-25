package com.kaspakinetic.wallet.ui.explore

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaspakinetic.wallet.core.explore.DistributionTier
import com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Animated dual-line normalized `%` performance chart comparing **KAS** vs **BTC** starting from 0%.
 * Sweeps smoothly left-to-right on mount or timeframe switch and supports touch-to-scrub with haptics.
 */
@Composable
fun AnimatedDualLinePerformanceChart(
    kasPoints: List<Pair<Long, Double>>,
    btcPoints: List<Pair<Long, Double>>,
    animationKey: Any,
    modifier: Modifier = Modifier,
    height: Dp = 205.dp,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current

    val kasColor = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val btcColor = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
    val gridColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val zeroLineColor = if (isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.24f)

    val sweep = remember(animationKey) { Animatable(0f) }
    var scrubbedIndex by remember(animationKey) { mutableStateOf<Int?>(null) }

    LaunchedEffect(animationKey, kasPoints.size) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing))
    }

    val count = minOf(kasPoints.size, btcPoints.size)
    if (count < 2) return

    val allVals = remember(kasPoints, btcPoints) {
        buildList(count * 2 + 1) {
            add(0.0)
            for (i in 0 until count) {
                add(kasPoints[i].second)
                add(btcPoints[i].second)
            }
        }
    }
    val rawMin = allVals.minOrNull() ?: -1.0
    val rawMax = allVals.maxOrNull() ?: 1.0
    val pad = max((rawMax - rawMin) * 0.12, 0.5)
    val minY = rawMin - pad
    val maxY = rawMax + pad
    val spanY = (maxY - minY).coerceAtLeast(1e-6)

    val activeIdx = (scrubbedIndex ?: (count - 1)).coerceIn(0, count - 1)
    val activeTs = kasPoints[activeIdx].first
    val activeKas = kasPoints[activeIdx].second
    val activeBtc = btcPoints[activeIdx].second
    val activeDelta = activeKas - activeBtc

    val pillShape = ContinuousCornerShape(14.dp)
    val pillBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.62f)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Live scrub / endpoint legend strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pillShape)
                .background(pillBg, pillShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LegendDotStat(label = "KAS", value = ExploreAnalyticsMath.formatSignedPct(activeKas), color = kasColor)
                LegendDotStat(label = "BTC", value = ExploreAnalyticsMath.formatSignedPct(activeBtc), color = btcColor)
            }
            Text(
                text = if (scrubbedIndex != null) {
                    "${ExploreAnalyticsMath.formatUtcDate(activeTs)} • Δ ${ExploreAnalyticsMath.formatSignedPct(activeDelta)}"
                } else {
                    "Δ ${ExploreAnalyticsMath.formatSignedPct(activeDelta)}"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (activeDelta >= 0.0) kasColor else MaterialTheme.colorScheme.error,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(count, animationKey) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val idx = ((pos.x / w) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                        scrubbedIndex = if (scrubbedIndex == idx) null else idx
                        if (hapticsEnabled) {
                            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                    }
                }
                .pointerInput(count, animationKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scrubbedIndex = null },
                        onDragCancel = { scrubbedIndex = null },
                        onHorizontalDrag = { change, _ ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val idx = ((change.position.x / w) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                            if (idx != scrubbedIndex) {
                                scrubbedIndex = idx
                                if (hapticsEnabled) {
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                            }
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!size.width.isFinite() || !size.height.isFinite() || size.width < 2f || size.height < 2f) return@Canvas
                val leftPad = 4.dp.toPx()
                val rightPad = 4.dp.toPx()
                val topPad = 10.dp.toPx()
                val bottomPad = 10.dp.toPx()
                val chartW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                fun xFor(i: Int): Float = leftPad + (i.toFloat() / (count - 1).toFloat()) * chartW
                fun yFor(v: Double): Float = topPad + ((maxY - v) / spanY).toFloat().coerceIn(0f, 1f) * chartH

                // Subtle horizontal reference gridlines
                for (step in 0..3) {
                    val gy = topPad + (step / 3f) * chartH
                    drawLine(gridColor, Offset(leftPad, gy), Offset(leftPad + chartW, gy), strokeWidth = 1.dp.toPx())
                }

                // Dashed 0% baseline
                val zeroY = yFor(0.0)
                drawLine(
                    color = zeroLineColor,
                    start = Offset(leftPad, zeroY),
                    end = Offset(leftPad + chartW, zeroY),
                    strokeWidth = 1.1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )

                val kasPath = Path()
                val kasFill = Path()
                val btcPath = Path()
                for (i in 0 until count) {
                    val x = xFor(i)
                    val ky = yFor(kasPoints[i].second)
                    val by = yFor(btcPoints[i].second)
                    if (i == 0) {
                        kasPath.moveTo(x, ky)
                        kasFill.moveTo(x, zeroY)
                        kasFill.lineTo(x, ky)
                        btcPath.moveTo(x, by)
                    } else {
                        kasPath.lineTo(x, ky)
                        kasFill.lineTo(x, ky)
                        btcPath.lineTo(x, by)
                    }
                }
                kasFill.lineTo(xFor(count - 1), zeroY)
                kasFill.close()

                val revealRight = leftPad + chartW * sweep.value.coerceIn(0f, 1f)
                clipRect(left = 0f, top = 0f, right = revealRight + 6.dp.toPx(), bottom = size.height) {
                    drawPath(
                        path = kasFill,
                        brush = Brush.verticalGradient(
                            colors = listOf(kasColor.copy(alpha = if (isDark) 0.24f else 0.18f), Color.Transparent),
                            startY = topPad,
                            endY = topPad + chartH,
                        ),
                    )
                    drawPath(
                        path = btcPath,
                        color = btcColor.copy(alpha = 0.88f),
                        style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = kasPath,
                        color = kasColor,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // Active / scrubbed vertical crosshair + luminous marker dots
                val selIdx = scrubbedIndex ?: if (sweep.value > 0.96f) count - 1 else -1
                if (selIdx in 0 until count) {
                    val sx = xFor(selIdx)
                    val sky = yFor(kasPoints[selIdx].second)
                    val sby = yFor(btcPoints[selIdx].second)
                    if (scrubbedIndex != null) {
                        drawLine(
                            color = zeroLineColor,
                            start = Offset(sx, topPad),
                            end = Offset(sx, topPad + chartH),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    drawCircle(btcColor.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = Offset(sx, sby))
                    drawCircle(btcColor, radius = 3.5.dp.toPx(), center = Offset(sx, sby))
                    drawCircle(kasColor.copy(alpha = 0.28f), radius = 8.dp.toPx(), center = Offset(sx, sky))
                    drawCircle(kasColor, radius = 4.dp.toPx(), center = Offset(sx, sky))
                }
            }
        }

        // X-axis endpoints + Y-axis bounds
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ExploreAnalyticsMath.formatUtcDate(kasPoints.first().first),
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = "${ExploreAnalyticsMath.formatSignedPct(rawMin)} .. ${ExploreAnalyticsMath.formatSignedPct(rawMax)}",
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = ExploreAnalyticsMath.formatUtcDate(kasPoints.last().first),
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
        }
    }
}

@Composable
private fun LegendDotStat(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
    }
}

/**
 * Animated single-series Kinetic Aero line chart with optional **Log10** Y-axis scaling and
 * interactive touch-to-scrub tooltips (used for Hashrate History & Block Reward Emission Schedule).
 */
@Composable
fun AnimatedSingleLineChart(
    points: List<Pair<Long, Double>>,
    animationKey: Any,
    valueFormatter: (Double) -> String,
    dateFormatter: (Long) -> String = { ExploreAnalyticsMath.formatUtcDate(it) },
    useLogScale: Boolean = false,
    lineColor: Color? = null,
    modifier: Modifier = Modifier,
    height: Dp = 185.dp,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current

    val strokeColor = lineColor ?: if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val gridColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    val sweep = remember(animationKey, useLogScale) { Animatable(0f) }
    var scrubbedIndex by remember(animationKey, useLogScale) { mutableStateOf<Int?>(null) }

    LaunchedEffect(animationKey, useLogScale, points.size) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing))
    }

    if (points.size < 2) return

    fun transform(v: Double): Double {
        if (!useLogScale) return v
        return ln(v.coerceAtLeast(1e-6))
    }

    val transformed = remember(points, useLogScale) { points.map { transform(it.second) } }
    val rawMin = transformed.minOrNull() ?: 0.0
    val rawMax = transformed.maxOrNull() ?: 1.0
    val pad = max((rawMax - rawMin) * 0.10, 1e-4)
    val minY = rawMin - pad
    val maxY = rawMax + pad
    val spanY = (maxY - minY).coerceAtLeast(1e-6)

    val activeIdx = (scrubbedIndex ?: points.lastIndex).coerceIn(0, points.lastIndex)
    val activePt = points[activeIdx]

    val pillShape = ContinuousCornerShape(14.dp)
    val pillBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.62f)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pillShape)
                .background(pillBg, pillShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dateFormatter(activePt.first),
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = valueFormatter(activePt.second),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = strokeColor,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points.size, animationKey) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val idx = ((pos.x / w) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                        scrubbedIndex = if (scrubbedIndex == idx) null else idx
                        if (hapticsEnabled) {
                            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                    }
                }
                .pointerInput(points.size, animationKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scrubbedIndex = null },
                        onDragCancel = { scrubbedIndex = null },
                        onHorizontalDrag = { change, _ ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val idx = ((change.position.x / w) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                            if (idx != scrubbedIndex) {
                                scrubbedIndex = idx
                                if (hapticsEnabled) {
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                            }
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!size.width.isFinite() || !size.height.isFinite() || size.width < 2f || size.height < 2f) return@Canvas
                val leftPad = 4.dp.toPx()
                val rightPad = 4.dp.toPx()
                val topPad = 8.dp.toPx()
                val bottomPad = 8.dp.toPx()
                val chartW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                fun xFor(i: Int): Float = leftPad + (i.toFloat() / (points.size - 1).toFloat()) * chartW
                fun yFor(tv: Double): Float = topPad + ((maxY - tv) / spanY).toFloat().coerceIn(0f, 1f) * chartH

                for (step in 0..3) {
                    val gy = topPad + (step / 3f) * chartH
                    drawLine(gridColor, Offset(leftPad, gy), Offset(leftPad + chartW, gy), strokeWidth = 1.dp.toPx())
                }

                val linePath = Path()
                val fillPath = Path()
                val bottomY = topPad + chartH
                for (i in points.indices) {
                    val x = xFor(i)
                    val y = yFor(transformed[i])
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, bottomY)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xFor(points.lastIndex), bottomY)
                fillPath.close()

                val revealRight = leftPad + chartW * sweep.value.coerceIn(0f, 1f)
                clipRect(left = 0f, top = 0f, right = revealRight + 6.dp.toPx(), bottom = size.height) {
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(strokeColor.copy(alpha = if (isDark) 0.28f else 0.20f), Color.Transparent),
                            startY = topPad,
                            endY = bottomY,
                        ),
                    )
                    drawPath(
                        path = linePath,
                        color = strokeColor,
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                val selIdx = scrubbedIndex ?: if (sweep.value > 0.96f) points.lastIndex else -1
                if (selIdx in points.indices) {
                    val sx = xFor(selIdx)
                    val sy = yFor(transformed[selIdx])
                    if (scrubbedIndex != null) {
                        drawLine(
                            color = strokeColor.copy(alpha = 0.45f),
                            start = Offset(sx, topPad),
                            end = Offset(sx, bottomY),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    drawCircle(strokeColor.copy(alpha = 0.28f), radius = 8.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(strokeColor, radius = 4.dp.toPx(), center = Offset(sx, sy))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dateFormatter(points.first().first),
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = dateFormatter(points.last().first),
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
        }
    }
}

/**
 * Animated vertical bar chart for Rich List Balance Distribution across Kaspa balance tiers.
 * Rises smoothly on load and supports tap/scrub selection with haptic detents.
 */
@Composable
fun AnimatedDistributionBarChart(
    tiers: List<DistributionTier>,
    addressesSuffix: String,
    modifier: Modifier = Modifier,
    height: Dp = 155.dp,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current

    if (tiers.isEmpty()) return

    val rise = remember(tiers) { Animatable(0f) }
    var selectedIdx by remember(tiers) { mutableStateOf<Int?>(null) }

    LaunchedEffect(tiers) {
        rise.snapTo(0f)
        rise.animateTo(1f, animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing))
    }

    val maxKas = (tiers.maxOfOrNull { it.amountKas } ?: 1L).coerceAtLeast(1L).toDouble()
    val primaryColor = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val selectedColor = if (isDark) Color(0xFF67E8F9) else Color(0xFF0284C7)
    val gridColor = if (isDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.07f)

    val activeTier = tiers[(selectedIdx ?: tiers.indices.maxByOrNull { tiers[it].amountKas } ?: 0).coerceIn(0, tiers.lastIndex)]
    val countFmt = remember { DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)) }

    val pillShape = ContinuousCornerShape(14.dp)
    val pillBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.62f)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pillShape)
                .background(pillBg, pillShape)
                .border(0.75.dp, if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f), pillShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${activeTier.rangeLabel} (${countFmt.format(activeTier.addressCount)} $addressesSuffix)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = glassPrimaryContentColor(),
            )
            Text(
                text = ExploreAnalyticsMath.formatCompactKas(activeTier.amountKas.toDouble()),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(tiers.size) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val idx = ((pos.x / w) * tiers.size).toInt().coerceIn(0, tiers.lastIndex)
                        selectedIdx = idx
                        if (hapticsEnabled) {
                            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                    }
                }
                .pointerInput(tiers.size) {
                    detectHorizontalDragGestures { change, _ ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val idx = ((change.position.x / w) * tiers.size).toInt().coerceIn(0, tiers.lastIndex)
                        if (idx != selectedIdx) {
                            selectedIdx = idx
                            if (hapticsEnabled) {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!size.width.isFinite() || !size.height.isFinite() || size.width < 2f || size.height < 2f) return@Canvas
                val topPad = 6.dp.toPx()
                val bottomPad = 6.dp.toPx()
                val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val slotW = size.width / tiers.size.toFloat()
                val barW = (slotW * 0.64f).coerceAtLeast(4.dp.toPx())

                for (step in 0..2) {
                    val gy = topPad + (step / 2f) * usableH
                    drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                }

                val progress = rise.value.coerceIn(0f, 1f)
                tiers.forEachIndexed { idx, tier ->
                    val ratio = (tier.amountKas.toDouble() / maxKas).toFloat().coerceIn(0.03f, 1f)
                    val staggered = ((progress * 1.25f) - (idx * 0.025f)).coerceIn(0f, 1f)
                    val barH = (usableH * ratio * staggered).coerceAtLeast(2.dp.toPx())
                    val left = idx * slotW + (slotW - barW) / 2f
                    val top = topPad + (usableH - barH)
                    val isSel = selectedIdx == idx
                    val barTint = if (isSel) selectedColor else primaryColor
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(barTint, barTint.copy(alpha = 0.42f)),
                            startY = top,
                            endY = top + barH,
                        ),
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(tiers.first().rangeLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
            Text(tiers[tiers.size / 2].rangeLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
            Text(tiers.last().rangeLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
        }
    }
}
