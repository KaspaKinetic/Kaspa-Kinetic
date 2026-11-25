package com.kaspakinetic.wallet.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.explore.CandlePoint
import com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath
import com.kaspakinetic.wallet.core.explore.KaspaExploreRepository
import com.kaspakinetic.wallet.core.explore.OceanTier
import com.kaspakinetic.wallet.core.explore.PerformanceMilestone
import com.kaspakinetic.wallet.core.explore.PerformanceTimeframe
import com.kaspakinetic.wallet.core.explore.WalletGoalEstimate
import com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine
import com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
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
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.locale.safeFormatLocalized
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 100% locally calculated personal wallet analytics & milestones screen ("Your Performance").
 * Works identically for Hot HD wallets and Watch-Only wallets, respects selected fiat currency,
 * and gracefully honors KAS Only Privacy Mode (hiding fiat P&L while keeping KAS charts & milestones live).
 */
@Composable
fun YourPerformanceScreen(
    walletState: WalletUiState,
    appearance: KineticAeroAppearanceSettings,
    repository: KaspaExploreRepository,
    onToggleHideBalances: () -> Unit,
    onSetGoal: (targetKas: Double?, customMonthlyDca: Double?) -> Unit,
    onClearGoal: () -> Unit,
    onInspectTxInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    val activeWallet = walletState.activeWallet
    val activeWalletId = activeWallet?.id
    val selectedCurrency: FiatCurrency = walletState.prefs.fiatCurrency
    val privacyKasOnly = walletState.prefs.kasOnlyPrivacyMode || walletState.activeNetwork.isTestnet
    val hideBalances = walletState.prefs.hideBalances
    val mask = walletStrings.maskedAmount
    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val greenInk = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val kasFmt = remember { DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)) }

    var valueTf by remember { mutableStateOf(PerformanceTimeframe.ALL) }
    var kasTf by remember { mutableStateOf(PerformanceTimeframe.ALL) }
    var showGoalDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (showGoalDialog) {
            showGoalDialog = false
        } else {
            onDismiss()
        }
    }
    var historicalPrices by remember(selectedCurrency, privacyKasOnly) { mutableStateOf<List<CandlePoint>>(emptyList()) }
    var spotInCurrency by remember(selectedCurrency, privacyKasOnly, walletState.spotPrice?.price) {
        mutableStateOf(
            if (!privacyKasOnly && walletState.spotPrice?.currency == selectedCurrency) walletState.spotPrice?.price else null,
        )
    }
    var loadingPrices by remember { mutableStateOf(!privacyKasOnly) }

    // Fetch historical daily KAS spot prices converted into user's selected currency (skipped in Privacy Mode!)
    LaunchedEffect(selectedCurrency, privacyKasOnly) {
        if (privacyKasOnly) {
            historicalPrices = emptyList()
            spotInCurrency = null
            loadingPrices = false
            return@LaunchedEffect
        }
        loadingPrices = true
        runCatchingNonCancellation {
            repository.fetchAllTimeDailyKasPricesInCurrency(
                currency = selectedCurrency,
                walletSpotPrice = walletState.spotPrice,
                privacyKasOnly = false,
            )
        }.onSuccess { candles ->
            historicalPrices = candles
            if (spotInCurrency == null) {
                spotInCurrency = candles.lastOrNull()?.close
            }
        }
        loadingPrices = false
    }

    val valueSnapshot = remember(
        walletState.transactions,
        walletState.totalBalanceSompi,
        historicalPrices,
        spotInCurrency,
        valueTf,
        privacyKasOnly,
    ) {
        WalletPerformanceEngine.computePerformance(
            transactions = walletState.transactions,
            currentBalanceSompi = walletState.totalBalanceSompi,
            historicalKasPricesInCurrency = historicalPrices,
            currentSpotPriceInCurrency = spotInCurrency,
            timeframe = valueTf,
            privacyKasOnly = privacyKasOnly,
        )
    }

    val kasSnapshot = remember(
        walletState.transactions,
        walletState.totalBalanceSompi,
        kasTf,
        privacyKasOnly,
    ) {
        if (kasTf == valueTf) {
            valueSnapshot
        } else {
            WalletPerformanceEngine.computePerformance(
                transactions = walletState.transactions,
                currentBalanceSompi = walletState.totalBalanceSompi,
                historicalKasPricesInCurrency = emptyList(),
                currentSpotPriceInCurrency = null,
                timeframe = kasTf,
                privacyKasOnly = true,
            )
        }
    }

    val savedGoalKas = activeWalletId?.let { walletState.prefs.walletGoalsKas[it] }?.takeIf { it > 0.0 }
    val savedCustomMonthlyDca = activeWalletId?.let { walletState.prefs.walletCustomMonthlyDca[it] }?.takeIf { it > 0.0 }
    val goalEstimate = remember(
        savedGoalKas,
        savedCustomMonthlyDca,
        valueSnapshot.currentKas,
        valueSnapshot.avgMonthlyDcaKas,
        valueSnapshot.avgMonthlyDcaFiat,
        valueSnapshot.currentPriceFiat,
        spotInCurrency,
        privacyKasOnly,
    ) {
        savedGoalKas?.let { target ->
            WalletPerformanceEngine.computeGoalEstimate(
                currentKas = valueSnapshot.currentKas,
                targetKas = target,
                historicalMonthlyDcaKas = valueSnapshot.avgMonthlyDcaKas,
                historicalMonthlyDcaFiat = valueSnapshot.avgMonthlyDcaFiat,
                currentPriceFiat = valueSnapshot.currentPriceFiat ?: spotInCurrency,
                privacyKasOnly = privacyKasOnly,
                customMonthlyDcaOverride = savedCustomMonthlyDca,
            )
        }
    }

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 104.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val ambientBackdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()
    val headerSubtitle = activeWallet?.let { w ->
        w.name + if (w.isWatchOnly) " • ${walletStrings.watchOnlyBadge}" else ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        // LAYER A: Tier 2 Viewport Capture (Wallpaper + Seamless Full-Screen Scrollable Content)
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
                if (valueSnapshot.isEmpty) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        userScrollEnabled = !gestureLocked,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = topPadDp + 10.dp,
                            bottom = 28.dp,
                        ),
                    ) {
                        item(key = "perf_empty_card") {
                            FluidGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backdrop = effectiveAmbient,
                                useBackdropCapture = true,
                                contentPadding = PaddingValues(24.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = accentInk,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Text(
                                        text = walletStrings.yourPerformanceEmptyTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = walletStrings.yourPerformanceEmptyBody,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = glassSecondaryContentColor(),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        item(key = "perf_empty_goal_card") {
                            YourGoalCard(
                                estimate = goalEstimate,
                                selectedCurrency = selectedCurrency,
                                unit = unit,
                                hideBalances = hideBalances,
                                kasFmt = kasFmt,
                                backdrop = effectiveAmbient,
                                onOpenDialog = { showGoalDialog = true },
                                onClearGoal = onClearGoal,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        userScrollEnabled = !gestureLocked,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = topPadDp + 10.dp,
                            bottom = 28.dp,
                        ),
                    ) {
                        // 1. Portfolio Value & P&L Over Time Card (hidden/replaced gracefully in KAS Only Privacy Mode)
                        item(key = "perf_portfolio_value") {
                            if (privacyKasOnly) {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    preset = GlassPreset.HighlightSurface,
                                    surfaceTint = MaterialTheme.colorScheme.primaryContainer,
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PrivacyTip,
                                            contentDescription = null,
                                            tint = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer else accentInk,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Column {
                                            Text(
                                                text = walletStrings.statsPrivacyModeTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer else glassPrimaryContentColor(),
                                            )
                                            Text(
                                                text = walletStrings.statsPrivacyModeBody,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else glassSecondaryContentColor(),
                                            )
                                        }
                                    }
                                }
                            } else {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "${walletStrings.yourPerformancePortfolioTitle} (${selectedCurrency.code})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = glassSecondaryContentColor(),
                                            )
                                            Text(
                                                text = if (hideBalances) {
                                                    mask
                                                } else {
                                                    valueSnapshot.currentFiatValue?.let {
                                                        ExploreAnalyticsMath.formatFullCurrency(it, selectedCurrency)
                                                    } ?: "—"
                                                },
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-0.5).sp,
                                                color = glassPrimaryContentColor(),
                                            )
                                        }
                                        valueSnapshot.timeframePnlFiat?.let { pnl ->
                                            val positive = pnl >= 0.0
                                            val pctPart = valueSnapshot.timeframePnlPct?.let {
                                                " (${ExploreAnalyticsMath.formatSignedPct(it)})"
                                            }.orEmpty()
                                            ExplorerStatusPill(
                                                text = if (hideBalances) {
                                                    mask
                                                } else {
                                                    ExploreAnalyticsMath.formatFullCurrency(pnl, selectedCurrency, showPlus = true) + pctPart
                                                },
                                                color = if (positive) Color(0xFF22C55E) else Color(0xFFEF4444),
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    PerformanceTimeframeRow(
                                        selected = valueTf,
                                        onSelect = { valueTf = it },
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    if (loadingPrices && valueSnapshot.valueTimeline.size < 2) {
                                        Box(modifier = Modifier.fillMaxWidth().height(165.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                                        }
                                    } else if (valueSnapshot.valueTimeline.size >= 2) {
                                        val isPositive = (valueSnapshot.timeframePnlFiat ?: 0.0) >= 0.0
                                        AnimatedSingleLineChart(
                                            points = valueSnapshot.valueTimeline,
                                            animationKey = "val_${valueTf}_${selectedCurrency}",
                                            valueFormatter = { v ->
                                                if (hideBalances) mask
                                                else ExploreAnalyticsMath.formatFullCurrency(v, selectedCurrency)
                                            },
                                            lineColor = if (isPositive) greenInk else MaterialTheme.colorScheme.error,
                                            height = 175.dp,
                                        )
                                    }

                                    if (valueSnapshot.estimatedCostBasisFiat != null || valueSnapshot.averageEntryPriceFiat != null) {
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            PerfMiniTile(
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                label = walletStrings.yourPerformanceEstCostBasisLabel,
                                                value = if (hideBalances) {
                                                    mask
                                                } else {
                                                    valueSnapshot.estimatedCostBasisFiat?.let {
                                                        ExploreAnalyticsMath.formatFullCurrency(it, selectedCurrency)
                                                    } ?: "—"
                                                },
                                            )
                                            PerfMiniTile(
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                                label = walletStrings.yourPerformanceAvgEntryLabel,
                                                value = if (hideBalances) {
                                                    mask
                                                } else {
                                                    valueSnapshot.averageEntryPriceFiat?.let {
                                                        ExploreAnalyticsMath.formatSpotPriceCurrency(it, selectedCurrency)
                                                    } ?: "—"
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. KAS Balance Over Time Chart (Always Active — Works in Privacy Mode too!)
                        item(key = "perf_kas_timeline") {
                            FluidGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backdrop = effectiveAmbient,
                                useBackdropCapture = true,
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = walletStrings.yourPerformanceKasBalanceTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                    )
                                    Text(
                                        text = if (hideBalances) "$mask $unit" else "${kasFmt.format(valueSnapshot.currentKas)} $unit",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = accentInk,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                PerformanceTimeframeRow(
                                    selected = kasTf,
                                    onSelect = { kasTf = it },
                                )
                                Spacer(Modifier.height(12.dp))
                                if (kasSnapshot.kasTimeline.size >= 2) {
                                    AnimatedSingleLineChart(
                                        points = kasSnapshot.kasTimeline,
                                        animationKey = "kas_${kasTf}_${walletState.activeWallet?.id}",
                                        valueFormatter = { v ->
                                            if (hideBalances) "$mask $unit" else "${kasFmt.format(v)} $unit"
                                        },
                                        lineColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                                        height = 175.dp,
                                    )
                                }
                            }
                        }

                        // 3. Current Ocean Tier & Next Tier Progress Bar Card
                        item(key = "perf_ocean_tier") {
                            FluidGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backdrop = effectiveAmbient,
                                useBackdropCapture = true,
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(text = valueSnapshot.currentTier.emoji, fontSize = 32.sp)
                                        Column {
                                            Text(
                                                text = walletStrings.yourPerformanceCurrentTierLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = glassSecondaryContentColor(),
                                            )
                                            Text(
                                                text = if (hideBalances) {
                                                    "${valueSnapshot.currentTier.displayName} ($mask)"
                                                } else {
                                                    "${valueSnapshot.currentTier.displayName} (${valueSnapshot.currentTier.rangeLabel})"
                                                },
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = glassPrimaryContentColor(),
                                            )
                                        }
                                    }
                                    valueSnapshot.nextTier?.let { next ->
                                        Text(text = next.emoji, fontSize = 24.sp)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(ContinuousCornerShape(50.dp))
                                        .background(if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(valueSnapshot.progressToNextTier.coerceIn(0.03f, 1f))
                                            .fillMaxHeight()
                                            .clip(ContinuousCornerShape(50.dp))
                                            .background(greenInk),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                val progressCopy = valueSnapshot.nextTier?.let { next ->
                                    val remDisplay = if (hideBalances) "$mask $unit" else "${kasFmt.format(valueSnapshot.kasRemainingToNextTier)} $unit"
                                    "$remDisplay ${walletStrings.yourPerformanceNextTierRemaining} ${next.emoji} ${next.displayName}"
                                } ?: walletStrings.yourPerformanceTopTierReached
                                Text(
                                    text = progressCopy,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }

                        // 3b. "Set Goal" Card (Placed directly underneath the Ocean Tier card!)
                        item(key = "perf_goal_card") {
                            YourGoalCard(
                                estimate = goalEstimate,
                                selectedCurrency = selectedCurrency,
                                unit = unit,
                                hideBalances = hideBalances,
                                kasFmt = kasFmt,
                                backdrop = effectiveAmbient,
                                onOpenDialog = { showGoalDialog = true },
                                onClearGoal = onClearGoal,
                            )
                        }

                        // 4. Equal-Height Stacking & DCA Breakdown Cards (2x2 / 3x2 grid)
                        item(key = "perf_kpi_grid_1") {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PerfKpiCard(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    label = walletStrings.yourPerformanceAvgMonthlyDcaLabel,
                                    value = if (hideBalances) "$mask $unit" else "${kasFmt.format(valueSnapshot.avgMonthlyDcaKas)} $unit",
                                    subtitle = if (!privacyKasOnly && valueSnapshot.avgMonthlyDcaFiat != null) {
                                        if (hideBalances) {
                                            "≈ $mask / ${walletStrings.yourPerformanceMonthsUnit}"
                                        } else {
                                            "≈ ${ExploreAnalyticsMath.formatFullCurrency(valueSnapshot.avgMonthlyDcaFiat, selectedCurrency)} / ${walletStrings.yourPerformanceMonthsUnit}"
                                        }
                                    } else null,
                                    backdrop = effectiveAmbient,
                                )
                                PerfKpiCard(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    label = walletStrings.yourPerformanceStackingSinceLabel,
                                    value = valueSnapshot.stackingSinceMs?.let { ExploreAnalyticsMath.formatUtcDate(it) } ?: "—",
                                    subtitle = "${valueSnapshot.daysStacking} ${walletStrings.yourPerformanceDaysUnit}",
                                    backdrop = effectiveAmbient,
                                )
                            }
                        }

                        item(key = "perf_kpi_grid_2") {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PerfKpiCard(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    label = walletStrings.yourPerformanceDiamondHandsLabel,
                                    value = String.format(Locale.US, "%.1f%%", valueSnapshot.diamondHandsRatioPct),
                                    subtitle = "${valueSnapshot.incomingCount} in • ${valueSnapshot.outgoingCount} out",
                                    backdrop = effectiveAmbient,
                                )
                                PerfKpiCard(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    label = walletStrings.yourPerformanceLongestStreakLabel,
                                    value = "${valueSnapshot.longestStackingStreakMonths} ${walletStrings.yourPerformanceMonthsUnit}",
                                    subtitle = if (!privacyKasOnly && valueSnapshot.allTimeHighFiatValue != null) {
                                        if (hideBalances) "ATH $mask"
                                        else "ATH ${ExploreAnalyticsMath.formatCompactCurrency(valueSnapshot.allTimeHighFiatValue, selectedCurrency)}"
                                    } else null,
                                    backdrop = effectiveAmbient,
                                )
                            }
                        }

                        if (!privacyKasOnly && valueSnapshot.allTimeHighFiatValue != null) {
                            item(key = "perf_ath_card") {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(14.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text(
                                                text = walletStrings.yourPerformanceAthValueLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = glassSecondaryContentColor(),
                                            )
                                            Text(
                                                text = if (hideBalances) {
                                                    mask
                                                } else {
                                                    ExploreAnalyticsMath.formatFullCurrency(valueSnapshot.allTimeHighFiatValue, selectedCurrency)
                                                },
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = accentInk,
                                            )
                                        }
                                        valueSnapshot.allTimeHighDateMs?.let { d ->
                                            Text(
                                                text = ExploreAnalyticsMath.formatUtcDate(d),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = glassSecondaryContentColor(),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Personal Milestones & Insights Feed
                        if (valueSnapshot.milestones.isNotEmpty()) {
                            item(key = "perf_milestones_header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                                ) {
                                    Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = walletStrings.yourPerformanceMilestonesTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                    )
                                }
                            }

                            itemsIndexed(valueSnapshot.milestones, key = { idx, _ -> "milestone_$idx" }) { _, milestone ->
                                val (emoji, message, clickTxId) = when (milestone) {
                                    is PerformanceMilestone.TierUnlock -> Triple(
                                        milestone.tier.emoji,
                                        safeFormatLocalized(
                                            walletStrings.yourPerformanceTierUnlockFmt,
                                            ExploreAnalyticsMath.formatUtcDate(milestone.achievedAtMs),
                                            milestone.tier.emoji,
                                            milestone.tier.displayName,
                                        ),
                                        null,
                                    )
                                    is PerformanceMilestone.BiggestStackingMonth -> Triple(
                                        "🚀",
                                        safeFormatLocalized(
                                            walletStrings.yourPerformanceBiggestMonthFmt,
                                            milestone.monthLabel,
                                            if (hideBalances) mask else kasFmt.format(milestone.netKas),
                                        ),
                                        null,
                                    )
                                    is PerformanceMilestone.SlowestGrowthMonth -> Triple(
                                        "🐢",
                                        safeFormatLocalized(
                                            walletStrings.yourPerformanceSlowestMonthFmt,
                                            milestone.monthLabel,
                                            if (hideBalances) mask else (if (milestone.netKas >= 0.0) "+" else "") + kasFmt.format(milestone.netKas),
                                        ),
                                        null,
                                    )
                                    is PerformanceMilestone.BiggestSingleDeposit -> Triple(
                                        "💎",
                                        safeFormatLocalized(
                                            walletStrings.yourPerformanceBiggestDepositFmt,
                                            if (hideBalances) mask else kasFmt.format(milestone.amountKas),
                                            ExploreAnalyticsMath.formatUtcDate(milestone.timestampMs),
                                        ),
                                        milestone.txId,
                                    )
                                    is PerformanceMilestone.StackingActivitySummary -> Triple(
                                        "📅",
                                        safeFormatLocalized(
                                            walletStrings.yourPerformanceActivitySummaryFmt,
                                            milestone.incomingCount,
                                            milestone.outgoingCount,
                                            ExploreAnalyticsMath.formatUtcDate(milestone.firstTxMs),
                                        ),
                                        null,
                                    )
                                }

                                val milestoneShape = ContinuousCornerShape(22.dp)
                                FluidGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (clickTxId != null) {
                                                Modifier.clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = { onInspectTxInExplorer(clickTxId) },
                                                )
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    shape = milestoneShape,
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(text = emoji, fontSize = 22.sp)
                                        Text(
                                            text = message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = glassPrimaryContentColor(),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header + Top Progressive Blur + In-Window Dialogs
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveViewport,
            LocalOverlayBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = walletStrings.yourPerformanceTitle,
                subtitle = headerSubtitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = onDismiss,
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
                trailingActions = {
                    FluidGlassIconButton(
                        onClick = onToggleHideBalances,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(
                            imageVector = if (hideBalances) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (hideBalances) walletStrings.showBalancesTooltip else walletStrings.hideBalancesTooltip,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )

            if (showGoalDialog) {
                SetGoalDialog(
                    currentTargetKas = savedGoalKas,
                    currentCustomDca = savedCustomMonthlyDca,
                    historicalMonthlyDcaFiat = valueSnapshot.avgMonthlyDcaFiat,
                    historicalMonthlyDcaKas = valueSnapshot.avgMonthlyDcaKas,
                    nextTier = valueSnapshot.nextTier,
                    selectedCurrency = selectedCurrency,
                    unit = unit,
                    privacyKasOnly = privacyKasOnly,
                    hideBalances = hideBalances,
                    kasFmt = kasFmt,
                    onSave = { target, customDca ->
                        onSetGoal(target, customDca)
                        showGoalDialog = false
                    },
                    onClear = {
                        onClearGoal()
                        showGoalDialog = false
                    },
                    onDismiss = { showGoalDialog = false },
                )
            }
        }
    }
}

@Composable
private fun YourGoalCard(
    estimate: WalletGoalEstimate?,
    selectedCurrency: FiatCurrency,
    unit: String,
    hideBalances: Boolean,
    kasFmt: DecimalFormat,
    backdrop: Backdrop?,
    onOpenDialog: () -> Unit,
    onClearGoal: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val greenInk = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val mask = walletStrings.maskedAmount

    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = backdrop,
        useBackdropCapture = true,
        contentPadding = PaddingValues(16.dp),
    ) {
        if (estimate == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = walletStrings.goalCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = walletStrings.goalCardPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // lockParentGestures = true acquires LocalFluidGestureCoordinator lock after >12px hold-drag
            // so the parent LazyColumn never steals touch while admiring button physics (KINETIC_AERO.md §11 #21)
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                lockParentGestures = true,
                onClick = onOpenDialog,
            ) {
                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = walletStrings.goalSetButton,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        } else {
            // Header row with Edit & Clear canonical 3D Kinetic Aero icon buttons
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
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = accentInk,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = walletStrings.goalCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FluidGlassIconButton(
                        onClick = onOpenDialog,
                        size = 34.dp,
                        preset = GlassPreset.Control,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = walletStrings.goalEditButton,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    FluidGlassIconButton(
                        onClick = onClearGoal,
                        size = 34.dp,
                        preset = GlassPreset.Control,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = walletStrings.goalClearButton,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Current KAS vs Target KAS + % complete (respects hideBalances privacy mask!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hideBalances) {
                            "$mask / $mask $unit"
                        } else {
                            "${kasFmt.format(estimate.currentKas)} / ${kasFmt.format(estimate.targetKas)} $unit"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = glassPrimaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!estimate.isReached) {
                        val remStr = if (hideBalances) "$mask $unit" else "${kasFmt.format(estimate.remainingKas)} $unit"
                        Text(
                            text = safeFormatLocalized(walletStrings.goalRemainingFmt, remStr),
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.1f%%", estimate.progressPct),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (estimate.isReached) greenInk else accentInk,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Kinetic Aero Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(ContinuousCornerShape(50.dp))
                    .background(if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(estimate.progressFraction.coerceIn(0.03f, 1f))
                        .fillMaxHeight()
                        .clip(ContinuousCornerShape(50.dp))
                        .background(greenInk),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (estimate.isReached) {
                val reachedShape = ContinuousCornerShape(16.dp)
                val reachedBg = when {
                    reduceTransparency || isDark -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color(0xFF15803D).copy(alpha = 0.14f)
                }
                val reachedInk = when {
                    reduceTransparency || isDark -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> Color(0xFF15803D)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(reachedShape)
                        .background(reachedBg, reachedShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = "🎉", fontSize = 22.sp)
                    Column {
                        Text(
                            text = walletStrings.goalReachedTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = reachedInk,
                        )
                        Text(
                            text = walletStrings.goalReachedBody,
                            style = MaterialTheme.typography.labelSmall,
                            color = reachedInk.copy(alpha = 0.88f),
                        )
                    }
                }
            } else {
                val etaBoxShape = ContinuousCornerShape(16.dp)
                val etaBoxBg = when {
                    reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
                    isDark -> Color.White.copy(alpha = 0.07f)
                    else -> Color.White.copy(alpha = 0.54f)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(etaBoxShape)
                        .background(etaBoxBg, etaBoxShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val fastMo = estimate.fasterMonthsMinus30Pct
                    val slowMo = estimate.slowerMonthsPlus30Pct
                    val baseMo = estimate.baselineMonths

                    when {
                        fastMo != null && slowMo != null && baseMo != null -> {
                            val fastStr = WalletPerformanceEngine.formatDurationMonths(
                                fastMo,
                                monthsUnit = walletStrings.yourPerformanceMonthsUnit,
                                yearsUnit = walletStrings.goalYearsUnit,
                            )
                            val slowStr = WalletPerformanceEngine.formatDurationMonths(
                                slowMo,
                                monthsUnit = walletStrings.yourPerformanceMonthsUnit,
                                yearsUnit = walletStrings.goalYearsUnit,
                            )
                            val baseStr = WalletPerformanceEngine.formatDurationMonths(
                                baseMo,
                                monthsUnit = walletStrings.yourPerformanceMonthsUnit,
                                yearsUnit = walletStrings.goalYearsUnit,
                            )
                            Text(
                                text = safeFormatLocalized(walletStrings.goalEtaRangeFmt, fastStr, slowStr),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentInk,
                            )
                            Text(
                                text = safeFormatLocalized(walletStrings.goalEtaBandCaptionFmt, baseStr),
                                style = MaterialTheme.typography.labelSmall,
                                color = glassPrimaryContentColor(),
                            )
                            estimate.effectiveMonthlyDcaFiat?.let { fiatPace ->
                                val paceStr = if (hideBalances) mask else ExploreAnalyticsMath.formatFullCurrency(fiatPace, selectedCurrency)
                                Text(
                                    text = safeFormatLocalized(walletStrings.goalEtaBasedOnPaceFmt, paceStr),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }

                        baseMo != null -> {
                            val baseStr = WalletPerformanceEngine.formatDurationMonths(
                                baseMo,
                                monthsUnit = walletStrings.yourPerformanceMonthsUnit,
                                yearsUnit = walletStrings.goalYearsUnit,
                            )
                            Text(
                                text = safeFormatLocalized(walletStrings.goalEtaPrivacyCaptionFmt, baseStr),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentInk,
                            )
                            estimate.effectiveMonthlyDcaKas?.let { kasPace ->
                                val paceStr = if (hideBalances) "$mask $unit" else "${kasFmt.format(kasPace)} $unit"
                                Text(
                                    text = safeFormatLocalized(walletStrings.goalEtaBasedOnPaceFmt, paceStr),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }

                        else -> {
                            Text(
                                text = walletStrings.goalEtaNoDcaNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = glassSecondaryContentColor(),
                            )
                            Spacer(Modifier.height(4.dp))
                            FluidGlassButton(
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                                onClick = onOpenDialog,
                            ) {
                                Text(
                                    text = walletStrings.goalSetPaceButton,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = walletStrings.goalDisclaimerNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor().copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
private fun SetGoalDialog(
    currentTargetKas: Double?,
    currentCustomDca: Double?,
    historicalMonthlyDcaFiat: Double?,
    historicalMonthlyDcaKas: Double,
    nextTier: OceanTier?,
    selectedCurrency: FiatCurrency,
    unit: String,
    privacyKasOnly: Boolean,
    hideBalances: Boolean,
    kasFmt: DecimalFormat,
    onSave: (targetKas: Double, customMonthlyDca: Double?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val mask = walletStrings.maskedAmount
    var targetInput by remember(currentTargetKas, hideBalances) {
        mutableStateOf(
            if (hideBalances) {
                ""
            } else {
                currentTargetKas?.let {
                    if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.US, "%.2f", it)
                }.orEmpty()
            },
        )
    }
    var customDcaInput by remember(currentCustomDca, hideBalances) {
        mutableStateOf(
            if (hideBalances) {
                ""
            } else {
                currentCustomDca?.let {
                    if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.US, "%.2f", it)
                }.orEmpty()
            },
        )
    }

    val parsedTarget = targetInput.replace(",", "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val parsedCustomDca = customDcaInput.replace(",", "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val dcaUnitLabel = if (privacyKasOnly) unit else selectedCurrency.code
    val autoDcaLabel = when {
        hideBalances -> mask
        privacyKasOnly -> if (historicalMonthlyDcaKas > 0.0) "${kasFmt.format(historicalMonthlyDcaKas)} $unit" else "0 $unit"
        else -> historicalMonthlyDcaFiat?.takeIf { it > 0.0 }?.let {
            ExploreAnalyticsMath.formatFullCurrency(it, selectedCurrency)
        } ?: "${selectedCurrency.symbol}0"
    }

    FluidGlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = walletStrings.goalDialogTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
            )
        },
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = onDismiss,
                    ) {
                        Text(kineticStrings.cancel, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        accent = true,
                        enabled = parsedTarget != null,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            if (parsedTarget != null) {
                                onSave(parsedTarget, parsedCustomDca)
                            }
                        },
                    ) {
                        Text(walletStrings.goalSaveButton, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (currentTargetKas != null) {
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        destructive = true,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = onClear,
                    ) {
                        Text(walletStrings.goalClearButton, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        },
    ) {
        FluidGlassTextField(
            value = targetInput,
            onValueChange = { raw -> targetInput = raw.filter { it.isDigit() || it == '.' || it == ',' } },
            label = walletStrings.goalTargetInputLabel,
            placeholder = walletStrings.goalTargetInputPlaceholder,
            singleLine = true,
            nestedInGlassCard = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = walletStrings.goalQuickPresetsLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = glassSecondaryContentColor(),
        )

        val presets = remember(nextTier) {
            buildList {
                if (nextTier != null) {
                    add("${nextTier.emoji} ${walletStrings.goalNextTierPreset}" to nextTier.minKas)
                }
                add("100K" to 100_000.0)
                add("500K" to 500_000.0)
                add("1M" to 1_000_000.0)
            }
        }
        presets.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPresets.forEach { (label, amount) ->
                    val isSelected = parsedTarget != null && kotlin.math.abs(parsedTarget - amount) < 0.01
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        selected = isSelected,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        onClick = { targetInput = amount.toLong().toString() },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (rowPresets.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        FluidGlassTextField(
            value = customDcaInput,
            onValueChange = { raw -> customDcaInput = raw.filter { it.isDigit() || it == '.' || it == ',' } },
            label = "${walletStrings.goalCustomDcaLabel} ($dcaUnitLabel)",
            placeholder = safeFormatLocalized(walletStrings.goalCustomDcaPlaceholderFmt, autoDcaLabel),
            singleLine = true,
            nestedInGlassCard = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

@Composable
private fun PerformanceTimeframeRow(
    selected: PerformanceTimeframe,
    onSelect: (PerformanceTimeframe) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PerformanceTimeframe.entries.forEach { tf ->
            FluidGlassChip(
                modifier = Modifier.weight(1f),
                selected = selected == tf,
                useBackdropCapture = false,
                lockParentGestures = false,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                onClick = { onSelect(tf) },
            ) {
                Text(
                    text = tf.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected == tf) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PerfKpiCard(
    label: String,
    value: String,
    subtitle: String?,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalGlassDarkTheme.current
    FluidGlassCard(
        modifier = modifier,
        backdrop = backdrop,
        useBackdropCapture = true,
        contentPadding = PaddingValues(14.dp),
    ) {
        MarqueeText(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PerfMiniTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(14.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.58f)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = glassPrimaryContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
