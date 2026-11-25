package com.kaspakinetic.wallet.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.BuildConfig
import com.kaspakinetic.wallet.core.model.AutoLockTimeout
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.data.preferences.ColorPalette
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import com.kaspakinetic.wallet.ui.components.FullScreenPinOverlay
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.PinFlowMode
import com.kaspakinetic.wallet.ui.components.RecoverySeedGrid3Col
import com.kaspakinetic.wallet.ui.components.ScreenshotDetectorEffect
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidOptionGridSelector
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.kineticInteractiveRow
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.onboarding.NodeConfigEditorBody
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch

/**
 * Kaspa Kinetic Settings screen built directly on top of the canonical [KineticAeroSettingsScreen]
 * so it inherits the exact single-pass blurred wallpaper / solid reduced-transparency canvas,
 * flat SettingsGroupSection rhythm, Appearance studio, Language picker, and Replay Welcome Setup.
 */
@Composable
fun KaspaWalletSettingsScreen(
    appearance: KineticAeroAppearanceSettings,
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onColorPaletteChanged: (ColorPalette) -> Unit,
    onCustomAccentColorChanged: (Long) -> Unit,
    onWallpaperPathChanged: (String?) -> Unit,
    onWallpaperBrightnessChanged: (Float) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onDisableProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onAppLanguageChanged: (String) -> Unit,
    onReplayOnboarding: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    BackHandler { onNavigateBack() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val scope = rememberCoroutineScope()
    val bioAvailable = remember(context) { SecureVault.isBiometricAvailable(context) }

    var activePinMode by remember { mutableStateOf<PinFlowMode?>(null) }
    var pendingPostPinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSeedDialog by remember { mutableStateOf<List<String>?>(null) }
    var showWalletPasswordDialog by remember { mutableStateOf(false) }
    var showAboutAppDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var testingNode by remember { mutableStateOf(false) }
    var nodeTestHealth by remember { mutableStateOf<NodeHealth?>(null) }

    fun openSeedReveal() {
        scope.launch {
            viewModel.revealActiveSeedPhrase()
                .onSuccess { phrase -> showSeedDialog = phrase.split(' ') }
                .onFailure { viewModel.showMessage(it.message ?: "Unable to reveal recovery phrase") }
        }
    }

    Box(modifier = Modifier.fillMaxSize().zIndex(62f)) {
        KineticAeroSettingsScreen(
            appearance = appearance,
            onThemeModeChanged = onThemeModeChanged,
            onColorPaletteChanged = onColorPaletteChanged,
            onCustomAccentColorChanged = onCustomAccentColorChanged,
            onWallpaperPathChanged = onWallpaperPathChanged,
            onWallpaperBrightnessChanged = onWallpaperBrightnessChanged,
            onFontScaleChanged = onFontScaleChanged,
            onReduceTransparencyChanged = onReduceTransparencyChanged,
            onDisableProgressiveBlurChanged = onDisableProgressiveBlurChanged,
            onHapticsChanged = onHapticsChanged,
            onAppLanguageChanged = onAppLanguageChanged,
            onNavigateBack = onNavigateBack,
            onReplayOnboarding = onReplayOnboarding,
            extraSections = {
                // 1. Currency & Privacy
                item(key = "kaspa_currency_privacy") {
                    SettingsGroupSection(title = walletStrings.settingsCurrencySection) {
                        KineticSettingSwitchRow(
                            title = walletStrings.kasOnlyPrivacyTitle,
                            subtitle = walletStrings.kasOnlyPrivacyDesc,
                            checked = walletState.prefs.kasOnlyPrivacyMode,
                            onCheckedChange = { viewModel.setKasOnlyPrivacyMode(it) },
                        )
                        if (!walletState.prefs.kasOnlyPrivacyMode) {
                            FluidOptionGridSelector(
                                title = walletStrings.displayCurrencyLabel,
                                items = FiatCurrency.entries,
                                selectedItem = walletState.prefs.fiatCurrency,
                                onSelect = { viewModel.setFiatCurrency(it) },
                                labelFor = { "${it.code} (${it.symbol})" },
                            )
                        }
                    }
                }

                // 2. Security (PIN Create / Change / Working Remove PIN, Biometrics, Auto-Lock, Password, Seed Reveal)
                item(key = "kaspa_security") {
                    SettingsGroupSection(title = walletStrings.settingsSecuritySection) {
                        if (walletState.isHardwareBackedVault) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = walletStrings.hardwareSecurityActive,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = !walletState.hasPin,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                onClick = {
                                    activePinMode = if (walletState.hasPin) PinFlowMode.CHANGE else PinFlowMode.CREATE
                                },
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                MarqueeText(
                                    text = if (walletState.hasPin) walletStrings.changePinButton else walletStrings.configurePinButton,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (walletState.hasPin) {
                                FluidGlassButton(
                                    modifier = Modifier.weight(1f),
                                    destructive = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = { activePinMode = PinFlowMode.REMOVE },
                                ) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    MarqueeText(walletStrings.removePinButton, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        KineticSettingSwitchRow(
                            title = walletStrings.biometricsTitle,
                            subtitle = if (bioAvailable) walletStrings.biometricsDesc else walletStrings.biometricsUnavailable,
                            checked = walletState.prefs.biometricsEnabled && bioAvailable,
                            enabled = bioAvailable,
                            onCheckedChange = { viewModel.setBiometricsEnabled(it) },
                        )

                        KineticSettingSwitchRow(
                            title = walletStrings.settingsAuthOnLaunch,
                            subtitle = walletStrings.settingsAuthOnLaunchDesc,
                            checked = walletState.prefs.authOnLaunch,
                            enabled = walletState.hasPin || (walletState.prefs.biometricsEnabled && bioAvailable),
                            onCheckedChange = { viewModel.setAuthOnLaunch(it) },
                        )

                        FluidOptionGridSelector(
                            title = walletStrings.settingsAutoLockLabel,
                            items = AutoLockTimeout.entries,
                            selectedItem = walletState.prefs.autoLockTimeout,
                            onSelect = { viewModel.setAutoLockTimeout(it) },
                            labelFor = {
                                when (it) {
                                    AutoLockTimeout.IMMEDIATE -> walletStrings.autoLockImmediate
                                    AutoLockTimeout.ONE_MINUTE -> walletStrings.autoLock1Min
                                    AutoLockTimeout.FIVE_MINUTES -> walletStrings.autoLock5Min
                                    AutoLockTimeout.FIFTEEN_MINUTES -> walletStrings.autoLock15Min
                                    AutoLockTimeout.NEVER -> walletStrings.autoLockNever
                                }
                            },
                        )

                        // Optional Wallet Password
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { showWalletPasswordDialog = true },
                        ) {
                            Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (walletState.hasWalletPassword) {
                                    "${walletStrings.settingsWalletPassword}: ${walletStrings.settingsRemovePassword}"
                                } else {
                                    "${walletStrings.settingsWalletPassword}: ${walletStrings.settingsSetPassword}"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Show Recovery Phrase (Hot wallets only, gated by PIN when set)
                        if (walletState.activeWallet?.isWatchOnly == false) {
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                useBackdropCapture = false,
                                lockParentGestures = false,
                                onClick = {
                                    if (walletState.hasPin) {
                                        pendingPostPinAction = { openSeedReveal() }
                                        activePinMode = PinFlowMode.VERIFY
                                    } else {
                                        openSeedReveal()
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(walletStrings.settingsShowSeed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // 3. Network & Node
                item(key = "kaspa_network_node") {
                    SettingsGroupSection(title = walletStrings.settingsNetworkSection) {
                        NodeConfigEditorBody(
                            nodeConfig = walletState.prefs.nodeConfig,
                            onNodeConfigChange = {
                                nodeTestHealth = null
                                viewModel.setNodeConfig(it)
                            },
                            testing = testingNode,
                            lastHealth = nodeTestHealth,
                            onTestClick = {
                                scope.launch {
                                    testingNode = true
                                    nodeTestHealth = runCatching {
                                        viewModel.testNodeConnection(walletState.prefs.nodeConfig)
                                    }.getOrNull()
                                    testingNode = false
                                }
                            },
                        )
                    }
                }

                // 4. Notifications
                item(key = "kaspa_notifications") {
                    SettingsGroupSection(title = walletStrings.settingsNotificationsSection) {
                        KineticSettingSwitchRow(
                            title = walletStrings.settingsIncomingAlerts,
                            subtitle = walletStrings.settingsIncomingAlertsDesc,
                            checked = walletState.prefs.incomingNotificationsEnabled,
                            onCheckedChange = { viewModel.setIncomingNotificationsEnabled(it) },
                        )
                    }
                }

                // 5. About & Open Source Licenses
                item(key = "kaspa_licenses") {
                    SettingsGroupSection(title = walletStrings.settingsAboutSection) {
                        Text(
                            text = walletStrings.settingsOpenSourceDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = glassSecondaryContentColor(),
                        )
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { showAboutAppDialog = true },
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(walletStrings.settingsAboutApp, fontWeight = FontWeight.SemiBold)
                        }
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { showLicensesDialog = true },
                        ) {
                            Icon(Icons.Default.Policy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            MarqueeText(walletStrings.settingsOpenSourceLicenses, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            overlayContent = {
                // Rendered INSIDE KineticAeroSettingsScreen Layer B so every modal samples
                // live `LocalOverlayBackdrop` (tier2ViewportBackdrop) for genuine 24.dp frosted glass blur!
                if (activePinMode != null) {
                    val mode = activePinMode!!
                    FullScreenPinOverlay(
                        mode = mode,
                        wallpaperPath = appearance.wallpaperPath,
                        wallpaperBrightness = appearance.wallpaperBrightness,
                        allowDismiss = true,
                        allowBiometrics = mode == PinFlowMode.VERIFY && walletState.prefs.biometricsEnabled,
                        onVerifyPin = { viewModel.verifyPin(it) },
                        onPinCreated = { pin -> viewModel.savePin(pin).getOrThrow() },
                        onVerifiedSuccess = {
                            activePinMode = null
                            when (mode) {
                                PinFlowMode.CREATE, PinFlowMode.CHANGE -> viewModel.showMessage(walletStrings.pinSavedMsg)
                                PinFlowMode.REMOVE -> {
                                    scope.launch {
                                        viewModel.removePin()
                                        viewModel.showMessage(walletStrings.pinRemovedMsg)
                                    }
                                }
                                PinFlowMode.VERIFY -> {
                                    pendingPostPinAction?.invoke()
                                    pendingPostPinAction = null
                                }
                            }
                        },
                        onDismiss = {
                            activePinMode = null
                            pendingPostPinAction = null
                        },
                    )
                }

                if (showSeedDialog != null) {
                    var showSeedScreenshotWarn by remember { mutableStateOf(false) }
                    ScreenshotDetectorEffect(
                        enabled = true,
                        onScreenshotDetected = { showSeedScreenshotWarn = true },
                    )
                    FluidGlassDialog(
                        onDismissRequest = { showSeedDialog = null },
                        title = {
                            Text(
                                text = walletStrings.recoveryPhraseTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                        },
                        actions = {
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                accent = true,
                                useBackdropCapture = false,
                                onClick = { showSeedDialog = null },
                            ) {
                                Text(kineticStrings.close, fontWeight = FontWeight.SemiBold)
                            }
                        },
                    ) {
                        if (showSeedScreenshotWarn) {
                            Text(
                                text = "${walletStrings.screenshotWarningTitle}: ${walletStrings.screenshotWarningBody}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        RecoverySeedGrid3Col(words = showSeedDialog!!)
                    }
                }

                if (showWalletPasswordDialog) {
                    var passInput by remember { mutableStateOf("") }
                    FluidGlassDialog(
                        onDismissRequest = { showWalletPasswordDialog = false },
                        title = {
                            Text(
                                text = walletStrings.settingsWalletPassword,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                        },
                        actions = {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FluidGlassButton(
                                    modifier = Modifier.weight(1f),
                                    useBackdropCapture = false,
                                    onClick = { showWalletPasswordDialog = false },
                                ) {
                                    Text(kineticStrings.cancel)
                                }
                                if (walletState.hasWalletPassword) {
                                    FluidGlassButton(
                                        modifier = Modifier.weight(1f),
                                        destructive = true,
                                        useBackdropCapture = false,
                                        onClick = {
                                            scope.launch {
                                                viewModel.removeWalletPassword()
                                                showWalletPasswordDialog = false
                                            }
                                        },
                                    ) {
                                        MarqueeText(walletStrings.settingsRemovePassword, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    FluidGlassButton(
                                        modifier = Modifier.weight(1f),
                                        accent = true,
                                        enabled = passInput.length >= 4,
                                        useBackdropCapture = false,
                                        onClick = {
                                            scope.launch {
                                                viewModel.setWalletPassword(passInput)
                                                showWalletPasswordDialog = false
                                            }
                                        },
                                    ) {
                                        Text(kineticStrings.save, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        },
                    ) {
                        Text(walletStrings.settingsWalletPasswordDesc, style = MaterialTheme.typography.bodySmall, color = glassSecondaryContentColor())
                        if (!walletState.hasWalletPassword) {
                            FluidGlassTextField(
                                value = passInput,
                                onValueChange = { passInput = it },
                                placeholder = walletStrings.passphrasePlaceholder,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        }
                    }
                }

                if (showAboutAppDialog) {
                    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
                    FluidGlassDialog(
                        onDismissRequest = { showAboutAppDialog = false },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = accentInk, modifier = Modifier.size(22.dp))
                                Text(
                                    text = walletStrings.settingsAboutApp,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = glassPrimaryContentColor(),
                                )
                            }
                        },
                        actions = {
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                accent = true,
                                useBackdropCapture = false,
                                onClick = { showAboutAppDialog = false },
                            ) {
                                Text(kineticStrings.close, fontWeight = FontWeight.SemiBold)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = walletStrings.appName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                            Text(
                                text = "${walletStrings.settingsAboutVersionLabel}: v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = accentInk,
                            )
                            Text(
                                text = "${walletStrings.settingsAboutLicenseLabel}: ${walletStrings.settingsAboutLicenseValue}",
                                style = MaterialTheme.typography.bodySmall,
                                color = glassSecondaryContentColor(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        AboutAppLinkRow(
                            icon = Icons.Default.Public,
                            label = "X (Twitter)",
                            value = "https://x.com/KaspaKinetic",
                            onOpen = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/KaspaKinetic")))
                                }
                            },
                            onCopy = {
                                copyTextToClipboard(context, "X (Twitter)", "https://x.com/KaspaKinetic")
                                viewModel.showMessage(walletStrings.copiedToClipboard)
                            },
                        )
                        AboutAppLinkRow(
                            icon = Icons.Default.Email,
                            label = walletStrings.settingsAboutEmailLabel,
                            value = "kaspakinetic@gmail.com",
                            onOpen = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:kaspakinetic@gmail.com")))
                                }
                            },
                            onCopy = {
                                copyTextToClipboard(context, walletStrings.settingsAboutEmailLabel, "kaspakinetic@gmail.com")
                                viewModel.showMessage(walletStrings.copiedToClipboard)
                            },
                        )
                        AboutAppLinkRow(
                            icon = Icons.Default.Code,
                            label = "GitHub",
                            value = "https://github.com/KaspaKinetic/Kaspa-Kinetic",
                            onOpen = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/KaspaKinetic/Kaspa-Kinetic")))
                                }
                            },
                            onCopy = {
                                copyTextToClipboard(context, "GitHub", "https://github.com/KaspaKinetic/Kaspa-Kinetic")
                                viewModel.showMessage(walletStrings.copiedToClipboard)
                            },
                        )
                    }
                }

                if (showLicensesDialog) {
                    FluidGlassDialog(
                        onDismissRequest = { showLicensesDialog = false },
                        title = {
                            Text(
                                text = walletStrings.settingsOpenSourceLicenses,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = glassPrimaryContentColor(),
                            )
                        },
                        actions = {
                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                accent = true,
                                useBackdropCapture = false,
                                onClick = { showLicensesDialog = false },
                            ) {
                                Text(kineticStrings.close, fontWeight = FontWeight.SemiBold)
                            }
                        },
                    ) {
                        LicenseBlock(
                            title = "Kaspire Kaspa Wallet (Apache-2.0)",
                            body = "Copyright 2026 Kaspire Contributors.\n" +
                                "Licensed under the Apache License, Version 2.0. Pure Kaspa L1 consensus mass calculations, " +
                                "Keystore/StrongBox architecture, and Schnorr transaction review references.\n" +
                                "NOTICE: Includes attribution notices preserved in repository file NOTICE.",
                        )
                        LicenseBlock(
                            title = "Kaspium Wallet (MIT License)",
                            body = "Copyright (c) Kaspium Contributors.\n" +
                                "Permission is hereby granted, free of charge, to any person obtaining a copy of this software, " +
                                "to deal in the Software without restriction under the MIT License. Referenced for Kaspa L1 HD gap-limit " +
                                "discovery, legacy 12-word detection, UTXO compounding, contacts, CSV report format, and message signing.",
                        )
                        LicenseBlock(
                            title = "KaChat — POOLS_v2.md Architecture (MIT License)",
                            body = "Copyright (c) KaChat Contributors.\n" +
                                "Licensed under the MIT License. Clean-room 3-tier decentralized Kaspa gRPC node pool " +
                                "(Automatic Scan) architecture inspired by KaChat's MIT-licensed POOLS_v2.md design.",
                        )
                        LicenseBlock(
                            title = "gRPC-Java / grpc-okhttp (Apache-2.0)",
                            body = "Copyright (c) The gRPC Authors.\n" +
                                "Licensed under the Apache License, Version 2.0. Powers persistent bidirectional HTTP/2 " +
                                "kaspad MessageStream networking.",
                        )
                        LicenseBlock(
                            title = "SilverScript & silverscript-abi (ISC License)",
                            body = "Copyright (c) Kaspanet Contributors.\n" +
                                "Licensed under the ISC License. Official Kaspa Layer-1 covenant language, " +
                                "portable SilAbiArtifact v1 schema, KCC-01 dispatch tags, and Mecenas / Escrow / Vault patterns.",
                        )
                        LicenseBlock(
                            title = "rusty-kaspa (ISC / MIT)",
                            body = "Kaspa consensus Transaction v0 & Toccata v1 BLAKE3 TransactionID hashing, KIP-20 covenant_id, " +
                                "BIP-340 Schnorr signature domain, and post-Toccata / KIP-9 compute, transient, and storage mass specification.",
                        )
                        LicenseBlock(
                            title = "Android & Cryptography Libraries",
                            body = "• Jetpack Compose, DataStore, Biometric, CameraX — Apache-2.0\n" +
                                "• BouncyCastle (bcprov-jdk18on) — MIT License\n" +
                                "• OkHttp & Kotlinx Serialization — Apache-2.0\n" +
                                "• ZXing Core QR Engine — Apache-2.0",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun AboutAppLinkRow(
    icon: ImageVector,
    label: String,
    value: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val shape = ContinuousCornerShape(16.dp)
    val bg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDark -> Color(0xFF151C24).copy(alpha = 0.54f)
        else -> Color.White.copy(alpha = 0.66f)
    }
    val border = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.84f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .kineticInteractiveRow(
                shape = shape,
                backgroundColor = bg,
                borderColor = border,
                onClick = onOpen,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(ContinuousCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accentInk, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = glassSecondaryContentColor(),
                    modifier = Modifier.size(12.dp),
                )
            }
            MarqueeText(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accentInk,
            )
        }
        FluidGlassIconButton(
            onClick = onCopy,
            size = 36.dp,
            useBackdropCapture = false,
            lockParentGestures = false,
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = label, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LicenseBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = glassSecondaryContentColor(),
        )
        Spacer(Modifier.height(6.dp))
    }
}
