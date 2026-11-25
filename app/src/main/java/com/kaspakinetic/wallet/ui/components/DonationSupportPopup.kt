package com.kaspakinetic.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk

const val DEVELOPER_DONATION_KASPA_ADDRESS =
    "kaspa:qp2kf6gf8py4gy7xe77gkey54vxacc7xslxm5qcwfw268wwurfqacyznv5cuk"
const val DEVELOPER_DONATION_DOT_K_ADDRESS = "funding.k"

/**
 * Every-5-launches Kinetic Aero frosted modal popup:
 * - Step 0 (unless opted out): Developer donation prompt with two copyable addresses (Kaspa L1 + dot.k)
 *   and "Don't show again" toggle.
 * - Step 1 (shown once automatically on first session): Live "Disable transparency" performance tip
 *   + X/Twitter @KaspaKinetic bug report contact.
 * - Step 2 (shown ONLY when Covenants are activated or active): Covenants `.kinetic` backup reminder
 *   with canonical 3D Kinetic Aero "Download Latest .kinetic" button.
 */
@Composable
fun DonationSupportPopup(
    hasSeenLagTipStep: Boolean,
    reduceTransparency: Boolean,
    donationOptedOut: Boolean = false,
    showCovenantsBackupReminder: Boolean = false,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onOptOutSelected: () -> Unit,
    onCovenantsReminderOptOutSelected: () -> Unit = {},
    onMarkLagTipSeen: () -> Unit,
    onDownloadKineticBackup: () -> Unit = {},
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val localReduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)

    // Snapshot sequence of steps for this modal session: 0 = Donation, 1 = Lag Tip, 2 = Covenants Backup
    val steps = remember {
        buildList {
            if (!donationOptedOut) add(0)
            if (!donationOptedOut && !hasSeenLagTipStep) add(1)
            if (showCovenantsBackupReminder) add(2)
        }.ifEmpty { listOf(0) }
    }
    var stepIdx by remember { mutableIntStateOf(0) }
    val currentStep = steps[stepIdx.coerceIn(0, steps.lastIndex)]
    val hasNextStep = stepIdx < steps.lastIndex
    var dontShowAgain by remember { mutableStateOf(false) }
    var dontShowCovenantsAgain by remember { mutableStateOf(false) }

    fun persistOptOutsIfChecked() {
        if (dontShowAgain) {
            onOptOutSelected()
        }
        if (dontShowCovenantsAgain) {
            onCovenantsReminderOptOutSelected()
        }
    }

    val cardShape = ContinuousCornerShape(18.dp)
    val cardBg = when {
        localReduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.56f)
    }
    val cardBorder = when {
        localReduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.85f)
    }

    FluidGlassDialog(
        onDismissRequest = {
            persistOptOutsIfChecked()
            onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = when (currentStep) {
                        0 -> Icons.Default.Favorite
                        1 -> Icons.Default.Tune
                        else -> Icons.Default.Backup
                    },
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(22.dp),
                )
                MarqueeText(
                    text = when (currentStep) {
                        0 -> walletStrings.supportPopupTitle
                        1 -> walletStrings.supportTipsTitle
                        else -> walletStrings.covReminderPopupTitle
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        actions = {
            if (currentStep == 2) {
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
                            persistOptOutsIfChecked()
                            onDownloadKineticBackup()
                            onDismiss()
                        },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        MarqueeText(
                            text = walletStrings.covReminderDownloadButton,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            persistOptOutsIfChecked()
                            onDismiss()
                        },
                    ) {
                        Text(walletStrings.done, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    useBackdropCapture = false,
                    lockParentGestures = true,
                    onClick = {
                        if (currentStep == 0) {
                            persistOptOutsIfChecked()
                        }
                        if (hasNextStep) {
                            val nextStep = steps[stepIdx + 1]
                            if (nextStep == 1) onMarkLagTipSeen()
                            stepIdx += 1
                        } else {
                            onDismiss()
                        }
                    },
                ) {
                    Text(
                        text = if (hasNextStep) walletStrings.supportNextButton else walletStrings.done,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        when (currentStep) {
            0 -> {
                Text(
                    text = walletStrings.supportPopupMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glassPrimaryContentColor(),
                )

                // Normal Kaspa L1 Donation Address
                DonationAddressRow(
                    label = walletStrings.supportKaspaAddressLabel,
                    address = DEVELOPER_DONATION_KASPA_ADDRESS,
                    monospace = true,
                    shape = cardShape,
                    bg = cardBg,
                    border = cardBorder,
                    onCopy = {
                        copyTextToClipboard(context, walletStrings.supportKaspaAddressLabel, DEVELOPER_DONATION_KASPA_ADDRESS)
                        onCopiedMessage(walletStrings.copiedToClipboard)
                    },
                )

                // dot.k Funding Address
                DonationAddressRow(
                    label = walletStrings.supportDotKLabel,
                    address = DEVELOPER_DONATION_DOT_K_ADDRESS,
                    monospace = false,
                    shape = cardShape,
                    bg = cardBg,
                    border = cardBorder,
                    onCopy = {
                        copyTextToClipboard(context, walletStrings.supportDotKLabel, DEVELOPER_DONATION_DOT_K_ADDRESS)
                        onCopiedMessage(walletStrings.copiedToClipboard)
                    },
                )

                // Don't show again checkbox + gentle reassurance line
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { dontShowAgain = !dontShowAgain }
                        .padding(vertical = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = glassPrimaryContentColor().copy(alpha = 0.72f),
                            ),
                        )
                        Text(
                            text = walletStrings.supportDontShowAgain,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = glassPrimaryContentColor(),
                        )
                    }
                    AnimatedVisibility(
                        visible = dontShowAgain,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Text(
                            text = walletStrings.supportWontBotherAgain,
                            style = MaterialTheme.typography.labelMedium,
                            color = accentInk,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                        )
                    }
                }
            }

            1 -> {
                // Step 2 (Shown once): Lag tip with live working Disable Transparency toggle + X/Twitter contact
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(cardBg, cardShape)
                        .border(0.85.dp, cardBorder, cardShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = walletStrings.supportLagTipPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = glassPrimaryContentColor(),
                        modifier = Modifier.weight(1f),
                    )
                    FluidGlassSwitch(
                        checked = reduceTransparency,
                        onCheckedChange = onReduceTransparencyChanged,
                    )
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(cardBg, cardShape)
                        .border(0.85.dp, cardBorder, cardShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = null,
                        tint = accentInk,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = walletStrings.supportBugContact,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            else -> {
                // Conditional Step: Covenants .kinetic Backup Reminder (ONLY when covenants activated/active and not muted)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(cardBg, cardShape)
                        .border(0.85.dp, cardBorder, cardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = walletStrings.covReminderPopupBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassPrimaryContentColor(),
                    )
                }

                // Persisted "Don't show again" toggle matching Step 0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { dontShowCovenantsAgain = !dontShowCovenantsAgain }
                        .padding(vertical = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = dontShowCovenantsAgain,
                            onCheckedChange = { dontShowCovenantsAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = glassPrimaryContentColor().copy(alpha = 0.72f),
                            ),
                        )
                        Text(
                            text = walletStrings.supportDontShowAgain,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = glassPrimaryContentColor(),
                        )
                    }
                    AnimatedVisibility(
                        visible = dontShowCovenantsAgain,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Text(
                            text = walletStrings.supportWontBotherAgain,
                            style = MaterialTheme.typography.labelMedium,
                            color = accentInk,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DonationAddressRow(
    label: String,
    address: String,
    monospace: Boolean,
    shape: ContinuousCornerShape,
    bg: Color,
    border: Color,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .border(0.85.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = address,
                style = if (monospace) {
                    MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                },
                color = glassPrimaryContentColor(),
            )
        }
        Spacer(Modifier.width(2.dp))
        FluidGlassIconButton(
            onClick = onCopy,
            size = 36.dp,
            useBackdropCapture = false,
            lockParentGestures = false,
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
