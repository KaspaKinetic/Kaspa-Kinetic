package com.kaspakinetic.wallet.ui.covenants

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor
import com.kaspakinetic.wallet.core.covenants.CovenantCompiler
import com.kaspakinetic.wallet.core.covenants.CovenantStatus
import com.kaspakinetic.wallet.core.covenants.CovenantTemplateId
import com.kaspakinetic.wallet.core.covenants.TimeLockStage
import com.kaspakinetic.wallet.core.covenants.TrackedCovenant
import kotlinx.coroutines.delay
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.formatFilterChipLabel
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.kineticInteractiveRow
import com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.locale.safeFormatLocalized
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CovenantListFilter {
    ACTIVE,
    SETTLED,
    SHARED,
    ALL,
}

fun filterCovenantsByStatus(
    covenants: List<TrackedCovenant>,
    filter: CovenantListFilter,
): List<TrackedCovenant> = when (filter) {
    CovenantListFilter.ACTIVE -> covenants.filter { !it.isSharedPassImport && it.liveStatus != CovenantStatus.SETTLED }
    CovenantListFilter.SETTLED -> covenants.filter { !it.isSharedPassImport && it.liveStatus == CovenantStatus.SETTLED }
    CovenantListFilter.SHARED -> covenants.filter { it.isSharedPassImport }
    CovenantListFilter.ALL -> covenants
}

fun covenantTemplateIcon(id: CovenantTemplateId): ImageVector = when (id) {
    CovenantTemplateId.CLAWBACK_VAULT -> Icons.Default.Security
    CovenantTemplateId.DEAD_MANS_SWITCH -> Icons.Default.HourglassBottom
    CovenantTemplateId.DAILY_SPEND_LIMIT -> Icons.Default.Speed
    CovenantTemplateId.COLD_WHITELIST -> Icons.Default.VerifiedUser
    CovenantTemplateId.HODL_TIMELOCK -> Icons.Default.LockClock
    CovenantTemplateId.GOAL_PIGGY_BANK -> Icons.Default.Savings
    CovenantTemplateId.RECURRING_ALLOWANCE -> Icons.Default.Repeat
}

fun covenantTemplateTitle(id: CovenantTemplateId, s: KaspaWalletStrings): String = when (id) {
    CovenantTemplateId.CLAWBACK_VAULT -> s.covClawbackTitle
    CovenantTemplateId.DEAD_MANS_SWITCH -> s.covDeadManTitle
    CovenantTemplateId.DAILY_SPEND_LIMIT -> s.covDailyLimitTitle
    CovenantTemplateId.COLD_WHITELIST -> s.covColdWhitelistTitle
    CovenantTemplateId.HODL_TIMELOCK -> s.covHodlLockTitle
    CovenantTemplateId.GOAL_PIGGY_BANK -> s.covGoalPiggyTitle
    CovenantTemplateId.RECURRING_ALLOWANCE -> s.covRecurringTitle
}

fun covenantTemplateDesc(id: CovenantTemplateId, s: KaspaWalletStrings): String = when (id) {
    CovenantTemplateId.CLAWBACK_VAULT -> s.covClawbackDesc
    CovenantTemplateId.DEAD_MANS_SWITCH -> s.covDeadManDesc
    CovenantTemplateId.DAILY_SPEND_LIMIT -> s.covDailyLimitDesc
    CovenantTemplateId.COLD_WHITELIST -> s.covColdWhitelistDesc
    CovenantTemplateId.HODL_TIMELOCK -> s.covHodlLockDesc
    CovenantTemplateId.GOAL_PIGGY_BANK -> s.covGoalPiggyDesc
    CovenantTemplateId.RECURRING_ALLOWANCE -> s.covRecurringDesc
}

fun covenantStatusLabel(status: CovenantStatus, s: KaspaWalletStrings): String = when (status) {
    CovenantStatus.ACTIVE -> s.covStatusActive
    CovenantStatus.UNVAULTING -> s.covStatusUnvaulting
    CovenantStatus.READY -> s.covStatusReady
    CovenantStatus.SETTLED -> s.covStatusSettled
    CovenantStatus.UNCONFIRMED -> s.covStatusUnconfirmed
}

/** Sentinel ceiling (~100 years in ms): anything above this represents an uninitialized countdown. */
const val MAX_SANE_COUNTDOWN_MS: Long = 36_500L * 86_400_000L

/**
 * UI-only guard preventing uninitialized / sentinel `Long.MAX_VALUE` countdowns from flashing
 * (`106751991167d 7h`) before the initial gRPC UTXO sync populates `liveUtxoTxId` & `liveUtxoBlockDaaScore`.
 */
fun isCountdownSyncingOrSentinel(
    covenant: TrackedCovenant,
    remainingMs: Long,
    requiresUtxoBlockDaa: Boolean = true,
): Boolean {
    if (remainingMs >= Long.MAX_VALUE / 2L || remainingMs > MAX_SANE_COUNTDOWN_MS) return true
    if (covenant.liveUtxoTxId.isNullOrBlank()) return true
    if (requiresUtxoBlockDaa && covenant.liveUtxoBlockDaaScore <= 0L) return true
    return false
}

fun formatDaaDurationShort(
    daaDelta: Long,
    readyLabel: String = "Ready",
    syncingLabel: String = "Syncing...",
): String {
    if (daaDelta <= 0L) return readyLabel
    val secs = daaDelta / CovenantCompiler.DAA_BLOCKS_PER_SECOND
    if (secs > 36_500L * 86_400L) return syncingLabel
    return formatSecondsShort(secs, readyLabel)
}

fun formatDurationMsShort(
    ms: Long,
    readyLabel: String = "Ready",
    syncingLabel: String = "Syncing...",
): String {
    if (ms <= 0L) return readyLabel
    if (ms >= Long.MAX_VALUE / 2L || ms > MAX_SANE_COUNTDOWN_MS) return syncingLabel
    return formatSecondsShort(ms / 1_000L, readyLabel)
}

private fun formatSecondsShort(secs: Long, readyLabel: String): String {
    if (secs <= 0L) return readyLabel
    val days = secs / 86_400L
    val hours = (secs % 86_400L) / 3_600L
    val mins = (secs % 3_600L) / 60L
    val remSecs = secs % 60L
    return when {
        days >= 1L -> "${days}d ${hours}h"
        hours >= 1L -> "${hours}h ${mins}m"
        mins >= 1L -> "${mins}m ${remSecs}s"
        else -> "${secs}s"
    }
}

fun formatShortTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
}

fun formatFullDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()).format(Date(epochMs))
}

fun formatCovenantQuickStatus(
    cov: TrackedCovenant,
    virtualDaa: Long,
    hideBalances: Boolean,
    masked: String,
    unit: String,
    s: KaspaWalletStrings = KaspaWalletStrings(),
    chainTimeAnchor: ChainTimeAnchor = ChainTimeAnchor(),
    nowElapsedRealtimeMs: Long = 0L,
    isConfirming: Boolean = false,
): String {
    if (isConfirming) {
        return s.covConfirmingOnL1
    }
    if (cov.liveStatus == CovenantStatus.SETTLED) {
        return s.covQuickSettled
    }
    val ageDaa = if (virtualDaa > 0L && cov.liveUtxoBlockDaaScore > 0L) {
        (virtualDaa - cov.liveUtxoBlockDaaScore).coerceAtLeast(0L)
    } else {
        0L
    }
    return when (cov.templateId) {
        CovenantTemplateId.CLAWBACK_VAULT -> {
            if (cov.params.vaultStage == 1) {
                if (cov.liveUtxoTxId.isNullOrBlank() || cov.liveUtxoBlockDaaScore <= 0L) {
                    s.covSyncingStatus
                } else if (chainTimeAnchor.hasLiveVirtualDaa) {
                    val eval = chainTimeAnchor.evaluateRelativeDaaLock(
                        utxoBlockDaaScore = cov.liveUtxoBlockDaaScore,
                        requiredDaa = cov.params.unvaultDelayDaa,
                        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    )
                    when {
                        eval.stage == TimeLockStage.READY -> s.covQuickUnvaultReady
                        eval.stage == TimeLockStage.FINALIZING -> s.covFinalizingOnL1
                        isCountdownSyncingOrSentinel(cov, eval.extrapolatedRemainingMs) -> s.covSyncingStatus
                        else -> safeFormatLocalized(
                            s.covQuickUnvaultLeftFmt,
                            formatDurationMsShort(eval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus),
                        )
                    }
                } else {
                    val rem = (cov.params.unvaultDelayDaa - ageDaa).coerceAtLeast(0L)
                    if (rem <= 0L) s.covQuickUnvaultReady
                    else safeFormatLocalized(s.covQuickUnvaultLeftFmt, formatDaaDurationShort(rem, s.covStatusReady, s.covSyncingStatus))
                }
            } else {
                safeFormatLocalized(s.covQuickVaultedDelayFmt, formatDaaDurationShort(cov.params.unvaultDelayDaa, s.covStatusReady, s.covSyncingStatus))
            }
        }
        CovenantTemplateId.DEAD_MANS_SWITCH -> {
            if (CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(cov.params)) {
                val eval = chainTimeAnchor.evaluateTimeLock(cov.params.inactivityDeadlineMs, nowElapsedRealtimeMs)
                when {
                    cov.isSharedPassImport && cov.liveUtxoTxId.isNullOrBlank() -> s.covSyncingStatus
                    eval.stage == TimeLockStage.READY -> s.covQuickHeirReady
                    eval.stage == TimeLockStage.FINALIZING -> s.covFinalizingOnL1
                    isCountdownSyncingOrSentinel(cov, eval.extrapolatedRemainingMs, requiresUtxoBlockDaa = false) -> s.covSyncingStatus
                    else -> safeFormatLocalized(
                        s.covQuickHeirTimerFmt,
                        formatDurationMsShort(eval.extrapolatedRemainingMs, s.covStatusReady, s.covSyncingStatus),
                    )
                }
            } else if (cov.liveUtxoTxId.isNullOrBlank() || cov.liveUtxoBlockDaaScore <= 0L) {
                s.covSyncingStatus
            } else if (chainTimeAnchor.hasLiveVirtualDaa) {
                val eval = chainTimeAnchor.evaluateRelativeDaaLock(
                    utxoBlockDaaScore = cov.liveUtxoBlockDaaScore,
                    requiredDaa = cov.params.inactivityDaa,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                )
                when {
                    eval.stage == TimeLockStage.READY -> s.covQuickHeirReady
                    eval.stage == TimeLockStage.FINALIZING -> s.covFinalizingOnL1
                    isCountdownSyncingOrSentinel(cov, eval.extrapolatedRemainingMs) -> s.covSyncingStatus
                    else -> safeFormatLocalized(
                        s.covQuickHeirTimerFmt,
                        formatDurationMsShort(eval.extrapolatedRemainingMs.coerceAtLeast(1_000L), s.covStatusReady, s.covSyncingStatus),
                    )
                }
            } else {
                val rem = (cov.params.inactivityDaa - ageDaa).coerceAtLeast(0L)
                if (rem <= 0L) s.covQuickHeirReady
                else safeFormatLocalized(s.covQuickHeirTimerFmt, formatDaaDurationShort(rem, s.covStatusReady, s.covSyncingStatus))
            }
        }
        CovenantTemplateId.DAILY_SPEND_LIMIT -> {
            val capStr = if (hideBalances) masked else KasFormat.formatKasSummary(cov.params.dailyCapSompi)
            val windowStr = formatDaaDurationShort(cov.params.windowDaa, s.covStatusReady)
            safeFormatLocalized(s.covQuickLimitFmt, capStr, unit, windowStr)
        }
        CovenantTemplateId.COLD_WHITELIST -> {
            val n = cov.params.whitelistAddresses.size.coerceAtLeast(1)
            safeFormatLocalized(s.covQuickWhitelistFmt, n)
        }
        CovenantTemplateId.HODL_TIMELOCK -> {
            if (cov.params.unlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                val eval = chainTimeAnchor.evaluateTimeLock(cov.params.unlockTimeMs, nowElapsedRealtimeMs)
                when (eval.stage) {
                    TimeLockStage.READY -> s.covQuickUnlockedReady
                    TimeLockStage.FINALIZING -> s.covFinalizingOnL1
                    TimeLockStage.LOCKED -> safeFormatLocalized(
                        s.covQuickUnlocksInFmt,
                        formatDurationMsShort(eval.extrapolatedRemainingMs, s.covStatusReady),
                    )
                }
            } else {
                val rem = if (virtualDaa > 0L) (cov.params.unlockDaa - virtualDaa).coerceAtLeast(0L) else 0L
                when {
                    virtualDaa > 0L && rem <= 0L -> s.covQuickUnlockedReady
                    rem > 0L -> safeFormatLocalized(s.covQuickUnlocksInFmt, formatDaaDurationShort(rem, s.covStatusReady))
                    cov.params.unlockDateIso.isNotBlank() -> safeFormatLocalized(s.covQuickUnlocksOnFmt, cov.params.unlockDateIso)
                    else -> s.covQuickTimeLocked
                }
            }
        }
        CovenantTemplateId.GOAL_PIGGY_BANK -> {
            val pct = if (cov.params.targetGoalSompi > 0L) {
                ((cov.liveBalanceSompi * 100L) / cov.params.targetGoalSompi).coerceIn(0L, 100L)
            } else 0L
            val targetStr = if (hideBalances) masked else KasFormat.formatKasSummary(cov.params.targetGoalSompi)
            if (hideBalances) s.covQuickGoalProgress
            else safeFormatLocalized(s.covQuickGoalPctFmt, pct.toInt(), targetStr, unit)
        }
        CovenantTemplateId.RECURRING_ALLOWANCE -> {
            val amtStr = if (hideBalances) masked else KasFormat.formatKasSummary(cov.params.allowanceSompi)
            val cycleStr = if (CovenantCompiler.isKeylessTimeBasedSubscription(cov.params)) {
                formatDurationMsShort(cov.params.intervalMs, s.covStatusReady)
            } else {
                formatDaaDurationShort(cov.params.periodDaa, s.covStatusReady)
            }
            safeFormatLocalized(s.covQuickAllowanceFmt, amtStr, unit, cycleStr)
        }
    }
}

@Composable
fun CovenantFilterChipsRow(
    selected: CovenantListFilter,
    onSelect: (CovenantListFilter) -> Unit,
    activeCount: Int,
    settledCount: Int,
    allCount: Int,
    sharedCount: Int = 0,
    showSharedPill: Boolean = false,
    hideBalances: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val s = LocalWalletStrings.current
    val visibleFilters = remember(showSharedPill) {
        if (showSharedPill) {
            listOf(
                CovenantListFilter.ACTIVE,
                CovenantListFilter.SETTLED,
                CovenantListFilter.SHARED,
                CovenantListFilter.ALL,
            )
        } else {
            listOf(
                CovenantListFilter.ACTIVE,
                CovenantListFilter.SETTLED,
                CovenantListFilter.ALL,
            )
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visibleFilters.forEach { f ->
            val label = when (f) {
                CovenantListFilter.ACTIVE -> formatFilterChipLabel(s.covFilterActive, activeCount, hideBalances)
                CovenantListFilter.SETTLED -> formatFilterChipLabel(s.covFilterSettled, settledCount, hideBalances)
                CovenantListFilter.SHARED -> formatFilterChipLabel(s.covFilterShared, sharedCount, hideBalances)
                CovenantListFilter.ALL -> formatFilterChipLabel(s.covFilterAll, allCount, hideBalances)
            }
            FluidGlassChip(
                selected = selected == f,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = { onSelect(f) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun CovenantWatchOnlyBannerCard(text: String) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val cardPreset = if (isDark) GlassPreset.HighlightSurface else GlassPreset.Card
    val cardTint = when {
        reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
        isDark -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }
    val ink = when {
        reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
        isDark -> Color(0xFFF4FFF8)
        else -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
    }
    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousCornerShape(20.dp),
        preset = cardPreset,
        useBackdropCapture = false,
        surfaceTint = cardTint,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = ink,
            )
        }
    }
}

@Composable
fun TrackedCovenantRowCard(
    covenant: TrackedCovenant,
    virtualDaa: Long,
    hideBalances: Boolean,
    kasOnlyPrivacyMode: Boolean,
    spotPrice: SpotPrice?,
    onClick: () -> Unit,
    chainTimeAnchor: ChainTimeAnchor = ChainTimeAnchor(),
    nowElapsedRealtimeMs: Long = 0L,
    isConfirming: Boolean = false,
) {
    val s = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val unit = if (covenant.network.isTestnet) s.tkasUnit else s.kasUnit
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
    val balText = if (hideBalances) {
        "${s.maskedAmount} $unit"
    } else {
        "${KasFormat.formatKasSummary(covenant.liveBalanceSompi)} $unit"
    }
    val fiatText = if (!hideBalances && !kasOnlyPrivacyMode && !covenant.network.isTestnet && spotPrice != null) {
        KasFormat.formatFiat(covenant.liveBalanceSompi, spotPrice)
    } else {
        null
    }
    val templateTitle = covenantTemplateTitle(covenant.templateId, s)
    val quickStatus = formatCovenantQuickStatus(
        cov = covenant,
        virtualDaa = virtualDaa,
        hideBalances = hideBalances,
        masked = s.maskedAmount,
        unit = unit,
        s = s,
        chainTimeAnchor = chainTimeAnchor,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        isConfirming = isConfirming,
    )
    // Flip status pill to READY ONLY when real non-extrapolated gRPC server pastMedianTime crosses the lock!
    val effectiveStatus = when {
        isConfirming -> CovenantStatus.UNCONFIRMED
        covenant.liveStatus == CovenantStatus.ACTIVE && (
            (covenant.templateId == CovenantTemplateId.HODL_TIMELOCK &&
                chainTimeAnchor.isServerTimeLockReady(covenant.params.unlockTimeMs)) ||
                (covenant.templateId == CovenantTemplateId.DEAD_MANS_SWITCH &&
                    CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(covenant.params) &&
                    chainTimeAnchor.isServerTimeLockReady(covenant.params.inactivityDeadlineMs)) ||
                (covenant.templateId == CovenantTemplateId.RECURRING_ALLOWANCE &&
                    CovenantCompiler.isKeylessTimeBasedSubscription(covenant.params) &&
                    chainTimeAnchor.isServerTimeLockReady(covenant.params.nextDueTimeMs))
            ) -> CovenantStatus.READY
        else -> covenant.liveStatus
    }
    // Omit repeating the template name in the subtitle when the row title already matches it!
    val subtitleText = if (covenant.label.trim().equals(templateTitle.trim(), ignoreCase = true) ||
        covenant.label.trim().equals(covenant.templateId.contractName, ignoreCase = true)
    ) {
        quickStatus
    } else {
        "$templateTitle • $quickStatus"
    }

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
                imageVector = covenantTemplateIcon(covenant.templateId),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MarqueeText(
                    text = covenant.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (covenant.network.isTestnet) {
                    Text(
                        text = "TN10",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            MarqueeText(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = glassSecondaryContentColor(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = balText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = glassPrimaryContentColor(),
            )
            if (fiatText != null) {
                Text(
                    text = fiatText,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
            }
            Text(
                text = covenantStatusLabel(effectiveStatus, s),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (effectiveStatus) {
                    CovenantStatus.READY -> accent
                    CovenantStatus.UNVAULTING, CovenantStatus.UNCONFIRMED -> Color(0xFFF39C12)
                    CovenantStatus.SETTLED -> glassSecondaryContentColor()
                    else -> accent
                },
            )
        }
    }
}

/**
 * Home Screen `Covenants` Tab (View-Only Active List):
 * - When Covenants are NOT Activated Yet: shows calm frosted card pointing to Explore drawer.
 * - When Covenants ARE Activated:
 *   1. Pinned **`Open Covenants Hub`** canonical 3D button at the **TOP** so it is always 1 tap away.
 *   2. Horizontal filter chips (`Active` [default], `Settled`, `All`) matching Home `Transactions`.
 *   3. Tracked covenant rows with zero-blur `Modifier.kineticInteractiveRow` + `MarqueeText`.
 */
@Composable
fun HomeCovenantsTabContent(
    covenantsActivated: Boolean,
    covenants: List<TrackedCovenant>,
    activeNetwork: KaspaNetwork,
    virtualDaa: Long,
    hideBalances: Boolean,
    kasOnlyPrivacyMode: Boolean,
    spotPrice: SpotPrice?,
    onOpenCovenantsHub: () -> Unit,
    onSelectCovenant: (String) -> Unit,
    activeWalletId: String? = null,
    chainTimeAnchor: ChainTimeAnchor = ChainTimeAnchor(),
    confirmingCovenantKeys: Set<String> = emptySet(),
    onPollServerAnchor: (suspend () -> Unit)? = null,
) {
    val s = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val backdrop = LocalGlassBackdrop.current
    var filter by remember { mutableStateOf(CovenantListFilter.ACTIVE) }
    var tickElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            tickElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    val liveDaa = chainTimeAnchor.estimateVirtualDaaScore(tickElapsedMs).takeIf { it > 0L } ?: virtualDaa

    if (!covenantsActivated) {
        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            backdrop = backdrop,
            useBackdropCapture = true,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(ContinuousCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = s.covHomeInactiveMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = glassPrimaryContentColor(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    // Identical single-source-of-truth filter as Covenants Hub -> Yours + Shared!
    val ownedCovenants = remember(covenants, activeNetwork, activeWalletId) {
        CovenantCompiler.ownedCovenantsForNetwork(covenants, activeNetwork, activeWalletId)
    }
    val sharedCovenants = remember(covenants, activeNetwork, activeWalletId) {
        CovenantCompiler.sharedCovenantsForNetwork(covenants, activeNetwork, activeWalletId)
    }
    val combinedNetworkCovenants = remember(ownedCovenants, sharedCovenants) {
        ownedCovenants + sharedCovenants
    }
    val needsFastFinalityPoll = combinedNetworkCovenants.any { chainTimeAnchor.isCovenantFinalizing(it, tickElapsedMs) }
    LaunchedEffect(needsFastFinalityPoll) {
        if (needsFastFinalityPoll && onPollServerAnchor != null) {
            while (true) {
                onPollServerAnchor()
                delay(2_000L)
            }
        }
    }
    val activeCount = remember(ownedCovenants) { ownedCovenants.count { it.liveStatus != CovenantStatus.SETTLED } }
    val settledCount = remember(ownedCovenants) { ownedCovenants.count { it.liveStatus == CovenantStatus.SETTLED } }
    val sharedCount = sharedCovenants.size
    val filtered = remember(combinedNetworkCovenants, filter) {
        filterCovenantsByStatus(combinedNetworkCovenants, filter)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Open Covenants Hub ALWAYS at the TOP so users never have to scroll past long lists!
        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            accent = true,
            useBackdropCapture = true,
            backdrop = backdrop,
            lockParentGestures = true,
            onClick = onOpenCovenantsHub,
        ) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            MarqueeText(
                text = s.covHomeOpenHubButton,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // 2. Horizontal filter chips matching Home Transactions tab (Active | Settled | Shared | All)
        if (combinedNetworkCovenants.isNotEmpty()) {
            CovenantFilterChipsRow(
                selected = filter,
                onSelect = { filter = it },
                activeCount = activeCount,
                settledCount = settledCount,
                sharedCount = sharedCount,
                allCount = combinedNetworkCovenants.size,
                showSharedPill = true,
                hideBalances = hideBalances,
            )
        }

        // 3. Covenant list or clean empty state
        if (filtered.isEmpty()) {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                useBackdropCapture = true,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        modifier = Modifier.size(28.dp),
                    )
                    val emptyTitle = when {
                        combinedNetworkCovenants.isEmpty() -> s.covHomeEmptyTitle
                        filter == CovenantListFilter.SHARED -> s.covSharedEmptyTitle
                        filter == CovenantListFilter.SETTLED -> "${s.covFilterSettled}: 0"
                        else -> "${s.covFilterActive}: 0"
                    }
                    val emptyBody = if (filter == CovenantListFilter.SHARED) {
                        s.covSharedEmptyBody
                    } else {
                        s.covHomeEmptyBody
                    }
                    Text(
                        text = emptyTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = emptyBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            filtered.forEach { cov ->
                TrackedCovenantRowCard(
                    covenant = cov,
                    virtualDaa = liveDaa,
                    hideBalances = hideBalances,
                    kasOnlyPrivacyMode = kasOnlyPrivacyMode,
                    spotPrice = spotPrice,
                    onClick = { onSelectCovenant(cov.canonicalKey) },
                    chainTimeAnchor = chainTimeAnchor,
                    nowElapsedRealtimeMs = tickElapsedMs,
                    isConfirming = cov.canonicalKey in confirmingCovenantKeys,
                )
            }
        }
    }
}

/**
 * Generates a crisp high-contrast QR code bitmap for Shareable Passes.
 */
@Composable
fun CovenantQrCodeImage(
    content: String,
    sizeDp: Int = 220,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) {
        runCatching {
            val px = 480
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            for (x in 0 until px) {
                for (y in 0 until px) {
                    bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }
    if (bitmap != null) {
        Box(
            modifier = modifier
                .size(sizeDp.dp)
                .clip(ContinuousCornerShape(18.dp))
                .background(Color.White)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size((sizeDp - 20).dp),
            )
        }
    }
}
