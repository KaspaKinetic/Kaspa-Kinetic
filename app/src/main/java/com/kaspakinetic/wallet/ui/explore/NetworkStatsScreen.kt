package com.kaspakinetic.wallet.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.explore.DistributionTier
import com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath
import com.kaspakinetic.wallet.core.explore.HashratePoint
import com.kaspakinetic.wallet.core.explore.HashrateTimeframe
import com.kaspakinetic.wallet.core.explore.KasPriceSeries
import com.kaspakinetic.wallet.core.explore.KasVsBtcComparison
import com.kaspakinetic.wallet.core.explore.KaspaExploreRepository
import com.kaspakinetic.wallet.core.explore.MarketOverview
import com.kaspakinetic.wallet.core.explore.MarketTimeframe
import com.kaspakinetic.wallet.core.explore.NetworkMiningStats
import com.kaspakinetic.wallet.core.explore.OceanTier
import com.kaspakinetic.wallet.core.explore.RichListEntry
import com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.KineticRefreshIconButton
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
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
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.onboarding.ErrorInlineBanner
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class StatsTab {
    MARKET,
    NETWORK,
    RICH_LIST,
}

@Composable
fun NetworkStatsScreen(
    network: KaspaNetwork,
    selectedCurrency: FiatCurrency,
    walletSpotPrice: SpotPrice?,
    privacyKasOnly: Boolean,
    initialTab: StatsTab = StatsTab.MARKET,
    onActiveTabChanged: (StatsTab) -> Unit = {},
    appearance: KineticAeroAppearanceSettings,
    repository: KaspaExploreRepository,
    onInspectAddressInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val scope = rememberCoroutineScope()

    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }

    // USD -> user's selected currency FX multiplier
    var usdToCurrencyRate by remember(selectedCurrency) { mutableDoubleStateOf(1.0) }

    // Tab 1: Market + Standalone Kaspa Performance + KAS vs BTC state
    var marketOverview by remember { mutableStateOf<MarketOverview?>(null) }
    var selectedKasTf by remember { mutableStateOf(MarketTimeframe.D1) }
    var kasPriceSeries by remember { mutableStateOf<KasPriceSeries?>(null) }
    var kasChartLoading by remember { mutableStateOf(false) }

    var selectedVsBtcTf by remember { mutableStateOf(MarketTimeframe.Y1) }
    var kasVsBtc by remember { mutableStateOf<KasVsBtcComparison?>(null) }
    var marketLoading by remember { mutableStateOf(false) }
    var marketError by remember { mutableStateOf<String?>(null) }

    // Tab 2: Network & Mining state
    var miningStats by remember(network) { mutableStateOf<NetworkMiningStats?>(null) }
    var selectedHashrateTf by remember { mutableStateOf(HashrateTimeframe.D30) }
    var hashrateLogScale by remember { mutableStateOf(false) }
    var hashrateHistory by remember(network) { mutableStateOf<List<HashratePoint>>(emptyList()) }
    var miningLoading by remember { mutableStateOf(false) }
    var miningError by remember { mutableStateOf<String?>(null) }

    // Tab 3: Rich List (paginated) & Sea-Creature Coin Distribution Table state
    var richList by remember(network) { mutableStateOf<List<RichListEntry>>(emptyList()) }
    var distribution by remember(network) { mutableStateOf<List<DistributionTier>>(emptyList()) }
    var richQuery by remember { mutableStateOf("") }
    var richPage by remember { mutableIntStateOf(0) }
    var richPageSize by remember { mutableIntStateOf(10) }
    var distShowFiat by remember { mutableStateOf(false) }
    var richLoading by remember { mutableStateOf(false) }
    var richError by remember { mutableStateOf<String?>(null) }
    val richListState = rememberLazyListState()

    // Live 1s countdown ticker for Block Reward next reduction
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeTab) {
        if (activeTab != StatsTab.NETWORK) return@LaunchedEffect
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    // Resolve FX conversion rate for the user's selected currency (skipped in Privacy Mode)
    LaunchedEffect(selectedCurrency, walletSpotPrice?.price, privacyKasOnly) {
        if (privacyKasOnly) {
            usdToCurrencyRate = 1.0
            return@LaunchedEffect
        }
        runCatchingNonCancellation {
            repository.getUsdToCurrencyRate(selectedCurrency, walletSpotPrice, privacyKasOnly = false)
        }.onSuccess { rate ->
            if (rate > 0.0) usdToCurrencyRate = rate
        }
    }

    // Load Market overview + standalone Kaspa price series
    LaunchedEffect(activeTab, selectedKasTf, selectedCurrency, privacyKasOnly) {
        if (activeTab != StatsTab.MARKET || privacyKasOnly) return@LaunchedEffect
        kasChartLoading = true
        if (marketOverview == null) {
            runCatchingNonCancellation { repository.fetchMarketOverview(privacyKasOnly = false) }
                .onSuccess { marketOverview = it }
        }
        runCatchingNonCancellation {
            repository.fetchKasPriceSeries(selectedKasTf, selectedCurrency, walletSpotPrice, privacyKasOnly = false)
        }.onSuccess { series ->
            kasPriceSeries = series
            marketError = null
        }.onFailure { err ->
            if (kasPriceSeries == null) marketError = KaspaErrorSanitizer.sanitize(err.message)
        }
        kasChartLoading = false
    }

    // Load KAS vs BTC comparison
    LaunchedEffect(activeTab, selectedVsBtcTf, privacyKasOnly) {
        if (activeTab != StatsTab.MARKET || privacyKasOnly) return@LaunchedEffect
        marketLoading = true
        runCatchingNonCancellation { repository.fetchKasVsBtcComparison(selectedVsBtcTf, privacyKasOnly = false) }
            .onSuccess {
                kasVsBtc = it
                marketError = null
            }
            .onFailure { err -> marketError = KaspaErrorSanitizer.sanitize(err.message) }
        marketLoading = false
    }

    // Load Network & Mining stats + hashrate history
    LaunchedEffect(activeTab, selectedHashrateTf, network) {
        if (activeTab != StatsTab.NETWORK) return@LaunchedEffect
        miningLoading = true
        miningError = null
        if (miningStats == null) {
            runCatchingNonCancellation { repository.fetchNetworkMiningStats(network) }
                .onSuccess { miningStats = it }
                .onFailure { err -> miningError = KaspaErrorSanitizer.sanitize(err.message) }
        }
        runCatchingNonCancellation { repository.fetchHashrateHistory(network, selectedHashrateTf) }
            .onSuccess { hashrateHistory = it }
            .onFailure { err ->
                if (hashrateHistory.isEmpty()) miningError = KaspaErrorSanitizer.sanitize(err.message)
            }
        miningLoading = false
    }

    // Load Rich List + Sea-Creature Distribution (+ MarketOverview for fiat toggle when not in privacy mode)
    LaunchedEffect(activeTab, network) {
        if (activeTab != StatsTab.RICH_LIST) return@LaunchedEffect
        if (!privacyKasOnly && marketOverview == null) {
            runCatchingNonCancellation { repository.fetchMarketOverview(privacyKasOnly = false) }
                .onSuccess { marketOverview = it }
        }
        if (richList.isNotEmpty() && distribution.isNotEmpty()) return@LaunchedEffect
        richLoading = true
        richError = null
        runCatchingNonCancellation { repository.fetchRichListAndDistribution(network) }
            .onSuccess { (list, dist) ->
                richList = list
                distribution = dist
            }
            .onFailure { err -> richError = KaspaErrorSanitizer.sanitize(err.message) }
        richLoading = false
    }

    val effectiveSpotInCurrency = remember(walletSpotPrice, marketOverview, usdToCurrencyRate, selectedCurrency) {
        when {
            walletSpotPrice != null && walletSpotPrice.currency == selectedCurrency && walletSpotPrice.price > 0.0 ->
                walletSpotPrice.price
            marketOverview != null && marketOverview!!.spotPriceUsd > 0.0 ->
                marketOverview!!.spotPriceUsd * usdToCurrencyRate
            else -> null
        }
    }

    // Always start the Rich List tab cleanly at index 0 (Balance Distribution) unless a search/page jump is active.
    // Also resets index 0 immediately when Balance Distribution finishes loading asynchronously above the Rich List!
    LaunchedEffect(activeTab, distribution.isNotEmpty()) {
        if (activeTab == StatsTab.RICH_LIST && richQuery.isBlank() && richPage == 0) {
            richListState.scrollToItem(0)
        }
    }

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 154.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val ambientBackdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        // LAYER A: Tier 2 Viewport Capture (Wallpaper + Seamless Full-Screen Scrollable Tab Body)
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
                val tabTopPadding = topPadDp + 10.dp
                when (activeTab) {
                    StatsTab.MARKET -> MarketStatsTabContent(
                        privacyKasOnly = privacyKasOnly,
                        selectedCurrency = selectedCurrency,
                        usdToCurrencyRate = usdToCurrencyRate,
                        effectiveSpotInCurrency = effectiveSpotInCurrency,
                        overview = marketOverview,
                        selectedKasTf = selectedKasTf,
                        onSelectKasTf = { selectedKasTf = it },
                        kasPriceSeries = kasPriceSeries,
                        kasChartLoading = kasChartLoading,
                        selectedVsBtcTf = selectedVsBtcTf,
                        onSelectVsBtcTf = { selectedVsBtcTf = it },
                        comparison = kasVsBtc,
                        isLoading = marketLoading,
                        errorMsg = marketError,
                        backdrop = effectiveAmbient,
                        topContentPadding = tabTopPadding,
                        gestureLocked = gestureLocked,
                    )

                    StatsTab.NETWORK -> NetworkMiningTabContent(
                        stats = miningStats,
                        selectedTf = selectedHashrateTf,
                        onSelectTf = { selectedHashrateTf = it },
                        logScale = hashrateLogScale,
                        onToggleLogScale = { hashrateLogScale = it },
                        hashrateHistory = hashrateHistory,
                        nowMs = nowMs,
                        isLoading = miningLoading,
                        errorMsg = miningError,
                        backdrop = effectiveAmbient,
                        topContentPadding = tabTopPadding,
                        gestureLocked = gestureLocked,
                    )

                    StatsTab.RICH_LIST -> RichListTabContent(
                        entries = richList,
                        distribution = distribution,
                        circulatingSupplyKas = marketOverview?.circulatingSupplyKas,
                        selectedCurrency = selectedCurrency,
                        spotPriceInCurrency = if (privacyKasOnly) null else effectiveSpotInCurrency,
                        privacyKasOnly = privacyKasOnly,
                        distShowFiat = distShowFiat && !privacyKasOnly,
                        onToggleDistFiat = { distShowFiat = it },
                        query = richQuery,
                        onQueryChange = {
                            richQuery = it
                            richPage = 0
                        },
                        currentPage = richPage,
                        onPageChange = { richPage = it },
                        pageSize = richPageSize,
                        onPageSizeChange = {
                            richPageSize = it
                            richPage = 0
                        },
                        listState = richListState,
                        isLoading = richLoading,
                        errorMsg = richError,
                        backdrop = effectiveAmbient,
                        topContentPadding = tabTopPadding,
                        gestureLocked = gestureLocked,
                        onInspectAddress = onInspectAddressInExplorer,
                    )
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header + Segmented Control + Top Progressive Blur
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveViewport,
            LocalOverlayBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = walletStrings.networkStatsTitle,
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
                    val isStatsRefreshing = when (activeTab) {
                        StatsTab.MARKET -> marketLoading || kasChartLoading
                        StatsTab.NETWORK -> miningLoading
                        StatsTab.RICH_LIST -> richLoading
                    }
                    KineticRefreshIconButton(
                        isRefreshing = isStatsRefreshing,
                        contentDescription = walletStrings.refresh,
                        onClick = {
                            scope.launch {
                                when (activeTab) {
                                    StatsTab.MARKET -> if (!privacyKasOnly) {
                                        kasChartLoading = true
                                        marketLoading = true
                                        marketError = null
                                        runCatchingNonCancellation { repository.fetchMarketOverview(false) }.onSuccess { marketOverview = it }
                                        runCatchingNonCancellation {
                                            repository.fetchKasPriceSeries(selectedKasTf, selectedCurrency, walletSpotPrice, false, forceRefresh = true)
                                        }.onSuccess { kasPriceSeries = it }
                                        runCatchingNonCancellation {
                                            repository.fetchKasVsBtcComparison(selectedVsBtcTf, false, forceRefresh = true)
                                        }.onSuccess { kasVsBtc = it }
                                            .onFailure { marketError = KaspaErrorSanitizer.sanitize(it.message) }
                                        kasChartLoading = false
                                        marketLoading = false
                                    }
                                    StatsTab.NETWORK -> {
                                        miningLoading = true
                                        miningError = null
                                        runCatchingNonCancellation { repository.fetchNetworkMiningStats(network) }.onSuccess { miningStats = it }
                                        runCatchingNonCancellation { repository.fetchHashrateHistory(network, selectedHashrateTf, forceRefresh = true) }
                                            .onSuccess { hashrateHistory = it }
                                            .onFailure { miningError = KaspaErrorSanitizer.sanitize(it.message) }
                                        miningLoading = false
                                    }
                                    StatsTab.RICH_LIST -> {
                                        richLoading = true
                                        richError = null
                                        runCatchingNonCancellation { repository.fetchRichListAndDistribution(network, forceRefresh = true) }
                                            .onSuccess { (l, d) -> richList = l; distribution = d }
                                            .onFailure { richError = KaspaErrorSanitizer.sanitize(it.message) }
                                        richLoading = false
                                    }
                                }
                            }
                        },
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    )
                },
                subBarContent = {
                    FluidGlassSegmentedControl(
                        items = StatsTab.entries,
                        selectedItem = activeTab,
                        backdrop = effectiveViewport,
                        useBackdropCapture = true,
                        onSelect = {
                            activeTab = it
                            onActiveTabChanged(it)
                        },
                        labelFor = {
                            when (it) {
                                StatsTab.MARKET -> walletStrings.statsTabMarket
                                StatsTab.NETWORK -> walletStrings.statsTabNetwork
                                StatsTab.RICH_LIST -> walletStrings.statsTabRichList
                            }
                        },
                    )
                },
            )
        }
    }
}

// ============================================================================
// Tab 1: Market + Standalone Kaspa Performance + KAS vs BTC Relative Performance
// ============================================================================

@Composable
private fun MarketStatsTabContent(
    privacyKasOnly: Boolean,
    selectedCurrency: FiatCurrency,
    usdToCurrencyRate: Double,
    effectiveSpotInCurrency: Double?,
    overview: MarketOverview?,
    selectedKasTf: MarketTimeframe,
    onSelectKasTf: (MarketTimeframe) -> Unit,
    kasPriceSeries: KasPriceSeries?,
    kasChartLoading: Boolean,
    selectedVsBtcTf: MarketTimeframe,
    onSelectVsBtcTf: (MarketTimeframe) -> Unit,
    comparison: KasVsBtcComparison?,
    isLoading: Boolean,
    errorMsg: String?,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    gestureLocked: Boolean,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val greenInk = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)

    if (privacyKasOnly) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = topContentPadding),
        ) {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                preset = GlassPreset.HighlightSurface,
                surfaceTint = MaterialTheme.colorScheme.primaryContainer,
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(22.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PrivacyTip,
                        contentDescription = null,
                        tint = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer else accentInk,
                        modifier = Modifier.size(34.dp),
                    )
                    Text(
                        text = walletStrings.statsPrivacyModeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer else glassPrimaryContentColor(),
                    )
                    Text(
                        text = walletStrings.statsPrivacyModeBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reduceTransparency || isDark) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else glassSecondaryContentColor(),
                    )
                }
            }
        }
        return
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
            item(key = "mkt_err") { ErrorInlineBanner(errorMsg) }
        }

        // 1. Live KAS Spot Price + Market Cap + Supply Card (in User's Selected Currency!)
        item(key = "mkt_overview") {
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${walletStrings.spotPriceLabel} (${selectedCurrency.code})",
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                        Text(
                            text = effectiveSpotInCurrency?.let {
                                ExploreAnalyticsMath.formatSpotPriceCurrency(it, selectedCurrency)
                            } ?: "—",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.6).sp,
                            color = glassPrimaryContentColor(),
                        )
                    }
                    overview?.change24hPct?.let { chg ->
                        ExplorerStatusPill(
                            text = ExploreAnalyticsMath.formatSignedPct(chg),
                            color = if (chg >= 0.0) Color(0xFF22C55E) else Color(0xFFEF4444),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val mcapInCurrency = overview?.marketCapUsd?.takeIf { it > 0.0 }?.times(usdToCurrencyRate)
                    StatMiniTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = walletStrings.statsMarketCapLabel,
                        value = mcapInCurrency?.let { ExploreAnalyticsMath.formatCompactCurrency(it, selectedCurrency) } ?: "—",
                    )
                    StatMiniTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = walletStrings.statsCirculatingSupplyLabel,
                        value = overview?.let { ExploreAnalyticsMath.formatCompactKas(it.circulatingSupplyKas) } ?: "—",
                    )
                }
                if (overview != null && overview.maxSupplyKas > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    val pctMined = ((overview.circulatingSupplyKas / overview.maxSupplyKas) * 100.0).coerceIn(0.0, 100.0)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${walletStrings.statsMaxSupplyLabel}: ${ExploreAnalyticsMath.formatCompactKas(overview.maxSupplyKas)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f%%", pctMined),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentInk,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(ContinuousCornerShape(50.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((pctMined / 100.0).toFloat())
                                .fillMaxHeight()
                                .clip(ContinuousCornerShape(50.dp))
                                .background(greenInk),
                        )
                    }
                }
            }
        }

        // 2. Standalone Kaspa Performance Card (ABOVE KAS vs BTC, in User's Selected Currency!)
        item(key = "kas_standalone_perf") {
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = walletStrings.statsKasPerformanceTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                        if (kasPriceSeries != null) {
                            Text(
                                text = "${walletStrings.statsDataSourceLabel}: ${kasPriceSeries.sourceName} • ${selectedCurrency.code}",
                                style = MaterialTheme.typography.labelSmall,
                                color = glassSecondaryContentColor(),
                            )
                        }
                    }
                    if (kasPriceSeries != null) {
                        ExplorerStatusPill(
                            text = ExploreAnalyticsMath.formatSignedPct(kasPriceSeries.changePct),
                            color = if (kasPriceSeries.changePct >= 0.0) Color(0xFF22C55E) else Color(0xFFEF4444),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                TimeframeGrid3x2(
                    selected = selectedKasTf,
                    onSelect = onSelectKasTf,
                )

                Spacer(Modifier.height(12.dp))

                if (kasChartLoading && kasPriceSeries == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(175.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.6.dp)
                    }
                } else if (kasPriceSeries != null && kasPriceSeries.pointsInCurrency.size >= 2) {
                    val isUp = kasPriceSeries.changePct >= 0.0
                    AnimatedSingleLineChart(
                        points = kasPriceSeries.pointsInCurrency,
                        animationKey = Triple(selectedKasTf, selectedCurrency, kasPriceSeries.sourceName),
                        valueFormatter = { ExploreAnalyticsMath.formatSpotPriceCurrency(it, selectedCurrency) },
                        lineColor = if (isUp) greenInk else MaterialTheme.colorScheme.error,
                        height = 185.dp,
                    )
                }
            }
        }

        // 3. KAS vs BTC Relative Performance Hero Card
        item(key = "kas_vs_btc_card") {
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
                    Text(
                        text = walletStrings.statsKasVsBtcTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    if (comparison != null) {
                        Text(
                            text = "${walletStrings.statsDataSourceLabel}: ${comparison.sourceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (comparison != null) {
                    val outperforming = comparison.relativeOutperformancePct >= 0.0
                    val diffAbs = ExploreAnalyticsMath.formatSignedPct(comparison.relativeOutperformancePct)
                    val bannerShape = ContinuousCornerShape(16.dp)
                    val bannerBg = when {
                        outperforming && (reduceTransparency || isDark) -> MaterialTheme.colorScheme.primaryContainer
                        outperforming -> Color(0xFF15803D).copy(alpha = 0.14f)
                        reduceTransparency || isDark -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    }
                    val bannerInk = when {
                        outperforming && (reduceTransparency || isDark) -> MaterialTheme.colorScheme.onPrimaryContainer
                        outperforming -> Color(0xFF15803D)
                        reduceTransparency || isDark -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.error
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(bannerShape)
                            .background(bannerBg, bannerShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (outperforming) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = bannerInk,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (outperforming) walletStrings.statsKasOutperformingLabel else walletStrings.statsKasUnderperformingLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = bannerInk.copy(alpha = 0.88f),
                            )
                            Text(
                                text = "$diffAbs (${selectedVsBtcTf.label})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = bannerInk,
                            )
                        }
                        if (selectedVsBtcTf == MarketTimeframe.ALL_5Y) {
                            Text(
                                text = "${walletStrings.statsEarliestDataLabel}\n${ExploreAnalyticsMath.formatUtcDate(comparison.earliestTimestampMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = bannerInk.copy(alpha = 0.82f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                TimeframeGrid3x2(
                    selected = selectedVsBtcTf,
                    onSelect = onSelectVsBtcTf,
                )

                Spacer(Modifier.height(14.dp))

                if (isLoading && comparison == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.6.dp)
                    }
                } else if (comparison != null) {
                    AnimatedDualLinePerformanceChart(
                        kasPoints = comparison.kasPctPoints,
                        btcPoints = comparison.btcPctPoints,
                        animationKey = selectedVsBtcTf to comparison.sourceName,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeframeGrid3x2(
    selected: MarketTimeframe,
    onSelect: (MarketTimeframe) -> Unit,
) {
    val rows = remember { MarketTimeframe.entries.chunked(3) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowItems.forEach { tf ->
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        selected = selected == tf,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        onClick = { onSelect(tf) },
                    ) {
                        Text(
                            text = tf.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Tab 2: Network & Mining (Equal-Height Hashrate Cards + Log Toggle + Emission)
// ============================================================================

@Composable
private fun NetworkMiningTabContent(
    stats: NetworkMiningStats?,
    selectedTf: HashrateTimeframe,
    onSelectTf: (HashrateTimeframe) -> Unit,
    logScale: Boolean,
    onToggleLogScale: (Boolean) -> Unit,
    hashrateHistory: List<HashratePoint>,
    nowMs: Long,
    isLoading: Boolean,
    errorMsg: String?,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    gestureLocked: Boolean,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val emissionPoints = remember(stats?.blockRewardKas, stats?.nextReductionTimestampSec) {
        if (stats != null && stats.blockRewardKas > 0.0) {
            ExploreAnalyticsMath.buildEmissionSchedulePoints(
                currentRewardKas = stats.blockRewardKas,
                nextReductionTimestampSec = stats.nextReductionTimestampSec,
                monthsAhead = 48,
            )
        } else {
            emptyList()
        }
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
            item(key = "net_err") { ErrorInlineBanner(errorMsg) }
        }

        // Strictly equal-height Current & ATH Max Hashrate KPI cards
        item(key = "net_hashrate_kpi") {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FluidGlassCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    backdrop = backdrop,
                    useBackdropCapture = true,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    MarqueeText(
                        text = walletStrings.statsCurrentHashrateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stats?.let { ExploreAnalyticsMath.formatHashrateFromThs(it.currentHashrateThs) } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (stats != null && stats.virtualDaaScore > 0L) "DAA ${stats.virtualDaaScore}" else " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FluidGlassCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    backdrop = backdrop,
                    useBackdropCapture = true,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    MarqueeText(
                        text = walletStrings.statsMaxHashrateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stats?.let { ExploreAnalyticsMath.formatHashrateFromThs(it.maxHashrateThs) } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = glassPrimaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stats?.maxHashrateDate?.takeIf { it.isNotBlank() } ?: " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Animated Hashrate History Chart + Linear / Log toggle
        item(key = "net_hashrate_chart") {
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
                    Text(
                        text = walletStrings.statsHashrateHistoryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FluidGlassChip(
                            selected = !logScale,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { onToggleLogScale(false) },
                        ) {
                            Text(walletStrings.statsScaleLinear, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        FluidGlassChip(
                            selected = logScale,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { onToggleLogScale(true) },
                        ) {
                            Text(walletStrings.statsScaleLog, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HashrateTimeframe.entries.forEach { tf ->
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedTf == tf,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            onClick = { onSelectTf(tf) },
                        ) {
                            Text(
                                text = tf.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedTf == tf) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (isLoading && hashrateHistory.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(175.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.6.dp)
                    }
                } else if (hashrateHistory.size >= 2) {
                    AnimatedSingleLineChart(
                        points = hashrateHistory.map { it.timestampMs to it.hashrateThs },
                        animationKey = selectedTf,
                        valueFormatter = { ExploreAnalyticsMath.formatHashrateFromThs(it) },
                        useLogScale = logScale,
                    )
                }
            }
        }

        // Block Reward, Next Reduction Countdown, & Animated Emission Schedule Curve
        item(key = "net_emission") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(
                    text = walletStrings.statsBlockRewardTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatMiniTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = walletStrings.statsCurrentRewardLabel,
                        value = stats?.blockRewardKas?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.4f KAS", it) } ?: "—",
                    )
                    StatMiniTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = walletStrings.statsNextReductionLabel,
                        value = stats?.nextReductionRewardKas?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.4f KAS", it) } ?: "—",
                    )
                }
                if (stats != null && stats.nextReductionTimestampSec > 0L) {
                    Spacer(Modifier.height(8.dp))
                    StatMiniTile(
                        modifier = Modifier.fillMaxWidth(),
                        label = "${walletStrings.statsCountdownLabel} (${stats.nextReductionDate.substringBefore(" ")})",
                        value = ExploreAnalyticsMath.formatCountdown(stats.nextReductionTimestampSec, nowMs),
                    )
                }

                if (emissionPoints.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = walletStrings.statsEmissionCurveTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glassSecondaryContentColor(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AnimatedSingleLineChart(
                        points = emissionPoints.map { it.timestampMs to it.blockRewardKas },
                        animationKey = "emission_${stats?.blockRewardKas}",
                        valueFormatter = { String.format(Locale.US, "%.4f KAS / block", it) },
                        lineColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                        height = 165.dp,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Tab 3: Paginated Top 10,000 Rich List + Official Sea-Creature Coin Distribution Table
// ============================================================================

@Composable
private fun RichListTabContent(
    entries: List<RichListEntry>,
    distribution: List<DistributionTier>,
    circulatingSupplyKas: Double?,
    selectedCurrency: FiatCurrency,
    spotPriceInCurrency: Double?,
    privacyKasOnly: Boolean,
    distShowFiat: Boolean,
    onToggleDistFiat: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    pageSize: Int,
    onPageSizeChange: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isLoading: Boolean,
    errorMsg: String?,
    backdrop: Backdrop?,
    topContentPadding: Dp,
    gestureLocked: Boolean,
    onInspectAddress: (String) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val scope = rememberCoroutineScope()
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val kasFmt = remember { DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)) }

    val pageSlice = remember(entries, query, currentPage, pageSize) {
        ExploreAnalyticsMath.paginateRichList(
            entries = entries,
            rawQuery = query,
            requestedPage = currentPage,
            pageSize = pageSize,
        )
    }

    val quickRanks = remember { listOf(1 to "#1", 100 to "#100", 1_000 to "#1K", 5_000 to "#5K", 10_000 to "#10K") }
    val pageSizes = remember { listOf(10, 25, 50, 100) }
    val totalSupplyKas = remember(distribution, circulatingSupplyKas) {
        val distSum = distribution.sumOf { it.amountKas }.toDouble()
        (circulatingSupplyKas?.takeIf { it > 0.0 } ?: distSum).coerceAtLeast(1.0)
    }

    val richListHeaderIndex = if (distribution.isNotEmpty()) 1 else 0

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        userScrollEnabled = !gestureLocked,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topContentPadding,
            bottom = 28.dp,
        ),
    ) {
        // 1. Official Sea-Creature Coin Distribution Table ABOVE the Rich List
        if (distribution.isNotEmpty()) {
            item(key = "sea_creature_distribution_table") {
                SeaCreatureDistributionTableCard(
                    distribution = distribution,
                    totalSupplyKas = totalSupplyKas,
                    selectedCurrency = selectedCurrency,
                    spotPriceInCurrency = spotPriceInCurrency,
                    privacyKasOnly = privacyKasOnly,
                    showFiat = distShowFiat,
                    onToggleFiat = onToggleDistFiat,
                    backdrop = backdrop,
                )
            }
        }

        // 2. Search Bar + Canonical 3D Kinetic Aero Quick-Jump Rank Pills + Rows-Per-Page Selector
        item(key = "rich_search_header") {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(14.dp),
            ) {
                FluidGlassTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = walletStrings.statsRichListSearchPlaceholder,
                    singleLine = true,
                    nestedInGlassCard = true,
                    leadingContent = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = glassSecondaryContentColor(), modifier = Modifier.size(18.dp))
                    },
                    trailingContent = if (query.isNotEmpty()) {
                        {
                            FluidGlassIconButton(
                                onClick = { onQueryChange("") },
                                size = 28.dp,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = walletStrings.clear, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else null,
                )
                Spacer(Modifier.height(10.dp))
                // Canonical 3D Kinetic Aero Quick-Jump Pills (zero clipping on #100 or #10K!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    quickRanks.forEach { (rankTarget, label) ->
                        val isSelected = pageSlice.highlightedRank == rankTarget ||
                            (rankTarget == 1 && query.isBlank() && pageSlice.currentPage == 0)
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            selected = isSelected,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            onClick = {
                                if (rankTarget == 1) {
                                    onQueryChange("")
                                    onPageChange(0)
                                } else {
                                    onQueryChange("#$rankTarget")
                                }
                            },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Canonical 3D Kinetic Aero Rows-per-page selector (10, 25, 50, 100)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${walletStrings.statsRowsPerPageLabel}:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = glassSecondaryContentColor(),
                    )
                    pageSizes.forEach { sizeOption ->
                        val isSelected = pageSize == sizeOption
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            selected = isSelected,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            onClick = { onPageSizeChange(sizeOption) },
                        ) {
                            Text(
                                text = "$sizeOption",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }

        if (!errorMsg.isNullOrBlank()) {
            item(key = "rich_err") { ErrorInlineBanner(errorMsg) }
        }

        // 3. Paginated Rich List Rows
        if (isLoading && entries.isEmpty()) {
            item(key = "rich_loading") {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.8.dp)
                }
            }
        } else if (pageSlice.pageItems.isEmpty()) {
            item(key = "rich_empty") {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backdrop = backdrop,
                    useBackdropCapture = false,
                    contentPadding = PaddingValues(18.dp),
                ) {
                    Text(
                        text = walletStrings.statsRankNotFound,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassSecondaryContentColor(),
                    )
                }
            }
        } else {
            items(pageSlice.pageItems, key = { "${it.rank}_${it.address}" }) { entry ->
                val isExactHit = pageSlice.highlightedRank == entry.rank
                val rowShape = ContinuousCornerShape(18.dp)
                val rowBg = when {
                    isExactHit && (reduceTransparency || isDark) -> MaterialTheme.colorScheme.primaryContainer
                    isExactHit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
                    isDark -> Color(0xFF141C24).copy(alpha = 0.62f)
                    else -> Color.White.copy(alpha = 0.72f)
                }
                val primaryInk = if (isExactHit && (reduceTransparency || isDark)) MaterialTheme.colorScheme.onPrimaryContainer else glassPrimaryContentColor()
                val secondaryInk = if (isExactHit && (reduceTransparency || isDark)) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.80f) else glassSecondaryContentColor()
                val ocean = OceanTier.forKasBalance(entry.amountKas.toDouble())
                val fiatStr = if (!privacyKasOnly && spotPriceInCurrency != null && spotPriceInCurrency > 0.0) {
                    ExploreAnalyticsMath.formatCompactCurrency(entry.amountKas.toDouble() * spotPriceInCurrency, selectedCurrency)
                } else null

                val rowBorderColor = if (isExactHit) accentInk.copy(alpha = 0.65f) else if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.85f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kineticInteractiveRow(
                            shape = rowShape,
                            backgroundColor = rowBg,
                            borderColor = rowBorderColor,
                            borderWidth = if (isExactHit) 1.2.dp else 0.75.dp,
                            isHighlighted = isExactHit,
                            onClick = { onInspectAddress(entry.address) },
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "#${entry.rank}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isExactHit) primaryInk else accentInk,
                            )
                            Text(text = ocean.emoji, fontSize = 14.sp)
                            if (!entry.label.isNullOrBlank()) {
                                EntityLabelPill(entry.label)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${kasFmt.format(entry.amountKas)} KAS",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk,
                            )
                            if (fiatStr != null) {
                                Text(
                                    text = fiatStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondaryInk,
                                )
                            }
                        }
                    }
                    Text(
                        text = entry.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = secondaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 4. Canonical 3D Kinetic Aero Pagination Controls (`<<`, `<`, Page X / Y, `>`, `>>`)
            item(key = "rich_pagination_bar") {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backdrop = backdrop,
                    useBackdropCapture = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val activePage = pageSlice.currentPage
                    val lastPage = (pageSlice.totalPages - 1).coerceAtLeast(0)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FluidGlassIconButton(
                                onClick = {
                                    if (query.startsWith("#") || query.all { it.isDigit() }) onQueryChange("")
                                    onPageChange(0)
                                    scope.launch { runCatching { listState.animateScrollToItem(richListHeaderIndex) } }
                                },
                                enabled = activePage > 0,
                                size = 36.dp,
                                preset = GlassPreset.Control,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.Default.FirstPage, contentDescription = "First", modifier = Modifier.size(18.dp))
                            }
                            FluidGlassIconButton(
                                onClick = {
                                    if (query.startsWith("#") || query.all { it.isDigit() }) onQueryChange("")
                                    onPageChange((activePage - 1).coerceAtLeast(0))
                                    scope.launch { runCatching { listState.animateScrollToItem(richListHeaderIndex) } }
                                },
                                enabled = activePage > 0,
                                size = 36.dp,
                                preset = GlassPreset.Control,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev", modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(
                            text = "${walletStrings.statsPageOfLabel} ${activePage + 1} / ${pageSlice.totalPages}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FluidGlassIconButton(
                                onClick = {
                                    if (query.startsWith("#") || query.all { it.isDigit() }) onQueryChange("")
                                    onPageChange((activePage + 1).coerceAtMost(lastPage))
                                    scope.launch { runCatching { listState.animateScrollToItem(richListHeaderIndex) } }
                                },
                                enabled = activePage < lastPage,
                                size = 36.dp,
                                preset = GlassPreset.Control,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", modifier = Modifier.size(18.dp))
                            }
                            FluidGlassIconButton(
                                onClick = {
                                    if (query.startsWith("#") || query.all { it.isDigit() }) onQueryChange("")
                                    onPageChange(lastPage)
                                    scope.launch { runCatching { listState.animateScrollToItem(richListHeaderIndex) } }
                                },
                                enabled = activePage < lastPage,
                                size = 36.dp,
                                preset = GlassPreset.Control,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.LastPage, contentDescription = "Last", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeaCreatureDistributionTableCard(
    distribution: List<DistributionTier>,
    totalSupplyKas: Double,
    selectedCurrency: FiatCurrency,
    spotPriceInCurrency: Double?,
    privacyKasOnly: Boolean,
    showFiat: Boolean,
    onToggleFiat: (Boolean) -> Unit,
    backdrop: Backdrop?,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val countFmt = remember { DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)) }
    val tiersDescending = remember(distribution) { distribution.sortedByDescending { it.tier } }

    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = backdrop,
        useBackdropCapture = true,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = walletStrings.statsDistributionTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!privacyKasOnly && spotPriceInCurrency != null && spotPriceInCurrency > 0.0) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FluidGlassChip(
                        selected = !showFiat,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        onClick = { onToggleFiat(false) },
                    ) {
                        Text(
                            text = "KAS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (!showFiat) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    FluidGlassChip(
                        selected = showFiat,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        onClick = { onToggleFiat(true) },
                    ) {
                        Text(
                            text = selectedCurrency.code,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (showFiat) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Column headers
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = walletStrings.statsDistributionTierCol,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = glassSecondaryContentColor(),
                modifier = Modifier.weight(1.45f),
            )
            Text(
                text = walletStrings.statsDistributionAddrsCol,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = glassSecondaryContentColor(),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.9f),
            )
            Text(
                text = if (showFiat) selectedCurrency.code else walletStrings.statsDistributionTotalCol,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = glassSecondaryContentColor(),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.1f),
            )
            Text(
                text = walletStrings.statsDistributionSupplyCol,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = glassSecondaryContentColor(),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.75f),
            )
        }

        HorizontalDivider(thickness = 0.75.dp, color = glassSecondaryContentColor().copy(alpha = 0.22f))

        tiersDescending.forEachIndexed { idx, row ->
            val ocean = OceanTier.fromTierIndex(row.tier)
            val supplyPct = ((row.amountKas.toDouble() / totalSupplyKas) * 100.0).coerceIn(0.0, 100.0)
            val totalText = if (showFiat && spotPriceInCurrency != null && spotPriceInCurrency > 0.0) {
                ExploreAnalyticsMath.formatCompactCurrency(row.amountKas.toDouble() * spotPriceInCurrency, selectedCurrency)
            } else {
                ExploreAnalyticsMath.formatCompactKas(row.amountKas.toDouble()).removeSuffix(" KAS")
            }

            if (idx > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = glassSecondaryContentColor().copy(alpha = 0.12f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1.45f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = ocean.emoji, fontSize = 16.sp)
                    Column {
                        Text(
                            text = ocean.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = ocean.rangeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = glassSecondaryContentColor(),
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = countFmt.format(row.addressCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = glassPrimaryContentColor(),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(0.9f),
                )
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accentInk,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1.1f),
                )
                Text(
                    text = String.format(Locale.US, "%.2f%%", supplyPct),
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(0.75f),
                )
            }
        }
    }
}

@Composable
private fun StatMiniTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(16.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.07f)
        else -> Color.White.copy(alpha = 0.56f)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
