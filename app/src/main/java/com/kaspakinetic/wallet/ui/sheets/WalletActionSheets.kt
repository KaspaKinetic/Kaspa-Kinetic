package com.kaspakinetic.wallet.ui.sheets

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.model.ContactRecord
import com.kaspakinetic.wallet.core.model.FeePriority
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.NodeConnectionMode
import com.kaspakinetic.wallet.core.model.PreparedTx
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.SyncStatus
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.WalletMode
import com.kaspakinetic.wallet.core.model.WalletTx
import com.kaspakinetic.wallet.ui.components.HighlightedKaspaAddressText
import com.kaspakinetic.wallet.ui.components.KaspaQrCodeBox
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.SecureScreenEffect
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.components.readTextFromClipboard
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassBottomSheet
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSegmentedControl
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.onboarding.ErrorInlineBanner
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

// ============================================================================
// 1. Receive Sheet (QR + Highlighted Address + Optional Amount + New Address)
// ============================================================================

@Composable
fun ReceiveSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val address = walletState.currentReceiveAddress
    val isSingleWatch = walletState.activeWallet?.mode == WalletMode.WATCH_ADDRESS
    var requestAmount by remember { mutableStateOf("") }

    val qrPayload = remember(address, requestAmount) {
        val amt = requestAmount.trim().replace(",", "")
        if (amt.isNotEmpty() && KasFormat.kasStringToSompi(amt) != null) {
            "$address?amount=$amt"
        } else {
            address
        }
    }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.receiveTitle,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            KaspaQrCodeBox(data = qrPayload, sizeDp = 196.dp)
        }

        Text(
            text = walletStrings.receiveAddressLabel,
            style = MaterialTheme.typography.labelMedium,
            color = glassSecondaryContentColor(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .clickable {
                    copyTextToClipboard(context, "Kaspa Address", address)
                    viewModel.showMessage(walletStrings.copiedToClipboard)
                }
                .padding(12.dp),
        ) {
            HighlightedKaspaAddressText(address = address, fontSizeSp = 13f)
        }

        Text(
            text = walletStrings.checksumHint,
            style = MaterialTheme.typography.labelSmall,
            color = glassSecondaryContentColor(),
        )

        FluidGlassTextField(
            value = requestAmount,
            onValueChange = { requestAmount = it },
            label = walletStrings.requestAmountLabel,
            placeholder = walletStrings.requestAmountPlaceholder,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FluidGlassButton(
                modifier = Modifier.weight(1f),
                accent = true,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = {
                    copyTextToClipboard(context, "Kaspa Address", qrPayload)
                    viewModel.showMessage(walletStrings.copiedToClipboard)
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(walletStrings.copyAddressButton, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            FluidGlassIconButton(
                onClick = {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, qrPayload)
                        }
                        context.startActivity(Intent.createChooser(send, walletStrings.share))
                    }
                },
                useBackdropCapture = false,
                lockParentGestures = false,
            ) {
                Icon(Icons.Default.Share, contentDescription = walletStrings.share, modifier = Modifier.size(18.dp))
            }
        }

        if (!isSingleWatch) {
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = { viewModel.generateNewReceiveAddress() },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(walletStrings.newAddressButton, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ============================================================================
// 2. Send Sheet (Hot Wallet + Watch-Only PSKT Export + Custom UTXOs + Review)
// ============================================================================

@Composable
fun SendSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    initialRecipient: String = "",
    initialAmountKas: String = "",
    initialUseSelectedUtxos: Boolean = false,
    onRequestQrScan: (onScanned: (String) -> Unit) -> Unit,
    onRequestAuth: (onAuthorized: () -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true
    val network = walletState.activeNetwork
    val unit = if (network.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val canUseFiat = !walletState.prefs.kasOnlyPrivacyMode && walletState.spotPrice != null && !network.isTestnet

    var recipient by remember(initialRecipient) { mutableStateOf(initialRecipient) }
    var amountInput by remember(initialAmountKas) { mutableStateOf(initialAmountKas) }
    var inputInFiat by remember { mutableStateOf(false) }
    var sendAll by remember { mutableStateOf(false) }
    var feePriority by remember { mutableStateOf(FeePriority.NORMAL) }
    var useSelectedUtxos by remember(initialUseSelectedUtxos) {
        mutableStateOf(initialUseSelectedUtxos && walletState.selectedUtxos.isNotEmpty())
    }
    var showContactPicker by remember { mutableStateOf(false) }
    var preparedForReview by remember { mutableStateOf<PreparedTx?>(null) }
    var exportedPsktJson by remember { mutableStateOf<String?>(null) }
    var broadcastTxId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = when {
            broadcastTxId != null -> walletStrings.broadcastSuccessTitle
            exportedPsktJson != null -> walletStrings.psktExportedTitle
            preparedForReview != null -> walletStrings.reviewTxTitle
            else -> walletStrings.sendTitle
        },
    ) {
        when {
            broadcastTxId != null -> {
                val txId = broadcastTxId!!
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally),
                )
                Text(
                    text = txId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                )
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        accent = true,
                        useBackdropCapture = false,
                        onClick = {
                            onDismiss()
                            onInspectInExplorer(txId)
                        },
                    ) {
                        Text(walletStrings.inspectInExplorerButton, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        onClick = {
                            copyTextToClipboard(context, "Transaction ID", txId)
                            viewModel.showMessage(walletStrings.copiedToClipboard)
                        },
                    ) {
                        Text(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            exportedPsktJson != null -> {
                val pskt = exportedPsktJson!!
                Text(
                    text = pskt.take(360) + if (pskt.length > 360) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassSecondaryContentColor(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        accent = true,
                        useBackdropCapture = false,
                        onClick = {
                            copyTextToClipboard(context, "Kaspa PSKT", pskt)
                            viewModel.showMessage(walletStrings.copiedToClipboard)
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = {
                            runCatching {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TEXT, pskt)
                                }
                                context.startActivity(Intent.createChooser(send, walletStrings.exportUnsignedPsktButton))
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(walletStrings.share, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            preparedForReview != null -> {
                val tx = preparedForReview!!
                Text(
                    text = walletStrings.recipientLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
                HighlightedKaspaAddressText(address = tx.recipientAddress, fontSizeSp = 12.5f)
                HorizontalDivider(thickness = 0.6.dp, color = glassSecondaryContentColor().copy(alpha = 0.2f))
                SheetKeyValueRow(walletStrings.reviewSendAmount, "${KasFormat.formatKas(tx.amountSompi)} $unit")
                SheetKeyValueRow(walletStrings.txFeeLabel, "${KasFormat.formatKas(tx.feeSompi)} $unit")
                SheetKeyValueRow(walletStrings.reviewChangeAmount, "${KasFormat.formatKas(tx.changeSompi)} $unit")
                SheetKeyValueRow(walletStrings.reviewTotalInput, "${tx.selectedUtxos.size} UTXOs (${KasFormat.formatKas(tx.totalInputSompi)} $unit)")
                SheetKeyValueRow(walletStrings.reviewComputeMass, "${tx.massBreakdown.computeMass} / ${tx.massBreakdown.storageMass}")
                SheetKeyValueRow(walletStrings.reviewHashLabel, tx.reviewHash.take(16) + "...")

                if (!errorMsg.isNullOrBlank()) {
                    ErrorInlineBanner(errorMsg!!)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(0.42f),
                        enabled = !busy,
                        useBackdropCapture = false,
                        onClick = { preparedForReview = null; errorMsg = null },
                    ) {
                        Text(kineticStrings.back)
                    }
                    if (isWatchOnly) {
                        FluidGlassButton(
                            modifier = Modifier.weight(0.58f),
                            accent = true,
                            useBackdropCapture = false,
                            onClick = {
                                exportedPsktJson = viewModel.exportUnsignedPskt(tx)
                            },
                        ) {
                            Text(walletStrings.exportUnsignedPsktButton, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        FluidGlassButton(
                            modifier = Modifier.weight(0.58f),
                            accent = true,
                            enabled = !busy,
                            useBackdropCapture = false,
                            onClick = {
                                onRequestAuth {
                                    scope.launch {
                                        busy = true
                                        errorMsg = null
                                        viewModel.signAndBroadcast(tx)
                                            .onSuccess { txId -> broadcastTxId = txId }
                                            .onFailure { err -> errorMsg = err.message ?: "Broadcast failed" }
                                        busy = false
                                    }
                                }
                            },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(walletStrings.signAndBroadcastButton, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            else -> {
                if (isWatchOnly) {
                    val isDark = LocalGlassDarkTheme.current
                    val reduceTransparency = LocalReduceTransparency.current
                    val watchCardPreset = if (isDark) GlassPreset.HighlightSurface else GlassPreset.Card
                    val watchCardTint = when {
                        reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
                        isDark -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    }
                    val watchTitleColor = when {
                        reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                        isDark -> Color(0xFFF4FFF8)
                        else -> glassPrimaryContentColor()
                    }
                    val watchBodyColor = when {
                        reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        isDark -> Color(0xFFD6EDE0)
                        else -> glassSecondaryContentColor()
                    }
                    FluidGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ContinuousCornerShape(24.dp),
                        preset = watchCardPreset,
                        useBackdropCapture = false,
                        surfaceTint = watchCardTint,
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = walletStrings.watchOnlySendTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = watchTitleColor,
                            )
                            Text(
                                text = walletStrings.watchOnlySendBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = watchBodyColor,
                            )
                        }
                    }
                }

                // Recipient input + quick actions
                FluidGlassTextField(
                    value = recipient,
                    onValueChange = {
                        recipient = it
                        errorMsg = null
                    },
                    label = walletStrings.recipientLabel,
                    placeholder = walletStrings.recipientPlaceholder,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            readTextFromClipboard(context)?.trim()?.let { clip ->
                                val parsed = KaspaAddress.parseUriOrAddress(clip)
                                recipient = parsed?.address ?: clip
                                if (parsed?.amountKas != null) {
                                    inputInFiat = false
                                    sendAll = false
                                    amountInput = parsed.amountKas.stripTrailingZeros().toPlainString()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(walletStrings.paste, style = MaterialTheme.typography.labelSmall)
                    }
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            onRequestQrScan { scanned ->
                                val parsed = KaspaAddress.parseUriOrAddress(scanned)
                                recipient = parsed?.address ?: scanned
                                if (parsed?.amountKas != null) {
                                    inputInFiat = false
                                    sendAll = false
                                    amountInput = parsed.amountKas.stripTrailingZeros().toPlainString()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(walletStrings.scanQrButton, style = MaterialTheme.typography.labelSmall)
                    }
                    FluidGlassChip(
                        modifier = Modifier.weight(1f),
                        selected = showContactPicker,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = { showContactPicker = !showContactPicker },
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(walletStrings.pickContactButton, style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (showContactPicker && walletState.prefs.contacts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        walletState.prefs.contacts.forEach { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ContinuousCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                    .clickable {
                                        recipient = contact.address
                                        showContactPicker = false
                                        errorMsg = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(contact.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = glassPrimaryContentColor())
                                Text(contact.address.take(18) + "...", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = glassSecondaryContentColor())
                            }
                        }
                    }
                }

                // Amount input + Fiat toggle + MAX
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${walletStrings.amountLabel} (${if (inputInFiat && canUseFiat) walletState.prefs.fiatCurrency.code else unit})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canUseFiat) {
                            FluidGlassChip(
                                selected = inputInFiat,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                onClick = {
                                    inputInFiat = !inputInFiat
                                    sendAll = false
                                    amountInput = ""
                                },
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text(if (inputInFiat) unit else walletState.prefs.fiatCurrency.code, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        FluidGlassChip(
                            selected = sendAll,
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = {
                                sendAll = !sendAll
                                inputInFiat = false
                                if (sendAll) {
                                    val poolSum = if (useSelectedUtxos && walletState.selectedUtxos.isNotEmpty()) {
                                        walletState.selectedUtxos.sumOf { it.amountSompi }
                                    } else {
                                        walletState.totalBalanceSompi
                                    }
                                    amountInput = KasFormat.formatKas(poolSum)
                                }
                            },
                        ) {
                            Text(walletStrings.maxButton, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                FluidGlassTextField(
                    value = amountInput,
                    onValueChange = {
                        amountInput = it
                        sendAll = false
                        errorMsg = null
                    },
                    placeholder = "0.00",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                // Custom UTXO Selection toggle if any UTXOs are selected
                if (walletState.selectedUtxos.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = walletStrings.customUtxoSelectionLabel,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = glassPrimaryContentColor(),
                            )
                            Text(
                                text = "${walletState.selectedUtxos.size} ${walletStrings.utxoSelectedCount} (${KasFormat.formatKas(walletState.selectedUtxos.sumOf { it.amountSompi })} $unit)",
                                style = MaterialTheme.typography.labelSmall,
                                color = glassSecondaryContentColor(),
                            )
                        }
                        FluidGlassSwitch(
                            checked = useSelectedUtxos,
                            onCheckedChange = { useSelectedUtxos = it },
                        )
                    }
                }

                // Fee Priority
                Text(
                    text = walletStrings.feePriorityLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
                FluidGlassSegmentedControl(
                    items = FeePriority.entries,
                    selectedItem = feePriority,
                    onSelect = { feePriority = it },
                    labelFor = {
                        when (it) {
                            FeePriority.LOW -> walletStrings.feeLow
                            FeePriority.NORMAL -> walletStrings.feeNormal
                            FeePriority.PRIORITY -> walletStrings.feePriority
                        }
                    },
                )

                if (!errorMsg.isNullOrBlank()) {
                    ErrorInlineBanner(errorMsg!!)
                }

                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = {
                        errorMsg = null
                        val addr = recipient.trim()
                        val decoded = KaspaAddress.decode(addr)
                        if (decoded == null) {
                            errorMsg = walletStrings.invalidAddressError
                            return@FluidGlassButton
                        }
                        if (decoded.prefix != network.hrp) {
                            errorMsg = walletStrings.wrongNetworkAddressError
                            return@FluidGlassButton
                        }
                        val sompi: Long = if (sendAll) {
                            0L
                        } else if (inputInFiat && canUseFiat) {
                            val fiatVal = amountInput.trim().toDoubleOrNull()
                            val price = walletState.spotPrice?.price ?: 0.0
                            if (fiatVal == null || fiatVal <= 0.0 || price <= 0.0) {
                                errorMsg = walletStrings.enterValidAmountError
                                return@FluidGlassButton
                            }
                            ((fiatVal / price) * SOMPI_PER_KAS.toDouble()).toLong()
                        } else {
                            KasFormat.kasStringToSompi(amountInput) ?: run {
                                errorMsg = walletStrings.enterValidAmountError
                                return@FluidGlassButton
                            }
                        }

                        viewModel.prepareSendTransaction(
                            recipientAddress = addr,
                            amountSompi = sompi,
                            feePriority = feePriority,
                            sendAll = sendAll,
                            useSelectedUtxos = useSelectedUtxos,
                        ).onSuccess { tx ->
                            preparedForReview = tx
                        }.onFailure { err ->
                            errorMsg = err.message ?: walletStrings.broadcastFailedError
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(walletStrings.reviewTxButton, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================================
// 3. Transaction Detail Sheet (+ Local Note Editor + Inspect in Explorer)
// ============================================================================

@Composable
fun TxDetailSheet(
    tx: WalletTx,
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onInspectInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    var noteText by remember(tx.transactionId) { mutableStateOf(walletState.prefs.txNotes[tx.transactionId].orEmpty()) }
    val timeStr = remember(tx.blockTimeMs) {
        if (tx.blockTimeMs > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tx.blockTimeMs))
        else walletStrings.txPending
    }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.txDetailTitle,
    ) {
        val signPrefix = when (tx.direction) {
            TxDirection.RECEIVED -> "+"
            TxDirection.SENT, TxDirection.BURN, TxDirection.MINER_DONATION, TxDirection.COVENANT -> "-"
            TxDirection.COMPOUND, TxDirection.SELF_TRANSFER -> ""
        }
        val amtText = if (walletState.prefs.hideBalances) {
            "${walletStrings.maskedAmount} $unit"
        } else {
            "$signPrefix${KasFormat.formatKas(kotlin.math.abs(tx.netAmountSompi), maxDecimals = 8, grouping = true)} $unit"
        }
        SheetKeyValueRow(walletStrings.amountLabel, amtText)
        SheetKeyValueRow(walletStrings.txFeeLabel, "${KasFormat.formatKas(tx.feeSompi, maxDecimals = 8, grouping = true)} $unit")
        if (tx.mass > 0L) SheetKeyValueRow(walletStrings.txMassLabel, "${tx.mass}")
        SheetKeyValueRow(walletStrings.txTimeLabel, timeStr)
        tx.acceptingBlockBlueScore?.let { SheetKeyValueRow(walletStrings.txBlueScoreLabel, "$it") }
        SheetKeyValueRow(walletStrings.txInputsLabel, "${tx.inputs.size}")
        SheetKeyValueRow(walletStrings.txOutputsLabel, "${tx.outputs.size}")

        Text(walletStrings.txIdLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
        Text(
            text = tx.transactionId,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = glassPrimaryContentColor(),
            modifier = Modifier.clickable {
                copyTextToClipboard(context, "Transaction ID", tx.transactionId)
                viewModel.showMessage(walletStrings.copiedToClipboard)
            },
        )

        if (tx.counterpartyAddress.isNotBlank()) {
            Text(walletStrings.recipientLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
            HighlightedKaspaAddressText(address = tx.counterpartyAddress, fontSizeSp = 12f)
        }

        FluidGlassTextField(
            value = noteText,
            onValueChange = { noteText = it },
            label = walletStrings.txLocalNoteLabel,
            placeholder = walletStrings.txLocalNotePlaceholder,
            singleLine = false,
            maxLines = 3,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = {
                    onDismiss()
                    onInspectInExplorer(tx.transactionId)
                },
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                MarqueeText(walletStrings.inspectInExplorerButton, fontWeight = FontWeight.SemiBold)
            }
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = {
                    viewModel.saveTxNote(tx.transactionId, noteText)
                    viewModel.showMessage(walletStrings.saveNoteButton)
                },
            ) {
                MarqueeText(walletStrings.saveNoteButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ============================================================================
// 4. Wallet Addresses Sheet (RECEIVE / CHANGE tabs)
// ============================================================================

@Composable
fun WalletAddressesSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    var chainTab by remember { mutableStateOf(0) } // 0 = RECEIVE, 1 = CHANGE
    val list = if (chainTab == 0) walletState.receiveAddresses else walletState.changeAddresses

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.menuWalletAddresses,
    ) {
        if (walletState.activeWallet?.mode != WalletMode.WATCH_ADDRESS) {
            FluidGlassSegmentedControl(
                items = listOf(0, 1),
                selectedItem = chainTab,
                onSelect = { chainTab = it },
                labelFor = { if (it == 0) walletStrings.tabReceiveChain else walletStrings.tabChangeChain },
            )
        }

        list.forEach { item ->
            val shape = ContinuousCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), shape)
                    .clickable {
                        copyTextToClipboard(context, "Kaspa Address", item.address)
                        viewModel.showMessage(walletStrings.copiedToClipboard)
                    }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${walletStrings.addressIndexLabel} #${item.index} (${item.derivationPath})",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassSecondaryContentColor(),
                    )
                    Text(
                        text = if (walletState.prefs.hideBalances) "${walletStrings.maskedAmount} $unit" else "${KasFormat.formatKas(item.balanceSompi)} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                }
                HighlightedKaspaAddressText(address = item.address, fontSizeSp = 12f)
            }
        }

        if (walletState.activeWallet?.mode != WalletMode.WATCH_ADDRESS && chainTab == 0) {
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                onClick = { viewModel.generateNewReceiveAddress() },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(walletStrings.newAddressButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ============================================================================
// 5. Contacts Sheet (Add / Edit / Delete / QR / Send)
// ============================================================================

@Composable
fun ContactsSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onSendToContact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    var editingContact by remember { mutableStateOf<ContactRecord?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var qrContact by remember { mutableStateOf<ContactRecord?>(null) }

    var nameField by remember { mutableStateOf("") }
    var addrField by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = when {
            qrContact != null -> qrContact!!.name
            isAdding || editingContact != null -> if (editingContact != null) walletStrings.editContactTitle else walletStrings.addContactButton
            else -> walletStrings.contactsTitle
        },
    ) {
        when {
            qrContact != null -> {
                val c = qrContact!!
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    KaspaQrCodeBox(data = c.address, sizeDp = 190.dp)
                }
                HighlightedKaspaAddressText(address = c.address, fontSizeSp = 12.5f)
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = false,
                    onClick = { qrContact = null },
                ) {
                    Text(kineticStrings.back)
                }
            }

            isAdding || editingContact != null -> {
                FluidGlassTextField(
                    value = nameField,
                    onValueChange = { nameField = it; errorMsg = null },
                    label = walletStrings.contactNameLabel,
                    placeholder = walletStrings.contactNamePlaceholder,
                    singleLine = true,
                )
                FluidGlassTextField(
                    value = addrField,
                    onValueChange = { addrField = it; errorMsg = null },
                    label = walletStrings.contactAddressLabel,
                    placeholder = walletStrings.watchAddressPlaceholder,
                    singleLine = false,
                    maxLines = 3,
                )
                if (!errorMsg.isNullOrBlank()) ErrorInlineBanner(errorMsg!!)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = { isAdding = false; editingContact = null; errorMsg = null },
                    ) {
                        Text(kineticStrings.cancel)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        accent = true,
                        useBackdropCapture = false,
                        onClick = {
                            if (nameField.isBlank()) {
                                errorMsg = walletStrings.contactNamePlaceholder
                                return@FluidGlassButton
                            }
                            if (KaspaAddress.decode(addrField.trim()) == null) {
                                errorMsg = walletStrings.invalidAddressError
                                return@FluidGlassButton
                            }
                            val rec = ContactRecord(
                                id = editingContact?.id ?: UUID.randomUUID().toString(),
                                name = nameField.trim(),
                                address = addrField.trim(),
                            )
                            viewModel.saveContact(rec)
                            isAdding = false
                            editingContact = null
                        },
                    ) {
                        Text(kineticStrings.save, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            else -> {
                if (walletState.prefs.contacts.isEmpty()) {
                    Text(
                        text = walletStrings.noContactsBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassSecondaryContentColor(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                } else {
                    walletState.prefs.contacts.forEach { contact ->
                        val shape = ContinuousCornerShape(16.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = glassPrimaryContentColor(),
                                    modifier = Modifier.weight(1f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FluidGlassIconButton(
                                        onClick = { qrContact = contact },
                                        size = 32.dp,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(Icons.Default.QrCode, contentDescription = walletStrings.showQrLabel, modifier = Modifier.size(16.dp))
                                    }
                                    FluidGlassIconButton(
                                        onClick = {
                                            editingContact = contact
                                            nameField = contact.name
                                            addrField = contact.address
                                        },
                                        size = 32.dp,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    FluidGlassIconButton(
                                        onClick = { viewModel.deleteContact(contact.id) },
                                        size = 32.dp,
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            HighlightedKaspaAddressText(address = contact.address, fontSizeSp = 11.5f)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FluidGlassChip(
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = {
                                        copyTextToClipboard(context, contact.name, contact.address)
                                        viewModel.showMessage(walletStrings.copiedToClipboard)
                                    },
                                ) {
                                    Text(walletStrings.copyAddressButton, style = MaterialTheme.typography.labelSmall)
                                }
                                FluidGlassChip(
                                    selected = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = {
                                        onDismiss()
                                        onSendToContact(contact.address)
                                    },
                                ) {
                                    Text(walletStrings.actionSend, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    useBackdropCapture = false,
                    onClick = {
                        nameField = ""
                        addrField = ""
                        errorMsg = null
                        isAdding = true
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(walletStrings.addContactButton, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ============================================================================
// 6. Compound Transactions Sheet (Custom UTXO Selection + Destination Picker)
// ============================================================================

@Composable
fun CompoundSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onRequestAuth: (onAuthorized: () -> Unit) -> Unit,
    onInspectInExplorer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val scope = rememberCoroutineScope()
    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true

    var useSelectedOnly by remember { mutableStateOf(walletState.selectedUtxos.size >= 2) }
    var destinationAddress by remember { mutableStateOf(walletState.currentReceiveAddress) }
    var feePriority by remember { mutableStateOf(FeePriority.NORMAL) }
    var prepared by remember { mutableStateOf<PreparedTx?>(null) }
    var txIdDone by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val poolCount = if (useSelectedOnly && walletState.selectedUtxos.size >= 2) {
        walletState.selectedUtxos.size
    } else {
        walletState.utxos.count { !it.isCoinbase }
    }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.compoundTitle,
    ) {
        when {
            txIdDone != null -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally))
                Text(txIdDone!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = glassPrimaryContentColor())
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    useBackdropCapture = false,
                    onClick = {
                        val id = txIdDone!!
                        onDismiss()
                        onInspectInExplorer(id)
                    },
                ) {
                    Text(walletStrings.inspectInExplorerButton, fontWeight = FontWeight.SemiBold)
                }
            }

            prepared != null -> {
                val tx = prepared!!
                SheetKeyValueRow(walletStrings.txInputsLabel, "${tx.selectedUtxos.size} UTXOs")
                SheetKeyValueRow(walletStrings.reviewSendAmount, "${KasFormat.formatKas(tx.amountSompi)} $unit")
                SheetKeyValueRow(walletStrings.txFeeLabel, "${KasFormat.formatKas(tx.feeSompi)} $unit")
                SheetKeyValueRow(walletStrings.reviewComputeMass, "${tx.massBreakdown.computeMass} / ${tx.massBreakdown.storageMass}")
                HighlightedKaspaAddressText(address = tx.recipientAddress, fontSizeSp = 12f)
                if (!errorMsg.isNullOrBlank()) ErrorInlineBanner(errorMsg!!)
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    enabled = !busy && !isWatchOnly,
                    useBackdropCapture = false,
                    onClick = {
                        onRequestAuth {
                            scope.launch {
                                busy = true
                                errorMsg = null
                                viewModel.signAndBroadcast(tx)
                                    .onSuccess { txIdDone = it }
                                    .onFailure { errorMsg = it.message ?: "Compound failed" }
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(walletStrings.signAndBroadcastButton, fontWeight = FontWeight.Bold)
                }
            }

            else -> {
                Text(walletStrings.compoundSubtitle, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())

                if (isWatchOnly) {
                    ErrorInlineBanner(walletStrings.compoundWatchOnlyNotice, useBackdropCapture = false)
                }

                if (poolCount < 2) {
                    ErrorInlineBanner(walletStrings.compoundNeedTwoUtxos, useBackdropCapture = false)
                } else {
                    Text(
                        text = "$poolCount ${walletStrings.compoundSelectedSummary}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                }

                if (walletState.selectedUtxos.size >= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(walletStrings.customUtxoSelectionLabel, style = MaterialTheme.typography.bodySmall, color = glassPrimaryContentColor())
                        FluidGlassSwitch(checked = useSelectedOnly, onCheckedChange = { useSelectedOnly = it })
                    }
                }

                // Destination wallet address picker
                Text(walletStrings.compoundDestinationLabel, style = MaterialTheme.typography.labelMedium, color = glassSecondaryContentColor())
                walletState.receiveAddresses.take(4).forEach { addr ->
                    FluidGlassChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = destinationAddress == addr.address,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = { destinationAddress = addr.address },
                    ) {
                        Text(
                            text = "#${addr.index}: ${addr.address.take(26)}...${addr.address.takeLast(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }

                FluidGlassSegmentedControl(
                    items = FeePriority.entries,
                    selectedItem = feePriority,
                    onSelect = { feePriority = it },
                    labelFor = {
                        when (it) {
                            FeePriority.LOW -> walletStrings.feeLow
                            FeePriority.NORMAL -> walletStrings.feeNormal
                            FeePriority.PRIORITY -> walletStrings.feePriority
                        }
                    },
                )

                if (!errorMsg.isNullOrBlank()) ErrorInlineBanner(errorMsg!!)

                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    enabled = poolCount >= 2 && !isWatchOnly,
                    useBackdropCapture = false,
                    onClick = {
                        viewModel.prepareCompoundTransaction(
                            destinationAddress = destinationAddress,
                            feePriority = feePriority,
                            useSelectedOnly = useSelectedOnly,
                        ).onSuccess { prepared = it }
                            .onFailure { errorMsg = it.message ?: "Cannot compound" }
                    },
                ) {
                    Text(walletStrings.compoundNowButton, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================================
// 7. Address Discovery Sheet (Kaspium 20-Gap-Limit Scan)
// ============================================================================

@Composable
fun AddressDiscoverySheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.discoveryTitle,
    ) {
        Text(walletStrings.discoverySubtitle, style = MaterialTheme.typography.bodyMedium, color = glassSecondaryContentColor())

        if (walletState.isDiscovering) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Text(
                    text = "${walletStrings.scanningProgressLabel} (${walletState.discoveryStatusText ?: "0"})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = glassPrimaryContentColor(),
                )
            }
        } else if (walletState.discoveryStatusText != null) {
            SheetKeyValueRow(walletStrings.discoveryFoundAddresses, walletState.discoveryStatusText)
        }

        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            accent = true,
            enabled = !walletState.isDiscovering && walletState.activeWallet?.mode != WalletMode.WATCH_ADDRESS,
            useBackdropCapture = false,
            onClick = { viewModel.runAddressDiscovery() },
        ) {
            Text(walletStrings.startDiscoveryButton, fontWeight = FontWeight.Bold)
        }
    }
}

// ============================================================================
// 8. Extended Public Key (kpub) Sheet (FLAG_SECURE + QR + Copy)
// ============================================================================

@Composable
fun ExtendedPubKeySheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    SecureScreenEffect(enabled = true)
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val kpub = remember(walletState.activeWallet, walletState.activeNetwork) {
        viewModel.getActiveKpubForNetwork()
    }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.kpubTitle,
    ) {
        if (kpub.isNullOrBlank()) {
            Text(walletStrings.kpubUnavailableWatchAddr, style = MaterialTheme.typography.bodyMedium, color = glassSecondaryContentColor())
        } else {
            Text(walletStrings.kpubSubtitle, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                KaspaQrCodeBox(data = kpub, sizeDp = 190.dp)
            }
            Text(
                text = kpub,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = glassPrimaryContentColor(),
            )
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                onClick = {
                    copyTextToClipboard(context, "Kaspa kpub", kpub)
                    viewModel.showMessage(walletStrings.copiedToClipboard)
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(walletStrings.copyKpubButton, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================================
// 9. Transaction Report (CSV) Sheet
// ============================================================================

@Composable
fun TxReportCsvSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.csvTitle,
    ) {
        Text(walletStrings.csvSubtitle, style = MaterialTheme.typography.bodyMedium, color = glassSecondaryContentColor())
        if (walletState.transactions.isEmpty()) {
            Text(walletStrings.csvNoTxBody, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
        } else {
            SheetKeyValueRow(walletStrings.tabTransactions, "${walletState.transactions.size}")
        }
        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            accent = true,
            enabled = !exporting && walletState.transactions.isNotEmpty(),
            useBackdropCapture = false,
            onClick = {
                scope.launch {
                    exporting = true
                    viewModel.exportCsvShareIntent()
                        .onSuccess { intent -> runCatching { context.startActivity(intent) } }
                        .onFailure { viewModel.showMessage(it.message ?: "Export failed") }
                    exporting = false
                }
            },
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(walletStrings.exportCsvButton, fontWeight = FontWeight.Bold)
        }
    }
}

// ============================================================================
// 10. Sign & Verify Message Sheet (Real Kaspa KIP-5 Schnorr Sign / Verify)
// ============================================================================

@Composable
fun SignVerifyMessageSheet(
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onRequestAuth: (onAuthorized: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWatchOnly = walletState.activeWallet?.isWatchOnly == true

    var modeTab by remember { mutableStateOf(if (isWatchOnly) 1 else 0) } // 0 = Sign, 1 = Verify
    var selectedAddr by remember { mutableStateOf(walletState.currentReceiveAddress) }
    var messageInput by remember { mutableStateOf("") }
    var signatureHex by remember { mutableStateOf("") }
    var digestHex by remember { mutableStateOf("") }
    var verifyResult by remember { mutableStateOf<Boolean?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.signVerifyTitle,
    ) {
        FluidGlassSegmentedControl(
            items = listOf(0, 1),
            selectedItem = modeTab,
            onSelect = {
                modeTab = it
                verifyResult = null
                errorMsg = null
            },
            labelFor = { if (it == 0) walletStrings.tabSignMessage else walletStrings.tabVerifyMessage },
        )

        if (modeTab == 0) {
            if (isWatchOnly) {
                ErrorInlineBanner(walletStrings.signMsgWatchOnlyNotice, useBackdropCapture = false)
            }
            Text(walletStrings.signingAddressLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
            HighlightedKaspaAddressText(address = selectedAddr, fontSizeSp = 12f)
            FluidGlassTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                label = walletStrings.messageInputLabel,
                placeholder = walletStrings.messageInputPlaceholder,
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = !isWatchOnly && messageInput.isNotEmpty(),
                useBackdropCapture = false,
                onClick = {
                    onRequestAuth {
                        scope.launch {
                            errorMsg = null
                            viewModel.signPersonalMessage(selectedAddr, messageInput)
                                .onSuccess { (sig, digest) ->
                                    signatureHex = sig
                                    digestHex = digest
                                }
                                .onFailure { errorMsg = it.message ?: "Signing failed" }
                        }
                    }
                },
            ) {
                Text(walletStrings.signMessageButton, fontWeight = FontWeight.Bold)
            }

            if (signatureHex.isNotEmpty()) {
                Text(walletStrings.personalMessageHashLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                Text(digestHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = glassPrimaryContentColor())
                Text(walletStrings.signatureInputLabel, style = MaterialTheme.typography.labelSmall, color = glassSecondaryContentColor())
                Text(signatureHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = glassPrimaryContentColor())
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = false,
                    onClick = {
                        copyTextToClipboard(context, "Kaspa Signature", signatureHex)
                        viewModel.showMessage(walletStrings.copiedToClipboard)
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            FluidGlassTextField(
                value = selectedAddr,
                onValueChange = { selectedAddr = it; verifyResult = null },
                label = walletStrings.signingAddressLabel,
                placeholder = walletStrings.watchAddressPlaceholder,
                singleLine = false,
                maxLines = 2,
            )
            FluidGlassTextField(
                value = messageInput,
                onValueChange = { messageInput = it; verifyResult = null },
                label = walletStrings.messageInputLabel,
                placeholder = walletStrings.messageInputPlaceholder,
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
            FluidGlassTextField(
                value = signatureHex,
                onValueChange = { signatureHex = it; verifyResult = null },
                label = walletStrings.signatureInputLabel,
                placeholder = walletStrings.signatureInputPlaceholder,
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = selectedAddr.isNotBlank() && signatureHex.isNotBlank(),
                useBackdropCapture = false,
                onClick = {
                    verifyResult = viewModel.verifyPersonalMessage(selectedAddr, messageInput, signatureHex)
                },
            ) {
                Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(walletStrings.verifyMessageButton, fontWeight = FontWeight.Bold)
            }

            if (verifyResult != null) {
                val ok = verifyResult!!
                val tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousCornerShape(14.dp))
                        .background(tint.copy(alpha = 0.14f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = tint,
                    )
                    Text(
                        text = if (ok) walletStrings.signatureValidBanner else walletStrings.signatureInvalidBanner,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                    )
                }
            }
        }

        if (!errorMsg.isNullOrBlank()) ErrorInlineBanner(errorMsg!!)
    }
}

// ============================================================================
// 11. Live Node & Sync Status Sheet (Opened by Top-Bar Circular Status Dot)
// ============================================================================

@Composable
fun NodeStatusSheet(
    walletState: WalletUiState,
    liveNodeHealth: com.kaspakinetic.wallet.core.model.NodeHealth? = null,
    onForceSync: () -> Unit,
    onOpenNodeSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val health = liveNodeHealth ?: walletState.nodeHealth
    val cfg = walletState.prefs.nodeConfig
    val dotColor = when (walletState.syncStatus) {
        SyncStatus.SYNCED -> Color(0xFF2ECC71)
        SyncStatus.CONNECTING -> Color(0xFFF39C12)
        SyncStatus.OFFLINE -> MaterialTheme.colorScheme.error
    }
    val statusLabel = when (walletState.syncStatus) {
        SyncStatus.SYNCED -> walletStrings.statusSynced
        SyncStatus.CONNECTING -> walletStrings.statusConnecting
        SyncStatus.OFFLINE -> walletStrings.statusOffline
    }

    FluidGlassBottomSheet(
        onDismissRequest = onDismiss,
        title = walletStrings.nodeStatusTitle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousCornerShape(16.dp))
                .background(dotColor.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(dotColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
                Text(
                    text = if (cfg.network.isTestnet) walletStrings.testnet10Label else walletStrings.mainnetLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = glassSecondaryContentColor(),
                )
            }
            if (walletState.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        if (health != null) {
            SheetKeyValueRow(
                walletStrings.daaScoreLabel,
                if (health.virtualDaaScore > 0L) String.format(java.util.Locale.US, "%,d", health.virtualDaaScore) else "—",
            )
            val protoTag = if (health.isTls) walletStrings.nodeTransportTlsTag else walletStrings.nodeTransportPlaintextTag
            val alreadyTagged = health.transportUsed.contains("TLS", ignoreCase = true) ||
                health.transportUsed.contains("Plaintext", ignoreCase = true) ||
                health.transportUsed.contains("HTTPS", ignoreCase = true) ||
                health.transportUsed.contains("REST", ignoreCase = true)
            val transportDisplay = if (alreadyTagged) {
                health.transportUsed
            } else {
                "${health.transportUsed} ($protoTag)"
            }
            SheetKeyValueRow(walletStrings.nodeTransportLabel, transportDisplay)
            if (cfg.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                SheetKeyValueRow(
                    walletStrings.nodeActivePoolLabel,
                    "${health.activePoolCount} ${walletStrings.nodePoolNodesUnit}",
                )
            }
            SheetKeyValueRow(walletStrings.latencyMsLabel, "${health.latencyMs} ms")
            SheetKeyValueRow(
                walletStrings.utxoIndexReady.substringBefore(":"),
                if (health.isUtxoIndexed) walletStrings.yes else walletStrings.no,
            )
        }
        val displayedEndpoint = health?.activeEndpoint?.takeIf { it.isNotBlank() }?.let { ep ->
            val isRest = health.transportUsed.contains("REST", ignoreCase = true)
            val tag = when {
                isRest && health.isTls -> "HTTPS"
                isRest -> "HTTP"
                health.isTls -> walletStrings.nodeTransportTlsTag
                else -> walletStrings.nodeTransportPlaintextTag
            }
            "$ep ($tag)"
        } ?: cfg.effectiveGrpcDisplay
        SheetKeyValueRow(walletStrings.nodeEndpointLabel, displayedEndpoint)

        if (!walletState.lastSyncError.isNullOrBlank()) {
            ErrorInlineBanner(walletState.lastSyncError, useBackdropCapture = false)
        }

        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                onClick = onForceSync,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(walletStrings.nodeForceSyncButton, fontWeight = FontWeight.Bold)
            }
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                onClick = {
                    onDismiss()
                    onOpenNodeSettings()
                },
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(walletStrings.nodeOpenSettingsButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SheetKeyValueRow(label: String, value: String) {
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
            modifier = Modifier.weight(0.42f, fill = false),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = glassPrimaryContentColor(),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f, fill = false),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
