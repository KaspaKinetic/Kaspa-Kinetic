package com.kaspakinetic.wallet.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.R
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.ui.components.HighlightedKaspaAddressText
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.explore.ExplorePlaceholderKind
import com.kaspakinetic.wallet.ui.explore.StageBadgePill
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidSprings
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalOverlayBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.fluidTouchPhysics
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import com.kaspakinetic.wallet.ui.glass.rememberFluidMotionState
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class DrawerToolAction {
    WALLET_ADDRESSES,
    CONTACTS,
    COMPOUND,
    ADDRESS_DISCOVERY,
    EXTENDED_PUB_KEY,
    TX_REPORT_CSV,
    SIGN_VERIFY_MESSAGE,
}

@Composable
fun WalletDrawerOverlay(
    visible: Boolean,
    walletState: WalletUiState,
    onDismiss: () -> Unit,
    onSelectWallet: (String) -> Unit,
    onRenameWallet: (String, String) -> Unit,
    onDeleteWallet: (String) -> Unit,
    onAddWallet: () -> Unit,
    onCopyAddress: (String) -> Unit,
    onOpenTool: (DrawerToolAction) -> Unit,
    onOpenExplore: (ExplorePlaceholderKind) -> Unit,
    onOpenBurnKaspa: () -> Unit,
    onOpenDonateMiners: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            shouldRender = true
            progress.animateTo(1f, FluidSprings.sheetExpand())
        } else if (shouldRender) {
            progress.animateTo(0f, FluidSprings.menuClose())
            shouldRender = false
        }
    }

    if (!shouldRender && !visible) return

    BackHandler(enabled = visible) { onDismiss() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val backdrop = if (reduceTransparency) null else (LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current)
    val density = LocalDensity.current

    val drawerWidthDp = 316.dp
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }
    val p = progress.value.coerceIn(0f, 1.04f)
    val offsetX = (-drawerWidthPx * (1f - p)).roundToInt()
    val unit = if (walletState.activeNetwork.isTestnet) walletStrings.tkasUnit else walletStrings.kasUnit
    val activeWallet = walletState.activeWallet
    val hideBalances = walletState.prefs.hideBalances
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember(activeWallet?.id, activeWallet?.name) {
        mutableStateOf(activeWallet?.name.orEmpty())
    }

    fun closeAndRun(action: () -> Unit) {
        scope.launch {
            onDismiss()
            action()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(52f),
    ) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (if (isDark) 0.56f else 0.34f) * p.coerceIn(0f, 1f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        val drawerShape = ContinuousCornerShape(
            topStart = 0.dp,
            topEnd = 30.dp,
            bottomStart = 0.dp,
            bottomEnd = 30.dp,
        )
        val dividerColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX, 0) }
                .width(drawerWidthDp)
                .fillMaxHeight()
                .liquidGlassSurface(
                    shape = drawerShape,
                    preset = GlassPreset.Drawer,
                    backdrop = backdrop,
                    shadowEnabled = true,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Active Wallet Header (wallet name + edit/rename button + close button — no app logo/brand clutter)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = activeWallet?.name ?: walletStrings.walletNameDefault,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    if (activeWallet?.isWatchOnly == true) {
                        Text(
                            text = walletStrings.watchOnlyBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        )
                    }
                }
                if (activeWallet != null) {
                    FluidGlassIconButton(
                        onClick = {
                            renameInput = activeWallet.name
                            showRenameDialog = true
                        },
                        size = 34.dp,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = walletStrings.renameWalletTitle, modifier = Modifier.size(16.dp))
                    }
                }
                FluidGlassIconButton(
                    onClick = onDismiss,
                    size = 34.dp,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                ) {
                    Icon(Icons.Default.Close, contentDescription = kineticStrings.close, modifier = Modifier.size(16.dp))
                }
            }

            // Copyable Active Receive Address
            if (walletState.currentReceiveAddress.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.09f))
                        .clickable {
                            copyTextToClipboard(context, "Kaspa Address", walletState.currentReceiveAddress)
                            onCopyAddress(walletState.currentReceiveAddress)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HighlightedKaspaAddressText(
                        address = walletState.currentReceiveAddress,
                        maxLines = 1,
                        fontSizeSp = 11.5f,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = walletStrings.copyAddressButton,
                        tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 0.6.dp, color = dividerColor)
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Wallets Switcher
                DrawerSectionLabel(walletStrings.drawerWalletsSection)
                walletState.prefs.wallets.forEach { w ->
                    val selected = w.id == activeWallet?.id
                    val bal = if (selected) walletState.totalBalanceSompi else (walletState.walletBalances[w.id] ?: 0L)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ContinuousCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f)
                                else Color.Transparent,
                            )
                            .clickable {
                                onSelectWallet(w.id)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = if (selected) readableAccentInk(MaterialTheme.colorScheme.primary, isDark) else glassSecondaryContentColor(),
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = w.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = glassPrimaryContentColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val balStr = if (hideBalances) "${walletStrings.maskedAmount} $unit" else "${KasFormat.formatKasSummary(bal)} $unit"
                            Text(
                                text = balStr + if (w.isWatchOnly) " • ${walletStrings.watchOnlyBadge}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = glassSecondaryContentColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (walletState.prefs.wallets.size > 1) {
                            FluidGlassIconButton(
                                onClick = { onDeleteWallet(w.id) },
                                size = 28.dp,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }

                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = { closeAndRun(onAddWallet) },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(walletStrings.addWalletButton, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

                // Core Wallet Tools (All 7)
                DrawerSectionLabel(walletStrings.drawerToolsSection)
                DrawerNavItem(Icons.AutoMirrored.Filled.ListAlt, walletStrings.menuWalletAddresses) {
                    closeAndRun { onOpenTool(DrawerToolAction.WALLET_ADDRESSES) }
                }
                DrawerNavItem(Icons.Default.Contacts, walletStrings.menuContacts) {
                    closeAndRun { onOpenTool(DrawerToolAction.CONTACTS) }
                }
                DrawerNavItem(Icons.AutoMirrored.Filled.MergeType, walletStrings.menuCompoundUtxos) {
                    closeAndRun { onOpenTool(DrawerToolAction.COMPOUND) }
                }
                DrawerNavItem(Icons.Default.TravelExplore, walletStrings.menuAddressDiscovery) {
                    closeAndRun { onOpenTool(DrawerToolAction.ADDRESS_DISCOVERY) }
                }
                DrawerNavItem(Icons.Default.Key, walletStrings.menuExtendedPubKey) {
                    closeAndRun { onOpenTool(DrawerToolAction.EXTENDED_PUB_KEY) }
                }
                DrawerNavItem(Icons.Default.Description, walletStrings.menuTxReportCsv) {
                    closeAndRun { onOpenTool(DrawerToolAction.TX_REPORT_CSV) }
                }
                DrawerNavItem(Icons.Default.Draw, walletStrings.menuSignVerifyMsg) {
                    closeAndRun { onOpenTool(DrawerToolAction.SIGN_VERIFY_MESSAGE) }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

                // Explore (Stage 2 — Live Native Suite)
                DrawerSectionLabel(walletStrings.drawerExploreSection)
                DrawerNavItem(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    label = walletStrings.yourPerformanceTitle,
                ) {
                    closeAndRun { onOpenExplore(ExplorePlaceholderKind.YOUR_PERFORMANCE) }
                }
                DrawerNavItem(
                    icon = Icons.Default.Search,
                    label = walletStrings.menuBlockExplorer,
                ) {
                    closeAndRun { onOpenExplore(ExplorePlaceholderKind.BLOCK_EXPLORER) }
                }
                DrawerNavItem(
                    icon = Icons.Default.Analytics,
                    label = walletStrings.menuNetworkStats,
                ) {
                    closeAndRun { onOpenExplore(ExplorePlaceholderKind.NETWORK_STATS) }
                }
                DrawerNavItem(
                    icon = Icons.Default.Hub,
                    label = walletStrings.menuBlockDagVisualizer,
                ) {
                    closeAndRun { onOpenExplore(ExplorePlaceholderKind.BLOCKDAG_VISUALIZER) }
                }
                DrawerNavItem(
                    icon = Icons.Default.Security,
                    label = walletStrings.menuCovenants,
                ) {
                    closeAndRun { onOpenExplore(ExplorePlaceholderKind.COVENANTS) }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

                // Fun stuff (FULLY WORKING in v1!)
                DrawerSectionLabel(walletStrings.drawerFunStuffSection)
                DrawerNavItem(
                    icon = Icons.Default.LocalFireDepartment,
                    label = walletStrings.menuBurnKaspa,
                    iconTint = MaterialTheme.colorScheme.error,
                ) {
                    closeAndRun(onOpenBurnKaspa)
                }
                DrawerNavItem(
                    icon = Icons.Default.Memory,
                    label = walletStrings.menuDonateMiners,
                ) {
                    closeAndRun(onOpenDonateMiners)
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(thickness = 0.6.dp, color = dividerColor)

                // Preferences -> Settings
                DrawerSectionLabel(walletStrings.drawerSettingsSection)
                DrawerNavItem(
                    icon = Icons.Default.Settings,
                    label = walletStrings.menuSettings,
                ) {
                    closeAndRun(onOpenSettings)
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // Rename Active Wallet Dialog
        if (showRenameDialog && activeWallet != null) {
            FluidGlassDialog(
                onDismissRequest = { showRenameDialog = false },
                title = {
                    Text(
                        text = walletStrings.renameWalletTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                },
                actions = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidGlassButton(
                            modifier = Modifier.weight(1f),
                            useBackdropCapture = false,
                            onClick = { showRenameDialog = false },
                        ) {
                            Text(kineticStrings.cancel)
                        }
                        FluidGlassButton(
                            modifier = Modifier.weight(1f),
                            accent = true,
                            enabled = renameInput.isNotBlank(),
                            useBackdropCapture = false,
                            onClick = {
                                onRenameWallet(activeWallet.id, renameInput.trim())
                                showRenameDialog = false
                            },
                        ) {
                            Text(kineticStrings.save, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            ) {
                FluidGlassTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    placeholder = walletStrings.renameWalletPlaceholder,
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    val isDark = LocalGlassDarkTheme.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val motion = rememberFluidMotionState()
    val tint = iconTint ?: readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fluidTouchPhysics(state = motion, pressedScale = 0.97f, lockParentGestures = false)
            .clip(ContinuousCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        MarqueeText(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = glassPrimaryContentColor(),
            modifier = Modifier.weight(1f),
        )
        if (!badge.isNullOrBlank()) {
            StageBadgePill(text = badge)
        }
    }
}
