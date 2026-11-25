package com.kaspakinetic.wallet.ui.`fun`

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kaspakinetic.wallet.core.service.MAX_MINER_DONATION_SPLITS
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.FeePriority
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.HighlightedKaspaAddressText
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.explore.StageBadgePill
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSlider
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
import com.kaspakinetic.wallet.ui.onboarding.ErrorInlineBanner
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// Fun Stuff #1: Burn Kaspa (FULLY WORKING REAL L1 TRANSACTION)
// ============================================================================

@Composable
fun BurnKaspaOverlay(
    walletState: WalletUiState,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onRequestAuth: (onAuthorized: () -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val scope = rememberCoroutineScope()

    val activeWallet = walletState.activeWallet
    val isWatchOnly = activeWallet?.isWatchOnly == true
    val network = walletState.activeNetwork
    val unit = if (network.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val burnAddress = remember(network) { KaspaAddress.burnAddress(network) }
    val canUseFiat = !walletState.prefs.kasOnlyPrivacyMode && walletState.spotPrice != null && !network.isTestnet

    var amountInput by remember { mutableStateOf("") }
    var inputInFiat by remember { mutableStateOf(false) }
    var confirmedIrreversible by remember { mutableStateOf(false) }
    var isBroadcasting by remember { mutableStateOf(false) }
    var broadcastTxId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val amountSompi: Long? = remember(amountInput, inputInFiat, walletState.spotPrice) {
        val cleaned = amountInput.trim()
        if (cleaned.isEmpty()) return@remember null
        if (inputInFiat && canUseFiat) {
            val fiatVal = cleaned.toDoubleOrNull() ?: return@remember null
            val spotPrice = walletState.spotPrice?.price ?: return@remember null
            if (fiatVal <= 0.0 || spotPrice <= 0.0) return@remember null
            ((fiatVal / spotPrice) * SOMPI_PER_KAS.toDouble()).toLong()
        } else {
            KasFormat.kasStringToSompi(cleaned)
        }
    }

    val previewResult = remember(amountSompi, walletState.utxos, walletState.feeEstimate, burnAddress) {
        val sompi = amountSompi ?: return@remember null
        if (sompi <= 0L) return@remember null
        viewModel.prepareSendTransaction(
            recipientAddress = burnAddress,
            amountSompi = sompi,
            feePriority = FeePriority.NORMAL,
            sendAll = false,
            useSelectedUtxos = false,
            isBurn = true,
        )
    }

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 100.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val backdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else backdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(68f),
    ) {
        // LAYER A: Tier 2 Viewport Capture (Wallpaper + Seamless Full-Screen Scrollable Burn Content)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(viewportBackdrop) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!reduceTransparency) Modifier.layerBackdrop(backdrop) else Modifier),
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
                                    if (isDark) Color(0xFF070B10).copy(alpha = 0.22f)
                                    else Color(0xFFF3F7F5).copy(alpha = 0.26f),
                                ),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides effectiveBackdrop) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState(), enabled = !gestureLocked)
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = topPadDp + 10.dp,
                            bottom = 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (broadcastTxId != null) {
                        // Success view with txid + Inspect in Block Explorer
                        val txId = broadcastTxId!!
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            contentPadding = PaddingValues(22.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = walletStrings.burnSuccessTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = walletStrings.burnPlayfulSubtext,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = walletStrings.txIdLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = glassSecondaryContentColor(),
                            )
                            Text(
                                text = txId,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = glassPrimaryContentColor(),
                            )
                            Spacer(Modifier.height(16.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = { onInspectInExplorer(txId) },
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = walletStrings.inspectInExplorerButton,
                                        maxLines = 1,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = {
                                        copyTextToClipboard(context, "Transaction ID", txId)
                                        viewModel.showMessage(walletStrings.copiedToClipboard)
                                    },
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        // Serious irreversibility warning + playful subtext using canonical FluidGlassCard (GlassPreset.Card)
                        val warnGlassTint = if (isDark) Color(0xFF42161C) else Color(0xFFFFDFE3)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            surfaceTint = warnGlassTint,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(
                                        text = walletStrings.burnWarningTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(
                                    text = walletStrings.burnWarningBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glassPrimaryContentColor(),
                                )
                                Text(
                                    text = walletStrings.burnPlayfulSubtext,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }

                        if (isWatchOnly) {
                            ErrorInlineBanner(walletStrings.burnWatchOnlyNotice)
                        }

                        // Zero-Point Burn Address card (Canonical FluidGlassCard matching Balance Card & Donate cards)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = walletStrings.burnDestinationLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = glassSecondaryContentColor(),
                            )
                            Spacer(Modifier.height(6.dp))
                            HighlightedKaspaAddressText(address = burnAddress, fontSizeSp = 12.5f)
                        }

                        // Amount + KAS/Fiat toggle + Quick 1 / 5 / 10 KAS presets (Canonical FluidGlassCard)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${walletStrings.amountLabel} (${if (inputInFiat && canUseFiat) walletState.prefs.fiatCurrency.code else unit})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glassPrimaryContentColor(),
                                )
                                if (canUseFiat) {
                                    FluidGlassChip(
                                        selected = inputInFiat,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        onClick = {
                                            inputInFiat = !inputInFiat
                                            amountInput = ""
                                        },
                                    ) {
                                        Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = if (inputInFiat) unit else walletState.prefs.fiatCurrency.code,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            FluidGlassTextField(
                                value = amountInput,
                                onValueChange = {
                                    amountInput = it
                                    errorMsg = null
                                },
                                placeholder = "0.00",
                                singleLine = true,
                                nestedInGlassCard = true,
                                enabled = !isWatchOnly && !isBroadcasting,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )

                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(1, 5, 10).forEach { presetKas ->
                                    FluidGlassChip(
                                        modifier = Modifier.weight(1f),
                                        selected = !inputInFiat && amountInput == "$presetKas",
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        enabled = !isWatchOnly && !isBroadcasting,
                                        onClick = {
                                            inputInFiat = false
                                            amountInput = "$presetKas"
                                            errorMsg = null
                                        },
                                    ) {
                                        Text("$presetKas $unit", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            // Live balance & fee preview
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "${walletStrings.totalBalanceLabel}: ${KasFormat.formatKasSummary(walletState.totalBalanceSompi)} $unit",
                                style = MaterialTheme.typography.labelSmall,
                                color = glassSecondaryContentColor(),
                            )
                            previewResult?.onSuccess { prepared ->
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(thickness = 0.6.dp, color = glassSecondaryContentColor().copy(alpha = 0.2f))
                                Spacer(Modifier.height(6.dp))
                                SummaryKeyValueRow(
                                    label = walletStrings.txFeeLabel,
                                    value = "${KasFormat.formatKas(prepared.feeSompi)} $unit",
                                )
                                SummaryKeyValueRow(
                                    label = walletStrings.reviewComputeMass,
                                    value = "${prepared.massBreakdown.computeMass} / ${prepared.massBreakdown.storageMass}",
                                )
                                if (canUseFiat && amountSompi != null) {
                                    SummaryKeyValueRow(
                                        label = walletStrings.displayCurrencyLabel,
                                        value = KasFormat.formatFiat(amountSompi, walletState.spotPrice),
                                    )
                                }
                            }?.onFailure { err ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = err.message ?: "Invalid amount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        // Mandatory confirmation checkbox inside canonical FluidGlassCard (zero invisible corner clip)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = !isWatchOnly && !isBroadcasting,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { confirmedIrreversible = !confirmedIrreversible },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = confirmedIrreversible,
                                    enabled = !isWatchOnly && !isBroadcasting,
                                    onCheckedChange = { confirmedIrreversible = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.error,
                                        uncheckedColor = glassPrimaryContentColor().copy(alpha = 0.78f),
                                    ),
                                )
                                Text(
                                    text = walletStrings.burnConfirmCheckbox,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glassPrimaryContentColor(),
                                )
                            }
                        }

                        if (!errorMsg.isNullOrBlank()) {
                            ErrorInlineBanner(errorMsg!!)
                        }

                        val preparedTx = previewResult?.getOrNull()
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            destructive = true,
                            enabled = !isWatchOnly && !isBroadcasting && confirmedIrreversible && preparedTx != null,
                            backdrop = effectiveBackdrop,
                            onClick = {
                                val tx = preparedTx ?: return@FluidGlassButton
                                onRequestAuth {
                                    scope.launch {
                                        isBroadcasting = true
                                        errorMsg = null
                                        viewModel.signAndBroadcast(tx)
                                            .onSuccess { txId -> broadcastTxId = txId }
                                            .onFailure { err -> errorMsg = err.message ?: "Burn broadcast failed" }
                                        isBroadcasting = false
                                    }
                                }
                            },
                        ) {
                            if (isBroadcasting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(walletStrings.burnActionButton, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header + Top Progressive Blur
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveViewport,
            LocalOverlayBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = walletStrings.burnTitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                titleLeading = {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                },
                titleTrailing = if (network.isTestnet) {
                    { StageBadgePill(text = walletStrings.burnTestnetBadge) }
                } else null,
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
            )
        }
    }
}

// ============================================================================
// Fun Stuff #2: Donate to Kaspa Miners (FULLY WORKING CHAINED SPLIT ENGINE)
// ============================================================================

@Composable
fun DonateMinersOverlay(
    walletState: WalletUiState,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onRequestAuth: (onAuthorized: () -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler {
        if (walletState.minerDonationProgress?.isRunning != true) {
            viewModel.dismissMinerDonationProgress()
        }
        onDismiss()
    }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    val activeWallet = walletState.activeWallet
    val isWatchOnly = activeWallet?.isWatchOnly == true
    val network = walletState.activeNetwork
    val unit = if (network.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val progress = walletState.minerDonationProgress

    var totalKasInput by remember { mutableStateOf("5") }
    var splitsText by remember { mutableStateOf("10") }
    val splits = remember(splitsText) {
        splitsText.trim().filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, MAX_MINER_DONATION_SPLITS) ?: 1
    }
    var runInBackground by remember { mutableStateOf(true) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    val totalSompi = remember(totalKasInput) { KasFormat.kasStringToSompi(totalKasInput) ?: 0L }
    val feePerTxSompi = remember(totalSompi, splits) {
        if (splits > 0 && totalSompi > 0L) totalSompi / splits else 0L
    }
    val estDurationSec = remember(splits) { (splits * 0.25).coerceAtLeast(0.3) }
    val spendableSompi = remember(walletState.utxos) {
        walletState.utxos.filter { !it.isCoinbase }.sumOf { it.amountSompi }
    }
    val returningSompi = (spendableSompi - totalSompi).coerceAtLeast(0L)
    val canStart = !isWatchOnly &&
        totalSompi > 0L &&
        feePerTxSompi >= 1_000L &&
        (spendableSompi - totalSompi) >= DUST_LIMIT_SOMPI

    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 100.dp.roundToPx() }) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val backdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else backdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(68f),
    ) {
        // LAYER A: Tier 2 Viewport Capture (Wallpaper + Seamless Full-Screen Scrollable Donate Content)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(viewportBackdrop) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!reduceTransparency) Modifier.layerBackdrop(backdrop) else Modifier),
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
                                    if (isDark) Color(0xFF070B10).copy(alpha = 0.22f)
                                    else Color(0xFFF3F7F5).copy(alpha = 0.26f),
                                ),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides effectiveBackdrop) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState(), enabled = !gestureLocked)
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = topPadDp + 10.dp,
                            bottom = 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (progress != null) {
                        // Live Miner Donation Progress View + Stop / Cancel Remaining
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            contentPadding = PaddingValues(20.dp),
                        ) {
                            Text(
                                text = walletStrings.donateProgressTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                            Spacer(Modifier.height(10.dp))

                            val frac = if (progress.totalSplits > 0) {
                                (progress.completedSplits.toFloat() / progress.totalSplits.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                            )

                            Spacer(Modifier.height(10.dp))
                            SummaryKeyValueRow(
                                label = walletStrings.donateSplitsLabel,
                                value = "${progress.completedSplits} / ${progress.totalSplits}",
                            )
                            SummaryKeyValueRow(
                                label = walletStrings.donateTotalKasLabel,
                                value = "${KasFormat.formatKas(progress.totalDonatedSompi)} / ${KasFormat.formatKas(progress.targetTotalSompi)} $unit",
                            )

                            if (progress.isCancelled) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = walletStrings.donateCancelledBanner,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (!progress.isRunning && progress.errorMessage == null && progress.completedSplits == progress.totalSplits) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = walletStrings.donateCompletedBanner,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                    )
                                }
                            } else if (!progress.errorMessage.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                ErrorInlineBanner(progress.errorMessage)
                            }

                            Spacer(Modifier.height(14.dp))
                            if (progress.isRunning) {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    destructive = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = { viewModel.cancelMinerDonation() },
                                ) {
                                    Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(walletStrings.donateStopButton, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = { viewModel.dismissMinerDonationProgress() },
                                ) {
                                    Text(walletStrings.done, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (progress.txIds.isNotEmpty()) {
                            FluidGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                useBackdropCapture = false,
                                contentPadding = PaddingValues(14.dp),
                            ) {
                                progress.txIds.reversed().forEachIndexed { idx, txId ->
                                    val seqNum = progress.txIds.size - idx
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onInspectInExplorer(txId) }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "#$seqNum",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                        )
                                        Text(
                                            text = txId,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = glassPrimaryContentColor(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = glassSecondaryContentColor(),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Setup Form — frosted description card so top text never washes out over wallpaper
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                text = walletStrings.donateMinersSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = glassPrimaryContentColor().copy(alpha = 0.90f),
                            )
                        }

                        if (isWatchOnly) {
                            ErrorInlineBanner(walletStrings.donateWatchOnlyNotice)
                        }

                        // Total KAS Input + Presets (Real frosted glass backing!)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            FluidGlassTextField(
                                value = totalKasInput,
                                onValueChange = { totalKasInput = it },
                                label = "${walletStrings.donateTotalKasLabel} ($unit)",
                                placeholder = "5.0",
                                singleLine = true,
                                nestedInGlassCard = true,
                                enabled = !isWatchOnly,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf("1", "5", "10", "25").forEach { preset ->
                                    FluidGlassChip(
                                        modifier = Modifier.weight(1f),
                                        selected = totalKasInput == preset,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        enabled = !isWatchOnly,
                                        onClick = { totalKasInput = preset },
                                    ) {
                                        Text("$preset", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        // Number of Splits / Blocks: Custom input up to 8,640,000 + Quick Slider + 2x3 Presets (no 100->10 clipping!)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = walletStrings.donateSplitsLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = glassPrimaryContentColor(),
                                )
                                Text(
                                    text = String.format(Locale.US, "%,d", splits),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            FluidGlassTextField(
                                value = splitsText,
                                onValueChange = { raw ->
                                    val digits = raw.filter { it.isDigit() }.take(7)
                                    val num = digits.toIntOrNull()
                                    splitsText = when {
                                        digits.isEmpty() -> ""
                                        num != null && num > MAX_MINER_DONATION_SPLITS -> MAX_MINER_DONATION_SPLITS.toString()
                                        else -> digits
                                    }
                                },
                                label = walletStrings.donateCustomSplitsLabel,
                                placeholder = walletStrings.donateCustomSplitsPlaceholder,
                                singleLine = true,
                                enabled = !isWatchOnly,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Spacer(Modifier.height(8.dp))
                            FluidGlassSlider(
                                value = splits.coerceIn(1, 100).toFloat(),
                                onValueChange = {
                                    splitsText = it.roundToInt().coerceIn(1, 100).toString()
                                },
                                valueRange = 1f..100f,
                                enabled = !isWatchOnly,
                            )
                            Spacer(Modifier.height(8.dp))
                            // 2 rows x 3 columns so 1 / 5 / 10 / 25 / 50 / 100 NEVER clip!
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(listOf(1, 5, 10), listOf(25, 50, 100)).forEach { rowCounts ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        rowCounts.forEach { count ->
                                            FluidGlassChip(
                                                modifier = Modifier.weight(1f),
                                                selected = splits == count,
                                                useBackdropCapture = false,
                                                lockParentGestures = false,
                                                enabled = !isWatchOnly,
                                                onClick = { splitsText = count.toString() },
                                            ) {
                                                Text(
                                                    text = "$count",
                                                    style = MaterialTheme.typography.labelMedium,
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

                        // Background Foreground Service Option (Real frosted glass backing!)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = walletStrings.donateBackgroundTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = glassPrimaryContentColor(),
                                    )
                                    Text(
                                        text = walletStrings.donateBackgroundDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = glassSecondaryContentColor(),
                                    )
                                }
                                FluidGlassSwitch(
                                    checked = runInBackground,
                                    enabled = !isWatchOnly,
                                    onCheckedChange = { checked ->
                                        runInBackground = checked
                                        if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        // Live Breakdown Summary (Real frosted glass backing!)
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            SummaryKeyValueRow(
                                label = walletStrings.donateFeePerTxLabel,
                                value = "${KasFormat.formatKas(feePerTxSompi)} $unit",
                            )
                            SummaryKeyValueRow(
                                label = walletStrings.donateEstDurationLabel,
                                value = KasFormat.formatDurationSeconds(estDurationSec),
                            )
                            SummaryKeyValueRow(
                                label = walletStrings.donateReturningLabel,
                                value = "${KasFormat.formatKas(returningSompi)} $unit",
                            )
                        }

                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = true,
                            enabled = canStart,
                            backdrop = effectiveBackdrop,
                            onClick = {
                                if (runInBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                onRequestAuth {
                                    viewModel.startMinerDonation(
                                        totalDonationSompi = totalSompi,
                                        splits = splits,
                                        runInBackground = runInBackground,
                                        notificationTitle = walletStrings.donateMinersTitle,
                                        notificationStopLabel = walletStrings.donateNotifStopLabel,
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(walletStrings.donateStartButton, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // LAYER B: Pinned Floating Kinetic Aero Top Header (Preserving Smooth Marquee!) + Top Progressive Blur
        CompositionLocalProvider(
            LocalGlassBackdrop provides effectiveViewport,
            LocalOverlayBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = walletStrings.donateMinersTitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                titleLeading = {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        modifier = Modifier.size(20.dp),
                    )
                },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = {
                            if (progress?.isRunning != true) {
                                viewModel.dismissMinerDonationProgress()
                            }
                            onDismiss()
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
            )
        }
    }
}

@Composable
private fun SummaryKeyValueRow(label: String, value: String) {
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
            fontFamily = FontFamily.Monospace,
            color = glassPrimaryContentColor(),
        )
    }
}
