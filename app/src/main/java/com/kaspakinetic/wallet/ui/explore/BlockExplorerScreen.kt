package com.kaspakinetic.wallet.ui.explore

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.explore.ExplorerAddressDetail
import com.kaspakinetic.wallet.core.explore.ExplorerBlockDetail
import com.kaspakinetic.wallet.core.explore.ExplorerTarget
import com.kaspakinetic.wallet.core.explore.ExplorerTxDetail
import com.kaspakinetic.wallet.core.explore.LiveFeedFilter
import com.kaspakinetic.wallet.core.explore.LiveTxFeedItem
import com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.core.network.BlockAddedStreamHandle
import com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.HighlightedKaspaAddressText
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.KineticRefreshIconButton
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.components.readTextFromClipboard
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidOptionGridSelector
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalOverlayBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.layerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.kineticInteractiveRow
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.onboarding.ErrorInlineBanner
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue

@Composable
fun BlockExplorerScreen(
    initialQuery: String,
    network: KaspaNetwork,
    spotPrice: SpotPrice?,
    privacyKasOnly: Boolean,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onNotify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val scope = rememberCoroutineScope()
    val unit = if (network.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val repository = viewModel.exploreRepository

    // Navigation back-stack inside Explorer:
    // If opened with a direct initialQuery (from Rich List, Main Wallet, or BlockDAG), do NOT seed
    // ExplorerTarget.Feed underneath it — pressing Back after unwinding nested drill-downs returns
    // directly to the exact screen the user came from!
    val startedFromDirectLookup = remember(initialQuery) { initialQuery.isNotBlank() }
    val backStack = remember(initialQuery) {
        mutableStateListOf<ExplorerTarget>().apply {
            if (!startedFromDirectLookup) add(ExplorerTarget.Feed)
        }
    }
    val currentTarget: ExplorerTarget? = backStack.lastOrNull()

    BackHandler {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            onDismiss()
        }
    }

    var searchQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var addressLabels by remember(network) { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Rolling ring-buffered Live Transactions state (gRPC-first, never wipes on empty/transient ticks!)
    var feedPlaying by remember { mutableStateOf(true) }
    var feedFilter by remember { mutableStateOf(LiveFeedFilter.ALL) }
    var feedItems by remember(network) { mutableStateOf(repository.getCachedLiveFeed(network)) }
    var feedLoading by remember { mutableStateOf(false) }
    var usingGrpcLiveFeed by remember(network) { mutableStateOf(false) }
    val pendingGrpcTxs = remember(network) { ConcurrentLinkedQueue<LiveTxFeedItem>() }
    val timeFormatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    // Detail states
    var txDetail by remember { mutableStateOf<ExplorerTxDetail?>(null) }
    var blockDetail by remember { mutableStateOf<ExplorerBlockDetail?>(null) }
    var addressDetail by remember { mutableStateOf<ExplorerAddressDetail?>(null) }
    var loadingMoreAddrTxs by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(startedFromDirectLookup) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun navigateTo(target: ExplorerTarget, replaceRoot: Boolean = false) {
        errorMsg = null
        if (replaceRoot) {
            backStack.clear()
            backStack.add(target)
        } else if (backStack.lastOrNull() != target) {
            backStack.add(target)
        }
        when (target) {
            is ExplorerTarget.Transaction -> searchQuery = target.txId
            is ExplorerTarget.Block -> searchQuery = target.blockHash
            is ExplorerTarget.Address -> searchQuery = target.address
            ExplorerTarget.Feed -> Unit
        }
    }

    fun executeSearch(raw: String, replaceRoot: Boolean = false) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            backStack.clear()
            backStack.add(ExplorerTarget.Feed)
            errorMsg = null
            return
        }
        scope.launch {
            isLoading = true
            errorMsg = null
            val res = viewModel.resolveExplorerQuerySafe(network, trimmed)
            res.onSuccess { target -> navigateTo(target, replaceRoot = replaceRoot) }
                .onFailure { err -> errorMsg = KaspaErrorSanitizer.sanitize(err.message) }
            isLoading = false
        }
    }

    // Load known entity labels once per network (never swallows CancellationException)
    LaunchedEffect(network) {
        runCatchingNonCancellation { repository.getAddressLabels(network) }
            .onSuccess { addressLabels = it }
    }

    // Auto-execute initial query directly into the lifecycle-safe ViewModel scope
    LaunchedEffect(initialQuery, network) {
        if (initialQuery.isNotBlank()) {
            searchQuery = initialQuery
            isLoading = true
            errorMsg = null
            val res = viewModel.resolveExplorerQuerySafe(network, initialQuery)
            res.onSuccess { target -> navigateTo(target, replaceRoot = true) }
                .onFailure { err -> errorMsg = KaspaErrorSanitizer.sanitize(err.message) }
            isLoading = false
        }
    }

    // Load detail whenever currentTarget changes (lifecycle-safe viewModelScope fetches!)
    LaunchedEffect(currentTarget, network) {
        val target = currentTarget ?: return@LaunchedEffect
        errorMsg = null
        when (target) {
            ExplorerTarget.Feed -> Unit
            is ExplorerTarget.Transaction -> {
                isLoading = true
                txDetail = null
                viewModel.fetchExplorerTxSafe(network, target.txId)
                    .onSuccess { txDetail = it }
                    .onFailure { err -> errorMsg = KaspaErrorSanitizer.sanitize(err.message) }
                isLoading = false
            }
            is ExplorerTarget.Block -> {
                isLoading = true
                blockDetail = null
                viewModel.fetchExplorerBlockSafe(network, target.blockHash)
                    .onSuccess { blockDetail = it }
                    .onFailure { err -> errorMsg = KaspaErrorSanitizer.sanitize(err.message) }
                isLoading = false
            }
            is ExplorerTarget.Address -> {
                isLoading = true
                addressDetail = null
                viewModel.fetchExplorerAddressSafe(network, target.address)
                    .onSuccess { addressDetail = it }
                    .onFailure { err -> errorMsg = KaspaErrorSanitizer.sanitize(err.message) }
                isLoading = false
            }
        }
    }

    // gRPC-first NotifyBlockAdded Live Transactions pipeline:
    // - Subscribes ONLY while viewing ExplorerTarget.Feed AND feedPlaying == true.
    // - Accumulates incoming 10 BPS block transactions off the main thread in ConcurrentLinkedQueue.
    // - Drains & merges into the deduplicated rolling ring buffer (capped at 80 rows) on a calm
    //   1200ms cadence so LazyColumn scrolling stays 60-120 FPS and LayerBackdrop never thrashes.
    // - Falls back cleanly to throttled REST only when live gRPC stream is unavailable.
    // - NEVER wipes existing feed rows on empty ticks or transient errors.
    LaunchedEffect(currentTarget, feedPlaying, network) {
        if (currentTarget != ExplorerTarget.Feed) {
            usingGrpcLiveFeed = false
            return@LaunchedEffect
        }

        // One polite initial REST seed if the rolling buffer is completely empty on first open
        if (feedItems.isEmpty()) {
            feedLoading = true
            viewModel.refreshExplorerLiveFeedSafe(network)
                .onSuccess { merged ->
                    if (merged.isNotEmpty()) {
                        feedItems = merged
                        errorMsg = null
                    }
                }
            feedLoading = false
        }

        if (!feedPlaying) {
            usingGrpcLiveFeed = false
            return@LaunchedEffect
        }

        var streamHandle: BlockAddedStreamHandle? = null
        try {
            streamHandle = runCatchingNonCancellation {
                viewModel.openLiveBlockDagStream { blockInfo ->
                    if (blockInfo.transactions.isEmpty()) return@openLiveBlockDagStream
                    val now = System.currentTimeMillis()
                    val blkTime = if (blockInfo.timestampMs > 0L) blockInfo.timestampMs else now
                    for (tx in blockInfo.transactions) {
                        if (tx.transactionId.isBlank()) continue
                        pendingGrpcTxs.offer(
                            LiveTxFeedItem(
                                transactionId = tx.transactionId,
                                blockHash = blockInfo.hash,
                                blockBlueScore = blockInfo.blueScore,
                                timestampMs = if (tx.blockTimeMs > 0L) tx.blockTimeMs else blkTime,
                                isCoinbase = tx.inputCount == 0,
                                isAccepted = blockInfo.isChainBlock,
                                totalOutputSompi = tx.totalOutputSompi,
                                inputCount = tx.inputCount,
                                outputCount = tx.outputCount,
                                primaryRecipient = tx.primaryRecipient,
                            ),
                        )
                    }
                }
            }.getOrNull()
            usingGrpcLiveFeed = streamHandle != null

            var restFallbackElapsedMs = 0L
            var grpcRetryElapsedMs = 0L
            val batchCadenceMs = 1_200L

            while (isActive) {
                delay(batchCadenceMs)
                if (!isActive) break

                // Drain all queued gRPC transactions off the main thread in one batch
                val batch = ArrayList<LiveTxFeedItem>()
                var next = pendingGrpcTxs.poll()
                while (next != null && batch.size < 250) {
                    batch.add(next)
                    next = pendingGrpcTxs.poll()
                }
                if (batch.isNotEmpty()) {
                    val merged = withContext(Dispatchers.Default) {
                        repository.mergeIntoLiveFeedBuffer(network, batch, maxBufferSize = 80)
                    }
                    if (merged.isNotEmpty()) {
                        feedItems = merged
                        errorMsg = null
                    }
                }

                // Offline / REST-only fallback when no gRPC stream is active
                if (streamHandle == null) {
                    restFallbackElapsedMs += batchCadenceMs
                    grpcRetryElapsedMs += batchCadenceMs
                    if (restFallbackElapsedMs >= 6_000L) {
                        restFallbackElapsedMs = 0L
                        viewModel.refreshExplorerLiveFeedSafe(network)
                            .onSuccess { merged ->
                                if (merged.isNotEmpty()) {
                                    feedItems = merged
                                    errorMsg = null
                                }
                            }
                            .onFailure { err ->
                                if (feedItems.isEmpty()) {
                                    errorMsg = KaspaErrorSanitizer.sanitize(err.message)
                                }
                            }
                    }
                    // Periodically try promoting back to Live gRPC
                    if (grpcRetryElapsedMs >= 12_000L) {
                        grpcRetryElapsedMs = 0L
                        streamHandle = runCatchingNonCancellation {
                            viewModel.openLiveBlockDagStream { blockInfo ->
                                if (blockInfo.transactions.isEmpty()) return@openLiveBlockDagStream
                                val now = System.currentTimeMillis()
                                val blkTime = if (blockInfo.timestampMs > 0L) blockInfo.timestampMs else now
                                for (tx in blockInfo.transactions) {
                                    if (tx.transactionId.isBlank()) continue
                                    pendingGrpcTxs.offer(
                                        LiveTxFeedItem(
                                            transactionId = tx.transactionId,
                                            blockHash = blockInfo.hash,
                                            blockBlueScore = blockInfo.blueScore,
                                            timestampMs = if (tx.blockTimeMs > 0L) tx.blockTimeMs else blkTime,
                                            isCoinbase = tx.inputCount == 0,
                                            isAccepted = blockInfo.isChainBlock,
                                            totalOutputSompi = tx.totalOutputSompi,
                                            inputCount = tx.inputCount,
                                            outputCount = tx.outputCount,
                                            primaryRecipient = tx.primaryRecipient,
                                        ),
                                    )
                                }
                            }
                        }.getOrNull()
                        usingGrpcLiveFeed = streamHandle != null
                    }
                }
            }
        } finally {
            usingGrpcLiveFeed = false
            pendingGrpcTxs.clear()
            streamHandle?.let { h ->
                withContext(NonCancellable) { runCatching { h.close() } }
            }
        }
    }

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 158.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val ambientBackdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()
    val headerTitle = when (currentTarget) {
        null, ExplorerTarget.Feed -> walletStrings.blockExplorerTitle
        is ExplorerTarget.Transaction -> walletStrings.explorerTxDetailHeader
        is ExplorerTarget.Block -> walletStrings.explorerBlockDetailHeader
        is ExplorerTarget.Address -> walletStrings.explorerAddressDetailHeader
    }
    val headerSubtitle = if (network.isTestnet) walletStrings.testnet10Label else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        // LAYER A: Tier 2 Viewport Capture (Wallpaper + Seamless Full-Screen Scrollable Explorer Body)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(viewportBackdrop) else Modifier),
        ) {
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
                    if (hasCustomWallpaper) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    if (isDark) Color(0xFF070B10).copy(alpha = 0.50f)
                                    else Color(0xFFF3F7F5).copy(alpha = 0.58f),
                                ),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides effectiveAmbient) {
                val bodyTopPadding = topPadDp + 10.dp
                when (val target = currentTarget) {
                    null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                    ) {
                        ExplorerLoadingCard(effectiveAmbient)
                    }

                    ExplorerTarget.Feed -> {
                        val filteredFeed = remember(feedItems, feedFilter) {
                            when (feedFilter) {
                                LiveFeedFilter.ALL -> feedItems
                                LiveFeedFilter.TRANSFERS -> feedItems.filter { !it.isCoinbase }
                                LiveFeedFilter.COINBASE -> feedItems.filter { it.isCoinbase }
                                LiveFeedFilter.LARGE -> feedItems.filter { it.isLarge }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                            userScrollEnabled = !gestureLocked,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = bodyTopPadding,
                                bottom = 28.dp,
                            ),
                        ) {
                            if (!errorMsg.isNullOrBlank()) {
                                item(key = "feed_err") { ErrorInlineBanner(message = errorMsg!!) }
                            }
                            item(key = "feed_controls") {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(14.dp),
                                ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            when {
                                                                !feedPlaying -> Color(0xFFF59E0B)
                                                                usingGrpcLiveFeed -> Color(0xFF22C55E)
                                                                else -> Color(0xFF38BDF8)
                                                            },
                                                        ),
                                                )
                                                Column {
                                                    Text(
                                                        text = walletStrings.explorerLiveFeedTitle,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = glassPrimaryContentColor(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = if (usingGrpcLiveFeed) walletStrings.dagStreamLiveGrpc else walletStrings.dagStreamRestFallback,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = glassSecondaryContentColor(),
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                FluidGlassChip(
                                                    selected = feedPlaying,
                                                    useBackdropCapture = false,
                                                    lockParentGestures = false,
                                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                                                    onClick = { feedPlaying = !feedPlaying },
                                                ) {
                                                    Icon(
                                                        imageVector = if (feedPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        text = if (feedPlaying) walletStrings.explorerPlayLabel else walletStrings.explorerPauseLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                                KineticRefreshIconButton(
                                                    isRefreshing = feedLoading,
                                                    contentDescription = walletStrings.refresh,
                                                    onClick = {
                                                        scope.launch {
                                                            feedLoading = true
                                                            viewModel.refreshExplorerLiveFeedSafe(network)
                                                                .onSuccess { merged ->
                                                                    if (merged.isNotEmpty()) feedItems = merged
                                                                }
                                                            feedLoading = false
                                                        }
                                                    },
                                                    size = 34.dp,
                                                    iconSize = 16.dp,
                                                    preset = GlassPreset.Control,
                                                    useBackdropCapture = false,
                                                    lockParentGestures = false,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        FluidOptionGridSelector(
                                            title = walletStrings.explorerLiveFeedTitle,
                                            items = LiveFeedFilter.entries,
                                            selectedItem = feedFilter,
                                            onSelect = { feedFilter = it },
                                            labelFor = {
                                                when (it) {
                                                    LiveFeedFilter.ALL -> walletStrings.explorerFilterAll
                                                    LiveFeedFilter.TRANSFERS -> walletStrings.explorerFilterTransfers
                                                    LiveFeedFilter.COINBASE -> walletStrings.explorerFilterCoinbase
                                                    LiveFeedFilter.LARGE -> walletStrings.explorerFilterLarge
                                                }
                                            },
                                        )
                                    }
                                }

                                if (feedItems.isEmpty()) {
                                    item(key = "feed_loading") {
                                        FluidGlassCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            backdrop = effectiveAmbient,
                                            useBackdropCapture = false,
                                            contentPadding = PaddingValues(24.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                            ) {
                                                if (feedLoading || feedPlaying) {
                                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.2.dp)
                                                    Spacer(Modifier.width(12.dp))
                                                }
                                                Text(
                                                    text = walletStrings.explorerWaitingLiveFeed,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = glassSecondaryContentColor(),
                                                )
                                            }
                                        }
                                    }
                                } else if (filteredFeed.isEmpty()) {
                                    // Shown ONLY when the buffer IS populated and the user's explicit filter truly matches 0 rows
                                    item(key = "feed_empty") {
                                        FluidGlassCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            backdrop = effectiveAmbient,
                                            useBackdropCapture = false,
                                            contentPadding = PaddingValues(20.dp),
                                        ) {
                                            Text(
                                                text = walletStrings.explorerNoResultsBody,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = glassSecondaryContentColor(),
                                            )
                                        }
                                    }
                                } else {
                                    items(filteredFeed, key = { it.transactionId }) { item ->
                                        val label = addressLabels[item.primaryRecipient]
                                        LiveFeedTxRowCard(
                                            item = item,
                                            entityLabel = label,
                                            unit = unit,
                                            timeFormatter = timeFormatter,
                                            onOpenTx = { navigateTo(ExplorerTarget.Transaction(item.transactionId)) },
                                            onOpenAddress = {
                                                if (item.primaryRecipient.isNotBlank()) {
                                                    navigateTo(ExplorerTarget.Address(item.primaryRecipient))
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        is ExplorerTarget.Transaction -> {
                            if (isLoading && txDetail == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ExplorerLoadingCard(effectiveAmbient)
                                }
                            } else if (txDetail != null) {
                                ExplorerTransactionDetailBody(
                                    tx = txDetail!!,
                                    labels = addressLabels,
                                    unit = unit,
                                    network = network,
                                    backdrop = effectiveAmbient,
                                    topContentPadding = bodyTopPadding,
                                    errorMsg = errorMsg,
                                    gestureLocked = gestureLocked,
                                    onOpenBlock = { navigateTo(ExplorerTarget.Block(it)) },
                                    onOpenAddress = { navigateTo(ExplorerTarget.Address(it)) },
                                    onNotify = onNotify,
                                )
                            } else if (!errorMsg.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ErrorInlineBanner(message = errorMsg!!)
                                }
                            }
                        }

                        is ExplorerTarget.Block -> {
                            if (isLoading && blockDetail == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ExplorerLoadingCard(effectiveAmbient)
                                }
                            } else if (blockDetail != null) {
                                ExplorerBlockDetailBody(
                                    block = blockDetail!!,
                                    labels = addressLabels,
                                    unit = unit,
                                    network = network,
                                    backdrop = effectiveAmbient,
                                    topContentPadding = bodyTopPadding,
                                    errorMsg = errorMsg,
                                    gestureLocked = gestureLocked,
                                    onOpenBlock = { navigateTo(ExplorerTarget.Block(it)) },
                                    onOpenTx = { navigateTo(ExplorerTarget.Transaction(it)) },
                                    onOpenAddress = { navigateTo(ExplorerTarget.Address(it)) },
                                    onNotify = onNotify,
                                )
                            } else if (!errorMsg.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ErrorInlineBanner(message = errorMsg!!)
                                }
                            }
                        }

                        is ExplorerTarget.Address -> {
                            if (isLoading && addressDetail == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ExplorerLoadingCard(effectiveAmbient)
                                }
                            } else if (addressDetail != null) {
                                ExplorerAddressDetailBody(
                                    detail = addressDetail!!,
                                    labels = addressLabels,
                                    unit = unit,
                                    spotPrice = if (privacyKasOnly || network.isTestnet) null else spotPrice,
                                    network = network,
                                    backdrop = effectiveAmbient,
                                    topContentPadding = bodyTopPadding,
                                    errorMsg = errorMsg,
                                    gestureLocked = gestureLocked,
                                    loadingMore = loadingMoreAddrTxs,
                                    onLoadMore = {
                                        val cursor = addressDetail?.nextPageBefore ?: return@ExplorerAddressDetailBody
                                        if (loadingMoreAddrTxs) return@ExplorerAddressDetailBody
                                        scope.launch {
                                            loadingMoreAddrTxs = true
                                            viewModel.fetchExplorerAddressSafe(
                                                network = network,
                                                address = target.address,
                                                beforeTimestampMs = cursor,
                                            ).onSuccess { next ->
                                                val prev = addressDetail ?: return@onSuccess
                                                val merged = (prev.transactions + next.transactions).distinctBy { it.transactionId }
                                                addressDetail = prev.copy(
                                                    transactions = merged,
                                                    nextPageBefore = if (next.transactions.isEmpty()) null else next.nextPageBefore,
                                                )
                                            }
                                            loadingMoreAddrTxs = false
                                        }
                                    },
                                    onOpenTx = { navigateTo(ExplorerTarget.Transaction(it)) },
                                    onNotify = onNotify,
                                )
                            } else if (!errorMsg.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .padding(start = 16.dp, end = 16.dp, top = bodyTopPadding),
                                ) {
                                    ErrorInlineBanner(message = errorMsg!!)
                                }
                            }
                        }
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header + Universal Search Bar + Top Progressive Blur
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveViewport,
            LocalOverlayBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = headerTitle,
                subtitle = headerSubtitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            } else {
                                onDismiss()
                            }
                        },
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = kineticStrings.back,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                trailingActions = if (currentTarget != null && currentTarget != ExplorerTarget.Feed) {
                    {
                        FluidGlassIconButton(
                            onClick = {
                                if (startedFromDirectLookup) {
                                    onDismiss()
                                } else {
                                    backStack.clear()
                                    backStack.add(ExplorerTarget.Feed)
                                    searchQuery = ""
                                }
                            },
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectiveViewport,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = kineticStrings.close, modifier = Modifier.size(18.dp))
                        }
                    }
                } else null,
                subBarContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FluidGlassTextField(
                            modifier = Modifier.weight(1f),
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = walletStrings.blockExplorerSearchPlaceholder,
                            singleLine = true,
                            backdrop = effectiveViewport,
                            useBackdropCapture = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { executeSearch(searchQuery) }),
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
                                        size = 28.dp,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = walletStrings.clear, modifier = Modifier.size(14.dp))
                                    }
                                } else {
                                    FluidGlassIconButton(
                                        onClick = {
                                            readTextFromClipboard(context)?.trim()?.takeIf { it.isNotEmpty() }?.let { clip ->
                                                searchQuery = clip
                                                executeSearch(clip)
                                            }
                                        },
                                        size = 28.dp,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = walletStrings.paste, modifier = Modifier.size(14.dp))
                                    }
                                }
                            },
                        )
                        FluidGlassIconButton(
                            onClick = { executeSearch(searchQuery) },
                            preset = GlassPreset.AccentPill,
                            backdrop = effectiveViewport,
                            size = 44.dp,
                        ) {
                            Icon(Icons.Default.Search, contentDescription = walletStrings.explorerSearchButton, modifier = Modifier.size(20.dp))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ExplorerLoadingCard(backdrop: Backdrop?) {
    val walletStrings = LocalWalletStrings.current
    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = backdrop,
        useBackdropCapture = false,
        contentPadding = PaddingValues(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.8.dp)
            Text(
                text = walletStrings.loading,
                style = MaterialTheme.typography.bodyMedium,
                color = glassSecondaryContentColor(),
            )
        }
    }
}

@Composable
private fun LiveFeedTxRowCard(
    item: LiveTxFeedItem,
    entityLabel: String?,
    unit: String,
    timeFormatter: DateFormat,
    onOpenTx: () -> Unit,
    onOpenAddress: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(20.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
        isDark -> MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    }
    val borderColor = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.86f)
    }
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val timeText = remember(item.timestampMs) {
        if (item.timestampMs > 0L) timeFormatter.format(Date(item.timestampMs)) else ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kineticInteractiveRow(
                shape = shape,
                backgroundColor = bg,
                borderColor = borderColor,
                onClick = onOpenTx,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExplorerStatusPill(
                    text = if (item.isCoinbase) walletStrings.explorerCoinbaseBadge else walletStrings.explorerAcceptedBadge,
                    color = if (item.isCoinbase) Color(0xFFF59E0B) else Color(0xFF22C55E),
                )
                if (!entityLabel.isNullOrBlank()) {
                    EntityLabelPill(text = entityLabel)
                }
            }
            Text(
                text = "${KasFormat.formatKasSummary(item.totalOutputSompi)} $unit",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accentInk,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${item.transactionId.take(16)}…${item.transactionId.takeLast(8)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = glassPrimaryContentColor(),
                maxLines = 1,
            )
            Text(
                text = "${item.inputCount}→${item.outputCount} • $timeText",
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
        }
        if (item.primaryRecipient.isNotBlank()) {
            Text(
                text = item.primaryRecipient,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = glassSecondaryContentColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onOpenAddress),
            )
        }
    }
}

@Composable
private fun ExplorerTransactionDetailBody(
    tx: ExplorerTxDetail,
    labels: Map<String, String>,
    unit: String,
    network: KaspaNetwork,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    errorMsg: String?,
    gestureLocked: Boolean,
    onOpenBlock: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onNotify: (String) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val timeText = remember(tx.blockTimeMs) {
        if (tx.blockTimeMs > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tx.blockTimeMs))
        else walletStrings.txPending
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        userScrollEnabled = !gestureLocked,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topContentPadding,
            bottom = 28.dp,
        ),
    ) {
        if (!errorMsg.isNullOrBlank()) {
            item(key = "tx_err") { ErrorInlineBanner(message = errorMsg) }
        }
        item(key = "tx_summary") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExplorerStatusPill(
                            text = if (tx.isAccepted) walletStrings.explorerAcceptedBadge else walletStrings.explorerUnacceptedBadge,
                            color = if (tx.isAccepted) Color(0xFF22C55E) else Color(0xFFF59E0B),
                        )
                        if (tx.isCoinbase) {
                            ExplorerStatusPill(text = walletStrings.explorerCoinbaseBadge, color = Color(0xFF38BDF8))
                        }
                    }
                    Text(
                        text = "${KasFormat.formatKas(tx.totalOutputSompi, maxDecimals = 8, grouping = true)} $unit",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(walletStrings.txIdLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                Text(
                    text = tx.transactionId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.clickable {
                        copyTextToClipboard(context, "Transaction ID", tx.transactionId)
                        onNotify(walletStrings.copiedToClipboard)
                    },
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.6.dp, color = glassSecondaryContentColor().copy(alpha = 0.18f))
                Spacer(Modifier.height(10.dp))
                ExplorerKvRow(walletStrings.txTimeLabel, timeText)
                ExplorerKvRow(walletStrings.txFeeLabel, "${KasFormat.formatKas(tx.feeSompi, maxDecimals = 8, grouping = true)} $unit")
                if (tx.mass > 0L) ExplorerKvRow(walletStrings.txMassLabel, "${tx.mass}")
                tx.acceptingBlockBlueScore?.let { ExplorerKvRow(walletStrings.txBlueScoreLabel, "$it") }

                val blockToOpen = tx.acceptingBlockHash ?: tx.blockHashes.firstOrNull()
                if (!blockToOpen.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(walletStrings.explorerContainingBlockLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                    Text(
                        text = blockToOpen,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        modifier = Modifier
                            .clip(ContinuousCornerShape(10.dp))
                            .clickable { onOpenBlock(blockToOpen) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        if (tx.inputs.isNotEmpty()) {
            item(key = "tx_inputs") {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backdrop = backdrop,
                    useBackdropCapture = false,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = "${walletStrings.txInputsLabel} (${tx.inputs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Spacer(Modifier.height(8.dp))
                    tx.inputs.forEachIndexed { idx, inp ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                thickness = 0.5.dp,
                                color = glassSecondaryContentColor().copy(alpha = 0.16f),
                            )
                        }
                        val addr = inp.address
                        val label = addr?.let { labels[it] }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!label.isNullOrBlank()) {
                                    EntityLabelPill(label)
                                } else {
                                    Text("#$idx", style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                                }
                                inp.amountSompi?.let { amt ->
                                    Text(
                                        text = "${KasFormat.formatKas(amt, maxDecimals = 8, grouping = true)} $unit",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = glassPrimaryContentColor(),
                                    )
                                }
                            }
                            if (!addr.isNullOrBlank()) {
                                Box(modifier = Modifier.clickable { onOpenAddress(addr) }) {
                                    HighlightedKaspaAddressText(address = addr, fontSizeSp = 11.5f)
                                }
                            } else if (inp.previousTxId.isNotBlank()) {
                                Text(
                                    text = "${inp.previousTxId.take(24)}…:${inp.previousOutpointIndex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "tx_outputs") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = false,
                contentPadding = PaddingValues(14.dp),
            ) {
                Text(
                    text = "${walletStrings.txOutputsLabel} (${tx.outputs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
                Spacer(Modifier.height(8.dp))
                tx.outputs.forEachIndexed { idx, out ->
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            thickness = 0.5.dp,
                            color = glassSecondaryContentColor().copy(alpha = 0.16f),
                        )
                    }
                    val label = labels[out.address]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!label.isNullOrBlank()) {
                                EntityLabelPill(label)
                            } else {
                                Text("#${out.index}", style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                            }
                            Text(
                                text = "${KasFormat.formatKas(out.amountSompi, maxDecimals = 8, grouping = true)} $unit",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                            )
                        }
                        if (out.address.isNotBlank()) {
                            Box(modifier = Modifier.clickable { onOpenAddress(out.address) }) {
                                HighlightedKaspaAddressText(address = out.address, fontSizeSp = 11.5f)
                            }
                        }
                    }
                }
            }
        }

        item(key = "tx_external") {
            SecondaryExternalActionsRow(
                idToCopy = tx.transactionId,
                externalUrl = "${network.explorerBaseUrl}/transactions/${tx.transactionId}",
                onNotify = onNotify,
            )
        }
    }
}

@Composable
private fun ExplorerBlockDetailBody(
    block: ExplorerBlockDetail,
    labels: Map<String, String>,
    unit: String,
    network: KaspaNetwork,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    errorMsg: String?,
    gestureLocked: Boolean,
    onOpenBlock: (String) -> Unit,
    onOpenTx: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onNotify: (String) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val timeText = remember(block.timestampMs) {
        if (block.timestampMs > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(block.timestampMs))
        else "—"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        userScrollEnabled = !gestureLocked,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topContentPadding,
            bottom = 28.dp,
        ),
    ) {
        if (!errorMsg.isNullOrBlank()) {
            item(key = "blk_err") { ErrorInlineBanner(message = errorMsg) }
        }
        item(key = "block_summary") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
                    if (block.isChainBlock) {
                        ExplorerStatusPill(walletStrings.explorerChainBlockBadge, Color(0xFF22C55E))
                    }
                    when (block.color) {
                        "blue" -> ExplorerStatusPill(walletStrings.explorerBlueBlockBadge, Color(0xFF38BDF8))
                        "red" -> ExplorerStatusPill(walletStrings.explorerRedBlockBadge, Color(0xFFEF4444))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = block.hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.clickable {
                        copyTextToClipboard(context, "Block Hash", block.hash)
                        onNotify(walletStrings.copiedToClipboard)
                    },
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.6.dp, color = glassSecondaryContentColor().copy(alpha = 0.18f))
                Spacer(Modifier.height(10.dp))
                ExplorerKvRow(walletStrings.daaScoreLabel, "${block.daaScore}")
                ExplorerKvRow(walletStrings.txBlueScoreLabel, "${block.blueScore}")
                ExplorerKvRow(walletStrings.txTimeLabel, timeText)
                ExplorerKvRow(walletStrings.explorerBlockTransactionsLabel, "${block.txCount}")
                if (!block.minerInfo.isNullOrBlank()) {
                    ExplorerKvRow(walletStrings.explorerMinerLabel, block.minerInfo)
                }
                if (!block.minerAddress.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    val mLabel = labels[block.minerAddress]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(walletStrings.explorerMinerLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                        if (!mLabel.isNullOrBlank()) EntityLabelPill(mLabel)
                    }
                    Box(modifier = Modifier.clickable { onOpenAddress(block.minerAddress) }) {
                        HighlightedKaspaAddressText(address = block.minerAddress, fontSizeSp = 11.5f)
                    }
                }
            }
        }

        if (block.parentHashes.isNotEmpty()) {
            item(key = "block_parents") {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backdrop = backdrop,
                    useBackdropCapture = false,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = "${walletStrings.explorerParentsLabel} (${block.parentHashes.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Spacer(Modifier.height(8.dp))
                    block.parentHashes.forEach { ph ->
                        val isSel = ph == block.selectedParentHash
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ContinuousCornerShape(12.dp))
                                .clickable { onOpenBlock(ph) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isSel) {
                                ExplorerStatusPill(walletStrings.explorerSelectedParentLabel, Color(0xFF22C55E))
                            }
                            Text(
                                text = "${ph.take(18)}…${ph.takeLast(10)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = accentInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (block.transactions.isNotEmpty()) {
            item(key = "block_txs_header") {
                Text(
                    text = "${walletStrings.explorerBlockTransactionsLabel} (${block.transactions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(block.transactions, key = { it.transactionId }) { tx ->
                val reduceTransparency = LocalReduceTransparency.current
                val shape = ContinuousCornerShape(18.dp)
                val bg = when {
                    reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
                    isDark -> MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                }
                val borderColor = when {
                    reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
                    isDark -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.86f)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kineticInteractiveRow(
                            shape = shape,
                            backgroundColor = bg,
                            borderColor = borderColor,
                            onClick = { onOpenTx(tx.transactionId) },
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${tx.transactionId.take(16)}…${tx.transactionId.takeLast(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = accentInk,
                        )
                        Text(
                            text = "${KasFormat.formatKasSummary(tx.totalOutputSompi)} $unit",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    }
                    Text(
                        text = if (tx.isCoinbase) walletStrings.explorerCoinbaseBadge else "${tx.inputCount} → ${tx.outputCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                }
            }
        }

        item(key = "block_external") {
            SecondaryExternalActionsRow(
                idToCopy = block.hash,
                externalUrl = "${network.explorerBaseUrl}/blocks/${block.hash}",
                onNotify = onNotify,
            )
        }
    }
}

@Composable
private fun ExplorerAddressDetailBody(
    detail: ExplorerAddressDetail,
    labels: Map<String, String>,
    unit: String,
    spotPrice: SpotPrice?,
    network: KaspaNetwork,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    errorMsg: String?,
    gestureLocked: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenTx: (String) -> Unit,
    onNotify: (String) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val resolvedLabel = detail.label ?: labels[detail.address]

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        userScrollEnabled = !gestureLocked,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topContentPadding,
            bottom = 28.dp,
        ),
    ) {
        if (!errorMsg.isNullOrBlank()) {
            item(key = "addr_err") { ErrorInlineBanner(message = errorMsg) }
        }
        item(key = "addr_summary") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
                    if (!resolvedLabel.isNullOrBlank()) {
                        EntityLabelPill(resolvedLabel)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = KasFormat.formatKasSummary(detail.balanceSompi),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentInk,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                if (spotPrice != null) {
                    val fiatVal = KasFormat.formatFiat(detail.balanceSompi, spotPrice)
                    if (fiatVal.isNotBlank()) {
                        Text(
                            text = fiatVal,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = glassSecondaryContentColor(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.clickable {
                        copyTextToClipboard(context, "Kaspa Address", detail.address)
                        onNotify(walletStrings.copiedToClipboard)
                    },
                ) {
                    HighlightedKaspaAddressText(address = detail.address, fontSizeSp = 12f)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.6.dp, color = glassSecondaryContentColor().copy(alpha = 0.18f))
                Spacer(Modifier.height(10.dp))
                ExplorerKvRow(walletStrings.explorerUtxoCountLabel, "${detail.utxoCount}")
                ExplorerKvRow(walletStrings.explorerTotalTxCountLabel, "${detail.txCount}")
            }
        }

        item(key = "addr_txs_title") {
            Text(
                text = "${walletStrings.tabTransactions} (${detail.transactions.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        items(detail.transactions, key = { it.transactionId }) { tx ->
            val reduceTransparency = LocalReduceTransparency.current
            val shape = ContinuousCornerShape(18.dp)
            val bg = when {
                reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
                isDark -> MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            }
            val borderColor = when {
                reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
                isDark -> Color.White.copy(alpha = 0.16f)
                else -> Color.White.copy(alpha = 0.86f)
            }
            val inForAddr = tx.inputs.filter { it.address == detail.address }.sumOf { it.amountSompi ?: 0L }
            val outForAddr = tx.outputs.filter { it.address == detail.address }.sumOf { it.amountSompi }
            val net = outForAddr - inForAddr
            val netColor = when {
                net > 0L -> if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
                net < 0L -> MaterialTheme.colorScheme.error
                else -> glassPrimaryContentColor()
            }
            val timeStr = remember(tx.blockTimeMs) {
                if (tx.blockTimeMs > 0L) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(tx.blockTimeMs))
                else walletStrings.txPending
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .kineticInteractiveRow(
                        shape = shape,
                        backgroundColor = bg,
                        borderColor = borderColor,
                        onClick = { onOpenTx(tx.transactionId) },
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${tx.transactionId.take(14)}…${tx.transactionId.takeLast(8)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = accentInk,
                    )
                    Text(
                        text = (if (net >= 0L) "+" else "-") + KasFormat.formatKasSummary(kotlin.math.abs(net)) + " $unit",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = netColor,
                    )
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
            }
        }

        if (detail.nextPageBefore != null) {
            item(key = "addr_load_more") {
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loadingMore,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = onLoadMore,
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(walletStrings.explorerLoadMoreButton, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item(key = "addr_external") {
            SecondaryExternalActionsRow(
                idToCopy = detail.address,
                externalUrl = "${network.explorerBaseUrl}/addresses/${detail.address}",
                onNotify = onNotify,
            )
        }
    }
}

@Composable
private fun SecondaryExternalActionsRow(
    idToCopy: String,
    externalUrl: String,
    onNotify: (String) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = false,
            lockParentGestures = false,
            onClick = {
                copyTextToClipboard(context, "Kaspa ID", idToCopy)
                onNotify(walletStrings.copiedToClipboard)
            },
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            MarqueeText(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
        }
        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = false,
            lockParentGestures = false,
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            MarqueeText(walletStrings.openOnKaspaStreamButton, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ExplorerKvRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = glassSecondaryContentColor(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
    }
}

@Composable
fun EntityLabelPill(text: String) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(50.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
        isDark -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val ink = when {
        reduceTransparency || isDark -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ExplorerStatusPill(text: String, color: Color) {
    val isDark = LocalGlassDarkTheme.current
    val shape = ContinuousCornerShape(50.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = if (isDark) 0.22f else 0.14f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isDark) color else color.copy(alpha = 0.92f),
            maxLines = 1,
        )
    }
}
