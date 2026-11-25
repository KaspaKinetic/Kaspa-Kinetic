package com.kaspakinetic.wallet.ui.covenants

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaspakinetic.wallet.core.covenants.CovenantCompiler
import com.kaspakinetic.wallet.core.covenants.CovenantTemplateId
import com.kaspakinetic.wallet.core.covenants.OnChainCovenantProof
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CovenantIntroGuide(
    val analogy: String,
    val whatItDoes: String,
    val howYouUseIt: String,
    val howYouGetKasBack: String,
    val realWorldScenario: String,
)

fun covenantIntroGuideFor(
    templateId: CovenantTemplateId,
    s: KaspaWalletStrings = KaspaWalletStrings(),
): CovenantIntroGuide = when (templateId) {
    CovenantTemplateId.DAILY_SPEND_LIMIT -> CovenantIntroGuide(
        analogy = s.covIntroDailyAnalogy,
        whatItDoes = s.covIntroDailyWhat,
        howYouUseIt = s.covIntroDailyHow,
        howYouGetKasBack = s.covIntroDailyBack,
        realWorldScenario = s.covIntroDailyScenario,
    )
    CovenantTemplateId.CLAWBACK_VAULT -> CovenantIntroGuide(
        analogy = s.covIntroClawbackAnalogy,
        whatItDoes = s.covIntroClawbackWhat,
        howYouUseIt = s.covIntroClawbackHow,
        howYouGetKasBack = s.covIntroClawbackBack,
        realWorldScenario = s.covIntroClawbackScenario,
    )
    CovenantTemplateId.DEAD_MANS_SWITCH -> CovenantIntroGuide(
        analogy = s.covIntroDeadManAnalogy,
        whatItDoes = s.covIntroDeadManWhat,
        howYouUseIt = s.covIntroDeadManHow,
        howYouGetKasBack = s.covIntroDeadManBack,
        realWorldScenario = s.covIntroDeadManScenario,
    )
    CovenantTemplateId.COLD_WHITELIST -> CovenantIntroGuide(
        analogy = s.covIntroWhitelistAnalogy,
        whatItDoes = s.covIntroWhitelistWhat,
        howYouUseIt = s.covIntroWhitelistHow,
        howYouGetKasBack = s.covIntroWhitelistBack,
        realWorldScenario = s.covIntroWhitelistScenario,
    )
    CovenantTemplateId.HODL_TIMELOCK -> CovenantIntroGuide(
        analogy = s.covIntroHodlAnalogy,
        whatItDoes = s.covIntroHodlWhat,
        howYouUseIt = s.covIntroHodlHow,
        howYouGetKasBack = s.covIntroHodlBack,
        realWorldScenario = s.covIntroHodlScenario,
    )
    CovenantTemplateId.GOAL_PIGGY_BANK -> CovenantIntroGuide(
        analogy = s.covIntroPiggyAnalogy,
        whatItDoes = s.covIntroPiggyWhat,
        howYouUseIt = s.covIntroPiggyHow,
        howYouGetKasBack = s.covIntroPiggyBack,
        realWorldScenario = s.covIntroPiggyScenario,
    )
    CovenantTemplateId.RECURRING_ALLOWANCE -> CovenantIntroGuide(
        analogy = s.covIntroRecurringAnalogy,
        whatItDoes = s.covIntroRecurringWhat,
        howYouUseIt = s.covIntroRecurringHow,
        howYouGetKasBack = s.covIntroRecurringBack,
        realWorldScenario = s.covIntroRecurringScenario,
    )
}

data class DurationMsPreset(
    val label: String,
    val durationMs: Long,
)

/**
 * Reusable Kinetic Aero **Blank-by-Default Address Input** with canonical 3D **`Use My Own Fresh Address`**
 * button (deriving a brand-new unused HD receive address every tap), clean network-aware placeholder
 * (`kaspa:q...` / `kaspatest:q...`), and optional QR scanner button.
 */
@Composable
fun CovenantAddressInputField(
    questionTitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    onUseFreshMyAddress: () -> Unit,
    onScanQr: (() -> Unit)? = null,
    isTestnet: Boolean = false,
    placeholder: String? = null,
) {
    val s = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val networkPlaceholder = placeholder ?: if (isTestnet) "kaspatest:q..." else "kaspa:q..."

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (questionTitle.isNotBlank()) {
            Text(
                text = questionTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accentInk,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluidGlassTextField(
                modifier = Modifier.weight(1f),
                value = value,
                onValueChange = onValueChange,
                placeholder = networkPlaceholder,
                singleLine = true,
                nestedInGlassCard = true,
            )
            if (onScanQr != null) {
                FluidGlassIconButton(
                    onClick = onScanQr,
                    size = 42.dp,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = s.covImportScanQrButton, modifier = Modifier.size(18.dp))
                }
            }
        }
        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = false,
            lockParentGestures = false,
            onClick = onUseFreshMyAddress,
        ) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            MarqueeText(
                text = s.covUseFreshAddressButton,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Reusable Kinetic Aero **Native `tx.time` Unix Timestamp & Duration Picker** — displays real
 * calendar dates, times, and human-readable durations ONLY (zero `~X blocks` predictions!).
 * Anchors relative durations to live consensus [basePastMedianTimeMs] so countdowns unlock right at 0s
 * with zero `"transaction input #0 is not finalized"` rejections!
 */
@Composable
fun CovenantTimestampPicker(
    questionTitle: String,
    presets: List<DurationMsPreset>,
    selectedDurationMs: Long,
    onTargetChanged: (durationMs: Long, targetEpochMs: Long, dateLabel: String) -> Unit,
    allowDateTimePicker: Boolean = true,
    maxDurationMs: Long = CovenantCompiler.MAX_HODL_LOCK_MS,
    basePastMedianTimeMs: Long = System.currentTimeMillis(),
) {
    val s = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

    val matchesPreset = presets.any { it.durationMs == selectedDurationMs }
    var customMode by remember { mutableStateOf(!matchesPreset) }
    var customUnit by remember { mutableStateOf(CustomDurationUnit.MINUTES) }
    var customValueText by remember { mutableStateOf("2") }
    var pickedEpochMs by remember { mutableStateOf<Long?>(null) }

    fun applyCustomNumber(raw: String, unit: CustomDurationUnit) {
        val n = raw.trim().toLongOrNull()?.coerceAtLeast(1L) ?: return
        val ms = when (unit) {
            CustomDurationUnit.MINUTES -> n * 60_000L
            CustomDurationUnit.HOURS -> n * 3_600_000L
            CustomDurationUnit.DAYS -> n * 86_400_000L
        }.coerceIn(60_000L, maxDurationMs)
        val target = basePastMedianTimeMs + ms
        pickedEpochMs = null
        onTargetChanged(ms, target, formatFullDateTime(target))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = questionTitle,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accentInk,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { p ->
                val isSelected = !customMode && selectedDurationMs == p.durationMs
                FluidGlassChip(
                    selected = isSelected,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = {
                        customMode = false
                        pickedEpochMs = null
                        val target = basePastMedianTimeMs + p.durationMs
                        onTargetChanged(p.durationMs, target, formatFullDateTime(target))
                    },
                ) {
                    Text(p.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            FluidGlassChip(
                selected = customMode,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = {
                    customMode = true
                    applyCustomNumber(customValueText, customUnit)
                },
            ) {
                Text(s.covCustomDurationLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(visible = customMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FluidGlassTextField(
                        modifier = Modifier.weight(0.9f),
                        value = customValueText,
                        onValueChange = { next ->
                            val clean = next.filter { it.isDigit() }.take(5)
                            customValueText = clean
                            applyCustomNumber(clean, customUnit)
                        },
                        placeholder = "2",
                        singleLine = true,
                        nestedInGlassCard = true,
                    )
                    CustomDurationUnit.entries.forEach { u ->
                        val unitLabel = when (u) {
                            CustomDurationUnit.MINUTES -> s.covMinutesUnit
                            CustomDurationUnit.HOURS -> s.covHoursUnit
                            CustomDurationUnit.DAYS -> s.covDaysUnit
                        }
                        FluidGlassChip(
                            selected = customUnit == u,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                customUnit = u
                                applyCustomNumber(customValueText, u)
                            },
                        ) {
                            Text(unitLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (allowDateTimePicker) {
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            val nowCal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            val chosen = Calendar.getInstance().apply {
                                                set(year, month, day, hour, minute, 0)
                                            }
                                            val diffMs = (chosen.timeInMillis - basePastMedianTimeMs)
                                                .coerceIn(60_000L, maxDurationMs)
                                            val targetMs = basePastMedianTimeMs + diffMs
                                            pickedEpochMs = targetMs
                                            onTargetChanged(diffMs, targetMs, formatFullDateTime(targetMs))
                                        },
                                        nowCal.get(Calendar.HOUR_OF_DAY),
                                        (nowCal.get(Calendar.MINUTE) + 2) % 60,
                                        true,
                                    ).show()
                                },
                                nowCal.get(Calendar.YEAR),
                                nowCal.get(Calendar.MONTH),
                                nowCal.get(Calendar.DAY_OF_MONTH),
                            ).apply {
                                datePicker.minDate = System.currentTimeMillis() - 60_000L
                            }.show()
                        },
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        val label = pickedEpochMs?.let { "${s.covPickDateTimeLabel}: ${formatFullDateTime(it)}" }
                            ?: s.covPickDateTimeLabel
                        MarqueeText(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        val displayEpoch = pickedEpochMs ?: (basePastMedianTimeMs + selectedDurationMs)
        Text(
            text = "${formatFullDateTime(displayEpoch)} (${formatDurationMsShort(selectedDurationMs, s.covStatusReady)})",
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
        )
    }
}

enum class CustomDurationUnit {
    MINUTES,
    HOURS,
    DAYS,
}

data class DurationPreset(
    val label: String,
    val daaBlocks: Long,
)

/**
 * Reusable Kinetic Aero Duration & Date/Time Picker for all 6 time-based covenants:
 * - Quick preset chips (e.g., `24h`, `3d`, `7d` / `30d`, `90d`, `365d`)
 * - `Custom` chip opening exact Minutes / Hours / Days input (down to 1 minute for fast testing!)
 *   AND native Android Date + Time picker (`Pick Date & Time`).
 */
@Composable
fun CovenantDurationPicker(
    questionTitle: String,
    presets: List<DurationPreset>,
    selectedDaa: Long,
    onDaaChanged: (Long, String?) -> Unit,
    allowDateTimePicker: Boolean = true,
    maxDaa: Long = CovenantCompiler.MAX_HODL_LOCK_DAA,
) {
    val s = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

    val matchesPreset = presets.any { it.daaBlocks == selectedDaa }
    var customMode by remember { mutableStateOf(!matchesPreset) }
    var customUnit by remember { mutableStateOf(CustomDurationUnit.MINUTES) }
    var customValueText by remember { mutableStateOf("2") }
    var pickedDateLabel by remember { mutableStateOf<String?>(null) }

    fun applyCustomNumber(raw: String, unit: CustomDurationUnit) {
        val n = raw.trim().toLongOrNull()?.coerceAtLeast(1L) ?: return
        val daa = when (unit) {
            CustomDurationUnit.MINUTES -> n * CovenantCompiler.DAA_PER_MINUTE
            CustomDurationUnit.HOURS -> n * CovenantCompiler.DAA_PER_HOUR
            CustomDurationUnit.DAYS -> n * CovenantCompiler.DAA_PER_DAY
        }.coerceIn(CovenantCompiler.DAA_PER_MINUTE, maxDaa)
        pickedDateLabel = null
        onDaaChanged(daa, null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = questionTitle,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accentInk,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { p ->
                val isSelected = !customMode && selectedDaa == p.daaBlocks
                FluidGlassChip(
                    selected = isSelected,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = {
                        customMode = false
                        pickedDateLabel = null
                        onDaaChanged(p.daaBlocks, null)
                    },
                ) {
                    Text(p.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            FluidGlassChip(
                selected = customMode,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = {
                    customMode = true
                    applyCustomNumber(customValueText, customUnit)
                },
            ) {
                Text(s.covCustomDurationLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(visible = customMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FluidGlassTextField(
                        modifier = Modifier.weight(0.9f),
                        value = customValueText,
                        onValueChange = { next ->
                            val clean = next.filter { it.isDigit() }.take(5)
                            customValueText = clean
                            applyCustomNumber(clean, customUnit)
                        },
                        placeholder = "2",
                        singleLine = true,
                        nestedInGlassCard = true,
                    )
                    CustomDurationUnit.entries.forEach { u ->
                        val unitLabel = when (u) {
                            CustomDurationUnit.MINUTES -> s.covMinutesUnit
                            CustomDurationUnit.HOURS -> s.covHoursUnit
                            CustomDurationUnit.DAYS -> s.covDaysUnit
                        }
                        FluidGlassChip(
                            selected = customUnit == u,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                customUnit = u
                                applyCustomNumber(customValueText, u)
                            },
                        ) {
                            Text(unitLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (allowDateTimePicker) {
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            val nowCal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            val chosen = Calendar.getInstance().apply {
                                                set(year, month, day, hour, minute, 0)
                                            }
                                            val diffSecs = ((chosen.timeInMillis - System.currentTimeMillis()) / 1_000L)
                                                .coerceAtLeast(60L)
                                            val daa = (diffSecs * CovenantCompiler.DAA_BLOCKS_PER_SECOND)
                                                .coerceIn(CovenantCompiler.DAA_PER_MINUTE, maxDaa)
                                            val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                                .format(Date(chosen.timeInMillis))
                                            pickedDateLabel = iso
                                            onDaaChanged(daa, iso)
                                        },
                                        nowCal.get(Calendar.HOUR_OF_DAY),
                                        (nowCal.get(Calendar.MINUTE) + 2) % 60,
                                        true,
                                    ).show()
                                },
                                nowCal.get(Calendar.YEAR),
                                nowCal.get(Calendar.MONTH),
                                nowCal.get(Calendar.DAY_OF_MONTH),
                            ).apply {
                                datePicker.minDate = System.currentTimeMillis() - 60_000L
                            }.show()
                        },
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(
                            text = pickedDateLabel?.let { "${s.covPickDateTimeLabel}: $it" } ?: s.covPickDateTimeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        val summaryLabel = pickedDateLabel?.let { "$it (${formatDaaDurationShort(selectedDaa, s.covStatusReady)})" }
            ?: formatDaaDurationShort(selectedDaa, s.covStatusReady)
        Text(
            text = summaryLabel,
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
        )
    }
}

/**
 * Live "Verify On-Chain" Cryptographic Proof Dialog displayed from every Covenant Detail screen.
 */
@Composable
fun CovenantVerifyOnChainDialog(
    proof: OnChainCovenantProof,
    hideBalances: Boolean,
    onInspectInExplorer: (String) -> Unit,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val unit = if (proof.covenant.network.isTestnet) s.tkasUnit else s.kasUnit

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    FluidGlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(22.dp),
                )
                MarqueeText(
                    text = s.covVerifyOnChainTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        actions = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    useBackdropCapture = false,
                    lockParentGestures = true,
                    onClick = {
                        onDismiss()
                        onInspectInExplorer(proof.latestTxId.ifBlank { proof.p2shAddress })
                    },
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    MarqueeText(s.explorerViewInExplorerButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = { openExternalUrl(proof.kaspaStreamUrl) },
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        MarqueeText(s.covViewOnKaspaStream, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = { openExternalUrl(proof.explorerKaspaOrgUrl) },
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        MarqueeText(s.covViewOnExplorerKaspaOrg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Live UTXO Status Badge + Local Cryptographic Hash Verification Badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.20f else 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (proof.isLiveOnChain) s.covProofLiveBadge else s.covProofSettledBadge,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentInk,
                    )
                    Text(
                        text = "${proof.nodeTransport} • ${if (proof.covenant.network.isTestnet) s.covTestnetBadge else s.covMainnetBadge}",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousCornerShape(14.dp))
                    .background(
                        if (proof.scriptHashVerified) {
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.14f else 0.08f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (proof.scriptHashVerified) Icons.Default.Verified else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (proof.scriptHashVerified) accentInk else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = if (proof.scriptHashVerified) s.covProofScriptVerified else s.covProofScriptMismatch,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
            }

            // 2. Balance & Block DAA
            val balDisplay = if (hideBalances) "${s.maskedAmount} $unit"
            else "${KasFormat.formatKas(proof.confirmedBalanceSompi)} $unit"
            ProofCopyableField(
                label = s.covProofBalanceLabel,
                value = balDisplay,
                monospace = true,
                onCopy = null,
            )
            ProofCopyableField(
                label = s.covProofBlockDaaLabel,
                value = if (proof.confirmedBlockDaaScore > 0L) {
                    String.format(Locale.US, "%,d", proof.confirmedBlockDaaScore)
                } else {
                    s.covStatusUnconfirmed
                },
                monospace = true,
                onCopy = null,
            )

            // 3. TxID, P2SH Address, and KIP-20 Covenant ID
            if (proof.latestTxId.isNotBlank()) {
                ProofCopyableField(
                    label = s.covProofLatestTxLabel,
                    value = proof.latestTxId,
                    monospace = true,
                    onCopy = {
                        copyTextToClipboard(context, s.covProofLatestTxLabel, proof.latestTxId)
                        onCopiedMessage(s.copiedToClipboard)
                    },
                )
            }
            ProofCopyableField(
                label = s.covP2shAddressLabel,
                value = proof.p2shAddress,
                monospace = true,
                onCopy = {
                    copyTextToClipboard(context, s.covP2shAddressLabel, proof.p2shAddress)
                    onCopiedMessage(s.copiedToClipboard)
                },
            )
            if (!proof.covenantIdHex.isNullOrBlank()) {
                ProofCopyableField(
                    label = s.covCovenantIdLabel,
                    value = proof.covenantIdHex,
                    monospace = true,
                    onCopy = {
                        copyTextToClipboard(context, s.covCovenantIdLabel, proof.covenantIdHex)
                        onCopiedMessage(s.copiedToClipboard)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProofCopyableField(
    label: String,
    value: String,
    monospace: Boolean,
    onCopy: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = glassSecondaryContentColor(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = glassPrimaryContentColor(),
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null) {
                FluidGlassIconButton(
                    onClick = onCopy,
                    size = 30.dp,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
