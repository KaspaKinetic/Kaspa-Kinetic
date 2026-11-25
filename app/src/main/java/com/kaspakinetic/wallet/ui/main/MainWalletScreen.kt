package com.kaspakinetic.wallet.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.MainTab
import com.kaspakinetic.wallet.core.model.SyncStatus
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.TxFilter
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.model.WalletTx
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.core.covenants.TrackedCovenant
import com.kaspakinetic.wallet.ui.components.HighlightedKaspaAddressText
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.KineticRefreshIconButton
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.SmallStatusTag
import com.kaspakinetic.wallet.ui.components.formatFilterChipLabel
import com.kaspakinetic.wallet.ui.covenants.HomeCovenantsTabContent
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSegmentedControl
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
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
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Primary Kaspa Kinetic wallet workspace implementing strict Tier 1 & Tier 2 Kinetic Aero glass
 * layering, clean top header bar (Drawer, Active Wallet pill, Circular Node Status dot button,
 * QR Scanner), Primary Balance Card with Privacy Mask toggle, 3 tabs (Transactions / UTXOs /
 * Covenants), and Floating Glass Action Dock (Receive / Send).
 */
@Composable
fun MainWalletScreen(
    appearance: KineticAeroAppearanceSettings,
    walletState: WalletUiState,
    onOpenDrawer: () -> Unit,
    onOpenNodeStatus: () -> Unit,
    onToggleHideBalances: () -> Unit,
    onRefresh: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenSend: (useSelectedUtxos: Boolean) -> Unit,
    onOpenCompound: () -> Unit,
    onOpenYourPerformance: () -> Unit,
    onSelectTx: (WalletTx) -> Unit,
    onToggleUtxo: (String) -> Unit,
    onClearUtxoSelection: () -> Unit,
    onInspectInExplorer: (String) -> Unit,
    covenants: List<TrackedCovenant>,
    confirmingCovenantKeys: Set<String> = emptySet(),
    chainTimeAnchorFlow: kotlinx.coroutines.flow.StateFlow<com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor>? = null,
    onPollServerAnchor: (suspend () -> Unit)? = null,
    onOpenCovenantsHub: () -> Unit,
    onSelectTrackedCovenant: (String) -> Unit,
    onRefreshCovenants: () -> Unit = {},
    onClearSnackbar: () -> Unit,
    overlayContent: @Composable () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    var activeTab by remember { mutableStateOf(MainTab.TRANSACTIONS) }
    var txFilter by remember { mutableStateOf(TxFilter.ALL) }
    var txSearch by remember { mutableStateOf("") }

    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val showFiat = !walletState.prefs.kasOnlyPrivacyMode && !walletState.activeNetwork.isTestnet
    val hideBalances = walletState.prefs.hideBalances
    val activeWallet = walletState.activeWallet
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    val filteredTxs = remember(walletState.transactions, txFilter, txSearch, walletState.prefs.txNotes) {
        val q = txSearch.trim()
        walletState.transactions.filter { tx ->
            val matchFilter = when (txFilter) {
                TxFilter.ALL -> true
                TxFilter.RECEIVED -> tx.direction == TxDirection.RECEIVED
                TxFilter.SENT -> tx.direction == TxDirection.SENT
                TxFilter.SELF_COMPOUND -> tx.direction == TxDirection.SELF_TRANSFER || tx.direction == TxDirection.COMPOUND
                TxFilter.FUN_STUFF -> tx.direction == TxDirection.BURN || tx.direction == TxDirection.MINER_DONATION
            }
            if (!matchFilter) return@filter false
            if (q.isEmpty()) return@filter true
            val note = walletState.prefs.txNotes[tx.transactionId].orEmpty()
            tx.transactionId.contains(q, ignoreCase = true) ||
                tx.counterpartyAddress.contains(q, ignoreCase = true) ||
                note.contains(q, ignoreCase = true)
        }
    }

    LaunchedEffect(activeTab, walletState.prefs.covenantsActivated) {
        if (activeTab == MainTab.COVENANTS && walletState.prefs.covenantsActivated) {
            onRefreshCovenants()
        }
    }

    LaunchedEffect(walletState.snackbarMessage) {
        if (walletState.snackbarMessage != null) {
            delay(3_200L)
            onClearSnackbar()
        }
    }

    // Tier 1 (Wallpaper) + Tier 2 (Viewport for Pinned Floating Header, Progressive Blur, Dock & Overlays)
    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 100.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val ambientBackdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Tier 2 captured layer (Wallpaper + Full-Screen Seamless Scrollable Wallet Content)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(viewportBackdrop) else Modifier),
        ) {
            // Tier 1 Wallpaper Backdrop + Adaptive Contrast Scrim for Custom Wallpapers
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
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isDark) Color(0xFF070B10).copy(alpha = 0.46f)
                                    else Color(0xFFF3F7F5).copy(alpha = 0.52f),
                                ),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides effectiveAmbient) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    userScrollEnabled = !gestureLocked,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topPadDp + 10.dp,
                        bottom = 112.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. Primary Balance Card (with Privacy Mask Eye Toggle next to Refresh!)
                    item(key = "balance_card") {
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveAmbient,
                            contentPadding = PaddingValues(20.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = walletStrings.totalBalanceLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = glassSecondaryContentColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FluidGlassIconButton(
                                        onClick = onToggleHideBalances,
                                        size = 34.dp,
                                        preset = GlassPreset.FloatingBar,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(
                                            imageVector = if (hideBalances) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (hideBalances) walletStrings.showBalancesTooltip else walletStrings.hideBalancesTooltip,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    KineticRefreshIconButton(
                                        isRefreshing = walletState.isRefreshing,
                                        contentDescription = walletStrings.refresh,
                                        onClick = onRefresh,
                                        size = 34.dp,
                                        iconSize = 18.dp,
                                        preset = GlassPreset.FloatingBar,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Canonical 3D Kinetic Aero frosted glass "Your Performance" button matching Eye & Refresh buttons
                            FluidGlassButton(
                                onClick = onOpenYourPerformance,
                                accent = false,
                                preset = GlassPreset.FloatingBar,
                                backdrop = effectiveAmbient,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = walletStrings.yourPerformancePillLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = glassPrimaryContentColor(),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (hideBalances) walletStrings.maskedAmount else KasFormat.formatKasSummary(walletState.totalBalanceSompi),
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.8).sp,
                                    color = glassPrimaryContentColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    text = unit,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                    modifier = Modifier.padding(bottom = 5.dp),
                                )
                            }

                            if (showFiat && walletState.spotPrice != null) {
                                Spacer(Modifier.height(6.dp))
                                val spot = walletState.spotPrice
                                val fiatTotal = if (hideBalances) walletStrings.maskedAmount else KasFormat.formatFiat(walletState.totalBalanceSompi, spot)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = fiatTotal,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = glassSecondaryContentColor(),
                                    )
                                    val changeText = spot.change24hPercent?.let { pct ->
                                        val sign = if (pct >= 0) "+" else ""
                                        String.format(Locale.US, " (%s%.2f%%)", sign, pct)
                                    }.orEmpty()
                                    val spotStr = "${KasFormat.formatSpotRate(spot)}$changeText"
                                    Text(
                                        text = "${walletStrings.spotPriceLabel}: $spotStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = glassSecondaryContentColor(),
                                    )
                                }
                            }

                            if (activeWallet?.isWatchOnly == true) {
                                Spacer(Modifier.height(12.dp))
                                val watchCardPreset = if (isDark) GlassPreset.HighlightSurface else GlassPreset.Card
                                val watchCardTint = when {
                                    reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
                                    isDark -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                }
                                val watchTitleColor = when {
                                    reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isDark -> Color(0xFFF4FFF8)
                                    else -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
                                }
                                val watchSubtitleColor = when {
                                    reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    isDark -> Color(0xFFD6EDE0)
                                    else -> glassSecondaryContentColor()
                                }
                                val watchIconTint = when {
                                    reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isDark -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
                                }
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = ContinuousCornerShape(18.dp),
                                    preset = watchCardPreset,
                                    useBackdropCapture = false,
                                    surfaceTint = watchCardTint,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = watchIconTint,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Column {
                                            Text(
                                                text = walletStrings.watchOnlyBanner,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = watchTitleColor,
                                            )
                                            Text(
                                                text = walletStrings.watchOnlyBannerSubtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = watchSubtitleColor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Three-Tab Segmented Control (Real frosted glass backing + crisp unselected contrast)
                    item(key = "main_tabs") {
                        FluidGlassSegmentedControl(
                            items = MainTab.entries,
                            selectedItem = activeTab,
                            backdrop = effectiveAmbient,
                            useBackdropCapture = true,
                            preset = GlassPreset.Card,
                            onSelect = { activeTab = it },
                            labelFor = {
                                when (it) {
                                    MainTab.TRANSACTIONS -> walletStrings.tabTransactions
                                    MainTab.UTXOS -> "${walletStrings.tabUtxos} (${walletState.utxos.size})"
                                    MainTab.COVENANTS -> walletStrings.tabCovenants
                                }
                            },
                        )
                    }

                    // 4. Tab Content
                    when (activeTab) {
                        MainTab.TRANSACTIONS -> {
                            item(key = "tx_search_filters") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FluidGlassTextField(
                                        value = txSearch,
                                        onValueChange = { txSearch = it },
                                        placeholder = walletStrings.searchTxPlaceholder,
                                        singleLine = true,
                                        backdrop = effectiveAmbient,
                                        useBackdropCapture = true,
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                tint = glassPrimaryContentColor().copy(alpha = 0.78f),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        trailingContent = {
                                            if (txSearch.isNotEmpty()) {
                                                FluidGlassIconButton(
                                                    onClick = { txSearch = "" },
                                                    size = 26.dp,
                                                    useBackdropCapture = false,
                                                    lockParentGestures = false,
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        },
                                    )
                                    val txFilterCounts = remember(walletState.transactions) {
                                        mapOf(
                                            TxFilter.ALL to walletState.transactions.size,
                                            TxFilter.RECEIVED to walletState.transactions.count { it.direction == TxDirection.RECEIVED },
                                            TxFilter.SENT to walletState.transactions.count { it.direction == TxDirection.SENT },
                                            TxFilter.SELF_COMPOUND to walletState.transactions.count {
                                                it.direction == TxDirection.SELF_TRANSFER || it.direction == TxDirection.COMPOUND
                                            },
                                            TxFilter.FUN_STUFF to walletState.transactions.count {
                                                it.direction == TxDirection.BURN || it.direction == TxDirection.MINER_DONATION
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        TxFilter.entries.forEach { f ->
                                            val baseLabel = when (f) {
                                                TxFilter.ALL -> walletStrings.filterAll
                                                TxFilter.RECEIVED -> walletStrings.filterReceived
                                                TxFilter.SENT -> walletStrings.filterSent
                                                TxFilter.SELF_COMPOUND -> walletStrings.filterSelfCompound
                                                TxFilter.FUN_STUFF -> walletStrings.filterFunStuff
                                            }
                                            val chipLabel = formatFilterChipLabel(
                                                baseLabel = baseLabel,
                                                count = txFilterCounts[f] ?: 0,
                                                hideBalances = hideBalances,
                                            )
                                            FluidGlassChip(
                                                selected = txFilter == f,
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                onClick = { txFilter = f },
                                            ) {
                                                Text(
                                                    text = chipLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (filteredTxs.isEmpty()) {
                                item(key = "tx_empty") {
                                    EmptyStateCard(
                                        title = walletStrings.noTransactionsTitle,
                                        body = walletStrings.noTransactionsBody,
                                    )
                                }
                            } else {
                                items(filteredTxs, key = { it.transactionId }) { tx ->
                                    TransactionRowCard(
                                        tx = tx,
                                        unit = unit,
                                        hideBalances = hideBalances,
                                        fiatText = if (showFiat) KasFormat.formatFiat(kotlin.math.abs(tx.netAmountSompi), walletState.spotPrice) else "",
                                        localNote = walletState.prefs.txNotes[tx.transactionId].orEmpty(),
                                        onClick = { onSelectTx(tx) },
                                    )
                                }
                            }
                        }

                        MainTab.UTXOS -> {
                            if (walletState.selectedUtxoKeys.isNotEmpty()) {
                                item(key = "utxo_selection_bar") {
                                    FluidGlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        useBackdropCapture = false,
                                        contentPadding = PaddingValues(12.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "${walletState.selectedUtxoKeys.size} ${walletStrings.utxoSelectedCount}",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                            )
                                            FluidGlassChip(
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                onClick = onClearUtxoSelection,
                                            ) {
                                                Text(walletStrings.utxoClearSelection, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                accent = true,
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                onClick = { onOpenSend(true) },
                                            ) {
                                                Text(walletStrings.utxoSendSelected, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            }
                                            if (walletState.selectedUtxoKeys.size >= 2) {
                                                FluidGlassButton(
                                                    modifier = Modifier.weight(1f),
                                                    useBackdropCapture = false,
                                                    lockParentGestures = false,
                                                    onClick = onOpenCompound,
                                                ) {
                                                    Text(walletStrings.utxoCompoundSelected, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (walletState.utxos.isEmpty()) {
                                item(key = "utxo_empty") {
                                    EmptyStateCard(
                                        title = walletStrings.noUtxosTitle,
                                        body = walletStrings.noUtxosBody,
                                    )
                                }
                            } else {
                                items(walletState.utxos, key = { it.outpointKey }) { utxo ->
                                    UtxoRowCard(
                                        utxo = utxo,
                                        unit = unit,
                                        hideBalances = hideBalances,
                                        selected = utxo.outpointKey in walletState.selectedUtxoKeys,
                                        onToggle = { onToggleUtxo(utxo.outpointKey) },
                                        onInspect = { onInspectInExplorer(utxo.transactionId) },
                                    )
                                }
                            }
                        }

                        MainTab.COVENANTS -> {
                            item(key = "covenants_tab") {
                                val anchorState = chainTimeAnchorFlow?.collectAsState()
                                HomeCovenantsTabContent(
                                    covenantsActivated = walletState.prefs.covenantsActivated,
                                    covenants = covenants,
                                    activeNetwork = walletState.activeNetwork,
                                    virtualDaa = walletState.nodeHealth?.virtualDaaScore ?: 0L,
                                    hideBalances = hideBalances,
                                    kasOnlyPrivacyMode = walletState.prefs.kasOnlyPrivacyMode,
                                    spotPrice = walletState.spotPrice,
                                    onOpenCovenantsHub = onOpenCovenantsHub,
                                    onSelectCovenant = onSelectTrackedCovenant,
                                    activeWalletId = activeWallet?.id,
                                    chainTimeAnchor = anchorState?.value ?: com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor(),
                                    confirmingCovenantKeys = confirmingCovenantKeys,
                                    onPollServerAnchor = onPollServerAnchor,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tier 2 Pinned Floating Top Header + Progressive Blur + Floating Bottom Dock + In-Window Overlays
        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = activeWallet?.name ?: walletStrings.appName,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                onTitleClick = onOpenDrawer,
                titleTrailing = if (activeWallet?.isWatchOnly == true) {
                    {
                        SmallStatusTag(text = "WO", color = MaterialTheme.colorScheme.primary)
                    }
                } else null,
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = onOpenDrawer,
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                },
                trailingActions = {
                    NodeStatusDotButton(
                        status = walletState.syncStatus,
                        backdrop = effectiveViewport,
                        onClick = onOpenNodeStatus,
                    )
                    FluidGlassIconButton(
                        onClick = onOpenQrScanner,
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = walletStrings.scanQrButton, modifier = Modifier.size(20.dp))
                    }
                },
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .liquidGlassSurface(
                        shape = ContinuousCornerShape(50.dp),
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                        shadowEnabled = true,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluidGlassButton(
                    modifier = Modifier.weight(1f),
                    useBackdropCapture = false,
                    onClick = onOpenReceive,
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    MarqueeText(walletStrings.actionReceive, fontWeight = FontWeight.Bold)
                }
                FluidGlassButton(
                    modifier = Modifier.weight(1f),
                    accent = true,
                    useBackdropCapture = false,
                    onClick = { onOpenSend(false) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    MarqueeText(walletStrings.actionSend, fontWeight = FontWeight.Bold)
                }
            }

            // Transient in-window notification banner
            AnimatedVisibility(
                visible = walletState.snackbarMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadDp + 6.dp, start = 20.dp, end = 20.dp)
                    .zIndex(80f),
            ) {
                val msg = walletState.snackbarMessage.orEmpty()
                Row(
                    modifier = Modifier
                        .liquidGlassSurface(
                            shape = ContinuousCornerShape(50.dp),
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectiveViewport,
                            shadowEnabled = true,
                        )
                        .clickable { onClearSnackbar() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            overlayContent()
        }
    }
}

@Composable
private fun NodeStatusDotButton(
    status: SyncStatus,
    backdrop: Backdrop?,
    onClick: () -> Unit,
) {
    val dotColor = when (status) {
        SyncStatus.SYNCED -> Color(0xFF2ECC71)
        SyncStatus.CONNECTING -> Color(0xFFF39C12)
        SyncStatus.OFFLINE -> MaterialTheme.colorScheme.error
    }
    FluidGlassIconButton(
        onClick = onClick,
        size = 42.dp,
        preset = GlassPreset.FloatingBar,
        backdrop = backdrop,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

@Composable
private fun TransactionRowCard(
    tx: WalletTx,
    unit: String,
    hideBalances: Boolean,
    fiatText: String,
    localNote: String,
    onClick: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(20.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
        isDark -> Color(0xFF141B22).copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.82f)
    }
    val border = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.90f)
    }

    val (icon, label, accentColor) = when (tx.direction) {
        TxDirection.RECEIVED -> Triple(Icons.AutoMirrored.Filled.CallReceived, walletStrings.txReceived, Color(0xFF2ECC71))
        TxDirection.SENT -> Triple(Icons.AutoMirrored.Filled.CallMade, walletStrings.txSent, MaterialTheme.colorScheme.error)
        TxDirection.SELF_TRANSFER -> Triple(Icons.Default.Sync, walletStrings.txSelfTransfer, MaterialTheme.colorScheme.primary)
        TxDirection.COMPOUND -> Triple(Icons.AutoMirrored.Filled.MergeType, walletStrings.txCompound, MaterialTheme.colorScheme.primary)
        TxDirection.BURN -> Triple(Icons.Default.LocalFireDepartment, walletStrings.txBurn, MaterialTheme.colorScheme.error)
        TxDirection.MINER_DONATION -> Triple(Icons.Default.Memory, walletStrings.txMinerDonation, MaterialTheme.colorScheme.tertiary)
        TxDirection.COVENANT -> Triple(Icons.Default.Shield, walletStrings.menuCovenants, MaterialTheme.colorScheme.primary)
    }
    val timeText = remember(tx.blockTimeMs) {
        if (tx.blockTimeMs > 0L) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(tx.blockTimeMs))
        else walletStrings.txPending
    }

    // Strict sign semantics: + for incoming, - for outgoing/burn/miner donation, no +/- for internal compound/self
    val amountPrefix = when (tx.direction) {
        TxDirection.RECEIVED -> "+"
        TxDirection.SENT, TxDirection.BURN, TxDirection.MINER_DONATION, TxDirection.COVENANT -> "-"
        TxDirection.COMPOUND, TxDirection.SELF_TRANSFER -> ""
    }
    val amountColor = when (tx.direction) {
        TxDirection.RECEIVED -> accentColor
        TxDirection.SENT, TxDirection.BURN -> MaterialTheme.colorScheme.error
        else -> glassPrimaryContentColor()
    }
    val formattedKas = if (hideBalances) {
        "${walletStrings.maskedAmount} $unit"
    } else {
        "$amountPrefix${KasFormat.formatKasSummary(kotlin.math.abs(tx.netAmountSompi))} $unit"
    }
    val statusText = if (tx.isAccepted) walletStrings.txConfirmed else walletStrings.txPending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .kineticInteractiveRow(
                shape = shape,
                backgroundColor = bg,
                borderColor = border,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(19.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formattedKas,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = amountColor,
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$timeText • $statusText",
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fiatText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (hideBalances) walletStrings.maskedAmount else fiatText,
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                        maxLines = 1,
                    )
                }
            }

            if (localNote.isNotBlank()) {
                Text(
                    text = localNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UtxoRowCard(
    utxo: UtxoItem,
    unit: String,
    hideBalances: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onInspect: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(18.dp)
    val bg = when {
        selected && reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
        isDark -> Color(0xFF141B22).copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.82f)
    }
    val border = if (selected) MaterialTheme.colorScheme.primary else if (isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.88f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .kineticInteractiveRow(
                shape = shape,
                backgroundColor = bg,
                borderColor = border,
                borderWidth = if (selected) 1.25.dp else 0.85.dp,
                isHighlighted = selected,
                onClick = onToggle,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) readableAccentInk(MaterialTheme.colorScheme.primary, isDark) else glassSecondaryContentColor(),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hideBalances) "${walletStrings.maskedAmount} $unit" else "${KasFormat.formatKasSummary(utxo.amountSompi)} $unit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                )
                if (utxo.isCoinbase) {
                    SmallStatusTag(text = walletStrings.utxoCoinbaseTag, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            HighlightedKaspaAddressText(address = utxo.address, maxLines = 1, fontSizeSp = 11f)
            Text(
                text = "${walletStrings.utxoDaaLabel}: ${utxo.blockDaaScore} • #${utxo.index}",
                style = MaterialTheme.typography.labelSmall,
                color = glassSecondaryContentColor(),
            )
        }
        FluidGlassIconButton(
            onClick = onInspect,
            size = 32.dp,
            useBackdropCapture = false,
            lockParentGestures = false,
        ) {
            Icon(Icons.Default.Search, contentDescription = walletStrings.inspectInExplorerButton, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        useBackdropCapture = false,
        contentPadding = PaddingValues(24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = glassSecondaryContentColor(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
