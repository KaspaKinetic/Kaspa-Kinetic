package com.kaspakinetic.wallet

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.model.AutoLockTimeout
import com.kaspakinetic.wallet.core.model.WalletTx
import com.kaspakinetic.wallet.core.security.CrashLogStore
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.core.service.MinerDonationService
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.KineticAeroPreferencesRepository
import com.kaspakinetic.wallet.ui.components.DonationSupportPopup
import com.kaspakinetic.wallet.ui.components.FullScreenPinOverlay
import com.kaspakinetic.wallet.ui.components.FullScreenQrScannerOverlay
import com.kaspakinetic.wallet.ui.components.LocalIsTestnetNetwork
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.PinFlowMode
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.covenants.CovenantsActivationScreen
import com.kaspakinetic.wallet.ui.covenants.CovenantsHubScreen
import com.kaspakinetic.wallet.ui.explore.BlockDagVisualizerScreen
import com.kaspakinetic.wallet.ui.explore.BlockExplorerScreen
import com.kaspakinetic.wallet.ui.explore.ExplorePlaceholderKind
import com.kaspakinetic.wallet.ui.explore.NetworkStatsScreen
import com.kaspakinetic.wallet.ui.explore.StatsTab
import com.kaspakinetic.wallet.ui.explore.YourPerformanceScreen
import com.kaspakinetic.wallet.ui.`fun`.BurnKaspaOverlay
import com.kaspakinetic.wallet.ui.`fun`.DonateMinersOverlay
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.main.DrawerToolAction
import com.kaspakinetic.wallet.ui.main.MainWalletScreen
import com.kaspakinetic.wallet.ui.main.WalletDrawerOverlay
import com.kaspakinetic.wallet.ui.onboarding.AddWalletOverlay
import com.kaspakinetic.wallet.ui.onboarding.KaspaFirstLaunchOnboarding
import com.kaspakinetic.wallet.ui.onboarding.KineticAeroOnboardingScreen
import com.kaspakinetic.wallet.ui.settings.KaspaWalletSettingsScreen
import com.kaspakinetic.wallet.ui.sheets.AddressDiscoverySheet
import com.kaspakinetic.wallet.ui.sheets.CompoundSheet
import com.kaspakinetic.wallet.ui.sheets.ContactsSheet
import com.kaspakinetic.wallet.ui.sheets.ExtendedPubKeySheet
import com.kaspakinetic.wallet.ui.sheets.NodeStatusSheet
import com.kaspakinetic.wallet.ui.sheets.ReceiveSheet
import com.kaspakinetic.wallet.ui.sheets.SendSheet
import com.kaspakinetic.wallet.ui.sheets.SignVerifyMessageSheet
import com.kaspakinetic.wallet.ui.sheets.TxDetailSheet
import com.kaspakinetic.wallet.ui.sheets.TxReportCsvSheet
import com.kaspakinetic.wallet.ui.sheets.WalletAddressesSheet
import com.kaspakinetic.wallet.ui.theme.KineticAeroTheme
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val walletViewModel: WalletViewModel by viewModels()
    private val pendingDeepLinkState = mutableStateOf<String?>(null)
    private val pendingOpenMinerDonationState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogStore.install(this)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        handleIncomingIntent(intent)

        val kineticPrefs = KineticAeroPreferencesRepository(applicationContext)

        setContent {
            val appearanceOrNull by kineticPrefs.settings.collectAsState(initial = null)
            val walletPrefsLoaded by walletViewModel.prefsLoaded.collectAsState()
            val scope = rememberCoroutineScope()

            val appearance = appearanceOrNull
            if (appearance == null || !walletPrefsLoaded) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@setContent
            }

            KineticAeroTheme(appearance = appearance) {
                KaspaKineticAppRoot(
                    activity = this,
                    appearance = appearance,
                    kineticPrefs = kineticPrefs,
                    walletViewModel = walletViewModel,
                    pendingDeepLink = pendingDeepLinkState.value,
                    onConsumeDeepLink = { pendingDeepLinkState.value = null },
                    pendingOpenMinerDonation = pendingOpenMinerDonationState.value,
                    onConsumeOpenMinerDonation = { pendingOpenMinerDonationState.value = false },
                    onUpdateAppearance = { block -> scope.launch { block() } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MinerDonationService.EXTRA_OPEN_MINER_DONATION, false) == true) {
            pendingOpenMinerDonationState.value = true
        }
        val data = intent?.dataString?.trim() ?: return
        if (data.startsWith("kaspa:", ignoreCase = true) || data.startsWith("kaspatest:", ignoreCase = true)) {
            pendingDeepLinkState.value = data
        }
    }
}

private enum class ActiveFullRoute {
    HOME,
    SETTINGS,
    REPLAY_WELCOME,
    ADD_WALLET,
    BURN_KASPA,
    DONATE_MINERS,
}

@Composable
private fun KaspaKineticAppRoot(
    activity: FragmentActivity,
    appearance: KineticAeroAppearanceSettings,
    kineticPrefs: KineticAeroPreferencesRepository,
    walletViewModel: WalletViewModel,
    pendingDeepLink: String?,
    onConsumeDeepLink: () -> Unit,
    pendingOpenMinerDonation: Boolean,
    onConsumeOpenMinerDonation: () -> Unit,
    onUpdateAppearance: (suspend () -> Unit) -> Unit,
) {
    val walletState by walletViewModel.uiState.collectAsState()
    val covenantsState by walletViewModel.covenantsState.collectAsState()
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootScope = rememberCoroutineScope()

    val reminderKineticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            rootScope.launch {
                walletViewModel.exportKineticBackupToUri(uri).onSuccess { name ->
                    walletViewModel.showMessage("Saved .kinetic backup: $name")
                }.onFailure { err ->
                    walletViewModel.showMessage(err.message ?: "Could not save .kinetic backup")
                }
            }
        }
    }

    var crashLogToShow by remember { mutableStateOf(CrashLogStore.readLastCrashLog(context)) }

    // App-lock state & Always-On Live Sync foreground/background lifecycle sync
    var isAppLocked by remember {
        mutableStateOf(
            walletState.prefs.wallets.isNotEmpty() &&
                walletState.prefs.authOnLaunch &&
                (walletState.hasPin || walletState.prefs.biometricsEnabled),
        )
    }
    var lastBackgroundedMs by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(
        lifecycleOwner,
        walletState.hasPin,
        walletState.prefs.autoLockTimeout,
        walletState.prefs.authOnLaunch,
        walletState.prefs.biometricsEnabled,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    lastBackgroundedMs = System.currentTimeMillis()
                    walletViewModel.setAppInForeground(false)
                }
                Lifecycle.Event.ON_START -> {
                    walletViewModel.setAppInForeground(true)
                    val stoppedAt = lastBackgroundedMs ?: return@LifecycleEventObserver
                    val canLock = walletState.prefs.wallets.isNotEmpty() &&
                        (walletState.hasPin || walletState.prefs.biometricsEnabled)
                    if (!canLock) return@LifecycleEventObserver
                    val timeout = walletState.prefs.autoLockTimeout
                    val shouldLock = when (timeout) {
                        AutoLockTimeout.NEVER -> false
                        AutoLockTimeout.IMMEDIATE -> true
                        else -> (System.currentTimeMillis() - stoppedAt) >= timeout.minutes * 60_000L
                    }
                    if (shouldLock) {
                        isAppLocked = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Monitor 5G / Wi-Fi network transitions to immediately recover stale gRPC streams or REST fallback
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                walletViewModel.onNetworkConnectivityChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    walletViewModel.onNetworkConnectivityChanged()
                }
            }
        }
        val registered = runCatching {
            cm?.registerDefaultNetworkCallback(callback)
            cm != null
        }.getOrDefault(false)
        onDispose {
            if (registered) {
                runCatching { cm?.unregisterNetworkCallback(callback) }
            }
        }
    }

    // Top-level route state (unmounts Home completely when visiting Settings/Burn/Donate/Explore/AddWallet!)
    var activeRoute by remember { mutableStateOf(ActiveFullRoute.HOME) }
    var activeExplore by remember { mutableStateOf<Pair<ExplorePlaceholderKind, String>?>(null) }
    var returnExploreAfterExplorer by remember { mutableStateOf<ExplorePlaceholderKind?>(null) }
    var lastStatsTab by remember { mutableStateOf(StatsTab.MARKET) }

    // Home-scoped in-window overlays
    var drawerVisible by remember { mutableStateOf(false) }
    var showNodeStatusSheet by remember { mutableStateOf(false) }
    var activeToolSheet by remember { mutableStateOf<DrawerToolAction?>(null) }
    var showReceiveSheet by remember { mutableStateOf(false) }
    var sendSheetParams by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var selectedTxForDetail by remember { mutableStateOf<WalletTx?>(null) }

    // Global overlays (QR scanner, PIN prompt & every-5-launches donation/tips popup)
    var qrScanCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingPinAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var launchCountedThisSession by rememberSaveable { mutableStateOf(false) }
    var showDonationPopup by rememberSaveable { mutableStateOf(false) }

    fun requestAuth(onAuthorized: () -> Unit) {
        when {
            walletState.hasPin -> {
                pendingPinAuthAction = onAuthorized
            }
            walletState.prefs.biometricsEnabled && SecureVault.isBiometricAvailable(activity) -> {
                SecureVault.promptBiometric(
                    activity = activity,
                    title = walletStrings.appName,
                    subtitle = walletStrings.biometricsDesc,
                    negativeButtonText = kineticStrings.cancel,
                    onSuccess = onAuthorized,
                    onFallbackOrError = {},
                )
            }
            else -> onAuthorized()
        }
    }

    LaunchedEffect(pendingOpenMinerDonation, walletState.prefs.wallets.isNotEmpty()) {
        if (pendingOpenMinerDonation && walletState.prefs.wallets.isNotEmpty()) {
            activeExplore = null
            activeRoute = ActiveFullRoute.DONATE_MINERS
            onConsumeOpenMinerDonation()
        }
    }

    LaunchedEffect(pendingDeepLink, walletState.prefs.wallets.isNotEmpty()) {
        val uri = pendingDeepLink ?: return@LaunchedEffect
        if (walletState.prefs.wallets.isNotEmpty()) {
            val parsed = KaspaAddress.parseUriOrAddress(uri)
            val addr = parsed?.address ?: uri
            val amtKas = parsed?.amountKas?.stripTrailingZeros()?.toPlainString().orEmpty()
            activeExplore = null
            activeRoute = ActiveFullRoute.HOME
            sendSheetParams = Triple(addr, amtKas, false)
            onConsumeDeepLink()
        }
    }

    val needsFirstLaunchOnboarding = !appearance.hasCompletedOnboarding || walletState.prefs.wallets.isEmpty()

    // Count each cold app open once the user is on the main workspace and unlocked; show donation modal every 5 launches
    LaunchedEffect(needsFirstLaunchOnboarding, isAppLocked) {
        if (!launchCountedThisSession && !needsFirstLaunchOnboarding && !isAppLocked) {
            launchCountedThisSession = true
            if (walletViewModel.recordAppLaunchAndShouldShowDonationPopup()) {
                showDonationPopup = true
            }
        }
    }

    CompositionLocalProvider(LocalIsTestnetNetwork provides walletState.activeNetwork.isTestnet) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            needsFirstLaunchOnboarding -> {
                KaspaFirstLaunchOnboarding(
                    appearance = appearance,
                    walletState = walletState,
                    viewModel = walletViewModel,
                    onSelectLanguage = { tag -> onUpdateAppearance { kineticPrefs.setAppLanguageTag(tag) } },
                    onSelectThemeMode = { mode -> onUpdateAppearance { kineticPrefs.setThemeMode(mode) } },
                    onReduceTransparencyChanged = { v -> onUpdateAppearance { kineticPrefs.setReduceTransparency(v) } },
                    onDisableProgressiveBlurChanged = { v -> onUpdateAppearance { kineticPrefs.setDisableProgressiveBlur(v) } },
                    onHapticsChanged = { v -> onUpdateAppearance { kineticPrefs.setHapticsEnabled(v) } },
                    onCompleteOnboarding = {
                        onUpdateAppearance { kineticPrefs.setHasCompletedOnboarding(true) }
                    },
                )
            }

            activeExplore != null -> {
                val (kind, query) = activeExplore!!
                when (kind) {
                    ExplorePlaceholderKind.BLOCK_EXPLORER -> {
                        BlockExplorerScreen(
                            initialQuery = query,
                            network = walletState.activeNetwork,
                            spotPrice = walletState.spotPrice,
                            privacyKasOnly = walletState.prefs.kasOnlyPrivacyMode,
                            appearance = appearance,
                            viewModel = walletViewModel,
                            onNotify = { walletViewModel.showMessage(it) },
                            onDismiss = {
                                val prev = returnExploreAfterExplorer
                                returnExploreAfterExplorer = null
                                activeExplore = prev?.let { it to "" }
                            },
                        )
                    }
                    ExplorePlaceholderKind.NETWORK_STATS -> {
                        NetworkStatsScreen(
                            network = walletState.activeNetwork,
                            selectedCurrency = walletState.prefs.fiatCurrency,
                            walletSpotPrice = walletState.spotPrice,
                            privacyKasOnly = walletState.prefs.kasOnlyPrivacyMode,
                            initialTab = lastStatsTab,
                            onActiveTabChanged = { lastStatsTab = it },
                            appearance = appearance,
                            repository = walletViewModel.exploreRepository,
                            onInspectAddressInExplorer = { addr ->
                                returnExploreAfterExplorer = ExplorePlaceholderKind.NETWORK_STATS
                                activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to addr
                            },
                            onDismiss = {
                                returnExploreAfterExplorer = null
                                activeExplore = null
                            },
                        )
                    }
                    ExplorePlaceholderKind.BLOCKDAG_VISUALIZER -> {
                        BlockDagVisualizerScreen(
                            network = walletState.activeNetwork,
                            appearance = appearance,
                            repository = walletViewModel.exploreRepository,
                            openBlockAddedStream = { cb -> walletViewModel.openLiveBlockDagStream(cb) },
                            onInspectBlockInExplorer = { blockHash ->
                                returnExploreAfterExplorer = ExplorePlaceholderKind.BLOCKDAG_VISUALIZER
                                activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to blockHash
                            },
                            onNotify = { walletViewModel.showMessage(it) },
                            onDismiss = {
                                returnExploreAfterExplorer = null
                                activeExplore = null
                            },
                        )
                    }
                    ExplorePlaceholderKind.YOUR_PERFORMANCE -> {
                        val activeId = walletState.activeWallet?.id
                        YourPerformanceScreen(
                            walletState = walletState,
                            appearance = appearance,
                            repository = walletViewModel.exploreRepository,
                            onToggleHideBalances = { walletViewModel.toggleHideBalances() },
                            onSetGoal = { targetKas, customMonthlyDca ->
                                if (activeId != null) {
                                    walletViewModel.setWalletGoal(activeId, targetKas, customMonthlyDca)
                                }
                            },
                            onClearGoal = {
                                if (activeId != null) {
                                    walletViewModel.clearWalletGoal(activeId)
                                }
                            },
                            onInspectTxInExplorer = { txId ->
                                returnExploreAfterExplorer = ExplorePlaceholderKind.YOUR_PERFORMANCE
                                activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to txId
                            },
                            onDismiss = {
                                returnExploreAfterExplorer = null
                                activeExplore = null
                            },
                        )
                    }
                    ExplorePlaceholderKind.COVENANTS -> {
                        if (!walletState.prefs.covenantsActivated) {
                            CovenantsActivationScreen(
                                appearance = appearance,
                                viewModel = walletViewModel,
                                onActivated = { _ ->
                                    // Stay on ExplorePlaceholderKind.COVENANTS; now covenantsActivated == true -> Hub renders
                                    // Post-activation scan is owned by WalletViewModel (viewModelScope) so navigation never cancels it!
                                },
                                onDismiss = {
                                    returnExploreAfterExplorer = null
                                    activeExplore = null
                                },
                            )
                        } else {
                            CovenantsHubScreen(
                                initialCovenantKey = query,
                                walletState = walletState,
                                appearance = appearance,
                                viewModel = walletViewModel,
                                onRequestAuth = { onOk -> requestAuth(onOk) },
                                onRequestQrScan = { cb -> qrScanCallback = cb },
                                onToggleHideBalances = { walletViewModel.toggleHideBalances() },
                                onInspectInExplorer = { id ->
                                    returnExploreAfterExplorer = ExplorePlaceholderKind.COVENANTS
                                    activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to id
                                },
                                onDismiss = {
                                    returnExploreAfterExplorer = null
                                    activeExplore = null
                                },
                            )
                        }
                    }
                }
            }

            activeRoute == ActiveFullRoute.SETTINGS -> {
                KaspaWalletSettingsScreen(
                    appearance = appearance,
                    walletState = walletState,
                    viewModel = walletViewModel,
                    onThemeModeChanged = { m -> onUpdateAppearance { kineticPrefs.setThemeMode(m) } },
                    onColorPaletteChanged = { p -> onUpdateAppearance { kineticPrefs.setColorPalette(p) } },
                    onCustomAccentColorChanged = { c -> onUpdateAppearance { kineticPrefs.setCustomAccentArgb(c) } },
                    onWallpaperPathChanged = { path -> onUpdateAppearance { kineticPrefs.setWallpaperPath(path) } },
                    onWallpaperBrightnessChanged = { b -> onUpdateAppearance { kineticPrefs.setWallpaperBrightness(b) } },
                    onFontScaleChanged = { f -> onUpdateAppearance { kineticPrefs.setFontScale(f) } },
                    onReduceTransparencyChanged = { r -> onUpdateAppearance { kineticPrefs.setReduceTransparency(r) } },
                    onDisableProgressiveBlurChanged = { d -> onUpdateAppearance { kineticPrefs.setDisableProgressiveBlur(d) } },
                    onHapticsChanged = { h -> onUpdateAppearance { kineticPrefs.setHapticsEnabled(h) } },
                    onAppLanguageChanged = { tag -> onUpdateAppearance { kineticPrefs.setAppLanguageTag(tag) } },
                    onReplayOnboarding = { activeRoute = ActiveFullRoute.REPLAY_WELCOME },
                    onNavigateBack = { activeRoute = ActiveFullRoute.HOME },
                )
            }

            activeRoute == ActiveFullRoute.REPLAY_WELCOME -> {
                KineticAeroOnboardingScreen(
                    appearance = appearance,
                    lightExampleDrawableRes = R.drawable.onboarding_example_light,
                    darkExampleDrawableRes = R.drawable.onboarding_example_dark,
                    onSelectLanguage = { tag -> onUpdateAppearance { kineticPrefs.setAppLanguageTag(tag) } },
                    onSelectThemeMode = { mode -> onUpdateAppearance { kineticPrefs.setThemeMode(mode) } },
                    onReduceTransparencyChanged = { v -> onUpdateAppearance { kineticPrefs.setReduceTransparency(v) } },
                    onDisableProgressiveBlurChanged = { v -> onUpdateAppearance { kineticPrefs.setDisableProgressiveBlur(v) } },
                    onHapticsChanged = { v -> onUpdateAppearance { kineticPrefs.setHapticsEnabled(v) } },
                    onFinish = { activeRoute = ActiveFullRoute.SETTINGS },
                )
            }

            activeRoute == ActiveFullRoute.ADD_WALLET -> {
                AddWalletOverlay(
                    appearance = appearance,
                    viewModel = walletViewModel,
                    onDismiss = { activeRoute = ActiveFullRoute.HOME },
                )
            }

            activeRoute == ActiveFullRoute.BURN_KASPA -> {
                BurnKaspaOverlay(
                    walletState = walletState,
                    appearance = appearance,
                    viewModel = walletViewModel,
                    onRequestAuth = { onOk -> requestAuth(onOk) },
                    onInspectInExplorer = { txId ->
                        activeRoute = ActiveFullRoute.HOME
                        activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to txId
                    },
                    onDismiss = { activeRoute = ActiveFullRoute.HOME },
                )
            }

            activeRoute == ActiveFullRoute.DONATE_MINERS -> {
                DonateMinersOverlay(
                    walletState = walletState,
                    appearance = appearance,
                    viewModel = walletViewModel,
                    onRequestAuth = { onOk -> requestAuth(onOk) },
                    onInspectInExplorer = { txId ->
                        activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to txId
                    },
                    onDismiss = { activeRoute = ActiveFullRoute.HOME },
                )
            }

            else -> {
                MainWalletScreen(
                    appearance = appearance,
                    walletState = walletState,
                    onOpenDrawer = { drawerVisible = true },
                    onOpenNodeStatus = { showNodeStatusSheet = true },
                    onToggleHideBalances = { walletViewModel.toggleHideBalances() },
                    onRefresh = { walletViewModel.refreshWallet() },
                    onOpenQrScanner = {
                        qrScanCallback = { scanned ->
                            val parsed = KaspaAddress.parseUriOrAddress(scanned)
                            if (parsed != null) {
                                sendSheetParams = Triple(
                                    parsed.address,
                                    parsed.amountKas?.stripTrailingZeros()?.toPlainString().orEmpty(),
                                    false,
                                )
                            } else {
                                activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to scanned.trim()
                            }
                        }
                    },
                    onOpenReceive = { showReceiveSheet = true },
                    onOpenSend = { useSelected -> sendSheetParams = Triple("", "", useSelected) },
                    onOpenCompound = { activeToolSheet = DrawerToolAction.COMPOUND },
                    onOpenYourPerformance = { activeExplore = ExplorePlaceholderKind.YOUR_PERFORMANCE to "" },
                    onSelectTx = { selectedTxForDetail = it },
                    onToggleUtxo = { walletViewModel.toggleUtxoSelection(it) },
                    onClearUtxoSelection = { walletViewModel.clearUtxoSelection() },
                    onInspectInExplorer = { id ->
                        activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to id
                    },
                    covenants = covenantsState.covenants,
                    confirmingCovenantKeys = covenantsState.confirmingCovenantKeys,
                    chainTimeAnchorFlow = walletViewModel.chainTimeAnchor,
                    onPollServerAnchor = { walletViewModel.pollServerBlockDagAnchorNow() },
                    onOpenCovenantsHub = {
                        activeExplore = ExplorePlaceholderKind.COVENANTS to ""
                    },
                    onSelectTrackedCovenant = { canonicalKey ->
                        activeExplore = ExplorePlaceholderKind.COVENANTS to canonicalKey
                    },
                    onRefreshCovenants = { walletViewModel.refreshCovenantsLiveState() },
                    onClearSnackbar = { walletViewModel.clearMessage() },
                    overlayContent = {
                        WalletDrawerOverlay(
                            visible = drawerVisible,
                            walletState = walletState,
                            onDismiss = { drawerVisible = false },
                            onSelectWallet = { walletViewModel.selectWallet(it) },
                            onRenameWallet = { id, newName -> walletViewModel.renameWallet(id, newName) },
                            onDeleteWallet = { id ->
                                requestAuth { walletViewModel.deleteWallet(id) }
                            },
                            onAddWallet = { activeRoute = ActiveFullRoute.ADD_WALLET },
                            onCopyAddress = { walletViewModel.showMessage(walletStrings.copiedToClipboard) },
                            onOpenTool = { tool ->
                                if (tool == DrawerToolAction.EXTENDED_PUB_KEY) {
                                    requestAuth { activeToolSheet = tool }
                                } else {
                                    activeToolSheet = tool
                                }
                            },
                            onOpenExplore = { kind -> activeExplore = kind to "" },
                            onOpenBurnKaspa = { activeRoute = ActiveFullRoute.BURN_KASPA },
                            onOpenDonateMiners = { activeRoute = ActiveFullRoute.DONATE_MINERS },
                            onOpenSettings = { activeRoute = ActiveFullRoute.SETTINGS },
                        )

                        if (showNodeStatusSheet) {
                            val liveHealth by walletViewModel.liveNodeHealth.collectAsState()
                            NodeStatusSheet(
                                walletState = walletState,
                                liveNodeHealth = liveHealth,
                                onForceSync = { walletViewModel.refreshWallet() },
                                onOpenNodeSettings = { activeRoute = ActiveFullRoute.SETTINGS },
                                onDismiss = { showNodeStatusSheet = false },
                            )
                        }

                        if (showReceiveSheet) {
                            ReceiveSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onDismiss = { showReceiveSheet = false },
                            )
                        }

                        sendSheetParams?.let { (initRecipient, initAmount, useSelected) ->
                            SendSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                initialRecipient = initRecipient,
                                initialAmountKas = initAmount,
                                initialUseSelectedUtxos = useSelected,
                                onRequestQrScan = { cb -> qrScanCallback = cb },
                                onRequestAuth = { onOk -> requestAuth(onOk) },
                                onInspectInExplorer = { txId ->
                                    activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to txId
                                },
                                onDismiss = { sendSheetParams = null },
                            )
                        }

                        selectedTxForDetail?.let { tx ->
                            TxDetailSheet(
                                tx = tx,
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onInspectInExplorer = { id ->
                                    activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to id
                                },
                                onDismiss = { selectedTxForDetail = null },
                            )
                        }

                        when (activeToolSheet) {
                            DrawerToolAction.WALLET_ADDRESSES -> WalletAddressesSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.CONTACTS -> ContactsSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onSendToContact = { addr -> sendSheetParams = Triple(addr, "", false) },
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.COMPOUND -> CompoundSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onRequestAuth = { onOk -> requestAuth(onOk) },
                                onInspectInExplorer = { id -> activeExplore = ExplorePlaceholderKind.BLOCK_EXPLORER to id },
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.ADDRESS_DISCOVERY -> AddressDiscoverySheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.EXTENDED_PUB_KEY -> ExtendedPubKeySheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.TX_REPORT_CSV -> TxReportCsvSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onDismiss = { activeToolSheet = null },
                            )
                            DrawerToolAction.SIGN_VERIFY_MESSAGE -> SignVerifyMessageSheet(
                                walletState = walletState,
                                viewModel = walletViewModel,
                                onRequestAuth = { onOk -> requestAuth(onOk) },
                                onDismiss = { activeToolSheet = null },
                            )
                            null -> Unit
                        }

                        if (showDonationPopup) {
                            val showCovBackupReminder = !walletState.prefs.covenantsReminderOptOut &&
                                (walletState.prefs.covenantsActivated || covenantsState.covenants.isNotEmpty())
                            DonationSupportPopup(
                                hasSeenLagTipStep = walletState.prefs.hasSeenLagTipStep,
                                reduceTransparency = appearance.reduceTransparency,
                                donationOptedOut = walletState.prefs.donationPopupOptOut,
                                showCovenantsBackupReminder = showCovBackupReminder,
                                onReduceTransparencyChanged = { enabled ->
                                    onUpdateAppearance { kineticPrefs.setReduceTransparency(enabled) }
                                },
                                onOptOutSelected = { walletViewModel.setDonationPopupOptOut(true) },
                                onCovenantsReminderOptOutSelected = { walletViewModel.setCovenantsReminderOptOut(true) },
                                onMarkLagTipSeen = { walletViewModel.markLagTipStepSeen() },
                                onDownloadKineticBackup = {
                                    reminderKineticExportLauncher.launch(walletViewModel.defaultKineticBackupFilename())
                                },
                                onCopiedMessage = { walletViewModel.showMessage(it) },
                                onDismiss = { showDonationPopup = false },
                            )
                        }

                        if (walletState.showRestFallbackWarning && !showDonationPopup) {
                            val isDarkDialog = com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme.current
                            val reduceTransDialog = com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency.current
                            val amberAccent = androidx.compose.ui.graphics.Color(0xFFF39C12)
                            val iconBadgeShape = com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape(12.dp)
                            val noticeCardShape = com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape(18.dp)
                            val noticeCardBg = when {
                                reduceTransDialog -> MaterialTheme.colorScheme.surfaceContainerHighest
                                isDarkDialog -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)
                                else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.56f)
                            }
                            FluidGlassDialog(
                                onDismissRequest = { walletViewModel.dismissRestFallbackWarning() },
                                title = {
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .background(
                                                    amberAccent.copy(alpha = if (isDarkDialog) 0.22f else 0.16f),
                                                    iconBadgeShape,
                                                ),
                                            contentAlignment = androidx.compose.ui.Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.WarningAmber,
                                                contentDescription = null,
                                                tint = amberAccent,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Text(
                                            text = walletStrings.restFallbackDialogTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = glassPrimaryContentColor(),
                                        )
                                    }
                                },
                                actions = {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            useBackdropCapture = false,
                                            onClick = {
                                                walletViewModel.dismissRestFallbackWarning()
                                                activeRoute = ActiveFullRoute.SETTINGS
                                            },
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(walletStrings.restFallbackOpenSettingsButton, fontWeight = FontWeight.Bold)
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            useBackdropCapture = false,
                                            onClick = { walletViewModel.dismissRestFallbackWarning() },
                                        ) {
                                            Text(walletStrings.restFallbackGotItButton, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(noticeCardBg, noticeCardShape)
                                        .padding(14.dp),
                                ) {
                                    Text(
                                        text = walletStrings.restFallbackDialogBody,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = glassPrimaryContentColor().copy(alpha = 0.92f),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        // Global QR Scanner Overlay
        qrScanCallback?.let { cb ->
            FullScreenQrScannerOverlay(
                onResult = { scanned ->
                    qrScanCallback = null
                    cb(scanned)
                },
                onDismiss = { qrScanCallback = null },
            )
        }

        // Global Transaction / Sensitive Action PIN Verification Overlay
        pendingPinAuthAction?.let { action ->
            FullScreenPinOverlay(
                mode = PinFlowMode.VERIFY,
                wallpaperPath = appearance.wallpaperPath,
                wallpaperBrightness = appearance.wallpaperBrightness,
                allowDismiss = true,
                allowBiometrics = walletState.prefs.biometricsEnabled,
                onVerifyPin = { walletViewModel.verifyPin(it) },
                onVerifiedSuccess = {
                    pendingPinAuthAction = null
                    action()
                },
                onDismiss = { pendingPinAuthAction = null },
            )
        }

        // App-Lock Screen (non-dismissible until unlocked)
        if (isAppLocked && !needsFirstLaunchOnboarding) {
            FullScreenPinOverlay(
                mode = PinFlowMode.VERIFY,
                wallpaperPath = appearance.wallpaperPath,
                wallpaperBrightness = appearance.wallpaperBrightness,
                allowDismiss = false,
                allowBiometrics = walletState.prefs.biometricsEnabled,
                onVerifyPin = { walletViewModel.verifyPin(it) },
                onVerifiedSuccess = { isAppLocked = false },
            )
        }

        // Built-in Crash Diagnostics Overlay ("Copy Last Crash Log")
        crashLogToShow?.let { crashText ->
            FluidGlassDialog(
                onDismissRequest = {
                    CrashLogStore.clearLastCrashLog(context)
                    crashLogToShow = null
                },
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = walletStrings.crashOverlayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    }
                },
                actions = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = true,
                            useBackdropCapture = false,
                            onClick = {
                                copyTextToClipboard(context, "Kaspa Kinetic Crash Log", crashText)
                                walletViewModel.showMessage(walletStrings.copiedToClipboard)
                                CrashLogStore.clearLastCrashLog(context)
                                crashLogToShow = null
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(walletStrings.copyCrashLogButton, fontWeight = FontWeight.SemiBold)
                        }
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            onClick = {
                                CrashLogStore.clearLastCrashLog(context)
                                crashLogToShow = null
                            },
                        ) {
                            MarqueeText(walletStrings.dismissCrashLogButton, fontWeight = FontWeight.Medium)
                        }
                    }
                },
            ) {
                Text(
                    text = walletStrings.crashOverlayBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = glassSecondaryContentColor(),
                )
                Text(
                    text = crashText.take(600),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                )
            }
        }
    }
    }
}
