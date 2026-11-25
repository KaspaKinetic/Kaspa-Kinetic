package com.kaspakinetic.wallet.ui.covenants

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor
import com.kaspakinetic.wallet.core.covenants.CovenantCategory
import com.kaspakinetic.wallet.core.covenants.CovenantCompiler
import com.kaspakinetic.wallet.core.covenants.CovenantStatus
import com.kaspakinetic.wallet.core.covenants.CovenantTemplateId
import com.kaspakinetic.wallet.core.covenants.TimeLockStage
import com.kaspakinetic.wallet.core.covenants.TrackedCovenant
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.KineticRefreshIconButton
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
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
import com.kaspakinetic.wallet.ui.locale.safeFormatLocalized
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CovenantsHubTab {
    YOURS,
    SHARED,
    CREATE,
    BACKUP,
}

private sealed class HubSubRoute {
    data object Hub : HubSubRoute()
    data class Create(val templateId: CovenantTemplateId) : HubSubRoute()
    data class Detail(val canonicalKey: String) : HubSubRoute()
    data object ReviewActivationWarning : HubSubRoute()
}

/**
 * Full-screen Stage 3.2 Kaspa L1 SilverScript Covenants Hub:
 * - Pinned Floating Kinetic Aero Top Header (exact app-wide `TN10` pill + exact spinning Refresh + `?` Guide/Settings)
 * - 4-Tab Segmented Slider (`Yours | Shared | Create | Backup`)
 * - Strictly scoped per active wallet (`Yours` owned vs `Shared` imported `.kpass` passes with 0-KAS 1-tap claims)
 */
@Composable
fun CovenantsHubScreen(
    walletState: WalletUiState,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    initialCovenantKey: String = "",
    onRequestAuth: (() -> Unit) -> Unit,
    onRequestQrScan: ((String) -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onToggleHideBalances: () -> Unit,
    onDismiss: () -> Unit,
) {
    val covState by viewModel.covenantsState.collectAsState()
    val liveHealth by viewModel.liveNodeHealth.collectAsState()
    val chainTimeAnchor by viewModel.chainTimeAnchor.collectAsState()

    var subRoute by remember(initialCovenantKey) {
        mutableStateOf<HubSubRoute>(
            if (initialCovenantKey.isNotBlank()) HubSubRoute.Detail(initialCovenantKey) else HubSubRoute.Hub,
        )
    }
    var activeTab by remember { mutableStateOf(CovenantsHubTab.YOURS) }
    var listFilter by remember { mutableStateOf(CovenantListFilter.ACTIVE) }
    var tickElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Per-tab preserved LazyListStates mounted across subRoute transitions so tapping an item on
    // `Yours` or `Create` NEVER tears down the list or flashes scrolled upward for 1 frame!
    val yoursListState = rememberLazyListState()
    val sharedListState = rememberLazyListState()
    val createListState = rememberLazyListState()
    val backupListState = rememberLazyListState()

    // Single stable monotonic 1-second ticker — never cancels on gRPC frames or DAG updates!
    LaunchedEffect(Unit) {
        viewModel.refreshCovenantsLiveState()
        while (true) {
            delay(1_000L)
            tickElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    val virtualDaa = chainTimeAnchor.estimateVirtualDaaScore(tickElapsedMs).takeIf { it > 0L }
        ?: liveHealth?.virtualDaaScore?.takeIf { it > 0L }
        ?: walletState.nodeHealth?.virtualDaaScore ?: 0L

    BackHandler(enabled = subRoute == HubSubRoute.Hub) { onDismiss() }

    val s = LocalWalletStrings.current
    val ks = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true
    val hideBalances = walletState.prefs.hideBalances
    val privacyMode = walletState.prefs.kasOnlyPrivacyMode
    val unit = if (walletState.activeNetwork.isTestnet) s.tkasUnit else s.kasUnit
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 154.dp.roundToPx() }) }
    var showGuideSettingsDialog by remember { mutableStateOf(false) }
    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var exportPassphraseInput by remember { mutableStateOf("") }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }

    var showRestorePassDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassInput by remember { mutableStateOf("") }
    var restorePassError by remember { mutableStateOf<String?>(null) }

    var showImportPassDialog by remember { mutableStateOf(false) }
    var importPassInput by remember { mutableStateOf("") }
    var pendingRemoveShared by remember { mutableStateOf<TrackedCovenant?>(null) }

    val postActivationScan = covState.postActivationScan

    // SAF: Download Latest `.kinetic` Backup
    val createKineticLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val res = viewModel.exportKineticBackupToUri(uri, pendingExportPassphrase)
                pendingExportPassphrase = null
                res.onSuccess { name ->
                    viewModel.showMessage(safeFormatLocalized(s.covSavedBackupMsgFmt, name))
                }.onFailure { err ->
                    viewModel.showMessage(err.message ?: s.covBackupDownloadButton)
                }
            }
        }
    }

    // SAF: Link External Auto-Sync `.kinetic` File
    val linkAutoSyncLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setCovenantsAutoSyncUri(uri.toString())
            viewModel.showMessage(s.covAutoSyncLinkedBadge)
        }
    }

    // SAF: Restore `.kinetic` Backup
    val openKineticLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val inspect = viewModel.inspectKineticBackupUri(uri)
                inspect.onSuccess { env ->
                    if (env.kdf == "pbkdf2-hmac-sha256") {
                        pendingRestoreUri = uri
                        restorePassInput = ""
                        restorePassError = null
                        showRestorePassDialog = true
                    } else {
                        val res = viewModel.restoreKineticBackupFromUri(uri)
                        res.onSuccess { rep ->
                            viewModel.showMessage(safeFormatLocalized(s.covRestoredBackupMsgFmt, rep.merged.size, rep.addedCount))
                        }.onFailure { err ->
                            viewModel.showMessage(err.message ?: s.covBackupRestoreButton)
                        }
                    }
                }.onFailure { err ->
                    viewModel.showMessage(err.message ?: s.covBackupRestoreButton)
                }
            }
        }
    }

    // SAF: Import `.kpass` Shared Pass File
    val openKpassLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.importKpassFromUri(uri)
                    .onSuccess { imported ->
                        activeTab = CovenantsHubTab.SHARED
                        viewModel.showMessage(safeFormatLocalized(s.covSharedImportedMsgFmt, imported.label))
                    }
                    .onFailure { err ->
                        viewModel.showMessage(err.message ?: s.covImportKpassFileButton)
                    }
            }
        }
    }

    val tier1Backdrop = rememberLayerBackdrop()
    val tier2Backdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else tier1Backdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else tier2Backdrop
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    val securityPresets = remember {
        CovenantTemplateId.entries.filter { it.category == CovenantCategory.SECURITY_VAULTS }
    }
    val savingsPresets = remember {
        CovenantTemplateId.entries.filter { it.category == CovenantCategory.SAVINGS_PAYMENTS }
    }

    // Identical single-source-of-truth filter as Home Covenants tab (strictly scoped by walletId + activeNetwork)!
    val activeWalletId = walletState.activeWallet?.id
    val ownedCovenants = remember(covState.covenants, walletState.activeNetwork, activeWalletId) {
        CovenantCompiler.ownedCovenantsForNetwork(covState.covenants, walletState.activeNetwork, activeWalletId)
    }
    val sharedCovenants = remember(covState.covenants, walletState.activeNetwork, activeWalletId) {
        CovenantCompiler.sharedCovenantsForNetwork(covState.covenants, walletState.activeNetwork, activeWalletId)
    }
    // Fast 2-second gRPC GetBlockDagInfo poll whenever any covenant is in `Finalizing on Kaspa L1...`
    val needsFastFinalityPoll = (ownedCovenants + sharedCovenants).any {
        chainTimeAnchor.isCovenantFinalizing(it, tickElapsedMs)
    }
    LaunchedEffect(needsFastFinalityPoll) {
        if (needsFastFinalityPoll) {
            while (true) {
                viewModel.pollServerBlockDagAnchorNow()
                delay(2_000L)
            }
        }
    }
    val activeCount = remember(ownedCovenants) { ownedCovenants.count { it.liveStatus != CovenantStatus.SETTLED } }
    val settledCount = remember(ownedCovenants) { ownedCovenants.count { it.liveStatus == CovenantStatus.SETTLED } }
    val filteredOwned = remember(ownedCovenants, listFilter) { filterCovenantsByStatus(ownedCovenants, listFilter) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        // LAYER A: Tier 2 Viewport Capture
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(tier2Backdrop) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!reduceTransparency) Modifier.layerBackdrop(tier1Backdrop) else Modifier),
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
                val activeListState = when (activeTab) {
                    CovenantsHubTab.YOURS -> yoursListState
                    CovenantsHubTab.SHARED -> sharedListState
                    CovenantsHubTab.CREATE -> createListState
                    CovenantsHubTab.BACKUP -> backupListState
                }
                LazyColumn(
                    state = activeListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    userScrollEnabled = !gestureLocked && subRoute == HubSubRoute.Hub,
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = topPadDp + 10.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (activeTab) {
                        CovenantsHubTab.YOURS -> {
                            item(key = "hub_your_filters") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!walletState.prefs.covenantsAutoSyncUri.isNullOrBlank()) {
                                        FluidGlassChip(
                                            selected = false,
                                            useBackdropCapture = false,
                                            lockParentGestures = false,
                                            onClick = { showGuideSettingsDialog = true },
                                        ) {
                                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = s.covAutoSyncLinkedBadge,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    if (isWatchOnly) {
                                        CovenantWatchOnlyBannerCard(text = s.covWatchOnlyBanner)
                                    }
                                    CovenantFilterChipsRow(
                                        selected = listFilter,
                                        onSelect = { listFilter = it },
                                        activeCount = activeCount,
                                        settledCount = settledCount,
                                        allCount = ownedCovenants.size,
                                        hideBalances = hideBalances,
                                    )
                                }
                            }

                            if (filteredOwned.isEmpty()) {
                                item(key = "hub_empty_state") {
                                    FluidGlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backdrop = effectiveAmbient,
                                        useBackdropCapture = true,
                                        contentPadding = PaddingValues(20.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccountBalanceWallet,
                                                contentDescription = null,
                                                tint = accentInk,
                                                modifier = Modifier.size(30.dp),
                                            )
                                            Text(
                                                text = if (ownedCovenants.isEmpty()) s.covHomeEmptyTitle else "${s.covFilterActive}: 0",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                                textAlign = TextAlign.Center,
                                            )
                                            Text(
                                                text = s.covHomeEmptyBody,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = glassSecondaryContentColor(),
                                                textAlign = TextAlign.Center,
                                            )
                                            FluidGlassButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                accent = true,
                                                useBackdropCapture = false,
                                                lockParentGestures = true,
                                                onClick = { activeTab = CovenantsHubTab.CREATE },
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                                Spacer(Modifier.width(6.dp))
                                                MarqueeText(
                                                    text = s.covCreateSectionHeader,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(filteredOwned, key = { it.canonicalKey }) { cov ->
                                    TrackedCovenantRowCard(
                                        covenant = cov,
                                        virtualDaa = virtualDaa,
                                        hideBalances = hideBalances,
                                        kasOnlyPrivacyMode = privacyMode,
                                        spotPrice = walletState.spotPrice,
                                        onClick = { subRoute = HubSubRoute.Detail(cov.canonicalKey) },
                                        chainTimeAnchor = chainTimeAnchor,
                                        nowElapsedRealtimeMs = tickElapsedMs,
                                        isConfirming = cov.canonicalKey in covState.confirmingCovenantKeys,
                                    )
                                }
                            }
                        }

                        CovenantsHubTab.SHARED -> {
                            item(key = "shared_import_actions") {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(14.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = { openKpassLauncher.launch(arrayOf("*/*")) },
                                        ) {
                                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(6.dp))
                                            MarqueeText(
                                                text = s.covImportKpassFileButton,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                onClick = {
                                                    onRequestQrScan { scanned ->
                                                        scope.launch {
                                                            viewModel.importSharedPassJson(scanned)
                                                                .onSuccess {
                                                                    viewModel.showMessage(safeFormatLocalized(s.covSharedImportedMsgFmt, it.label))
                                                                }
                                                                .onFailure {
                                                                    viewModel.showMessage(it.message ?: s.covImportScanQrButton)
                                                                }
                                                        }
                                                    }
                                                },
                                            ) {
                                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(5.dp))
                                                MarqueeText(s.covImportScanQrButton, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                            }
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                onClick = {
                                                    importPassInput = ""
                                                    showImportPassDialog = true
                                                },
                                            ) {
                                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(5.dp))
                                                MarqueeText(s.covImportPasteJsonButton, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }

                            if (sharedCovenants.isEmpty()) {
                                item(key = "shared_empty") {
                                    FluidGlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backdrop = effectiveAmbient,
                                        useBackdropCapture = true,
                                        contentPadding = PaddingValues(20.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = null,
                                                tint = accentInk,
                                                modifier = Modifier.size(28.dp),
                                            )
                                            Text(
                                                text = s.covSharedEmptyTitle,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                                textAlign = TextAlign.Center,
                                            )
                                            Text(
                                                text = s.covSharedEmptyBody,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = glassSecondaryContentColor(),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(sharedCovenants, key = { "shared_${it.canonicalKey}" }) { cov ->
                                    SharedPassRecipientCard(
                                        covenant = cov,
                                        unit = unit,
                                        chainTimeAnchor = chainTimeAnchor,
                                        nowElapsedRealtimeMs = tickElapsedMs,
                                        virtualDaa = virtualDaa,
                                        hideBalances = hideBalances,
                                        isConfirming = cov.canonicalKey in covState.confirmingCovenantKeys,
                                        onOpenDetail = { subRoute = HubSubRoute.Detail(cov.canonicalKey) },
                                        onRequestRemove = { pendingRemoveShared = cov },
                                    )
                                }
                            }
                        }

                        CovenantsHubTab.CREATE -> {
                            if (isWatchOnly) {
                                item(key = "hub_create_watch_only") {
                                    CovenantWatchOnlyBannerCard(text = s.covWatchOnlyBanner)
                                }
                            }
                            item(key = "cov_grp_security_hdr") {
                                Text(
                                    text = s.covenantsGroupSecurity,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = accentInk,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            items(securityPresets, key = { it.id }) { tpl ->
                                CovenantPresetActionRow(
                                    templateId = tpl,
                                    onClick = { subRoute = HubSubRoute.Create(tpl) },
                                )
                            }

                            item(key = "cov_grp_savings_hdr") {
                                Text(
                                    text = s.covenantsGroupSavings,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = accentInk,
                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                                )
                            }
                            items(savingsPresets, key = { it.id }) { tpl ->
                                CovenantPresetActionRow(
                                    templateId = tpl,
                                    onClick = { subRoute = HubSubRoute.Create(tpl) },
                                )
                            }
                        }

                        CovenantsHubTab.BACKUP -> {
                            item(key = "cov_backup_card") {
                                val updatedStr = formatShortTimestamp(covState.vaultLastUpdatedAtMs)
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(ContinuousCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Default.Backup, contentDescription = null, tint = accentInk, modifier = Modifier.size(20.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = s.covBackupCardTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                            )
                                            Text(
                                                text = safeFormatLocalized(s.covBackupMetaFmt, covState.covenants.size, updatedStr),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = accentInk,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = s.covBackupBannerNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = glassPrimaryContentColor(),
                                    )
                                    if (!covState.externalAutoSyncWarning.isNullOrBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = covState.externalAutoSyncWarning!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFF39C12),
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = {
                                                exportPassphraseInput = ""
                                                showExportPassphraseDialog = true
                                            },
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            MarqueeText(
                                                text = s.covBackupDownloadButton,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            useBackdropCapture = false,
                                            lockParentGestures = false,
                                            onClick = { openKineticLauncher.launch(arrayOf("*/*")) },
                                        ) {
                                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            MarqueeText(
                                                text = s.covBackupRestoreButton,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !covState.isScanningChain && !isWatchOnly,
                                            useBackdropCapture = false,
                                            lockParentGestures = false,
                                            onClick = {
                                                scope.launch {
                                                    val res = viewModel.scanChainForKkcv1Covenants()
                                                    res.onSuccess { rep ->
                                                        viewModel.showMessage(safeFormatLocalized(s.covChainScanResultFmt, rep.merged.size, rep.addedCount))
                                                    }.onFailure { err ->
                                                        viewModel.showMessage(err.message ?: s.covScanChainButton)
                                                    }
                                                }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = if (covState.isScanningChain) Icons.Default.Sync else Icons.Default.Search,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            MarqueeText(
                                                text = if (covState.isScanningChain) s.covScanningChainLabel else s.covScanChainButton,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }

                            item(key = "cov_autosync_card") {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Text(
                                        text = s.covAutoSyncTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = s.covAutoSyncDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = glassSecondaryContentColor(),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    if (walletState.prefs.covenantsAutoSyncUri.isNullOrBlank()) {
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            useBackdropCapture = false,
                                            lockParentGestures = false,
                                            onClick = { linkAutoSyncLauncher.launch(viewModel.defaultKineticBackupFilename()) },
                                        ) {
                                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            MarqueeText(s.covAutoSyncLinkButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        }
                                    } else {
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            destructive = true,
                                            useBackdropCapture = false,
                                            lockParentGestures = false,
                                            onClick = { viewModel.setCovenantsAutoSyncUri(null) },
                                        ) {
                                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            MarqueeText(s.covAutoSyncUnlinkButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header + 4-Tab Segmented Slider + Progressive Blur
        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = s.covHubTitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = onDismiss,
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = ks.back, modifier = Modifier.size(20.dp))
                    }
                },
                trailingActions = {
                    KineticRefreshIconButton(
                        isRefreshing = covState.isSyncing,
                        contentDescription = s.refresh,
                        onClick = { viewModel.refreshCovenantsLiveState() },
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    )
                    FluidGlassIconButton(
                        onClick = { showGuideSettingsDialog = true },
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = s.covHelpButtonTooltip, modifier = Modifier.size(19.dp))
                    }
                },
                subBarContent = {
                    FluidGlassSegmentedControl(
                        items = CovenantsHubTab.entries,
                        selectedItem = activeTab,
                        backdrop = effectiveViewport,
                        useBackdropCapture = true,
                        onSelect = { activeTab = it },
                        labelFor = {
                            when (it) {
                                CovenantsHubTab.YOURS -> s.covTabYours
                                CovenantsHubTab.SHARED -> s.covTabShared
                                CovenantsHubTab.CREATE -> s.covTabCreate
                                CovenantsHubTab.BACKUP -> s.covTabBackup
                            }
                        },
                    )
                },
            )

            // Post-Activation Automatic BlockDAG Covenant Scan Dialog (driven by viewModelScope!)
            // Non-dismissible while scanning so navigation or accidental back presses cannot abort it.
            if (postActivationScan.visible) {
                FluidGlassDialog(
                    onDismissRequest = { viewModel.dismissPostActivationScanDialog() },
                    dismissOnBackPress = postActivationScan.dismissAllowed,
                    dismissOnClickOutside = postActivationScan.dismissAllowed,
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = accentInk, modifier = Modifier.size(20.dp))
                            MarqueeText(
                                text = s.covInitialScanTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                    actions = {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = !postActivationScan.isScanning,
                            enabled = !postActivationScan.isScanning,
                            useBackdropCapture = false,
                            lockParentGestures = true,
                            onClick = { viewModel.dismissPostActivationScanDialog() },
                        ) {
                            Text(
                                text = if (postActivationScan.isScanning) s.covInitialScanPleaseWait else s.done,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                ) {
                    if (postActivationScan.isScanning) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = accentInk,
                            )
                            Text(
                                text = s.covInitialScanScanningBody,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = glassPrimaryContentColor(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = when {
                                    postActivationScan.foundCount > 0 ->
                                        safeFormatLocalized(s.covInitialScanFoundFmt, postActivationScan.foundCount)
                                    else -> s.covInitialScanNoneFound
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (postActivationScan.foundCount > 0) accentInk else glassPrimaryContentColor(),
                            )
                            if (!postActivationScan.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = postActivationScan.errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = s.covInitialScanMissingHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = glassSecondaryContentColor(),
                            )
                        }
                    }
                }
            }

            // `?` Guide & Covenants Settings Dialog
            if (showGuideSettingsDialog) {
                FluidGlassDialog(
                    onDismissRequest = { showGuideSettingsDialog = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = accentInk, modifier = Modifier.size(20.dp))
                            MarqueeText(
                                text = s.covGuideSheetTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                    actions = {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = true,
                            useBackdropCapture = false,
                            onClick = { showGuideSettingsDialog = false },
                        ) {
                            Text(s.done, fontWeight = FontWeight.Bold)
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = s.covGuideIntro,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = glassPrimaryContentColor(),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.covGuideYoursDesc, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
                            Text(s.covGuideSharedDesc, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
                            Text(s.covGuideCreateDesc, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
                            Text(s.covGuideBackupDesc, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ContinuousCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.09f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = s.covGuideExamplesTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = accentInk,
                            )
                            Text(
                                text = s.covGuideExamplesBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = glassPrimaryContentColor(),
                            )
                        }

                        HorizontalDivider(color = glassSecondaryContentColor().copy(alpha = 0.22f))

                        Text(
                            text = s.covSettingsTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentInk,
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = s.covAutoSyncTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                            Text(
                                text = s.covAutoSyncDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = glassSecondaryContentColor(),
                            )
                            if (walletState.prefs.covenantsAutoSyncUri.isNullOrBlank()) {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    useBackdropCapture = false,
                                    onClick = {
                                        showGuideSettingsDialog = false
                                        linkAutoSyncLauncher.launch(viewModel.defaultKineticBackupFilename())
                                    },
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    MarqueeText(s.covAutoSyncLinkButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    destructive = true,
                                    useBackdropCapture = false,
                                    onClick = { viewModel.setCovenantsAutoSyncUri(null) },
                                ) {
                                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    MarqueeText(s.covAutoSyncUnlinkButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = s.covReadActivationWarningTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                            Text(
                                text = s.covReadActivationWarningDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = glassSecondaryContentColor(),
                            )
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                useBackdropCapture = false,
                                onClick = {
                                    showGuideSettingsDialog = false
                                    subRoute = HubSubRoute.ReviewActivationWarning
                                },
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                MarqueeText(
                                    text = s.covReadActivationWarningButton,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            // Export `.kinetic` Optional Passphrase Dialog
            if (showExportPassphraseDialog) {
                FluidGlassDialog(
                    onDismissRequest = { showExportPassphraseDialog = false },
                    title = {
                        Text(
                            text = s.covPassphrasePromptTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    },
                    actions = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                useBackdropCapture = false,
                                onClick = { showExportPassphraseDialog = false },
                            ) { Text(ks.cancel) }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = true,
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    pendingExportPassphrase = exportPassphraseInput.takeIf { it.isNotBlank() }
                                    showExportPassphraseDialog = false
                                    createKineticLauncher.launch(viewModel.defaultKineticBackupFilename())
                                },
                            ) { MarqueeText(s.done, fontWeight = FontWeight.Bold) }
                        }
                    },
                ) {
                    Text(
                        text = s.covPassphraseOptionalHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                    Spacer(Modifier.height(10.dp))
                    FluidGlassTextField(
                        value = exportPassphraseInput,
                        onValueChange = { exportPassphraseInput = it },
                        placeholder = s.passphrasePlaceholder,
                        singleLine = true,
                        nestedInGlassCard = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }

            // Restore Passphrase Dialog
            if (showRestorePassDialog && pendingRestoreUri != null) {
                FluidGlassDialog(
                    onDismissRequest = {
                        showRestorePassDialog = false
                        pendingRestoreUri = null
                    },
                    title = {
                        Text(
                            text = s.covPassphrasePromptTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    },
                    actions = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                useBackdropCapture = false,
                                onClick = {
                                    showRestorePassDialog = false
                                    pendingRestoreUri = null
                                },
                            ) { Text(ks.cancel) }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = true,
                                enabled = restorePassInput.isNotEmpty(),
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    val uri = pendingRestoreUri ?: return@FluidGlassButton
                                    scope.launch {
                                        val res = viewModel.restoreKineticBackupFromUri(uri, restorePassInput)
                                        res.onSuccess { rep ->
                                            showRestorePassDialog = false
                                            pendingRestoreUri = null
                                            viewModel.showMessage(safeFormatLocalized(s.covRestoredBackupMsgFmt, rep.merged.size, rep.addedCount))
                                        }.onFailure { err ->
                                            restorePassError = err.message ?: s.covPassphrasePromptTitle
                                        }
                                    }
                                },
                            ) { Text(s.done, fontWeight = FontWeight.Bold) }
                        }
                    },
                ) {
                    FluidGlassTextField(
                        value = restorePassInput,
                        onValueChange = {
                            restorePassInput = it
                            restorePassError = null
                        },
                        placeholder = s.passphrasePlaceholder,
                        singleLine = true,
                        nestedInGlassCard = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (!restorePassError.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(restorePassError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Import Shared Pass (Paste JSON) Dialog
            if (showImportPassDialog) {
                FluidGlassDialog(
                    onDismissRequest = { showImportPassDialog = false },
                    title = {
                        Text(
                            text = s.covImportPasteJsonButton,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    },
                    actions = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                useBackdropCapture = false,
                                onClick = { showImportPassDialog = false },
                            ) { Text(ks.cancel) }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = true,
                                enabled = importPassInput.isNotBlank(),
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    scope.launch {
                                        viewModel.importSharedPassJson(importPassInput)
                                            .onSuccess {
                                                showImportPassDialog = false
                                                activeTab = CovenantsHubTab.SHARED
                                                viewModel.showMessage(safeFormatLocalized(s.covSharedImportedMsgFmt, it.label))
                                            }
                                            .onFailure { viewModel.showMessage(it.message ?: s.covImportPasteJsonButton) }
                                    }
                                },
                            ) { Text(s.done, fontWeight = FontWeight.Bold) }
                        }
                    },
                ) {
                    FluidGlassTextField(
                        value = importPassInput,
                        onValueChange = { importPassInput = it },
                        placeholder = "{\"format\":\"kaspa-kinetic-pass-v1\", ...}",
                        minLines = 3,
                        maxLines = 6,
                        nestedInGlassCard = true,
                    )
                }
            }

            pendingRemoveShared?.let { toRemove ->
                FluidGlassDialog(
                    onDismissRequest = { pendingRemoveShared = null },
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = s.covRemoveSharedDialogTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                            MarqueeText(
                                text = toRemove.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = glassSecondaryContentColor(),
                            )
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                onClick = { pendingRemoveShared = null },
                            ) { Text(ks.cancel, fontWeight = FontWeight.SemiBold) }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                destructive = true,
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    val target = toRemove
                                    pendingRemoveShared = null
                                    scope.launch {
                                        viewModel.removeSharedCovenant(target.canonicalKey)
                                            .onSuccess {
                                                viewModel.showMessage(safeFormatLocalized(s.covRemovedSharedMsgFmt, target.label))
                                            }
                                            .onFailure { err ->
                                                viewModel.showMessage(err.message ?: s.covRemoveSharedButton)
                                            }
                                    }
                                },
                            ) { Text(s.covRemoveSharedButton, fontWeight = FontWeight.Bold) }
                        }
                    },
                ) {
                    Text(
                        text = s.covRemoveSharedDialogBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassPrimaryContentColor(),
                    )
                }
            }
        }

        // LAYER C: Full-screen sub-routes rendered cleanly OVER the Hub so the underlying Hub
        // LazyColumn & header inset padding NEVER tear down or flash scrolled upward for 1 frame!
        when (val route = subRoute) {
            is HubSubRoute.Create -> {
                CreateCovenantScreen(
                    templateId = route.templateId,
                    walletState = walletState,
                    virtualDaa = virtualDaa,
                    appearance = appearance,
                    viewModel = viewModel,
                    onRequestAuth = onRequestAuth,
                    onRequestQrScan = onRequestQrScan,
                    onViewActiveVault = { created ->
                        subRoute = HubSubRoute.Detail(created.canonicalKey)
                    },
                    onBack = { subRoute = HubSubRoute.Hub },
                )
            }
            is HubSubRoute.Detail -> {
                val found = covState.covenants.firstOrNull { it.canonicalKey == route.canonicalKey }
                if (found != null) {
                    CovenantDetailScreen(
                        covenant = found,
                        walletState = walletState,
                        virtualDaa = virtualDaa,
                        appearance = appearance,
                        viewModel = viewModel,
                        onRequestAuth = onRequestAuth,
                        onInspectInExplorer = onInspectInExplorer,
                        onToggleHideBalances = onToggleHideBalances,
                        onBack = {
                            if (initialCovenantKey.isNotBlank()) onDismiss() else subRoute = HubSubRoute.Hub
                        },
                    )
                } else {
                    LaunchedEffect(route.canonicalKey) {
                        subRoute = HubSubRoute.Hub
                    }
                }
            }
            HubSubRoute.ReviewActivationWarning -> {
                CovenantsActivationScreen(
                    appearance = appearance,
                    viewModel = viewModel,
                    onActivated = { _ -> subRoute = HubSubRoute.Hub },
                    onDismiss = { subRoute = HubSubRoute.Hub },
                    readOnlyReviewMode = true,
                )
            }
            HubSubRoute.Hub -> Unit
        }
    }
}

/**
 * Tailored recipient / heir card inside the `Shared` tab (Dead Man's Switch & Recurring Allowance only)
 * with live gRPC balance, monotonic consensus countdown, `0.00 KAS` claim notice, tap-to-open Detail
 * (where claiming lives), and a canonical Kinetic Aero `Remove` button with confirmation dialog.
 */
@Composable
private fun SharedPassRecipientCard(
    covenant: TrackedCovenant,
    unit: String,
    chainTimeAnchor: ChainTimeAnchor,
    nowElapsedRealtimeMs: Long,
    virtualDaa: Long,
    hideBalances: Boolean,
    isConfirming: Boolean,
    onOpenDetail: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val s = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val accent = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val isSettled = covenant.liveStatus == CovenantStatus.SETTLED
    val balText = if (hideBalances) "${s.maskedAmount} $unit"
    else "${KasFormat.formatKasSummary(covenant.liveBalanceSompi)} $unit"

    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        useBackdropCapture = false,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .kineticInteractiveRow(
                        shape = ContinuousCornerShape(14.dp),
                        backgroundColor = Color.Transparent,
                        borderColor = Color.Transparent,
                        onClick = onOpenDetail,
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(ContinuousCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(covenantTemplateIcon(covenant.templateId), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MarqueeText(
                        text = covenant.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = covenantTemplateTitle(covenant.templateId, s),
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = glassSecondaryContentColor(), modifier = Modifier.size(18.dp))
            }

            when (covenant.templateId) {
                CovenantTemplateId.RECURRING_ALLOWANCE -> {
                    val isTimeBased = CovenantCompiler.isKeylessTimeBasedSubscription(covenant.params)
                    val eval = if (isTimeBased) {
                        chainTimeAnchor.evaluateTimeLock(covenant.params.nextDueTimeMs, nowElapsedRealtimeMs)
                    } else null
                    val legacyAgeDaa = if (virtualDaa > 0L && covenant.liveUtxoBlockDaaScore > 0L) {
                        (virtualDaa - covenant.liveUtxoBlockDaaScore).coerceAtLeast(0L)
                    } else 0L
                    val legacyRemMs = ((covenant.params.periodDaa - legacyAgeDaa).coerceAtLeast(0L) * 100L)
                    val isReady = !isSettled && !isConfirming && covenant.liveBalanceSompi > 0L && if (isTimeBased) {
                        eval?.stage == TimeLockStage.READY
                    } else {
                        legacyAgeDaa >= covenant.params.periodDaa
                    }
                    val isFinalizing = !isSettled && !isConfirming && isTimeBased && eval?.stage == TimeLockStage.FINALIZING
                    val perCycleStr = if (hideBalances) "${s.maskedAmount} $unit"
                    else "${KasFormat.formatKasSummary(covenant.params.allowanceSompi)} $unit"

                    SharedMetricLine(s.covSharedPoolBalanceLabel, balText, mono = true)
                    SharedMetricLine(s.covSharedClaimPerCycleLabel, perCycleStr, mono = true)
                    SharedMetricLine(
                        label = s.covSharedNextClaimLabel,
                        value = when {
                            isConfirming -> s.covConfirmingOnL1
                            isSettled -> s.covStatusSettled
                            isFinalizing -> s.covFinalizingOnL1
                            isReady -> s.covSharedReadyToClaim
                            isTimeBased -> "${formatFullDateTime(covenant.params.nextDueTimeMs)} (${formatDurationMsShort(eval?.extrapolatedRemainingMs ?: 0L, s.covStatusReady)})"
                            else -> formatDurationMsShort(legacyRemMs, s.covStatusReady)
                        },
                        highlight = isReady,
                    )
                    if (isTimeBased && !isSettled) {
                        Text(
                            text = s.covSharedZeroKasNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                    }
                }

                CovenantTemplateId.DEAD_MANS_SWITCH -> {
                    val isTimeBased = CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(covenant.params)
                    val isKeyless = CovenantCompiler.isKeylessDeadMansSwitch(covenant.params)
                    val eval = if (isTimeBased) {
                        chainTimeAnchor.evaluateTimeLock(covenant.params.inactivityDeadlineMs, nowElapsedRealtimeMs)
                    } else {
                        chainTimeAnchor.evaluateRelativeDaaLock(
                            utxoBlockDaaScore = covenant.liveUtxoBlockDaaScore,
                            requiredDaa = covenant.params.inactivityDaa,
                            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                        )
                    }
                    val isInitialSyncing = !isSettled && !isConfirming && isCountdownSyncingOrSentinel(
                        covenant = covenant,
                        remainingMs = eval.extrapolatedRemainingMs,
                        requiresUtxoBlockDaa = !isTimeBased,
                    )
                    val isReady = !isSettled && !isConfirming && !isInitialSyncing && covenant.liveBalanceSompi > 0L && eval.stage == TimeLockStage.READY
                    val isFinalizing = !isSettled && !isConfirming && !isInitialSyncing && covenant.liveBalanceSompi > 0L && eval.stage == TimeLockStage.FINALIZING

                    SharedMetricLine(
                        label = s.covSharedAlarmStatusLabel,
                        value = when {
                            isConfirming -> s.covConfirmingOnL1
                            isSettled -> s.covStatusSettled
                            isInitialSyncing -> s.covSyncingStatus
                            isFinalizing -> s.covFinalizingOnL1
                            isReady -> s.covSharedAvailableToClaim
                            else -> s.covSharedLockedOwnerActive
                        },
                        highlight = isReady,
                    )
                    SharedMetricLine(s.covSharedTotalInsideLabel, balText, mono = true)
                    SharedMetricLine(
                        label = s.covSharedUnlockDateLabel,
                        value = when {
                            isConfirming -> s.covConfirmingOnL1
                            isSettled -> s.covStatusSettled
                            isInitialSyncing -> s.covSyncingStatus
                            isFinalizing -> s.covFinalizingOnL1
                            isReady -> s.covSharedAvailableToClaim
                            isTimeBased -> "${formatFullDateTime(covenant.params.inactivityDeadlineMs)} (${formatDurationMsShort(eval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus)})"
                            else -> formatDurationMsShort(eval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus)
                        },
                        highlight = isReady,
                    )
                    if (isKeyless && !isSettled) {
                        Text(
                            text = s.covSharedZeroKasNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = glassSecondaryContentColor(),
                        )
                    }
                }

                else -> {
                    SharedMetricLine(s.covSharedTotalInsideLabel, balText, mono = true)
                }
            }

            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
                useBackdropCapture = false,
                lockParentGestures = true,
                onClick = onRequestRemove,
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                MarqueeText(
                    text = s.covRemoveSharedButton,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SharedMetricLine(
    label: String,
    value: String,
    mono: Boolean = false,
    highlight: Boolean = false,
) {
    val isDark = LocalGlassDarkTheme.current
    val accent = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (highlight) accent else glassPrimaryContentColor(),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CovenantPresetActionRow(
    templateId: CovenantTemplateId,
    onClick: () -> Unit,
) {
    val s = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(20.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerLow
        isDark -> Color(0xFF151C24).copy(alpha = 0.62f)
        else -> Color.White.copy(alpha = 0.74f)
    }
    val border = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.88f)
    }
    val accent = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

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
                .size(40.dp)
                .clip(ContinuousCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = covenantTemplateIcon(templateId),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MarqueeText(
                text = covenantTemplateTitle(templateId, s),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
            )
            Text(
                text = covenantTemplateDesc(templateId, s),
                style = MaterialTheme.typography.bodySmall,
                color = glassSecondaryContentColor(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = glassSecondaryContentColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}
