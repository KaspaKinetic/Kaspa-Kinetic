package com.kaspakinetic.wallet.ui.covenants

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import com.kaspakinetic.wallet.core.covenants.CovenantCompiler
import com.kaspakinetic.wallet.core.covenants.CovenantParams
import com.kaspakinetic.wallet.core.covenants.CovenantStatus
import com.kaspakinetic.wallet.core.covenants.CovenantTemplateId
import com.kaspakinetic.wallet.core.covenants.KineticBackupEngine
import com.kaspakinetic.wallet.core.covenants.OnChainCovenantProof
import com.kaspakinetic.wallet.core.covenants.TimeLockStage
import com.kaspakinetic.wallet.core.covenants.TrackedCovenant
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.KineticRefreshIconButton
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
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
import com.kaspakinetic.wallet.ui.onboarding.ErrorInlineBanner
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

private enum class GuidedCreateStep {
    INTRO,
    SETUP,
    ALL_SET,
}

private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_WEEK = 7L * MS_PER_DAY
private const val MS_PER_MONTH = 30L * MS_PER_DAY

/**
 * Guided 3-Step Creation Flow for all 7 Consumer Kaspa L1 Covenants (Stage 3.2):
 * - 100% localized across all 11 languages
 * - Native `tx.time` Unix millisecond timestamps + zero `~X blocks` predictions
 * - Blank address inputs by default + canonical `Use Fresh My Address` derivation button
 * - Optional Auto-Settle Date toggle (OFF by default) on Recurring Allowance / Subscription
 */
@Composable
fun CreateCovenantScreen(
    templateId: CovenantTemplateId,
    walletState: WalletUiState,
    virtualDaa: Long,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onRequestAuth: (() -> Unit) -> Unit,
    onRequestQrScan: ((String) -> Unit) -> Unit,
    onViewActiveVault: (TrackedCovenant) -> Unit,
    onBack: () -> Unit,
) {
    var step by remember(templateId) { mutableStateOf(GuidedCreateStep.INTRO) }
    var deployedCovenant by remember(templateId) { mutableStateOf<TrackedCovenant?>(null) }

    BackHandler {
        when (step) {
            GuidedCreateStep.INTRO -> onBack()
            GuidedCreateStep.SETUP -> step = GuidedCreateStep.INTRO
            GuidedCreateStep.ALL_SET -> {
                val created = deployedCovenant
                if (created != null) onViewActiveVault(created) else onBack()
            }
        }
    }

    val s = LocalWalletStrings.current
    val ks = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeNet = walletState.activeNetwork
    val unit = if (activeNet.isTestnet) s.tkasUnit else s.kasUnit
    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true
    val ownerAddr = walletState.currentReceiveAddress
    val ownerPubKeyHex = remember(ownerAddr) { viewModel.activeWalletOwnerPubKeyHex() }
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val introGuide = remember(templateId, s) { covenantIntroGuideFor(templateId, s) }

    // Unique per-vault CSPRNG salt generated once per creation session so preview P2SH matches deployed P2SH!
    val vaultSaltHex = remember(templateId) { CovenantCompiler.generateVaultSaltHex() }
    val chainTimeAnchor by viewModel.chainTimeAnchor.collectAsState()
    val liveBasePmtMs = chainTimeAnchor.estimatePastMedianTimeMs(SystemClock.elapsedRealtime())
    val sessionStartMs = remember(templateId) { viewModel.currentEstimatedPastMedianTimeMs() }

    // Common form fields
    var label by remember(templateId) { mutableStateOf(covenantTemplateTitle(templateId, s)) }
    var depositKas by remember(templateId) { mutableStateOf("10") }

    // ALL address fields intentionally start BLANK by default for privacy — users paste or tap `Use Fresh My Address`!
    // Clawback Vault (relative CSV delay)
    var recoveryAddress by remember(templateId) { mutableStateOf("") }
    var unvaultDelayDaa by remember(templateId) { mutableLongStateOf(CovenantCompiler.DAA_PER_DAY) }

    // Dead Man's Switch (native tx.time Unix timestamp)
    var heirAddress by remember(templateId) { mutableStateOf("") }
    var inactivityDurationMs by remember(templateId) { mutableLongStateOf(MS_PER_MONTH) }
    var inactivityDeadlineMs by remember(templateId) { mutableLongStateOf(sessionStartMs + MS_PER_MONTH) }

    // Daily Spend Limit (rolling CSV window + native tx.time full-withdraw bypass date)
    var dailyCapKas by remember(templateId) { mutableStateOf("5") }
    var dailyWindowDaa by remember(templateId) { mutableLongStateOf(CovenantCompiler.DAA_PER_DAY) }
    var dailyBypassDurationMs by remember(templateId) { mutableLongStateOf(MS_PER_MONTH) }
    var dailyBypassUnlockTimeMs by remember(templateId) { mutableLongStateOf(sessionStartMs + MS_PER_MONTH) }

    // Cold Whitelist (1..3 blank inputs)
    var coldAddr1 by remember(templateId) { mutableStateOf("") }
    var coldAddr2 by remember(templateId) { mutableStateOf("") }
    var coldAddr3 by remember(templateId) { mutableStateOf("") }

    // Hodl Time-Lock (native tx.time Unix timestamp)
    var hodlDurationMs by remember(templateId) { mutableLongStateOf(MS_PER_MONTH) }
    var hodlUnlockTimeMs by remember(templateId) { mutableLongStateOf(sessionStartMs + MS_PER_MONTH) }

    // Goal-Locked Piggy Bank (native tx.time fallback date)
    var goalTargetKas by remember(templateId) { mutableStateOf("50") }
    var fallbackDurationMs by remember(templateId) { mutableLongStateOf(90L * MS_PER_DAY) }
    var fallbackUnlockTimeMs by remember(templateId) { mutableLongStateOf(sessionStartMs + 90L * MS_PER_DAY) }

    // Recurring Allowance / Subscription (pure Kaspa L1 keyless tx.time — recipient claims via .kpass!)
    var recipientAddress by remember(templateId) { mutableStateOf("") }
    var allowanceKas by remember(templateId) { mutableStateOf("2") }
    var allowanceIntervalMs by remember(templateId) { mutableLongStateOf(MS_PER_WEEK) }
    var allowanceFirstDueMs by remember(templateId) { mutableLongStateOf(sessionStartMs + MS_PER_WEEK) }

    var showAdvancedDetails by remember { mutableStateOf(false) }
    var deployPhase by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var postDeployPassTarget by remember { mutableStateOf<TrackedCovenant?>(null) }
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 104.dp.roundToPx() }) }

    fun deriveFreshInto(setter: (String) -> Unit) {
        scope.launch {
            viewModel.deriveFreshUnusedReceiveAddress()
                .onSuccess { setter(it) }
                .onFailure { viewModel.showMessage(it.message ?: s.covUseFreshAddressButton) }
        }
    }

    fun kasToSompiOrZero(raw: String): Long = runCatching {
        BigDecimal(raw.trim()).multiply(BigDecimal(SOMPI_PER_KAS)).toLong()
    }.getOrDefault(0L)

    val effectiveBaseDaa = virtualDaa.takeIf { it > 0L } ?: 100_000_000L

    val hasRequiredAddresses = when (templateId) {
        CovenantTemplateId.CLAWBACK_VAULT -> recoveryAddress.isNotBlank()
        CovenantTemplateId.DEAD_MANS_SWITCH -> heirAddress.isNotBlank()
        CovenantTemplateId.COLD_WHITELIST -> listOf(coldAddr1, coldAddr2, coldAddr3).any { it.isNotBlank() }
        CovenantTemplateId.RECURRING_ALLOWANCE -> recipientAddress.isNotBlank()
        else -> true
    }

    val builtParams = remember(
        templateId, ownerPubKeyHex, ownerAddr, vaultSaltHex,
        recoveryAddress, unvaultDelayDaa,
        heirAddress, inactivityDurationMs, inactivityDeadlineMs,
        dailyCapKas, dailyWindowDaa, dailyBypassUnlockTimeMs,
        coldAddr1, coldAddr2, coldAddr3,
        hodlUnlockTimeMs,
        goalTargetKas, fallbackUnlockTimeMs,
        recipientAddress, allowanceKas, allowanceIntervalMs, allowanceFirstDueMs,
        effectiveBaseDaa,
    ) {
        when (templateId) {
            CovenantTemplateId.CLAWBACK_VAULT -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                alarmPubKeyHex = ownerPubKeyHex,
                recoveryAddress = recoveryAddress.trim().ifBlank { ownerAddr },
                unvaultDelayDaa = unvaultDelayDaa,
                vaultStage = 0,
            )
            CovenantTemplateId.DEAD_MANS_SWITCH -> {
                val durMs = inactivityDurationMs.coerceIn(60_000L, CovenantCompiler.MAX_HODL_LOCK_MS)
                val inactivityDaa = ((durMs / 1_000L) * CovenantCompiler.DAA_BLOCKS_PER_SECOND)
                    .coerceIn(CovenantCompiler.DAA_PER_MINUTE, CovenantCompiler.MAX_HODL_LOCK_DAA)
                CovenantParams(
                    ownerPubKeyHex = ownerPubKeyHex,
                    ownerAddress = ownerAddr,
                    vaultSaltHex = vaultSaltHex,
                    heirAddress = heirAddress.trim().ifBlank { ownerAddr },
                    inactivityDaa = inactivityDaa,
                    inactivityDeadlineMs = 0L,
                    inactivityDurationMs = durMs,
                    keylessCsvHeir = true,
                )
            }
            CovenantTemplateId.DAILY_SPEND_LIMIT -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                dailyCapSompi = kasToSompiOrZero(dailyCapKas).coerceAtLeast(DUST_LIMIT_SOMPI),
                windowDaa = dailyWindowDaa,
                spentInWindowSompi = 0L,
                windowStartDaa = effectiveBaseDaa,
                fullWithdrawUnlockTimeMs = dailyBypassUnlockTimeMs,
            )
            CovenantTemplateId.COLD_WHITELIST -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                whitelistAddresses = listOf(coldAddr1, coldAddr2, coldAddr3).map { it.trim() }.filter { it.isNotBlank() },
            )
            CovenantTemplateId.HODL_TIMELOCK -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                unlockTimeMs = hodlUnlockTimeMs,
                unlockDateIso = formatFullDateTime(hodlUnlockTimeMs),
            )
            CovenantTemplateId.GOAL_PIGGY_BANK -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                targetGoalSompi = kasToSompiOrZero(goalTargetKas).coerceAtLeast(DUST_LIMIT_SOMPI),
                fallbackUnlockTimeMs = fallbackUnlockTimeMs,
            )
            CovenantTemplateId.RECURRING_ALLOWANCE -> CovenantParams(
                ownerPubKeyHex = ownerPubKeyHex,
                ownerAddress = ownerAddr,
                vaultSaltHex = vaultSaltHex,
                recipientAddress = recipientAddress.trim().ifBlank { ownerAddr },
                allowanceSompi = kasToSompiOrZero(allowanceKas).coerceAtLeast(DUST_LIMIT_SOMPI),
                nextDueTimeMs = allowanceFirstDueMs,
                intervalMs = allowanceIntervalMs,
            )
        }
    }

    val compiledPreview = remember(templateId, builtParams, activeNet) {
        runCatching { CovenantCompiler.compile(templateId, builtParams, activeNet) }.getOrNull()
    }

    val plainEnglishSummary = remember(
        templateId, depositKas, unit, s,
        unvaultDelayDaa, inactivityDurationMs, inactivityDeadlineMs,
        dailyCapKas, dailyWindowDaa, dailyBypassUnlockTimeMs,
        coldAddr1, coldAddr2, coldAddr3,
        hodlUnlockTimeMs,
        goalTargetKas, fallbackUnlockTimeMs,
        allowanceKas, allowanceIntervalMs, allowanceFirstDueMs,
    ) {
        val dep = depositKas.trim().ifBlank { "0" }
        when (templateId) {
            CovenantTemplateId.DAILY_SPEND_LIMIT -> safeFormatLocalized(
                s.covSummaryDailyFmt,
                dep,
                unit,
                dailyCapKas.trim().ifBlank { "0" },
                unit,
                formatDaaDurationShort(dailyWindowDaa, s.covStatusReady),
                formatFullDateTime(dailyBypassUnlockTimeMs),
            )
            CovenantTemplateId.CLAWBACK_VAULT -> safeFormatLocalized(
                s.covSummaryClawbackFmt,
                dep,
                unit,
                formatDaaDurationShort(unvaultDelayDaa, s.covStatusReady),
            )
            CovenantTemplateId.DEAD_MANS_SWITCH -> safeFormatLocalized(
                s.covSummaryDeadManFmt,
                dep,
                unit,
                formatDurationMsShort(inactivityDurationMs, s.covStatusReady),
                formatFullDateTime(inactivityDeadlineMs),
            )
            CovenantTemplateId.COLD_WHITELIST -> {
                val count = listOf(coldAddr1, coldAddr2, coldAddr3).count { it.isNotBlank() }.coerceAtLeast(1)
                safeFormatLocalized(s.covSummaryWhitelistFmt, dep, unit, count)
            }
            CovenantTemplateId.HODL_TIMELOCK -> safeFormatLocalized(
                s.covSummaryHodlFmt,
                dep,
                unit,
                formatFullDateTime(hodlUnlockTimeMs),
            )
            CovenantTemplateId.GOAL_PIGGY_BANK -> safeFormatLocalized(
                s.covSummaryPiggyFmt,
                dep,
                unit,
                goalTargetKas.trim().ifBlank { "0" },
                unit,
                formatFullDateTime(fallbackUnlockTimeMs),
            )
            CovenantTemplateId.RECURRING_ALLOWANCE -> safeFormatLocalized(
                s.covSummaryRecurringFmt,
                dep,
                unit,
                allowanceKas.trim().ifBlank { "0" },
                unit,
                formatDurationMsShort(allowanceIntervalMs, s.covStatusReady),
                formatFullDateTime(allowanceFirstDueMs),
            )
        }
    }

    val tier1Backdrop = rememberLayerBackdrop()
    val tier2Backdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else tier1Backdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else tier2Backdrop
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(66f),
    ) {
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
                AnimatedContent(
                    targetState = step,
                    label = "guided_covenant_step",
                ) { currentStep ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState(), enabled = !gestureLocked)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(
                                start = 18.dp,
                                end = 18.dp,
                                top = topPadDp + 12.dp,
                                bottom = 28.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (currentStep) {
                            GuidedCreateStep.INTRO -> {
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(18.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(ContinuousCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = covenantTemplateIcon(templateId),
                                                contentDescription = null,
                                                tint = accentInk,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = s.covStepWhatIsThis,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = accentInk,
                                            )
                                            Text(
                                                text = covenantTemplateTitle(templateId, s),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = introGuide.analogy,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = glassPrimaryContentColor(),
                                    )
                                }

                                IntroBulletCard(
                                    title = s.covWhatItDoesLabel,
                                    body = introGuide.whatItDoes,
                                    backdrop = effectiveAmbient,
                                )
                                IntroBulletCard(
                                    title = s.covHowYouUseItLabel,
                                    body = introGuide.howYouUseIt,
                                    backdrop = effectiveAmbient,
                                )
                                IntroBulletCard(
                                    title = s.covHowYouGetKasBackLabel,
                                    body = introGuide.howYouGetKasBack,
                                    backdrop = effectiveAmbient,
                                )
                                IntroBulletCard(
                                    title = s.covRealWorldScenarioLabel,
                                    body = introGuide.realWorldScenario,
                                    backdrop = effectiveAmbient,
                                )

                                if (isWatchOnly) {
                                    CovenantWatchOnlyBannerCard(text = s.covWatchOnlyBanner)
                                }

                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    enabled = !isWatchOnly,
                                    useBackdropCapture = true,
                                    backdrop = effectiveAmbient,
                                    lockParentGestures = true,
                                    onClick = { step = GuidedCreateStep.SETUP },
                                ) {
                                    MarqueeText(s.covStepSetUpButton, fontWeight = FontWeight.Bold)
                                }
                            }

                            GuidedCreateStep.SETUP -> {
                                if (isWatchOnly) {
                                    CovenantWatchOnlyBannerCard(text = s.covWatchOnlyBanner)
                                }

                                // Name & Initial Deposit Card
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Text(
                                        text = s.covVaultNameQuestion,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = accentInk,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    FluidGlassTextField(
                                        value = label,
                                        onValueChange = { label = it },
                                        placeholder = covenantTemplateTitle(templateId, s),
                                        singleLine = true,
                                        nestedInGlassCard = true,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = safeFormatLocalized(s.covDepositAmountQuestionFmt, unit),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = accentInk,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = safeFormatLocalized(
                                            s.covAvailableBalanceFmt,
                                            if (walletState.prefs.hideBalances) s.maskedAmount else KasFormat.formatKasSummary(walletState.totalBalanceSompi),
                                            unit,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = glassSecondaryContentColor(),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    FluidGlassTextField(
                                        value = depositKas,
                                        onValueChange = { depositKas = it },
                                        placeholder = "10",
                                        singleLine = true,
                                        nestedInGlassCard = true,
                                    )
                                }

                                // Template-Specific Friendly Questions Card
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    when (templateId) {
                                        CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                                            Text(
                                                text = safeFormatLocalized(s.covDailyCapQuestionFmt, unit),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = accentInk,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            FluidGlassTextField(
                                                value = dailyCapKas,
                                                onValueChange = { dailyCapKas = it },
                                                placeholder = "5",
                                                singleLine = true,
                                                nestedInGlassCard = true,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantDurationPicker(
                                                questionTitle = s.covResetWindowQuestion,
                                                presets = listOf(
                                                    DurationPreset("1h", CovenantCompiler.DAA_PER_HOUR),
                                                    DurationPreset("24h", CovenantCompiler.DAA_PER_DAY),
                                                    DurationPreset("7d", CovenantCompiler.DAA_PER_WEEK),
                                                ),
                                                selectedDaa = dailyWindowDaa,
                                                onDaaChanged = { daa, _ -> dailyWindowDaa = daa },
                                                allowDateTimePicker = false,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantTimestampPicker(
                                                questionTitle = s.covFullWithdrawQuestion,
                                                presets = listOf(
                                                    DurationMsPreset("7d", MS_PER_WEEK),
                                                    DurationMsPreset("30d", MS_PER_MONTH),
                                                    DurationMsPreset("90d", 90L * MS_PER_DAY),
                                                ),
                                                selectedDurationMs = dailyBypassDurationMs,
                                                onTargetChanged = { dur, target, _ ->
                                                    dailyBypassDurationMs = dur
                                                    dailyBypassUnlockTimeMs = target
                                                },
                                                allowDateTimePicker = true,
                                                basePastMedianTimeMs = liveBasePmtMs,
                                            )
                                        }

                                        CovenantTemplateId.CLAWBACK_VAULT -> {
                                            CovenantAddressInputField(
                                                questionTitle = s.covRecoveryAddressQuestion,
                                                value = recoveryAddress,
                                                onValueChange = { recoveryAddress = it },
                                                onUseFreshMyAddress = { deriveFreshInto { recoveryAddress = it } },
                                                onScanQr = { onRequestQrScan { recoveryAddress = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantDurationPicker(
                                                questionTitle = s.covUnvaultDelayQuestion,
                                                presets = listOf(
                                                    DurationPreset("24h", CovenantCompiler.DAA_PER_DAY),
                                                    DurationPreset("3d", 3L * CovenantCompiler.DAA_PER_DAY),
                                                    DurationPreset("7d", CovenantCompiler.DAA_PER_WEEK),
                                                ),
                                                selectedDaa = unvaultDelayDaa,
                                                onDaaChanged = { daa, _ -> unvaultDelayDaa = daa },
                                                allowDateTimePicker = false,
                                            )
                                        }

                                        CovenantTemplateId.DEAD_MANS_SWITCH -> {
                                            CovenantAddressInputField(
                                                questionTitle = s.covHeirAddressQuestion,
                                                value = heirAddress,
                                                onValueChange = { heirAddress = it },
                                                onUseFreshMyAddress = { deriveFreshInto { heirAddress = it } },
                                                onScanQr = { onRequestQrScan { heirAddress = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantTimestampPicker(
                                                questionTitle = s.covInactivityQuestion,
                                                presets = listOf(
                                                    DurationMsPreset("30d", MS_PER_MONTH),
                                                    DurationMsPreset("90d", 90L * MS_PER_DAY),
                                                    DurationMsPreset("180d", 180L * MS_PER_DAY),
                                                    DurationMsPreset("365d", 365L * MS_PER_DAY),
                                                ),
                                                selectedDurationMs = inactivityDurationMs,
                                                onTargetChanged = { dur, target, _ ->
                                                    inactivityDurationMs = dur
                                                    inactivityDeadlineMs = target
                                                },
                                                allowDateTimePicker = false,
                                                basePastMedianTimeMs = liveBasePmtMs,
                                            )
                                        }

                                        CovenantTemplateId.COLD_WHITELIST -> {
                                            Text(
                                                text = s.covWhitelistAddressesQuestion,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = accentInk,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            CovenantAddressInputField(
                                                questionTitle = "#1",
                                                value = coldAddr1,
                                                onValueChange = { coldAddr1 = it },
                                                onUseFreshMyAddress = { deriveFreshInto { coldAddr1 = it } },
                                                onScanQr = { onRequestQrScan { coldAddr1 = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            CovenantAddressInputField(
                                                questionTitle = "#2",
                                                value = coldAddr2,
                                                onValueChange = { coldAddr2 = it },
                                                onUseFreshMyAddress = { deriveFreshInto { coldAddr2 = it } },
                                                onScanQr = { onRequestQrScan { coldAddr2 = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            CovenantAddressInputField(
                                                questionTitle = "#3",
                                                value = coldAddr3,
                                                onValueChange = { coldAddr3 = it },
                                                onUseFreshMyAddress = { deriveFreshInto { coldAddr3 = it } },
                                                onScanQr = { onRequestQrScan { coldAddr3 = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                        }

                                        CovenantTemplateId.HODL_TIMELOCK -> {
                                            CovenantTimestampPicker(
                                                questionTitle = s.covHodlUnlockQuestion,
                                                presets = listOf(
                                                    DurationMsPreset("30d", MS_PER_MONTH),
                                                    DurationMsPreset("6m", 180L * MS_PER_DAY),
                                                    DurationMsPreset("1y", 365L * MS_PER_DAY),
                                                ),
                                                selectedDurationMs = hodlDurationMs,
                                                onTargetChanged = { dur, target, _ ->
                                                    hodlDurationMs = dur
                                                    hodlUnlockTimeMs = target
                                                },
                                                allowDateTimePicker = true,
                                                maxDurationMs = CovenantCompiler.MAX_HODL_LOCK_MS,
                                                basePastMedianTimeMs = liveBasePmtMs,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.WarningAmber,
                                                    contentDescription = null,
                                                    tint = Color(0xFFF39C12),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Text(
                                                    text = s.covHodlWarningBody,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = glassPrimaryContentColor(),
                                                )
                                            }
                                        }

                                        CovenantTemplateId.GOAL_PIGGY_BANK -> {
                                            Text(
                                                text = safeFormatLocalized(s.covPiggyGoalQuestionFmt, unit),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = accentInk,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            FluidGlassTextField(
                                                value = goalTargetKas,
                                                onValueChange = { goalTargetKas = it },
                                                placeholder = "50",
                                                singleLine = true,
                                                nestedInGlassCard = true,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantTimestampPicker(
                                                questionTitle = s.covPiggyFallbackQuestion,
                                                presets = listOf(
                                                    DurationMsPreset("30d", MS_PER_MONTH),
                                                    DurationMsPreset("90d", 90L * MS_PER_DAY),
                                                    DurationMsPreset("1y", 365L * MS_PER_DAY),
                                                ),
                                                selectedDurationMs = fallbackDurationMs,
                                                onTargetChanged = { dur, target, _ ->
                                                    fallbackDurationMs = dur
                                                    fallbackUnlockTimeMs = target
                                                },
                                                allowDateTimePicker = true,
                                                basePastMedianTimeMs = liveBasePmtMs,
                                            )
                                        }

                                        CovenantTemplateId.RECURRING_ALLOWANCE -> {
                                            CovenantAddressInputField(
                                                questionTitle = s.covRecipientAddressQuestion,
                                                value = recipientAddress,
                                                onValueChange = { recipientAddress = it },
                                                onUseFreshMyAddress = { deriveFreshInto { recipientAddress = it } },
                                                onScanQr = { onRequestQrScan { recipientAddress = it } },
                                                isTestnet = activeNet.isTestnet,
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = safeFormatLocalized(s.covAllowanceAmountQuestionFmt, unit),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = accentInk,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            FluidGlassTextField(
                                                value = allowanceKas,
                                                onValueChange = { allowanceKas = it },
                                                placeholder = "2",
                                                singleLine = true,
                                                nestedInGlassCard = true,
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            CovenantTimestampPicker(
                                                questionTitle = s.covAllowanceCycleQuestion,
                                                presets = listOf(
                                                    DurationMsPreset("24h", MS_PER_DAY),
                                                    DurationMsPreset("7d", MS_PER_WEEK),
                                                    DurationMsPreset("30d", MS_PER_MONTH),
                                                ),
                                                selectedDurationMs = allowanceIntervalMs,
                                                onTargetChanged = { dur, target, _ ->
                                                    allowanceIntervalMs = dur
                                                    allowanceFirstDueMs = target
                                                },
                                                allowDateTimePicker = true,
                                                basePastMedianTimeMs = liveBasePmtMs,
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = s.covSubscriptionShareHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = glassSecondaryContentColor(),
                                            )
                                        }
                                    }
                                }

                                // Live Plain-English Summary Card
                                FluidGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = effectiveAmbient,
                                    useBackdropCapture = true,
                                    preset = if (isDark) GlassPreset.HighlightSurface else GlassPreset.Card,
                                    surfaceTint = if (isDark) MaterialTheme.colorScheme.primaryContainer else null,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    Text(
                                        text = s.covSummaryCardTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color(0xFFF4FFF8) else accentInk,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = plainEnglishSummary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDark) Color(0xFFE7F6EE) else glassPrimaryContentColor(),
                                    )
                                }

                                // Collapsible Advanced Details Card
                                if (compiledPreview != null) {
                                    FluidGlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backdrop = effectiveAmbient,
                                        useBackdropCapture = true,
                                        contentPadding = PaddingValues(14.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) { showAdvancedDetails = !showAdvancedDetails },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(Icons.Default.Code, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = s.covAdvancedDetailsTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = glassPrimaryContentColor(),
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                imageVector = if (showAdvancedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = glassSecondaryContentColor(),
                                            )
                                        }
                                        AnimatedVisibility(visible = showAdvancedDetails) {
                                            Column(
                                                modifier = Modifier.padding(top = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = "${s.covP2shAddressLabel}: ${compiledPreview.p2shAddress}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = glassSecondaryContentColor(),
                                                )
                                                Text(
                                                    text = compiledPreview.silSource,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = glassSecondaryContentColor(),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (!formError.isNullOrBlank()) {
                                    ErrorInlineBanner(
                                        message = formError!!,
                                        backdrop = effectiveAmbient,
                                        useBackdropCapture = true,
                                        copyButtonLabel = s.covCopyErrorButton,
                                        onCopy = {
                                            copyTextToClipboard(context, s.covCopyErrorButton, formError!!)
                                            viewModel.showMessage(s.copiedToClipboard)
                                        },
                                    )
                                }

                                val depositSompi = kasToSompiOrZero(depositKas)
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    enabled = !isWatchOnly && deployPhase == null && depositSompi >= DUST_LIMIT_SOMPI && hasRequiredAddresses && compiledPreview != null,
                                    useBackdropCapture = true,
                                    backdrop = effectiveAmbient,
                                    lockParentGestures = true,
                                    onClick = {
                                        formError = null
                                        onRequestAuth {
                                            deployPhase = s.covBroadcastingLabel
                                            scope.launch {
                                                val res = viewModel.deployCovenant(
                                                    templateId = templateId,
                                                    label = label,
                                                    note = "",
                                                    params = builtParams,
                                                    depositSompi = depositSompi,
                                                )
                                                deployPhase = null
                                                res.onSuccess { created ->
                                                    deployedCovenant = created
                                                    step = GuidedCreateStep.ALL_SET
                                                }.onFailure { err ->
                                                    formError = err.message ?: s.covDeployButton
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    MarqueeText(
                                        text = deployPhase ?: s.covDeployButton,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            GuidedCreateStep.ALL_SET -> {
                                val created = deployedCovenant
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
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = accentInk,
                                            modifier = Modifier.size(48.dp),
                                        )
                                        Text(
                                            text = s.covAllSetTitle,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = glassPrimaryContentColor(),
                                            textAlign = TextAlign.Center,
                                        )
                                        Text(
                                            text = s.covPostDeployBody,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = glassSecondaryContentColor(),
                                            textAlign = TextAlign.Center,
                                        )
                                        if (created != null && created.deployTxId.isNotBlank()) {
                                            Text(
                                                text = "TxID: ${created.deployTxId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = glassSecondaryContentColor(),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }

                                if (created != null && created.templateId in setOf(
                                        CovenantTemplateId.DEAD_MANS_SWITCH,
                                        CovenantTemplateId.RECURRING_ALLOWANCE,
                                    )
                                ) {
                                    val shareLabel = when (created.templateId) {
                                        CovenantTemplateId.DEAD_MANS_SWITCH -> s.covShareHeirPassButton
                                        else -> s.covShareAllowancePassButton
                                    }
                                    FluidGlassButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        useBackdropCapture = true,
                                        backdrop = effectiveAmbient,
                                        lockParentGestures = false,
                                        onClick = { postDeployPassTarget = created },
                                    ) {
                                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        MarqueeText(shareLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    useBackdropCapture = true,
                                    backdrop = effectiveAmbient,
                                    lockParentGestures = true,
                                    onClick = {
                                        if (created != null) onViewActiveVault(created) else onBack()
                                    },
                                ) {
                                    MarqueeText(s.covViewActiveVaultButton, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = covenantTemplateTitle(templateId, s),
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = {
                            when (step) {
                                GuidedCreateStep.INTRO -> onBack()
                                GuidedCreateStep.SETUP -> step = GuidedCreateStep.INTRO
                                GuidedCreateStep.ALL_SET -> {
                                    val created = deployedCovenant
                                    if (created != null) onViewActiveVault(created) else onBack()
                                }
                            }
                        },
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = ks.back, modifier = Modifier.size(20.dp))
                    }
                },
            )

            postDeployPassTarget?.let { targetCov ->
                SharedPassExportDialog(
                    covenant = targetCov,
                    viewModel = viewModel,
                    onDismiss = { postDeployPassTarget = null },
                )
            }
        }
    }
}

/**
 * Step 4 — Active Vault Management Screen for all 7 Consumer Covenants (Stage 3.2):
 * - Exact spinning `KineticRefreshIconButton` + exact `TN10` top pill
 * - Non-wrapping Hero Card title (`Local VM Verified` stacked cleanly underneath)
 * - Native `tx.time` countdowns (zero `~X blocks` predictions!)
 * - Blank destination input + `Use Fresh My Address` button
 * - Redesigned QR + `.kpass` Share Pass sheet (zero raw JSON wall!)
 */
@Composable
fun CovenantDetailScreen(
    covenant: TrackedCovenant,
    walletState: WalletUiState,
    virtualDaa: Long,
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onRequestAuth: (() -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onToggleHideBalances: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val s = LocalWalletStrings.current
    val ks = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val covState by viewModel.covenantsState.collectAsState()
    val chainTimeAnchor by viewModel.chainTimeAnchor.collectAsState()

    var tickElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(covenant.canonicalKey) {
        viewModel.refreshCovenantsLiveState()
    }
    // Single stable monotonic 1-second ticker — never cancelled or reset by gRPC DAG/UTXO updates!
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            tickElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    val liveVirtualDaa = chainTimeAnchor.estimateVirtualDaaScore(tickElapsedMs).takeIf { it > 0L }
        ?: virtualDaa
    val isConfirming = covenant.canonicalKey in covState.confirmingCovenantKeys
    val isFinalizing = !isConfirming && chainTimeAnchor.isCovenantFinalizing(covenant, tickElapsedMs)

    // Fast 2-second gRPC GetBlockDagInfo poll whenever countdown enters `Finalizing on Kaspa L1...`
    // until real server pastMedianTime strictly crosses unlockTimeMs!
    LaunchedEffect(isFinalizing) {
        if (isFinalizing) {
            while (true) {
                viewModel.pollServerBlockDagAnchorNow()
                delay(2_000L)
            }
        }
    }

    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true
    val hideBalances = walletState.prefs.hideBalances
    val privacyMode = walletState.prefs.kasOnlyPrivacyMode
    val unit = if (covenant.network.isTestnet) s.tkasUnit else s.kasUnit
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val isSettled = covenant.liveStatus == CovenantStatus.SETTLED

    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 104.dp.roundToPx() }) }
    // Destination address starts BLANK by default (except Cold Whitelist where 1st approved cold addr is selected via chip)
    var actionRecipient by remember(covenant.canonicalKey) {
        mutableStateOf(
            if (covenant.templateId == CovenantTemplateId.COLD_WHITELIST) {
                covenant.params.whitelistAddresses.firstOrNull().orEmpty()
            } else {
                ""
            },
        )
    }
    var actionAmountKas by remember(covenant.canonicalKey) {
        val defaultSompi = when (covenant.templateId) {
            CovenantTemplateId.DAILY_SPEND_LIMIT -> minOf(2L * SOMPI_PER_KAS, covenant.params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI))
            else -> 2L * SOMPI_PER_KAS
        }
        val formatted = BigDecimal(defaultSompi)
            .divide(BigDecimal(SOMPI_PER_KAS))
            .stripTrailingZeros()
            .toPlainString()
        mutableStateOf(formatted)
    }
    var actionPhase by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showAdvancedDetails by remember { mutableStateOf(false) }
    var showSharePassDialog by remember { mutableStateOf(false) }

    var showTopUpDialog by remember { mutableStateOf(false) }
    var topUpAmountKas by remember { mutableStateOf("5") }
    var verifyingProof by remember { mutableStateOf(false) }
    var activeProof by remember { mutableStateOf<OnChainCovenantProof?>(null) }

    fun kasToSompi(raw: String): Long = runCatching {
        BigDecimal(raw.trim()).multiply(BigDecimal(SOMPI_PER_KAS)).toLong()
    }.getOrDefault(0L)

    val submitInFlight = remember(covenant.canonicalKey) { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun runEntry(
        entryName: String,
        spendAmt: Long? = null,
        topUpAmt: Long = 0L,
        isKeyless: Boolean = false,
    ) {
        if (actionPhase != null || isConfirming || submitInFlight.get()) return
        actionError = null
        val launchBlock: () -> Unit = {
            if (submitInFlight.compareAndSet(false, true)) {
                actionPhase = s.covBroadcastingLabel
                scope.launch {
                    try {
                        val res = viewModel.executeCovenantAction(
                            covenant = covenant,
                            entryName = entryName,
                            recipientAddress = actionRecipient,
                            spendAmountSompi = spendAmt,
                            topUpExtraSompi = topUpAmt,
                        )
                        res.onSuccess { txId ->
                            viewModel.showMessage("${s.covActionBroadcastedTitle}: ${txId.take(12)}…")
                        }.onFailure { err ->
                            actionError = err.message ?: s.covCopyErrorButton
                        }
                    } finally {
                        actionPhase = null
                        submitInFlight.set(false)
                    }
                }
            }
        }
        if (isKeyless) launchBlock() else onRequestAuth(launchBlock)
    }

    val ageDaa = if (liveVirtualDaa > 0L && covenant.liveUtxoBlockDaaScore > 0L) {
        (liveVirtualDaa - covenant.liveUtxoBlockDaaScore).coerceAtLeast(0L)
    } else {
        0L
    }
    val balDisplay = if (hideBalances) "${s.maskedAmount} $unit" else "${KasFormat.formatKas(covenant.liveBalanceSompi)} $unit"
    val fiatDisplay = if (!hideBalances && !privacyMode && !covenant.network.isTestnet && walletState.spotPrice != null) {
        KasFormat.formatFiat(covenant.liveBalanceSompi, walletState.spotPrice)
    } else {
        null
    }

    val tier1Backdrop = rememberLayerBackdrop()
    val tier2Backdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else tier1Backdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else tier2Backdrop
    val topPadDp = with(density) { topChromeHeightPx.toDp() }
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(66f),
    ) {
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = !gestureLocked)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            top = topPadDp + 12.dp,
                            bottom = 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. Hero Card — full-width title so long names NEVER wrap mid-word, with Local VM Verified stacked cleanly below!
                    FluidGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backdrop = effectiveAmbient,
                        useBackdropCapture = true,
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(ContinuousCornerShape(13.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(covenantTemplateIcon(covenant.templateId), contentDescription = null, tint = accentInk, modifier = Modifier.size(22.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                MarqueeText(
                                    text = covenantTemplateTitle(covenant.templateId, s),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accentInk,
                                    fontWeight = FontWeight.Bold,
                                )
                                val heroStatusLabel = if (
                                    covenant.isSharedPassImport &&
                                    covenant.liveStatus == CovenantStatus.UNCONFIRMED &&
                                    covenant.liveUtxoTxId.isNullOrBlank()
                                ) {
                                    s.covSyncingStatus
                                } else {
                                    covenantStatusLabel(covenant.liveStatus, s)
                                }
                                Text(
                                    text = "$heroStatusLabel • ${if (covenant.network.isTestnet) s.covTestnetBadge else s.covMainnetBadge}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = glassSecondaryContentColor(),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        FluidGlassChip(
                            selected = true,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {},
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.covVmVerifiedBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = balDisplay,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = glassPrimaryContentColor(),
                        )
                        if (fiatDisplay != null) {
                            Text(fiatDisplay, style = MaterialTheme.typography.bodyMedium, color = glassSecondaryContentColor())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = formatCovenantQuickStatus(
                                cov = covenant,
                                virtualDaa = liveVirtualDaa,
                                hideBalances = hideBalances,
                                masked = s.maskedAmount,
                                unit = unit,
                                s = s,
                                chainTimeAnchor = chainTimeAnchor,
                                nowElapsedRealtimeMs = tickElapsedMs,
                                isConfirming = isConfirming,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isConfirming || isFinalizing) Color(0xFFF39C12) else glassPrimaryContentColor(),
                        )

                        if (!isSettled) {
                            when (covenant.templateId) {
                                CovenantTemplateId.GOAL_PIGGY_BANK -> {
                                    val progress = if (covenant.params.targetGoalSompi > 0L) {
                                        (covenant.liveBalanceSompi.toFloat() / covenant.params.targetGoalSompi.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(ContinuousCornerShape(6.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    )
                                }
                                CovenantTemplateId.CLAWBACK_VAULT -> if (covenant.params.vaultStage == 1) {
                                    val progress = if (covenant.params.unvaultDelayDaa > 0L) {
                                        (ageDaa.toFloat() / covenant.params.unvaultDelayDaa.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(ContinuousCornerShape(6.dp)),
                                        color = Color(0xFFF39C12),
                                        trackColor = Color(0xFFF39C12).copy(alpha = 0.20f),
                                    )
                                }
                                else -> Unit
                            }
                        }

                        // Consensus-locked payout / emergency recovery destination readouts
                        val lockedDestPair: Pair<String, String>? = when (covenant.templateId) {
                            CovenantTemplateId.CLAWBACK_VAULT ->
                                covenant.params.recoveryAddress.takeIf { it.isNotBlank() }?.let {
                                    s.covEmergencyClawbackDestLabel to it
                                }
                            CovenantTemplateId.DEAD_MANS_SWITCH ->
                                covenant.params.heirAddress.takeIf { it.isNotBlank() }?.let {
                                    s.covPaysStrictlyToLabel to it
                                }
                            CovenantTemplateId.RECURRING_ALLOWANCE ->
                                covenant.params.recipientAddress.takeIf { it.isNotBlank() }?.let {
                                    s.covPaysStrictlyToLabel to it
                                }
                            else -> null
                        }
                        if (lockedDestPair != null) {
                            val (destLabel, destAddr) = lockedDestPair
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ContinuousCornerShape(14.dp))
                                    .background(
                                        if (reduceTransparency) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.14f else 0.08f)
                                        },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                CopyableTechRow(
                                    label = destLabel,
                                    value = destAddr,
                                    onCopy = {
                                        copyTextToClipboard(context, destLabel, destAddr)
                                        viewModel.showMessage(s.copiedToClipboard)
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !verifyingProof,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                verifyingProof = true
                                scope.launch {
                                    val res = viewModel.verifyCovenantOnChain(covenant)
                                    verifyingProof = false
                                    res.onSuccess { activeProof = it }
                                        .onFailure { viewModel.showMessage(it.message ?: s.covVerifyOnChainButton) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (verifyingProof) Icons.Default.Sync else Icons.Default.Verified,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            MarqueeText(
                                text = if (verifyingProof) s.loading else s.covVerifyOnChainButton,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    // Stray External Unbound Deposit Alert + Rescue Action
                    if (covenant.strayUnboundBalanceSompi > 0L && !isWatchOnly) {
                        val strayText = "${KasFormat.formatKasSummary(covenant.strayUnboundBalanceSompi)} $unit"
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveAmbient,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF39C12), modifier = Modifier.size(19.dp))
                                Text(
                                    text = safeFormatLocalized(s.covStrayUtxoWarningFmt, strayText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = glassPrimaryContentColor(),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionPhase == null && !isConfirming,
                                useBackdropCapture = false,
                                onClick = {
                                    onRequestAuth {
                                        actionPhase = s.covBroadcastingLabel
                                        scope.launch {
                                            val res = viewModel.rescueStrayUnboundUtxo(covenant)
                                            actionPhase = null
                                            res.onSuccess { txId ->
                                                viewModel.showMessage("${s.covActionBroadcastedTitle}: ${txId.take(12)}…")
                                            }.onFailure { err ->
                                                actionError = err.message ?: s.covRescueStrayButton
                                            }
                                        }
                                    }
                                },
                            ) {
                                MarqueeText(s.covRescueStrayButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // 2. Smart Active Vault Actions Card (when not settled)
                    if (!isSettled) {
                        FluidGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = effectiveAmbient,
                            useBackdropCapture = true,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            val isBusy = actionPhase != null || isConfirming
                            val busyLabel = actionPhase ?: if (isConfirming) s.covConfirmingOnL1 else null
                            val needsRecipient = covenant.templateId in setOf(
                                CovenantTemplateId.CLAWBACK_VAULT,
                                CovenantTemplateId.DAILY_SPEND_LIMIT,
                                CovenantTemplateId.COLD_WHITELIST,
                                CovenantTemplateId.HODL_TIMELOCK,
                                CovenantTemplateId.GOAL_PIGGY_BANK,
                            )
                            val serverVirtualDaa = chainTimeAnchor.virtualDaaScore.takeIf { it > 0L } ?: virtualDaa
                            val dailyWindow = if (covenant.templateId == CovenantTemplateId.DAILY_SPEND_LIMIT) {
                                CovenantCompiler.evaluateDailySpendWindow(
                                    params = covenant.params,
                                    liveBalanceSompi = covenant.liveBalanceSompi,
                                    utxoBlockDaaScore = covenant.liveUtxoBlockDaaScore,
                                    virtualDaaScore = serverVirtualDaa,
                                    extrapolatedVirtualDaaScore = liveVirtualDaa,
                                )
                            } else {
                                null
                            }

                            if (needsRecipient) {
                                val destPromptTitle = when (covenant.templateId) {
                                    CovenantTemplateId.DAILY_SPEND_LIMIT -> s.covDestPromptDailyLimit
                                    CovenantTemplateId.CLAWBACK_VAULT -> s.covDestPromptClawback
                                    CovenantTemplateId.COLD_WHITELIST -> s.covDestPromptColdWhitelist
                                    CovenantTemplateId.HODL_TIMELOCK,
                                    CovenantTemplateId.GOAL_PIGGY_BANK -> s.covDestPromptUnlock
                                    else -> s.covWithdrawDestQuestion
                                }
                                CovenantAddressInputField(
                                    questionTitle = destPromptTitle,
                                    value = actionRecipient,
                                    onValueChange = { actionRecipient = it },
                                    onUseFreshMyAddress = {
                                        scope.launch {
                                            viewModel.deriveFreshUnusedReceiveAddress()
                                                .onSuccess { actionRecipient = it }
                                                .onFailure { viewModel.showMessage(it.message ?: s.covUseFreshAddressButton) }
                                        }
                                    },
                                    isTestnet = covenant.network.isTestnet,
                                )
                                Spacer(Modifier.height(10.dp))
                            }

                            if (dailyWindow != null) {
                                val availText = if (hideBalances) s.maskedAmount else KasFormat.formatKasSummary(dailyWindow.availableNowSompi)
                                val capText = if (hideBalances) s.maskedAmount else KasFormat.formatKasSummary(dailyWindow.capSompi)
                                val windowTimerText = when {
                                    dailyWindow.isFinalizing -> s.covFinalizingOnL1
                                    dailyWindow.resetRemainingMs > 0L -> safeFormatLocalized(
                                        s.covDailyWindowResetsInFmt,
                                        formatDurationMsShort(dailyWindow.resetRemainingMs.coerceAtLeast(1_000L), s.covStatusReady),
                                    )
                                    else -> s.covDailyWindowReadyNow
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(ContinuousCornerShape(14.dp))
                                        .background(
                                            if (reduceTransparency) {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            } else {
                                                MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.14f else 0.08f)
                                            },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = safeFormatLocalized(s.covDailyAvailableNowFmt, availText, unit, capText, unit),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = glassPrimaryContentColor(),
                                    )
                                    Text(
                                        text = windowTimerText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (dailyWindow.isInCooldown) Color(0xFFF39C12) else accentInk,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            if (covenant.templateId in setOf(CovenantTemplateId.DAILY_SPEND_LIMIT, CovenantTemplateId.COLD_WHITELIST)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = safeFormatLocalized(s.covSpendAmountQuestionFmt, unit),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = accentInk,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FluidGlassChip(
                                        selected = false,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        onClick = {
                                            val maxTargetSompi = when (covenant.templateId) {
                                                CovenantTemplateId.DAILY_SPEND_LIMIT -> dailyWindow?.availableNowSompi ?: 0L
                                                else -> covenant.liveBalanceSompi
                                            }
                                            if (maxTargetSompi > 0L) {
                                                actionAmountKas = BigDecimal(maxTargetSompi)
                                                    .divide(BigDecimal(SOMPI_PER_KAS))
                                                    .stripTrailingZeros()
                                                    .toPlainString()
                                                actionError = null
                                            }
                                        },
                                    ) {
                                        Text(
                                            text = s.covMaxButton,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                FluidGlassTextField(
                                    value = actionAmountKas,
                                    onValueChange = { actionAmountKas = it },
                                    placeholder = if (covenant.templateId == CovenantTemplateId.DAILY_SPEND_LIMIT) {
                                        BigDecimal(minOf(2L * SOMPI_PER_KAS, covenant.params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI)))
                                            .divide(BigDecimal(SOMPI_PER_KAS))
                                            .stripTrailingZeros()
                                            .toPlainString()
                                    } else {
                                        "2"
                                    },
                                    singleLine = true,
                                    nestedInGlassCard = true,
                                )
                                Spacer(Modifier.height(10.dp))
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (covenant.templateId) {
                                    CovenantTemplateId.CLAWBACK_VAULT -> {
                                        if (covenant.params.vaultStage == 0) {
                                            FluidGlassButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                accent = true,
                                                enabled = !isWatchOnly && !isBusy,
                                                useBackdropCapture = false,
                                                lockParentGestures = true,
                                                onClick = { runEntry("initiateUnvault") },
                                            ) { MarqueeText(busyLabel ?: s.covActionInitiateUnvault, fontWeight = FontWeight.Bold) }
                                        } else {
                                            val unvaultEval = chainTimeAnchor.evaluateRelativeDaaLock(
                                                utxoBlockDaaScore = covenant.liveUtxoBlockDaaScore,
                                                requiredDaa = covenant.params.unvaultDelayDaa,
                                                nowElapsedRealtimeMs = tickElapsedMs,
                                            )
                                            val delayMet = unvaultEval.stage == TimeLockStage.READY
                                            val delayFinalizing = unvaultEval.stage == TimeLockStage.FINALIZING
                                            val delaySyncing = !delayMet && !delayFinalizing &&
                                                isCountdownSyncingOrSentinel(covenant, unvaultEval.extrapolatedRemainingMs)
                                            FluidGlassButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                accent = true,
                                                enabled = !isWatchOnly && !isBusy && delayMet,
                                                useBackdropCapture = false,
                                                lockParentGestures = true,
                                                onClick = { runEntry("completeUnvault") },
                                            ) {
                                                val lbl = busyLabel ?: when {
                                                    delayMet -> s.covActionCompleteUnvault
                                                    delayFinalizing -> s.covFinalizingOnL1
                                                    delaySyncing -> s.covSyncingStatus
                                                    else -> safeFormatLocalized(
                                                        s.covQuickUnlocksInFmt,
                                                        formatDurationMsShort(unvaultEval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus),
                                                    )
                                                }
                                                MarqueeText(lbl, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            destructive = true,
                                            enabled = !isWatchOnly && !isBusy,
                                            useBackdropCapture = false,
                                            onClick = { runEntry("alarmClawback") },
                                        ) { MarqueeText(s.covActionAlarmClawback, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                                    }

                                    CovenantTemplateId.DEAD_MANS_SWITCH -> {
                                        val isTimeMode = CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(covenant.params)
                                        val isKeylessHeir = CovenantCompiler.isKeylessDeadMansSwitch(covenant.params)
                                        val eval = if (isTimeMode) {
                                            chainTimeAnchor.evaluateTimeLock(covenant.params.inactivityDeadlineMs, tickElapsedMs)
                                        } else {
                                            chainTimeAnchor.evaluateRelativeDaaLock(
                                                utxoBlockDaaScore = covenant.liveUtxoBlockDaaScore,
                                                requiredDaa = covenant.params.inactivityDaa,
                                                nowElapsedRealtimeMs = tickElapsedMs,
                                            )
                                        }
                                        val heirSyncing = isCountdownSyncingOrSentinel(
                                            covenant = covenant,
                                            remainingMs = eval.extrapolatedRemainingMs,
                                            requiresUtxoBlockDaa = !isTimeMode,
                                        )
                                        val heirReady = !heirSyncing && eval.stage == TimeLockStage.READY
                                        val heirFinalizing = !heirSyncing && eval.stage == TimeLockStage.FINALIZING
                                        if (!covenant.isSharedPassImport) {
                                            FluidGlassButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                accent = true,
                                                enabled = !isWatchOnly && !isBusy,
                                                useBackdropCapture = false,
                                                lockParentGestures = true,
                                                onClick = { runEntry("checkIn") },
                                            ) { MarqueeText(busyLabel ?: s.covActionCheckIn, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!covenant.isSharedPassImport) {
                                                FluidGlassButton(
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !isWatchOnly && !isBusy,
                                                    useBackdropCapture = false,
                                                    onClick = { runEntry("ownerReclaim") },
                                                ) { MarqueeText(s.covActionReclaim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                                            }
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                accent = covenant.isSharedPassImport && heirReady,
                                                enabled = (!isWatchOnly || isKeylessHeir) && !isBusy && heirReady,
                                                useBackdropCapture = false,
                                                onClick = { runEntry("heirClaim", isKeyless = isKeylessHeir) },
                                            ) {
                                                val heirLbl = busyLabel ?: when {
                                                    heirSyncing -> s.covSyncingStatus
                                                    heirReady -> s.covActionHeirClaim
                                                    heirFinalizing -> s.covFinalizingOnL1
                                                    else -> safeFormatLocalized(
                                                        s.covQuickHeirTimerFmt,
                                                        formatDurationMsShort(eval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus),
                                                    )
                                                }
                                                MarqueeText(heirLbl, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }

                                    CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                                        val usesTimeBypass = covenant.params.fullWithdrawUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD
                                        val bypassEval = if (usesTimeBypass) {
                                            chainTimeAnchor.evaluateTimeLock(covenant.params.fullWithdrawUnlockTimeMs, tickElapsedMs)
                                        } else null
                                        val legacyBypassRemMs = run {
                                            val daaRem = if (covenant.params.fullWithdrawUnlockDaa > 0L && liveVirtualDaa > 0L) {
                                                (covenant.params.fullWithdrawUnlockDaa - liveVirtualDaa).coerceAtLeast(0L)
                                            } else {
                                                (covenant.params.windowDaa - ageDaa).coerceAtLeast(0L)
                                            }
                                            daaRem * 100L
                                        }
                                        val bypassReady = if (usesTimeBypass) {
                                            bypassEval?.stage == TimeLockStage.READY
                                        } else if (covenant.params.fullWithdrawUnlockDaa > 0L) {
                                            serverVirtualDaa > 0L && serverVirtualDaa >= covenant.params.fullWithdrawUnlockDaa
                                        } else {
                                            chainTimeAnchor.isServerRelativeDaaLockReady(
                                                covenant.liveUtxoBlockDaaScore,
                                                covenant.params.windowDaa,
                                            )
                                        }
                                        val bypassFinalizing = usesTimeBypass && bypassEval?.stage == TimeLockStage.FINALIZING
                                        val spendInCooldown = dailyWindow?.isInCooldown == true
                                        val spendFinalizing = spendInCooldown && dailyWindow?.isFinalizing == true
                                        val spendBtnLabel = busyLabel ?: when {
                                            spendFinalizing -> s.covFinalizingOnL1
                                            spendInCooldown -> safeFormatLocalized(
                                                s.covDailyWindowResetsInFmt,
                                                formatDurationMsShort((dailyWindow?.resetRemainingMs ?: 1_000L).coerceAtLeast(1_000L), s.covStatusReady),
                                            )
                                            else -> s.covActionSpendLimit
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = !spendInCooldown,
                                            enabled = !isWatchOnly && !isBusy && !spendInCooldown,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = { runEntry("spendWithinLimit", spendAmt = kasToSompi(actionAmountKas)) },
                                        ) { MarqueeText(spendBtnLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !isWatchOnly && !isBusy && bypassReady,
                                            useBackdropCapture = false,
                                            onClick = { runEntry("ownerReclaim") },
                                        ) {
                                            val lbl = when {
                                                bypassReady -> s.covSettleWithdrawAllButton
                                                bypassFinalizing -> s.covFinalizingOnL1
                                                usesTimeBypass -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort((bypassEval?.extrapolatedRemainingMs ?: 0L).coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                                else -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort(legacyBypassRemMs.coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                            }
                                            MarqueeText(lbl, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    CovenantTemplateId.COLD_WHITELIST -> {
                                        if (covenant.params.whitelistAddresses.size > 1) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                covenant.params.whitelistAddresses.forEachIndexed { idx, addr ->
                                                    FluidGlassChip(
                                                        selected = actionRecipient == addr,
                                                        useBackdropCapture = false,
                                                        lockParentGestures = false,
                                                        onClick = {
                                                            actionRecipient = addr
                                                            actionError = null
                                                        },
                                                    ) { Text("#${idx + 1}", style = MaterialTheme.typography.labelSmall) }
                                                }
                                            }
                                        }
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            enabled = !isWatchOnly && !isBusy,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = {
                                                // Pre-validate against the vault's approved cold whitelist BEFORE running VM!
                                                val dest = actionRecipient.trim()
                                                if (!CovenantCompiler.isDestinationWhitelisted(covenant, dest)) {
                                                    actionError = s.covWhitelistNotApprovedError
                                                } else {
                                                    val parsed = kasToSompi(actionAmountKas).takeIf { it >= DUST_LIMIT_SOMPI }
                                                    runEntry("spend", spendAmt = parsed)
                                                }
                                            },
                                        ) { MarqueeText(busyLabel ?: s.covActionSpendWhitelist, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                                    }

                                    CovenantTemplateId.HODL_TIMELOCK -> {
                                        val isTimeMode = covenant.params.unlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD
                                        val hodlEval = if (isTimeMode) {
                                            chainTimeAnchor.evaluateTimeLock(covenant.params.unlockTimeMs, tickElapsedMs)
                                        } else null
                                        val legacyRemMs = (if (liveVirtualDaa > 0L) (covenant.params.unlockDaa - liveVirtualDaa).coerceAtLeast(0L) else covenant.params.unlockDaa) * 100L
                                        val unlocked = if (isTimeMode) {
                                            hodlEval?.stage == TimeLockStage.READY
                                        } else {
                                            liveVirtualDaa > 0L && liveVirtualDaa >= covenant.params.unlockDaa
                                        }
                                        val hodlFinalizing = isTimeMode && hodlEval?.stage == TimeLockStage.FINALIZING
                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            enabled = !isWatchOnly && !isBusy && unlocked,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = { runEntry("unlock") },
                                        ) {
                                            val lbl = busyLabel ?: when {
                                                unlocked -> s.covActionUnlock
                                                hodlFinalizing -> s.covFinalizingOnL1
                                                isTimeMode -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort((hodlEval?.extrapolatedRemainingMs ?: 0L).coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                                else -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort(legacyRemMs.coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                            }
                                            MarqueeText(lbl, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    CovenantTemplateId.GOAL_PIGGY_BANK -> {
                                        val goalMet = covenant.liveBalanceSompi >= covenant.params.targetGoalSompi
                                        val isTimeFallback = covenant.params.fallbackUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD
                                        val fbEval = if (isTimeFallback) {
                                            chainTimeAnchor.evaluateTimeLock(covenant.params.fallbackUnlockTimeMs, tickElapsedMs)
                                        } else null
                                        val legacyFbRemMs = (if (liveVirtualDaa > 0L) (covenant.params.fallbackUnlockDaa - liveVirtualDaa).coerceAtLeast(0L) else covenant.params.fallbackUnlockDaa) * 100L
                                        val fallbackMet = if (isTimeFallback) {
                                            fbEval?.stage == TimeLockStage.READY
                                        } else {
                                            liveVirtualDaa > 0L && covenant.params.fallbackUnlockDaa > 0L && liveVirtualDaa >= covenant.params.fallbackUnlockDaa
                                        }
                                        val fbFinalizing = isTimeFallback && fbEval?.stage == TimeLockStage.FINALIZING
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                accent = goalMet,
                                                enabled = !isWatchOnly && !isBusy && goalMet,
                                                useBackdropCapture = false,
                                                lockParentGestures = goalMet,
                                                onClick = { runEntry("unlockGoal") },
                                            ) {
                                                val lbl = busyLabel ?: if (goalMet) s.covActionUnlockGoal
                                                else "${KasFormat.formatKasSummary((covenant.params.targetGoalSompi - covenant.liveBalanceSompi).coerceAtLeast(0L))} $unit"
                                                MarqueeText(lbl, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            }
                                            FluidGlassButton(
                                                modifier = Modifier.weight(1f),
                                                enabled = !isWatchOnly && !isBusy && fallbackMet,
                                                useBackdropCapture = false,
                                                onClick = { runEntry("unlockFallback") },
                                            ) {
                                                val lbl = when {
                                                    fallbackMet -> s.covActionUnlockFallback
                                                    fbFinalizing -> s.covFinalizingOnL1
                                                    isTimeFallback -> formatDurationMsShort((fbEval?.extrapolatedRemainingMs ?: 0L).coerceAtLeast(1_000L), s.covStatusReady)
                                                    else -> formatDurationMsShort(legacyFbRemMs.coerceAtLeast(1_000L), s.covStatusReady)
                                                }
                                                MarqueeText(lbl, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }

                                    CovenantTemplateId.RECURRING_ALLOWANCE -> {
                                        val isKeylessSub = CovenantCompiler.isKeylessTimeBasedSubscription(covenant.params)
                                        val subEval = if (isKeylessSub) {
                                            chainTimeAnchor.evaluateTimeLock(covenant.params.nextDueTimeMs, tickElapsedMs)
                                        } else null
                                        val legacyRemMs = ((covenant.params.periodDaa - ageDaa).coerceAtLeast(0L) * 100L)
                                        val cycleReady = if (isKeylessSub) {
                                            subEval?.stage == TimeLockStage.READY
                                        } else {
                                            liveVirtualDaa > 0L && covenant.liveUtxoBlockDaaScore > 0L && legacyRemMs <= 0L
                                        }
                                        val subFinalizing = isKeylessSub && subEval?.stage == TimeLockStage.FINALIZING
                                        val amtStr = if (hideBalances) s.maskedAmount else KasFormat.formatKasSummary(covenant.params.allowanceSompi)

                                        FluidGlassButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            accent = true,
                                            enabled = (!isWatchOnly || isKeylessSub) && !isBusy && cycleReady,
                                            useBackdropCapture = false,
                                            lockParentGestures = true,
                                            onClick = { runEntry("withdraw", isKeyless = isKeylessSub) },
                                        ) {
                                            val lbl = busyLabel ?: when {
                                                cycleReady -> safeFormatLocalized(s.covSharedClaimAmountFmt, amtStr, unit)
                                                subFinalizing -> s.covFinalizingOnL1
                                                isKeylessSub -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort((subEval?.extrapolatedRemainingMs ?: 0L).coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                                else -> safeFormatLocalized(
                                                    s.covQuickUnlocksInFmt,
                                                    formatDurationMsShort(legacyRemMs.coerceAtLeast(1_000L), s.covStatusReady),
                                                )
                                            }
                                            MarqueeText(lbl, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        }

                                        if (!covenant.isSharedPassImport) {
                                            FluidGlassButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                destructive = true,
                                                enabled = !isWatchOnly && !isBusy,
                                                useBackdropCapture = false,
                                                onClick = { runEntry("cancelReclaim") },
                                            ) { MarqueeText(s.covActionCancelReclaim, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                                        }
                                    }
                                }

                                // Universal In-App Top Up Button for owned vaults
                                val hasTopUpEntry = !covenant.isSharedPassImport &&
                                    covenant.abiArtifact.contracts[covenant.templateId.contractName]
                                        ?.entries?.containsKey("topUp") == true
                                if (hasTopUpEntry) {
                                    FluidGlassButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        accent = covenant.templateId == CovenantTemplateId.GOAL_PIGGY_BANK &&
                                            covenant.liveBalanceSompi < covenant.params.targetGoalSompi,
                                        enabled = !isWatchOnly && !isBusy,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        onClick = {
                                            topUpAmountKas = "5"
                                            showTopUpDialog = true
                                        },
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        MarqueeText(s.covTopUpButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(
                                        text = s.covTopUpNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = glassSecondaryContentColor(),
                                    )
                                }
                            }

                            if (!actionError.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                ErrorInlineBanner(
                                    message = actionError!!,
                                    useBackdropCapture = false,
                                    copyButtonLabel = s.covCopyErrorButton,
                                    onCopy = {
                                        copyTextToClipboard(context, s.covCopyErrorButton, actionError!!)
                                        viewModel.showMessage(s.copiedToClipboard)
                                    },
                                )
                            }
                        }
                    }

                    // 3. Shareable `.kpass` Export (Dead Man's Switch & Recurring Allowance ONLY)
                    if (covenant.templateId in setOf(
                            CovenantTemplateId.DEAD_MANS_SWITCH,
                            CovenantTemplateId.RECURRING_ALLOWANCE,
                        )
                    ) {
                        val shareLabel = when (covenant.templateId) {
                            CovenantTemplateId.DEAD_MANS_SWITCH -> s.covShareHeirPassButton
                            else -> s.covShareAllowancePassButton
                        }
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = true,
                            backdrop = effectiveAmbient,
                            lockParentGestures = false,
                            onClick = { showSharePassDialog = true },
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(shareLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // 4. Collapsible Advanced Details Card
                    FluidGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backdrop = effectiveAmbient,
                        useBackdropCapture = true,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { showAdvancedDetails = !showAdvancedDetails },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
                            Text(
                                text = s.covAdvancedDetailsTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showAdvancedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = glassSecondaryContentColor(),
                            )
                        }

                        AnimatedVisibility(visible = showAdvancedDetails) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CopyableTechRow(
                                    label = s.covP2shAddressLabel,
                                    value = covenant.currentP2shAddress,
                                    onCopy = {
                                        copyTextToClipboard(context, s.covP2shAddressLabel, covenant.currentP2shAddress)
                                        viewModel.showMessage(s.copiedToClipboard)
                                    },
                                )
                                if (!covenant.covenantIdHex.isNullOrBlank()) {
                                    CopyableTechRow(
                                        label = s.covCovenantIdLabel,
                                        value = covenant.covenantIdHex,
                                        onCopy = {
                                            copyTextToClipboard(context, s.covCovenantIdLabel, covenant.covenantIdHex)
                                            viewModel.showMessage(s.copiedToClipboard)
                                        },
                                    )
                                }
                                Text(
                                    text = covenant.silSource,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = glassSecondaryContentColor(),
                                )
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    useBackdropCapture = false,
                                    onClick = {
                                        val abiJson = CovenantCompiler.formatAbiPrettyJson(covenant.abiArtifact)
                                        copyTextToClipboard(context, s.covCopyAbiJsonButton, abiJson)
                                        viewModel.showMessage(s.copiedToClipboard)
                                    },
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    MarqueeText(s.covCopyAbiJsonButton, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pinned Floating Kinetic Aero Top Header (Back + Title + exact TN10 + exact spinning Refresh + Eye Toggle)
        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = covenant.label,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = onBack,
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
                        onClick = onToggleHideBalances,
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(
                            imageVector = if (hideBalances) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (hideBalances) s.showBalancesTooltip else s.hideBalancesTooltip,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )

            // In-App Top Up Dialog
            if (showTopUpDialog) {
                val remainingGoalSompi = if (covenant.templateId == CovenantTemplateId.GOAL_PIGGY_BANK) {
                    (covenant.params.targetGoalSompi - covenant.liveBalanceSompi).coerceAtLeast(0L)
                } else {
                    0L
                }
                FluidGlassDialog(
                    onDismissRequest = { showTopUpDialog = false },
                    title = {
                        Text(
                            text = s.covTopUpButton,
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
                                onClick = { showTopUpDialog = false },
                            ) { Text(ks.cancel) }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = true,
                                enabled = kasToSompi(topUpAmountKas) >= DUST_LIMIT_SOMPI && actionPhase == null,
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    val amt = kasToSompi(topUpAmountKas)
                                    showTopUpDialog = false
                                    runEntry("topUp", topUpAmt = amt)
                                },
                            ) { MarqueeText(s.done, fontWeight = FontWeight.Bold) }
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = safeFormatLocalized(s.covTopUpBodyFmt, unit),
                            style = MaterialTheme.typography.bodySmall,
                            color = glassSecondaryContentColor(),
                        )
                        FluidGlassTextField(
                            value = topUpAmountKas,
                            onValueChange = { topUpAmountKas = it },
                            placeholder = "5",
                            singleLine = true,
                            nestedInGlassCard = true,
                        )
                        if (remainingGoalSompi >= DUST_LIMIT_SOMPI) {
                            val remKasStr = BigDecimal(remainingGoalSompi)
                                .divide(BigDecimal(SOMPI_PER_KAS), 8, RoundingMode.UP)
                                .stripTrailingZeros()
                                .toPlainString()
                            FluidGlassChip(
                                selected = true,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                onClick = { topUpAmountKas = remKasStr },
                            ) {
                                Text(
                                    text = "${s.covFillRemainingChip} ($remKasStr $unit)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            // Live "Verify On-Chain" Cryptographic Proof Dialog
            activeProof?.let { proof ->
                CovenantVerifyOnChainDialog(
                    proof = proof,
                    hideBalances = hideBalances,
                    onInspectInExplorer = onInspectInExplorer,
                    onCopiedMessage = { viewModel.showMessage(it) },
                    onDismiss = { activeProof = null },
                )
            }

            // Redesigned QR + `.kpass` Share Pass Dialog
            if (showSharePassDialog) {
                SharedPassExportDialog(
                    covenant = covenant,
                    viewModel = viewModel,
                    onDismiss = { showSharePassDialog = false },
                )
            }
        }
    }
}

@Composable
private fun IntroBulletCard(
    title: String,
    body: String,
    backdrop: Backdrop?,
) {
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = backdrop,
        useBackdropCapture = true,
        contentPadding = PaddingValues(15.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accentInk,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = glassPrimaryContentColor(),
        )
    }
}

@Composable
private fun CopyableTechRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accentInk)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = glassPrimaryContentColor(),
                modifier = Modifier.weight(1f),
            )
            FluidGlassIconButton(
                onClick = onCopy,
                size = 34.dp,
                useBackdropCapture = false,
                lockParentGestures = false,
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Redesigned Stage 3.2 Share Pass Sheet:
 * 1. Real scannable QR code on top (compact minified JSON so it ALWAYS fits cleanly & scans fast!)
 * 2. Plain-English zero-private-key safety note
 * 3. Primary 3D button: `Save / Share .kpass File` (Android Share Sheet + SAF save option!)
 * 4. Tucked-away secondary button: `Copy Raw JSON (Advanced)` (ZERO raw JSON wall!)
 */
@Composable
private fun SharedPassExportDialog(
    covenant: TrackedCovenant,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val s = LocalWalletStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val passObj = remember(covenant) {
        val kind = when (covenant.templateId) {
            CovenantTemplateId.DEAD_MANS_SWITCH -> KineticBackupEngine.SharedPassKind.HEIR_CLAIM_PASS
            else -> KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS
        }
        KineticBackupEngine.SharedCovenantPass(
            kind = kind,
            network = covenant.network,
            templateId = covenant.templateId,
            label = covenant.label,
            covenantIdHex = covenant.covenantIdHex,
            p2shAddress = covenant.currentP2shAddress,
            redeemScriptHex = covenant.currentRedeemScriptHex,
            abiArtifact = covenant.abiArtifact,
            params = covenant.params,
            createdAtMs = 0L,
        )
    }
    val compactQrPayload = remember(passObj) { KineticBackupEngine.exportCompactSharedPassQrJson(passObj) }
    val prettyJsonPayload = remember(passObj) { KineticBackupEngine.exportSharedPassJson(passObj) }
    val defaultFilename = remember(covenant) { viewModel.defaultKpassFilename(covenant) }

    val saveKpassLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.exportKpassToUri(covenant, uri)
                    .onSuccess { name -> viewModel.showMessage(safeFormatLocalized(s.covSavedKpassMsgFmt, name)) }
                    .onFailure { err -> viewModel.showMessage(err.message ?: s.covSaveShareKpassButton) }
            }
        }
    }

    val title = when (covenant.templateId) {
        CovenantTemplateId.DEAD_MANS_SWITCH -> s.covShareHeirPassButton
        CovenantTemplateId.RECURRING_ALLOWANCE -> s.covShareAllowancePassButton
        else -> s.covExportPassButton
    }

    FluidGlassDialog(
        onDismissRequest = onDismiss,
        title = {
            MarqueeText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
            )
        },
        actions = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        accent = true,
                        useBackdropCapture = false,
                        lockParentGestures = true,
                        onClick = {
                            scope.launch {
                                viewModel.createShareableKpassCacheFile(covenant)
                                    .onSuccess { file ->
                                        runCatching {
                                            val contentUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file,
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(Intent.EXTRA_STREAM, contentUri)
                                                putExtra(Intent.EXTRA_SUBJECT, defaultFilename)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, title))
                                        }.onFailure {
                                            saveKpassLauncher.launch(defaultFilename)
                                        }
                                    }
                                    .onFailure { saveKpassLauncher.launch(defaultFilename) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(s.covSaveShareKpassButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    FluidGlassIconButton(
                        onClick = { saveKpassLauncher.launch(defaultFilename) },
                        size = 44.dp,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = s.covSaveShareKpassButton, modifier = Modifier.size(18.dp))
                    }
                }
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = false,
                    onClick = {
                        copyTextToClipboard(context, defaultFilename, prettyJsonPayload)
                        viewModel.showMessage(s.copiedToClipboard)
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    MarqueeText(s.covCopyRawJsonAdvanced, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = false,
                    onClick = onDismiss,
                ) {
                    Text(s.done, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CovenantQrCodeImage(content = compactQrPayload, sizeDp = 220)
            Text(
                text = defaultFilename,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = glassPrimaryContentColor(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = s.covSharePassSafeNote,
                style = MaterialTheme.typography.bodySmall,
                color = glassSecondaryContentColor(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
