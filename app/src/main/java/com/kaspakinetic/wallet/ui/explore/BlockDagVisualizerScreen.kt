package com.kaspakinetic.wallet.ui.explore

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.explore.DagBlockNode
import com.kaspakinetic.wallet.core.explore.KaspaExploreRepository
import com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.network.BlockAddedStreamHandle
import com.kaspakinetic.wallet.core.network.KaspadProtoCodec
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassBottomSheet
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalOverlayBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.layerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToLong

private const val MAX_DAG_HISTORY_BLOCKS = 260
private const val WORLD_COL_SPACING = 86f
private const val WORLD_LANE_SPACING = 74f
private const val BLOCK_WIDTH = 56f
private const val BLOCK_HEIGHT = 42f
private const val VERTICAL_LANE_SLOTS = 9 // Lanes -4..+4 -> spreads blocks across ~65-75% of screen height!

private data class PositionedDagBlock(
    val node: DagBlockNode,
    val worldX: Float,
    val worldY: Float,
    val columnIndex: Long,
    val lane: Int,
    val hexSuffix: String,
)

/**
 * High-performance 60–120 FPS mobile-first Live 10 BPS BlockDAG Visualizer.
 *
 * Visual & Performance Architecture:
 * - Compact horizontal columns (`86f`) so ~5–8 columns fill the screen width at default zoom.
 * - Full vertical spread across 9 deterministic hash-distributed + collision-resolved lanes (~65–75% screen height)
 *   forming the unmistakable dense Kaspa BlockDAG web.
 * - Crisp rounded squircle block tiles displaying their 2-char uppercase hex hash suffix (e.g. `E5`, `D6`, `F2`)
 *   using a cached `android.graphics.Paint` (zero allocations per frame).
 * - Pure hardware-accelerated `Canvas` for the DAG (ZERO per-block `LayerBackdrop` / `RenderEffect` shaders!) and
 *   100% isolated from `WalletUiState`.
 */
@Composable
fun BlockDagVisualizerScreen(
    network: KaspaNetwork,
    appearance: KineticAeroAppearanceSettings,
    repository: KaspaExploreRepository,
    openBlockAddedStream: suspend ((KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit) -> BlockAddedStreamHandle?,
    onInspectBlockInExplorer: (String) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current

    var selectedBlock by remember { mutableStateOf<DagBlockNode?>(null) }

    BackHandler {
        if (selectedBlock != null) {
            selectedBlock = null
        } else {
            onDismiss()
        }
    }

    // Isolated 10 BPS graph state (never leaks to WalletUiState)
    val incomingQueue = remember(network) { ConcurrentLinkedQueue<DagBlockNode>() }
    var blocksMap by remember(network) { mutableStateOf<LinkedHashMap<String, PositionedDagBlock>>(LinkedHashMap()) }
    var minColOrigin by remember(network) { mutableLongStateOf(0L) }
    var latestDaa by remember(network) { mutableLongStateOf(0L) }
    var measuredBps by remember(network) { mutableFloatStateOf(0f) }
    var usingGrpcStream by remember(network) { mutableStateOf(false) }

    // Camera & Playback state (smooth pan, pinch-zoom, momentum fling, auto-follow live tip)
    var isPlaying by remember { mutableStateOf(true) }
    var autoFollow by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var cameraWorldX by remember { mutableFloatStateOf(0f) }
    var cameraWorldY by remember { mutableFloatStateOf(0f) }
    var velocityWorldX by remember { mutableFloatStateOf(0f) }
    var velocityWorldY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val recentArrivalTimestamps = remember(network) { ArrayDeque<Long>() }
    // Track occupied vertical lanes per column so parallel blocks NEVER overlap and spread cleanly vertically!
    val occupiedLanesByCol = remember(network) { HashMap<Long, MutableSet<Int>>() }

    // Cached Paint for crisp 2-char hex suffixes inside block tiles (zero per-frame allocations!)
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = android.graphics.Color.WHITE
        }
    }

    fun pickSpreadLane(colKey: Long, hash: String, isChain: Boolean): Int {
        val taken = occupiedLanesByCol.getOrPut(colKey) { HashSet() }
        // Keep virtual chain blocks gliding gently near the center band (-1..1), while parallel
        // blue/red blocks distribute cleanly across the full vertical web (-4..+4)!
        val preferredOrder = if (isChain) {
            listOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
        } else {
            val hashByte = hash.takeLast(2).toIntOrNull(16) ?: hash.hashCode()
            val startIdx = abs(hashByte) % VERTICAL_LANE_SLOTS
            val allLanes = listOf(-4, -3, -2, -1, 0, 1, 2, 3, 4)
            buildList(VERTICAL_LANE_SLOTS) {
                for (offset in 0 until VERTICAL_LANE_SLOTS) {
                    add(allLanes[(startIdx + offset) % VERTICAL_LANE_SLOTS])
                }
            }
        }
        val chosen = preferredOrder.firstOrNull { it !in taken } ?: ((taken.size % VERTICAL_LANE_SLOTS) - 4)
        taken.add(chosen)
        return chosen
    }

    fun ingestNodes(newNodes: List<DagBlockNode>) {
        if (newNodes.isEmpty()) return
        val map = LinkedHashMap(blocksMap)
        var origin = minColOrigin
        var maxDaa = latestDaa

        for (node in newNodes) {
            if (node.hash.isBlank()) continue
            if (node.daaScore > maxDaa) maxDaa = node.daaScore

            val existing = map[node.hash]
            if (existing != null) {
                map[node.hash] = existing.copy(
                    node = existing.node.copy(
                        isChainBlock = existing.node.isChainBlock || node.isChainBlock,
                        isRed = existing.node.isRed || node.isRed,
                        txCount = maxOf(existing.node.txCount, node.txCount),
                    ),
                )
                continue
            }

            // Group blocks into tight, dense DAG columns using blueScore (or daaScore / 2 fallback)
            // so parallel 10 BPS blocks naturally share columns and form a lush interconnected web!
            val colKey = when {
                node.blueScore > 0L -> node.blueScore / 2L
                node.daaScore > 0L -> node.daaScore / 2L
                else -> map.size.toLong()
            }
            if (origin == 0L && colKey > 0L) {
                origin = colKey
            }
            val colDelta = (colKey - origin).toFloat()
            val lane = pickSpreadLane(colKey, node.hash, node.isChainBlock)

            // Subtle deterministic horizontal jitter inside the column (non-chain blocks) for organic DAG mesh feel
            val microJitterX = if (node.isChainBlock) 0f else (((abs(node.hash.hashCode()) % 17) - 8) * 1.3f)
            val wx = colDelta * WORLD_COL_SPACING + microJitterX
            val wy = lane * WORLD_LANE_SPACING
            val suffix = node.hash.takeLast(2).uppercase(Locale.US)

            map[node.hash] = PositionedDagBlock(
                node = node,
                worldX = wx,
                worldY = wy,
                columnIndex = colKey,
                lane = lane,
                hexSuffix = suffix,
            )
            recentArrivalTimestamps.addLast(node.arrivalTimeMs)
        }

        // Trim oldest entries beyond MAX_DAG_HISTORY_BLOCKS
        if (map.size > MAX_DAG_HISTORY_BLOCKS) {
            val sortedHashes = map.values
                .sortedWith(compareBy<PositionedDagBlock> { it.columnIndex }.thenBy { it.node.arrivalTimeMs })
                .map { it.node.hash }
            val toDrop = map.size - MAX_DAG_HISTORY_BLOCKS
            for (i in 0 until toDrop) {
                map.remove(sortedHashes[i])
            }
        }

        // Rolling 3-second BPS estimate
        val now = System.currentTimeMillis()
        while (recentArrivalTimestamps.isNotEmpty() && now - recentArrivalTimestamps.first() > 3_000L) {
            recentArrivalTimestamps.removeFirst()
        }
        measuredBps = (recentArrivalTimestamps.size / 3.0f).coerceIn(0f, 30f)

        minColOrigin = origin
        latestDaa = maxDaa
        blocksMap = map
    }

    // 1. Parallel backfill on open + Live gRPC NotifyBlockAdded stream + graceful REST fallback
    LaunchedEffect(network) {
        val seed = runCatchingNonCancellation {
            repository.fetchRecentDagBlocks(network, lookbackBlueScores = 24)
        }.getOrDefault(emptyList())
        if (seed.isNotEmpty()) {
            ingestNodes(seed)
            val tipX = blocksMap.values.maxOfOrNull { it.worldX } ?: 0f
            cameraWorldX = tipX - WORLD_COL_SPACING * 2.2f
            cameraWorldY = 0f
        }

        var streamHandle: BlockAddedStreamHandle? = null
        try {
            streamHandle = openBlockAddedStream { hdr ->
                val now = System.currentTimeMillis()
                val node = DagBlockNode(
                    hash = hdr.hash,
                    daaScore = hdr.daaScore,
                    blueScore = hdr.blueScore,
                    timestampMs = if (hdr.timestampMs > 0L) hdr.timestampMs else now,
                    parentHashes = hdr.parentHashes,
                    selectedParentHash = hdr.selectedParentHash,
                    isChainBlock = hdr.isChainBlock,
                    isRed = false,
                    txCount = hdr.txCount,
                    arrivalTimeMs = now,
                )
                incomingQueue.offer(node)
                hdr.mergeSetRedHashes.forEach { redHash ->
                    val existing = blocksMap[redHash]?.node
                    if (existing != null && !existing.isRed) {
                        incomingQueue.offer(existing.copy(isRed = true))
                    }
                }
            }
            usingGrpcStream = streamHandle != null

            while (isActive) {
                if (!usingGrpcStream) {
                    val fresh = runCatchingNonCancellation {
                        repository.fetchRecentDagBlocks(network, lookbackBlueScores = 6)
                    }.getOrDefault(emptyList())
                    fresh.forEach { incomingQueue.offer(it) }
                    delay(2_200L)
                } else {
                    delay(1_000L)
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { streamHandle?.close() }
            }
        }
    }

    // 2. Smooth 60–120 FPS VSync loop
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = nanos
                frameNowMs = System.currentTimeMillis()

                if (isPlaying && incomingQueue.isNotEmpty()) {
                    val batch = ArrayList<DagBlockNode>(16)
                    while (batch.size < 24) {
                        val next = incomingQueue.poll() ?: break
                        batch.add(next)
                    }
                    if (batch.isNotEmpty()) {
                        ingestNodes(batch)
                    }
                } else if (!isPlaying && incomingQueue.size > 140) {
                    while (incomingQueue.size > 90) incomingQueue.poll()
                }

                if (autoFollow && isPlaying) {
                    val tipX = blocksMap.values.maxOfOrNull { it.worldX } ?: cameraWorldX
                    val targetX = tipX - (canvasSize.width.toFloat() * 0.28f / zoom.coerceAtLeast(0.45f))
                    val alpha = 1f - exp(-5.0f * dt)
                    cameraWorldX += (targetX - cameraWorldX) * alpha
                    cameraWorldY += (0f - cameraWorldY) * (1f - exp(-3.0f * dt))
                    velocityWorldX = 0f
                    velocityWorldY = 0f
                } else if (abs(velocityWorldX) > 2f || abs(velocityWorldY) > 2f) {
                    cameraWorldX += velocityWorldX * dt
                    cameraWorldY += velocityWorldY * dt
                    val friction = exp(-5.2f * dt)
                    velocityWorldX *= friction
                    velocityWorldY *= friction
                }
            }
        }
    }

    // Tier-1 calm ambient backdrop for floating glass HUD controls (never captures the moving DAG canvas!)
    val ambientBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    // Kinetic Aero DAG color palette
    val chainBlockColor = if (isDark) Color(0xFF10B981) else Color(0xFF059669)
    val blueBlockColor = if (isDark) Color(0xFF0EA5E9) else Color(0xFF0284C7)
    val redBlockColor = if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626)
    val tipGlowColor = if (isDark) Color(0xFF6EE7B7) else Color(0xFF34D399)
    val edgeBaseColor = if (isDark) Color(0xFF94A3B8).copy(alpha = 0.26f) else Color(0xFF0F172A).copy(alpha = 0.22f)
    val edgeChainColor = if (isDark) Color(0xFF34D399).copy(alpha = 0.72f) else Color(0xFF059669).copy(alpha = 0.65f)
    val edgeHighlightColor = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val axisColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        // Layer 0: Calm ambient background recorded for floating Aero Glass HUD controls
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(ambientBackdrop) else Modifier),
        ) {
            if (reduceTransparency) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            } else {
                AmbientLiquidBackdrop(
                    wallpaperPath = appearance.wallpaperPath,
                    wallpaperBrightness = appearance.wallpaperBrightness,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (isDark) Color(0xFF060B10).copy(alpha = if (hasCustomWallpaper) 0.64f else 0.36f)
                            else Color(0xFFF2F7F4).copy(alpha = if (hasCustomWallpaper) 0.68f else 0.30f),
                        ),
                )
            }
        }

        // Layer A: Pure 60–120 FPS Hardware-Accelerated Interactive BlockDAG Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downPos = firstDown.position
                        var movedDistance = 0f
                        var lastEventTimeNanos = System.nanoTime()
                        var lastVelX = 0f
                        var lastVelY = 0f

                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val nowNanos = System.nanoTime()
                            val dt = ((nowNanos - lastEventTimeNanos) / 1_000_000_000f).coerceIn(0.002f, 0.05f)
                            lastEventTimeNanos = nowNanos

                            val panDist = hypot(panChange.x, panChange.y)
                            movedDistance += panDist

                            if (abs(zoomChange - 1f) > 0.002f || panDist > 0.5f) {
                                val prevZoom = zoom
                                val nextZoom = (prevZoom * zoomChange).coerceIn(0.45f, 2.5f)
                                if (centroid != Offset.Unspecified && canvasSize.width > 0 && canvasSize.height > 0) {
                                    val halfW = canvasSize.width / 2f
                                    val halfH = canvasSize.height / 2f
                                    val worldBeforeX = cameraWorldX + (centroid.x - halfW) / prevZoom
                                    val worldBeforeY = cameraWorldY + (centroid.y - halfH) / prevZoom
                                    zoom = nextZoom
                                    cameraWorldX = worldBeforeX - (centroid.x - halfW) / nextZoom
                                    cameraWorldY = worldBeforeY - (centroid.y - halfH) / nextZoom
                                } else {
                                    zoom = nextZoom
                                }

                                val deltaWorldX = -panChange.x / zoom
                                val deltaWorldY = -panChange.y / zoom
                                cameraWorldX += deltaWorldX
                                cameraWorldY += deltaWorldY

                                if (panDist > 2f) {
                                    autoFollow = false
                                    lastVelX = (deltaWorldX / dt).coerceIn(-4_500f, 4_500f)
                                    lastVelY = (deltaWorldY / dt).coerceIn(-4_500f, 4_500f)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (movedDistance < 18f) {
                            val halfW = canvasSize.width / 2f
                            val halfH = canvasSize.height / 2f
                            // Dynamic vertical stretch factor matching draw transform so hit-testing is pixel-exact!
                            val verticalScale = ((canvasSize.height * 0.68f) / (VERTICAL_LANE_SLOTS * WORLD_LANE_SPACING)).coerceIn(0.85f, 1.85f)
                            val tapWorldX = cameraWorldX + (downPos.x - halfW) / zoom
                            val tapWorldY = cameraWorldY + (downPos.y - halfH) / (zoom * verticalScale)
                            val hitHalfW = BLOCK_WIDTH * 0.68f
                            val hitHalfH = BLOCK_HEIGHT * 0.72f
                            val hit = blocksMap.values.firstOrNull { b ->
                                abs(tapWorldX - b.worldX) <= hitHalfW && abs(tapWorldY - b.worldY) <= hitHalfH
                            }
                            selectedBlock = hit?.node
                            if (hit != null && hapticsEnabled) {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        } else if (!autoFollow) {
                            velocityWorldX = lastVelX * 0.55f
                            velocityWorldY = lastVelY * 0.55f
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!size.width.isFinite() || !size.height.isFinite() || size.width < 2f || size.height < 2f) return@Canvas
                val halfW = size.width / 2f
                val halfH = size.height / 2f
                val z = zoom
                val camX = cameraWorldX
                val camY = cameraWorldY
                // Spread vertical lanes gracefully across ~68% of screen height!
                val verticalScale = ((size.height * 0.68f) / (VERTICAL_LANE_SLOTS * WORLD_LANE_SPACING)).coerceIn(0.85f, 1.85f)

                fun toScreenX(wx: Float): Float = halfW + (wx - camX) * z
                fun toScreenY(wy: Float): Float = halfH + (wy - camY) * z * verticalScale

                val snapshot = blocksMap
                val selHash = selectedBlock?.hash
                val selParents = selectedBlock?.parentHashes?.toSet().orEmpty()

                // Identify live tip blocks (blocks with no children currently in the rolling buffer)
                val referencedParents = HashSet<String>(snapshot.size * 2)
                for (b in snapshot.values) {
                    referencedParents.addAll(b.node.parentHashes)
                }

                // 1. Subtle bottom DAA axis baseline & vertical column gridlines
                val axisY = size.height - 96.dp.toPx()
                drawLine(axisColor, Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 1.dp.toPx())

                val visibleLeftWorld = camX - halfW / z - WORLD_COL_SPACING
                val visibleRightWorld = camX + halfW / z + WORLD_COL_SPACING
                val colStep = WORLD_COL_SPACING * 2f
                val firstTickWorld = kotlin.math.floor(visibleLeftWorld / colStep) * colStep
                var tickW = firstTickWorld
                while (tickW <= visibleRightWorld) {
                    val sx = toScreenX(tickW)
                    drawLine(
                        color = axisColor.copy(alpha = 0.45f),
                        start = Offset(sx, 0f),
                        end = Offset(sx, axisY),
                        strokeWidth = 0.75.dp.toPx(),
                    )
                    tickW += colStep
                }

                // 2. Draw crisp parent-pointer DAG mesh edges across columns
                val edgePath = Path()
                for (block in snapshot.values) {
                    if (block.worldX < visibleLeftWorld - WORLD_COL_SPACING * 2.5f ||
                        block.worldX > visibleRightWorld + WORLD_COL_SPACING * 2.5f
                    ) continue
                    val childX = toScreenX(block.worldX)
                    val childY = toScreenY(block.worldY)
                    val childIsSelected = block.node.hash == selHash

                    for (pHash in block.node.parentHashes) {
                        val parent = snapshot[pHash] ?: continue
                        val parentX = toScreenX(parent.worldX)
                        val parentY = toScreenY(parent.worldY)
                        val isHighlighted = childIsSelected || pHash == selHash
                        val isChainLink = pHash == block.node.selectedParentHash && block.node.isChainBlock

                        edgePath.reset()
                        val midX = (parentX + childX) * 0.5f
                        edgePath.moveTo(parentX, parentY)
                        edgePath.cubicTo(midX, parentY, midX, childY, childX, childY)

                        drawPath(
                            path = edgePath,
                            color = when {
                                isHighlighted -> edgeHighlightColor
                                isChainLink -> edgeChainColor
                                else -> edgeBaseColor
                            },
                            style = Stroke(
                                width = when {
                                    isHighlighted -> (3.0f * z).coerceIn(2.0f, 4.8f)
                                    isChainLink -> (2.2f * z).coerceIn(1.5f, 3.4f)
                                    else -> (1.25f * z).coerceIn(0.9f, 2.2f)
                                },
                                cap = StrokeCap.Round,
                            ),
                        )
                    }
                }

                // 3. Draw crisp rounded squircle block tiles with 2-char uppercase hex suffix (`E5`, `D6`, `F2`)
                val cornerPx = (14f * z).coerceIn(6f, 22f)
                val bw = BLOCK_WIDTH * z
                val bh = BLOCK_HEIGHT * z
                val showText = bw >= 28f
                labelPaint.textSize = (14.5f * z).coerceIn(9f, 26f)
                val textVerticalOffset = (labelPaint.descent() + labelPaint.ascent()) * 0.5f

                for (block in snapshot.values) {
                    if (block.worldX < visibleLeftWorld || block.worldX > visibleRightWorld) continue
                    val sx = toScreenX(block.worldX)
                    val sy = toScreenY(block.worldY)

                    val ageMs = (frameNowMs - block.node.arrivalTimeMs).coerceAtLeast(0L)
                    val animScale = if (ageMs < 360L) {
                        val t = ageMs / 360f
                        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                        0.60f + 0.40f * eased
                    } else 1f

                    val w = bw * animScale
                    val h = bh * animScale
                    val topLeft = Offset(sx - w / 2f, sy - h / 2f)
                    val isSel = block.node.hash == selHash
                    val isRelated = block.node.hash in selParents ||
                        (selHash != null && selHash in block.node.parentHashes)
                    val isTip = block.node.hash !in referencedParents

                    val tileColor = when {
                        block.node.isRed -> redBlockColor
                        block.node.isChainBlock -> chainBlockColor
                        else -> blueBlockColor
                    }

                    // Soft luminous outer halo for selected blocks, virtual chain blocks, and live tips
                    if (isSel || isRelated || block.node.isChainBlock || isTip) {
                        val glowPad = (if (isSel) 9f else if (isTip) 6f else 4.5f) * z
                        val haloColor = if (isTip && !block.node.isRed) tipGlowColor else tileColor
                        drawRoundRect(
                            color = haloColor.copy(alpha = if (isSel) 0.42f else if (isTip) 0.24f else 0.16f),
                            topLeft = Offset(topLeft.x - glowPad, topLeft.y - glowPad),
                            size = Size(w + glowPad * 2f, h + glowPad * 2f),
                            cornerRadius = CornerRadius(cornerPx + glowPad * 0.5f, cornerPx + glowPad * 0.5f),
                        )
                    }

                    // Crisp squircle tile body
                    drawRoundRect(
                        color = tileColor.copy(alpha = if (isDark) 0.88f else 0.92f),
                        topLeft = topLeft,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    )

                    // Top specular rim catchlight
                    drawRoundRect(
                        color = if (isTip && !isSel) tipGlowColor.copy(alpha = 0.85f) else Color.White.copy(alpha = if (isSel) 0.96f else 0.50f),
                        topLeft = topLeft,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = if (isSel) 2.3.dp.toPx() else if (isTip) 1.5.dp.toPx() else 1.dp.toPx()),
                    )

                    // 2-character uppercase hex hash suffix (e.g. `E5`, `D6`, `F2`)
                    if (showText) {
                        labelPaint.color = Color.White.copy(alpha = 0.96f).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(
                            block.hexSuffix,
                            sx,
                            sy - textVerticalOffset,
                            labelPaint,
                        )
                    }
                }
            }

            // Subtle DAA Score Axis Label Strip at bottom of canvas
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 78.dp, start = 18.dp, end = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = walletStrings.dagTapHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor().copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (latestDaa > 0L) "DAA $latestDaa" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor().copy(alpha = 0.84f),
                )
            }
        }

        // Layer B: Floating Kinetic Aero Frosted-Glass HUD Controls
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveBackdrop,
            LocalOverlayBackdrop provides effectiveBackdrop,
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FluidGlassIconButton(
                    onClick = onDismiss,
                    preset = GlassPreset.FloatingBar,
                    backdrop = effectiveBackdrop,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = kineticStrings.back,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .liquidGlassSurface(
                            shape = ContinuousCornerShape(50.dp),
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                        )
                        .clip(ContinuousCornerShape(50.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MarqueeText(
                        text = walletStrings.blockDagV2Title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (usingGrpcStream) Color(0xFF22C55E) else Color(0xFFF59E0B)),
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f %s", measuredBps, walletStrings.dagLiveBpsLabel),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        )
                    }
                }
            }

            // Bottom Floating HUD Controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassIconButton(
                        onClick = { isPlaying = !isPlaying },
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveBackdrop,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) walletStrings.explorerPauseLabel else walletStrings.explorerPlayLabel,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    FluidGlassIconButton(
                        onClick = { zoom = (zoom / 1.25f).coerceIn(0.45f, 2.5f) },
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveBackdrop,
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom out", modifier = Modifier.size(20.dp))
                    }
                    FluidGlassIconButton(
                        onClick = { zoom = (zoom * 1.25f).coerceIn(0.45f, 2.5f) },
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveBackdrop,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom in", modifier = Modifier.size(20.dp))
                    }
                }

                if (!autoFollow || !isPlaying) {
                    FluidGlassButton(
                        accent = true,
                        backdrop = effectiveBackdrop,
                        useBackdropCapture = true,
                        onClick = {
                            isPlaying = true
                            autoFollow = true
                            velocityWorldX = 0f
                            velocityWorldY = 0f
                        },
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(walletStrings.dagJumpToLiveButton, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .liquidGlassSurface(
                                shape = ContinuousCornerShape(50.dp),
                                preset = GlassPreset.FloatingBar,
                                backdrop = effectiveBackdrop,
                                useBackdropCapture = true,
                            )
                            .clip(ContinuousCornerShape(50.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (usingGrpcStream) walletStrings.dagStreamLiveGrpc else walletStrings.dagStreamRestFallback,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = glassPrimaryContentColor(),
                        )
                    }
                }
            }

            // Layer C: Frosted-Glass Pop-up Block Detail Bottom Sheet on Block Tap
            selectedBlock?.let { blk ->
                val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
                val timeStr = remember(blk.timestampMs) {
                    if (blk.timestampMs > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(blk.timestampMs))
                    else "—"
                }
                FluidGlassBottomSheet(
                    onDismissRequest = { selectedBlock = null },
                    title = walletStrings.dagBlockDetailsTitle,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (blk.isChainBlock) {
                            ExplorerStatusPill(walletStrings.explorerChainBlockBadge, Color(0xFF22C55E))
                        }
                        ExplorerStatusPill(
                            text = if (blk.isRed) walletStrings.explorerRedBlockBadge else walletStrings.explorerBlueBlockBadge,
                            color = if (blk.isRed) Color(0xFFEF4444) else Color(0xFF38BDF8),
                        )
                    }

                    Text(
                        text = blk.hash,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = glassPrimaryContentColor(),
                        modifier = Modifier.clickable {
                            copyTextToClipboard(context, "Block Hash", blk.hash)
                            onNotify(walletStrings.copiedToClipboard)
                        },
                    )

                    DagKvRow(walletStrings.daaScoreLabel, "${blk.daaScore}")
                    DagKvRow(walletStrings.txBlueScoreLabel, "${blk.blueScore}")
                    DagKvRow(walletStrings.txTimeLabel, timeStr)
                    DagKvRow(walletStrings.explorerBlockTransactionsLabel, "${blk.txCount}")
                    DagKvRow(walletStrings.explorerParentsLabel, "${blk.parentHashes.size}")

                    if (blk.parentHashes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            blk.parentHashes.take(4).forEach { ph ->
                                Text(
                                    text = "↳ ${ph.take(20)}…${ph.takeLast(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = accentInk,
                                    modifier = Modifier
                                        .clip(ContinuousCornerShape(8.dp))
                                        .clickable {
                                            selectedBlock = null
                                            onInspectBlockInExplorer(ph)
                                        }
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = true,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                val hashToOpen = blk.hash
                                selectedBlock = null
                                onInspectBlockInExplorer(hashToOpen)
                            },
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(walletStrings.explorerViewInExplorerButton, fontWeight = FontWeight.Bold)
                        }
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                copyTextToClipboard(context, "Block Hash", blk.hash)
                                onNotify(walletStrings.copiedToClipboard)
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DagKvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
    }
}
