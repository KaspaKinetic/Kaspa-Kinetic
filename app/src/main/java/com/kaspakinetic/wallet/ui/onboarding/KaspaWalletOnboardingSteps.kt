package com.kaspakinetic.wallet.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.R
import com.kaspakinetic.wallet.core.crypto.Bip39
import com.kaspakinetic.wallet.core.crypto.Bip39Wordlist
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.NodeConfig
import com.kaspakinetic.wallet.core.model.NodeConnectionMode
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.ThemeMode
import com.kaspakinetic.wallet.ui.components.FullScreenPinOverlay
import com.kaspakinetic.wallet.ui.components.FullScreenQrScannerOverlay
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.PinFlowMode
import com.kaspakinetic.wallet.ui.components.RecoverySeedGrid3Col
import com.kaspakinetic.wallet.ui.components.ScreenshotDetectorEffect
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.components.readTextFromClipboard
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassChip
import com.kaspakinetic.wallet.ui.glass.FluidGlassDialog
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassSegmentedControl
import com.kaspakinetic.wallet.ui.glass.FluidGlassSwitch
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.FluidOptionGridSelector
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalOverlayBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.layerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.kaspakinetic.wallet.ui.glass.fluidTouchPhysics
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import com.kaspakinetic.wallet.ui.glass.rememberFluidMotionState
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletUiState
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch

enum class WalletSetupChoice {
    CREATE_NEW,
    IMPORT_SEED,
    IMPORT_KPUB,
    WATCH_ADDRESS,
}

/**
 * First-launch onboarding seamlessly integrating Kaspa Kinetic wallet setup steps INTO
 * the documented Kinetic Aero onboarding framework (Steps 0-2 intact, followed by
 * one-job-per-screen wallet setup steps).
 */
@Composable
fun KaspaFirstLaunchOnboarding(
    appearance: KineticAeroAppearanceSettings,
    walletState: WalletUiState,
    viewModel: WalletViewModel,
    onSelectLanguage: (String) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    onDisableProgressiveBlurChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onCompleteOnboarding: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val scope = rememberCoroutineScope()

    // Wallet creation / import state
    var setupChoice by rememberSaveable { mutableStateOf(WalletSetupChoice.CREATE_NEW) }
    var walletName by rememberSaveable { mutableStateOf(walletStrings.walletNameDefault) }
    var usePassphrase by rememberSaveable { mutableStateOf(false) }
    var passphrase by rememberSaveable { mutableStateOf("") }

    // Create flow seed state (12 or 24 words)
    var createWordCount by rememberSaveable { mutableIntStateOf(24) }
    var generatedSeed12 by rememberSaveable { mutableStateOf(Bip39.generateMnemonic(12)) }
    var generatedSeed24 by rememberSaveable { mutableStateOf(Bip39.generateMnemonic(24)) }
    var seedRevealed by rememberSaveable { mutableStateOf(false) }

    // Import seed state (12/15/18/21/24 words)
    var importWordCount by rememberSaveable { mutableIntStateOf(12) }
    val importWords = remember { mutableStateListOf<String>().apply { repeat(24) { add("") } } }
    var importKpubText by rememberSaveable { mutableStateOf("") }
    var watchAddressText by rememberSaveable { mutableStateOf("") }
    var setupError by remember { mutableStateOf<String?>(null) }

    // Security step state
    var showPinCreator by remember { mutableStateOf(false) }
    var biometricsEnabled by rememberSaveable { mutableStateOf(walletState.prefs.biometricsEnabled) }

    // Currency & Privacy step state
    var selectedCurrency by rememberSaveable { mutableStateOf(walletState.prefs.fiatCurrency) }
    var kasOnlyPrivacy by rememberSaveable { mutableStateOf(walletState.prefs.kasOnlyPrivacyMode) }

    // Node & Network step state
    var nodeConfig by remember { mutableStateOf(walletState.prefs.nodeConfig) }
    var isFinishing by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    val isCreateFlow = setupChoice == WalletSetupChoice.CREATE_NEW
    val extraStepCount = if (isCreateFlow) 6 else 4
    // Step index mapping inside extraStepContent:
    // Create flow:   0=Mode&Name, 1=SafetyFirst+SeedGrid, 2=BackupConfirm, 3=SecurityPIN, 4=Currency, 5=Node&Finish
    // Non-create:    0=Mode&Name, 1=SecurityPIN, 2=Currency, 3=Node&Finish
    val securityStepIdx = if (isCreateFlow) 3 else 1
    val currencyStepIdx = if (isCreateFlow) 4 else 2
    val nodeStepIdx = if (isCreateFlow) 5 else 3

    fun validateModeStep(): Boolean {
        setupError = null
        return when (setupChoice) {
            WalletSetupChoice.CREATE_NEW -> true
            WalletSetupChoice.IMPORT_SEED -> {
                val phrase = importWords.take(importWordCount).joinToString(" ") { it.trim().lowercase() }
                val ok = Bip39.validateMnemonic(phrase)
                if (!ok) setupError = walletStrings.invalidSeedError
                ok
            }
            WalletSetupChoice.IMPORT_KPUB -> {
                val ok = runCatching { KaspaCrypto.normalizeKpubToMainnet(importKpubText.trim()) }.isSuccess
                if (!ok) setupError = walletStrings.invalidKpubError
                ok
            }
            WalletSetupChoice.WATCH_ADDRESS -> {
                val ok = KaspaAddress.decode(watchAddressText.trim()) != null
                if (!ok) setupError = walletStrings.invalidAddressError
                ok
            }
        }
    }

    fun finishAll() {
        if (isFinishing) return
        scope.launch {
            isFinishing = true
            setupError = null
            try {
                viewModel.setFiatCurrency(selectedCurrency)
                viewModel.setKasOnlyPrivacyMode(kasOnlyPrivacy)
                viewModel.setBiometricsEnabled(biometricsEnabled)
                viewModel.setNodeConfig(nodeConfig)

                val effectivePassphrase = if (usePassphrase) passphrase else ""
                when (setupChoice) {
                    WalletSetupChoice.CREATE_NEW -> {
                        val phrase = if (createWordCount == 12) generatedSeed12 else generatedSeed24
                        viewModel.createHotWallet(
                            name = walletName.ifBlank { walletStrings.walletNameDefault },
                            mnemonic = phrase,
                            passphrase = effectivePassphrase,
                        ).getOrThrow()
                    }
                    WalletSetupChoice.IMPORT_SEED -> {
                        val phrase = importWords.take(importWordCount).joinToString(" ") { it.trim().lowercase() }
                        val (_, legacy) = viewModel.importSeedWalletWithAutoDetect(
                            name = walletName.ifBlank { walletStrings.walletNameDefault },
                            mnemonic = phrase,
                            passphrase = effectivePassphrase,
                        ).getOrThrow()
                        if (legacy) {
                            viewModel.showMessage(walletStrings.legacyWalletDetected)
                        }
                    }
                    WalletSetupChoice.IMPORT_KPUB -> {
                        viewModel.importKpubWallet(
                            name = walletName.ifBlank { walletStrings.modeImportKpub },
                            kpubInput = importKpubText.trim(),
                        ).getOrThrow()
                    }
                    WalletSetupChoice.WATCH_ADDRESS -> {
                        viewModel.importWatchAddressWallet(
                            name = walletName.ifBlank { walletStrings.modeWatchAddress },
                            addressInput = watchAddressText.trim(),
                        ).getOrThrow()
                    }
                }
                onCompleteOnboarding()
            } catch (e: Exception) {
                setupError = e.message ?: "Wallet setup error"
                viewModel.showMessage(setupError ?: "Wallet setup error")
            } finally {
                isFinishing = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        KineticAeroOnboardingScreen(
            appearance = appearance,
            lightExampleDrawableRes = R.drawable.onboarding_example_light,
            darkExampleDrawableRes = R.drawable.onboarding_example_dark,
            onSelectLanguage = onSelectLanguage,
            onSelectThemeMode = onSelectThemeMode,
            onReduceTransparencyChanged = onReduceTransparencyChanged,
            onDisableProgressiveBlurChanged = onDisableProgressiveBlurChanged,
            onHapticsChanged = onHapticsChanged,
            onFinish = { finishAll() },
            extraStepCount = extraStepCount,
            canContinueExtraStep = { extraIdx ->
                when {
                    extraIdx == 0 -> when (setupChoice) {
                        WalletSetupChoice.CREATE_NEW -> true
                        WalletSetupChoice.IMPORT_SEED -> importWords.take(importWordCount).all { it.isNotBlank() }
                        WalletSetupChoice.IMPORT_KPUB -> importKpubText.isNotBlank()
                        WalletSetupChoice.WATCH_ADDRESS -> watchAddressText.isNotBlank()
                    }
                    isCreateFlow && extraIdx == 1 -> seedRevealed
                    extraIdx == nodeStepIdx -> !isFinishing
                    else -> true
                }
            },
            hideDefaultBottomNavForExtraStep = { extraIdx ->
                // Hide default Back/Continue on the Backup Confirmation step so its own Yes/No buttons are clean
                (isCreateFlow && extraIdx == 2) || (isCreateFlow && extraIdx == 1 && !seedRevealed)
            },
            finalActionLabel = if (isFinishing) walletStrings.loading else walletStrings.finishSetupButton,
            onExtraStepContinue = { extraIdx, advance ->
                when (extraIdx) {
                    0 -> if (validateModeStep()) advance()
                    nodeStepIdx -> finishAll()
                    else -> advance()
                }
            },
            extraStepContent = { extraIdx, goBack, goNext ->
                when {
                    extraIdx == 0 -> WalletModeAndNameStep(
                        choice = setupChoice,
                        onSelectChoice = {
                            setupChoice = it
                            setupError = null
                        },
                        walletName = walletName,
                        onWalletNameChange = { walletName = it },
                        usePassphrase = usePassphrase,
                        onUsePassphraseChange = { usePassphrase = it },
                        passphrase = passphrase,
                        onPassphraseChange = { passphrase = it },
                        importWordCount = importWordCount,
                        onImportWordCountChange = { importWordCount = it },
                        importWords = importWords,
                        importKpubText = importKpubText,
                        onImportKpubChange = {
                            importKpubText = it
                            setupError = null
                        },
                        watchAddressText = watchAddressText,
                        onWatchAddressChange = {
                            watchAddressText = it
                            setupError = null
                        },
                        errorMessage = setupError,
                        onOpenQrScanner = { showQrScanner = true },
                    )

                    isCreateFlow && extraIdx == 1 -> RecoveryPhraseStep(
                        wordCount = createWordCount,
                        onWordCountChange = { createWordCount = it },
                        words = remember(createWordCount, generatedSeed12, generatedSeed24) {
                            (if (createWordCount == 12) generatedSeed12 else generatedSeed24).split(' ')
                        },
                        revealed = seedRevealed,
                        onReveal = { seedRevealed = true },
                        onReroll = {
                            if (createWordCount == 12) {
                                generatedSeed12 = Bip39.generateMnemonic(12)
                            } else {
                                generatedSeed24 = Bip39.generateMnemonic(24)
                            }
                        },
                        onApplyDicePhrase = { customPhrase ->
                            if (createWordCount == 12) generatedSeed12 = customPhrase else generatedSeed24 = customPhrase
                        },
                        onCopiedMessage = { viewModel.showMessage(it) },
                        onBack = goBack,
                    )

                    isCreateFlow && extraIdx == 2 -> BackupConfirmationStep(
                        onYes = goNext,
                        onNo = goBack,
                    )

                    extraIdx == securityStepIdx -> SecuritySetupStep(
                        hasPin = walletState.hasPin,
                        biometricsEnabled = biometricsEnabled,
                        onConfigurePin = { showPinCreator = true },
                        onBiometricsChanged = { biometricsEnabled = it },
                    )

                    extraIdx == currencyStepIdx -> CurrencyAndPrivacyStep(
                        selectedCurrency = selectedCurrency,
                        onSelectCurrency = { selectedCurrency = it },
                        kasOnlyPrivacy = kasOnlyPrivacy,
                        onKasOnlyPrivacyChanged = { kasOnlyPrivacy = it },
                    )

                    extraIdx == nodeStepIdx -> NodeConfigurationStep(
                        nodeConfig = nodeConfig,
                        onNodeConfigChange = { nodeConfig = it },
                        onTestConnection = { viewModel.testNodeConnection(it) },
                        errorMessage = setupError,
                    )
                }
            },
        )

        // Dedicated full-screen 6-digit auto-advancing PIN creator overlay
        if (showPinCreator) {
            FullScreenPinOverlay(
                mode = PinFlowMode.CREATE,
                wallpaperPath = appearance.wallpaperPath,
                wallpaperBrightness = appearance.wallpaperBrightness,
                allowDismiss = true,
                allowBiometrics = false,
                onVerifyPin = { viewModel.verifyPin(it) },
                onPinCreated = { pin -> viewModel.savePin(pin).getOrThrow() },
                onVerifiedSuccess = {
                    showPinCreator = false
                    viewModel.showMessage(walletStrings.pinSavedMsg)
                },
                onDismiss = { showPinCreator = false },
            )
        }

        if (showQrScanner) {
            FullScreenQrScannerOverlay(
                onResult = { scanned ->
                    showQrScanner = false
                    when (setupChoice) {
                        WalletSetupChoice.IMPORT_KPUB -> importKpubText = KaspaCrypto.sanitizeExtendedPublicKey(scanned)
                        WalletSetupChoice.WATCH_ADDRESS -> {
                            val parsed = KaspaAddress.parseUriOrAddress(scanned)
                            watchAddressText = parsed?.address ?: scanned.trim()
                        }
                        else -> Unit
                    }
                    setupError = null
                },
                onDismiss = { showQrScanner = false },
            )
        }
    }
}

/**
 * Full-screen in-window overlay for "+ Add Wallet" triggered from the Wallet Drawer.
 * Reuses the exact same one-job-per-screen wallet setup components.
 */
@Composable
fun AddWalletOverlay(
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val reduceTransparency = LocalReduceTransparency.current
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) } // 0=Mode, 1=Seed(create), 2=Confirm(create)
    var setupChoice by rememberSaveable { mutableStateOf(WalletSetupChoice.CREATE_NEW) }
    var walletName by rememberSaveable { mutableStateOf("") }
    var usePassphrase by rememberSaveable { mutableStateOf(false) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var createWordCount by rememberSaveable { mutableIntStateOf(24) }
    var generatedSeed12 by rememberSaveable { mutableStateOf(Bip39.generateMnemonic(12)) }
    var generatedSeed24 by rememberSaveable { mutableStateOf(Bip39.generateMnemonic(24)) }
    var seedRevealed by rememberSaveable { mutableStateOf(false) }
    var importWordCount by rememberSaveable { mutableIntStateOf(12) }
    val importWords = remember { mutableStateListOf<String>().apply { repeat(24) { add("") } } }
    var importKpubText by rememberSaveable { mutableStateOf("") }
    var watchAddressText by rememberSaveable { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    BackHandler {
        if (step > 0) step-- else onDismiss()
    }

    fun submitWallet() {
        if (busy) return
        scope.launch {
            busy = true
            errorMsg = null
            try {
                val effectivePass = if (usePassphrase) passphrase else ""
                when (setupChoice) {
                    WalletSetupChoice.CREATE_NEW -> {
                        val phrase = if (createWordCount == 12) generatedSeed12 else generatedSeed24
                        viewModel.createHotWallet(
                            name = walletName.ifBlank { walletStrings.walletNameDefault },
                            mnemonic = phrase,
                            passphrase = effectivePass,
                        ).getOrThrow()
                    }
                    WalletSetupChoice.IMPORT_SEED -> {
                        val phrase = importWords.take(importWordCount).joinToString(" ") { it.trim().lowercase() }
                        val (_, legacy) = viewModel.importSeedWalletWithAutoDetect(
                            name = walletName.ifBlank { walletStrings.walletNameDefault },
                            mnemonic = phrase,
                            passphrase = effectivePass,
                        ).getOrThrow()
                        if (legacy) viewModel.showMessage(walletStrings.legacyWalletDetected)
                    }
                    WalletSetupChoice.IMPORT_KPUB -> {
                        viewModel.importKpubWallet(
                            name = walletName.ifBlank { walletStrings.modeImportKpub },
                            kpubInput = importKpubText.trim(),
                        ).getOrThrow()
                    }
                    WalletSetupChoice.WATCH_ADDRESS -> {
                        viewModel.importWatchAddressWallet(
                            name = walletName.ifBlank { walletStrings.modeWatchAddress },
                            addressInput = watchAddressText.trim(),
                        ).getOrThrow()
                    }
                }
                onDismiss()
            } catch (e: Exception) {
                errorMsg = e.message ?: "Could not add wallet"
            } finally {
                busy = false
            }
        }
    }

    // Canonical Two-Tier Kinetic Aero Backdrop:
    // Tier 1 (ambientBackdrop) captures wallpaper for scrolling cards/inputs;
    // Tier 2 (viewportBackdrop) captures wallpaper + scrolling content for floating header + progressive blur.
    val ambientBackdrop = rememberLayerBackdrop()
    val viewportBackdrop = rememberLayerBackdrop()
    val effectiveAmbient: Backdrop? = if (reduceTransparency) null else ambientBackdrop
    val effectiveViewport: Backdrop? = if (reduceTransparency) null else viewportBackdrop
    val isDark = LocalGlassDarkTheme.current
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()
    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(0) }
    val topPadDp = with(density) { topChromeHeightPx.toDp() }.coerceAtLeast(88.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(70f),
    ) {
        // Tier 2 viewport layer: ambient wallpaper + seamlessly scrolling content behind floating header
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(viewportBackdrop) else Modifier),
        ) {
            // Tier 1 ambient layer: wallpaper + readability scrim matching Covenants / Explore screens
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!reduceTransparency) Modifier.layerBackdrop(ambientBackdrop) else Modifier),
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
                                    if (isDark) Color(0xFF070B10).copy(alpha = 0.48f)
                                    else Color(0xFFF3F7F5).copy(alpha = 0.56f),
                                ),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides effectiveAmbient) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp),
                ) {
                    AnimatedContent(
                        targetState = step,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "add_wallet_step",
                    ) { s ->
                        when (s) {
                            0 -> WalletModeAndNameStep(
                                choice = setupChoice,
                                onSelectChoice = {
                                    setupChoice = it
                                    errorMsg = null
                                },
                                walletName = walletName,
                                onWalletNameChange = { walletName = it },
                                usePassphrase = usePassphrase,
                                onUsePassphraseChange = { usePassphrase = it },
                                passphrase = passphrase,
                                onPassphraseChange = { passphrase = it },
                                importWordCount = importWordCount,
                                onImportWordCountChange = { importWordCount = it },
                                importWords = importWords,
                                importKpubText = importKpubText,
                                onImportKpubChange = {
                                    importKpubText = it
                                    errorMsg = null
                                },
                                watchAddressText = watchAddressText,
                                onWatchAddressChange = {
                                    watchAddressText = it
                                    errorMsg = null
                                },
                                errorMessage = errorMsg,
                                onOpenQrScanner = { showQrScanner = true },
                                topContentPadding = topPadDp + 8.dp,
                                bottomContentPadding = 16.dp,
                            )
                            1 -> RecoveryPhraseStep(
                                wordCount = createWordCount,
                                onWordCountChange = { createWordCount = it },
                                words = (if (createWordCount == 12) generatedSeed12 else generatedSeed24).split(' '),
                                revealed = seedRevealed,
                                onReveal = { seedRevealed = true },
                                onReroll = {
                                    if (createWordCount == 12) {
                                        generatedSeed12 = Bip39.generateMnemonic(12)
                                    } else {
                                        generatedSeed24 = Bip39.generateMnemonic(24)
                                    }
                                },
                                onApplyDicePhrase = { customPhrase ->
                                    if (createWordCount == 12) generatedSeed12 = customPhrase else generatedSeed24 = customPhrase
                                },
                                onCopiedMessage = { viewModel.showMessage(it) },
                                onBack = { step = 0 },
                                topContentPadding = topPadDp + 8.dp,
                            )
                            else -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = topPadDp + 8.dp),
                            ) {
                                BackupConfirmationStep(
                                    onYes = { submitWallet() },
                                    onNo = { step = 1 },
                                )
                            }
                        }
                    }

                    if (step == 0 || (step == 1 && seedRevealed)) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FluidGlassButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            accent = true,
                            enabled = !busy,
                            backdrop = effectiveAmbient,
                            lockParentGestures = true,
                            onClick = {
                                if (step == 0) {
                                    when (setupChoice) {
                                        WalletSetupChoice.CREATE_NEW -> step = 1
                                        WalletSetupChoice.IMPORT_SEED -> {
                                            val phrase = importWords.take(importWordCount).joinToString(" ") { it.trim().lowercase() }
                                            if (Bip39.validateMnemonic(phrase)) submitWallet()
                                            else errorMsg = walletStrings.invalidSeedError
                                        }
                                        WalletSetupChoice.IMPORT_KPUB -> {
                                            if (runCatching { KaspaCrypto.normalizeKpubToMainnet(importKpubText.trim()) }.isSuccess) {
                                                submitWallet()
                                            } else {
                                                errorMsg = walletStrings.invalidKpubError
                                            }
                                        }
                                        WalletSetupChoice.WATCH_ADDRESS -> {
                                            if (KaspaAddress.decode(watchAddressText.trim()) != null) submitWallet()
                                            else errorMsg = walletStrings.invalidAddressError
                                        }
                                    }
                                } else {
                                    step = 2
                                }
                            },
                        ) {
                            Text(
                                text = if (busy) walletStrings.loading else kineticStrings.continueLabel,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Canonical Floating Kinetic Aero Top Header + ProgressiveGlassEdgeBand
        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = walletStrings.addWalletButton,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = { if (step > 0) step-- else onDismiss() },
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(
                            imageVector = if (step > 0) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = kineticStrings.back,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        }

        if (showQrScanner) {
            FullScreenQrScannerOverlay(
                onResult = { scanned ->
                    showQrScanner = false
                    when (setupChoice) {
                        WalletSetupChoice.IMPORT_KPUB -> importKpubText = KaspaCrypto.sanitizeExtendedPublicKey(scanned)
                        WalletSetupChoice.WATCH_ADDRESS -> {
                            val parsed = KaspaAddress.parseUriOrAddress(scanned)
                            watchAddressText = parsed?.address ?: scanned.trim()
                        }
                        else -> Unit
                    }
                    errorMsg = null
                },
                onDismiss = { showQrScanner = false },
            )
        }
    }
}

// ============================================================================
// Step 3: Wallet Mode & Name (+ Import Seed 4-letter Autocomplete / kpub / Watch)
// ============================================================================

@Composable
private fun WalletModeAndNameStep(
    choice: WalletSetupChoice,
    onSelectChoice: (WalletSetupChoice) -> Unit,
    walletName: String,
    onWalletNameChange: (String) -> Unit,
    usePassphrase: Boolean,
    onUsePassphraseChange: (Boolean) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    importWordCount: Int,
    onImportWordCountChange: (Int) -> Unit,
    importWords: MutableList<String>,
    importKpubText: String,
    onImportKpubChange: (String) -> Unit,
    watchAddressText: String,
    onWatchAddressChange: (String) -> Unit,
    errorMessage: String?,
    onOpenQrScanner: () -> Unit,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 24.dp,
) {
    val walletStrings = LocalWalletStrings.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val context = LocalContext.current
    var showPassphrase by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (topContentPadding > 0.dp) {
            Spacer(modifier = Modifier.height(topContentPadding))
        }
        Text(
            text = walletStrings.onboardWalletTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = walletStrings.onboardWalletSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = glassSecondaryContentColor(),
        )

        ModeOptionTile(
            title = walletStrings.modeCreateNew,
            subtitle = walletStrings.modeCreateNewDesc,
            icon = Icons.Default.AccountBalanceWallet,
            selected = choice == WalletSetupChoice.CREATE_NEW,
            onClick = { onSelectChoice(WalletSetupChoice.CREATE_NEW) },
        )
        ModeOptionTile(
            title = walletStrings.modeImportSeed,
            subtitle = walletStrings.modeImportSeedDesc,
            icon = Icons.Default.Key,
            selected = choice == WalletSetupChoice.IMPORT_SEED,
            onClick = { onSelectChoice(WalletSetupChoice.IMPORT_SEED) },
        )
        ModeOptionTile(
            title = walletStrings.modeImportKpub,
            subtitle = walletStrings.modeImportKpubDesc,
            icon = Icons.Default.Visibility,
            selected = choice == WalletSetupChoice.IMPORT_KPUB,
            onClick = { onSelectChoice(WalletSetupChoice.IMPORT_KPUB) },
        )
        ModeOptionTile(
            title = walletStrings.modeWatchAddress,
            subtitle = walletStrings.modeWatchAddressDesc,
            icon = Icons.Default.Shield,
            selected = choice == WalletSetupChoice.WATCH_ADDRESS,
            onClick = { onSelectChoice(WalletSetupChoice.WATCH_ADDRESS) },
        )

        // Wallet Name + Optional BIP-39 Passphrase wrapped in canonical frosted Kinetic Aero FluidGlassCard
        // for crisp readability over custom wallpapers in both Light and Dark mode.
        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            FluidGlassTextField(
                value = walletName,
                onValueChange = onWalletNameChange,
                label = walletStrings.walletNameLabel,
                placeholder = walletStrings.walletNameDefault,
                singleLine = true,
                useBackdropCapture = false,
                nestedInGlassCard = true,
            )

            if (choice == WalletSetupChoice.CREATE_NEW || choice == WalletSetupChoice.IMPORT_SEED) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = walletStrings.passphraseToggleTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = glassPrimaryContentColor(),
                        )
                        Text(
                            text = walletStrings.passphraseToggleDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = glassSecondaryContentColor(),
                        )
                    }
                    FluidGlassSwitch(
                        checked = usePassphrase,
                        onCheckedChange = onUsePassphraseChange,
                    )
                }

                if (usePassphrase) {
                    Spacer(Modifier.height(12.dp))
                    FluidGlassTextField(
                        value = passphrase,
                        onValueChange = onPassphraseChange,
                        label = walletStrings.passphraseInputLabel,
                        placeholder = walletStrings.passphrasePlaceholder,
                        singleLine = true,
                        useBackdropCapture = false,
                        nestedInGlassCard = true,
                        visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingContent = {
                            FluidGlassIconButton(
                                onClick = { showPassphrase = !showPassphrase },
                                size = 30.dp,
                                useBackdropCapture = false,
                                lockParentGestures = false,
                            ) {
                                Icon(
                                    imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        // Import Seed sub-flow: 12 / 15 / 18 / 21 / 24 word count + Paste Full Phrase + 4-letter autocomplete grid
        if (choice == WalletSetupChoice.IMPORT_SEED) {
            SeedImportWordEntrySection(
                wordCount = importWordCount,
                onWordCountChange = onImportWordCountChange,
                words = importWords,
            )
        }

        // Import kpub / xpub / Ledger hex sub-flow
        if (choice == WalletSetupChoice.IMPORT_KPUB) {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                FluidGlassTextField(
                    value = importKpubText,
                    onValueChange = onImportKpubChange,
                    label = walletStrings.kpubInputLabel,
                    placeholder = walletStrings.kpubInputPlaceholder,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    useBackdropCapture = false,
                    nestedInGlassCard = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            readTextFromClipboard(context)?.let { onImportKpubChange(KaspaCrypto.sanitizeExtendedPublicKey(it)) }
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(walletStrings.paste, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = onOpenQrScanner,
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(walletStrings.scanQrButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
            KpubFormatGuideCard()
        }

        // Watch Address sub-flow
        if (choice == WalletSetupChoice.WATCH_ADDRESS) {
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = true,
                contentPadding = PaddingValues(16.dp),
            ) {
                FluidGlassTextField(
                    value = watchAddressText,
                    onValueChange = onWatchAddressChange,
                    label = walletStrings.watchAddressInputLabel,
                    placeholder = walletStrings.watchAddressPlaceholder,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    useBackdropCapture = false,
                    nestedInGlassCard = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            readTextFromClipboard(context)?.trim()?.let {
                                val parsed = KaspaAddress.parseUriOrAddress(it)
                                onWatchAddressChange(parsed?.address ?: it)
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(walletStrings.paste, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = onOpenQrScanner,
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        MarqueeText(walletStrings.scanQrButton, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            ErrorInlineBanner(message = errorMessage)
        }

        Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

@Composable
private fun KpubFormatGuideCard() {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val accentInk = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val chipBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHighest
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.Black.copy(alpha = 0.06f)
    }

    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = LocalGlassBackdrop.current,
        useBackdropCapture = true,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = walletStrings.kpubHelperTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
            }
            Text(
                text = walletStrings.kpubHelperFormatsBody,
                style = MaterialTheme.typography.bodySmall,
                color = glassPrimaryContentColor(),
            )
            // Compact truncated format example pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("kpub2...", "xpub6...", "4104...").forEach { sample ->
                    Box(
                        modifier = Modifier
                            .clip(ContinuousCornerShape(10.dp))
                            .background(chipBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = sample,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = accentInk,
                        )
                    }
                }
            }
            Text(
                text = walletStrings.kpubHelperPasteTip,
                style = MaterialTheme.typography.labelMedium,
                color = glassSecondaryContentColor(),
            )
            Text(
                text = walletStrings.kpubHelperLedgerTip,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = glassPrimaryContentColor().copy(alpha = 0.88f),
            )
        }
    }
}

@Composable
private fun ModeOptionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val backdrop = LocalGlassBackdrop.current
    val motion = rememberFluidMotionState()
    val shape = ContinuousCornerShape(24.dp)
    val selectedSurfaceTint = if (selected) MaterialTheme.colorScheme.primaryContainer else null
    val titleInk = if (selected && reduceTransparency) MaterialTheme.colorScheme.onPrimaryContainer else glassPrimaryContentColor()
    val subInk = if (selected && reduceTransparency) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else glassSecondaryContentColor()

    FluidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .fluidTouchPhysics(state = motion, pressedScale = 0.94f, lockParentGestures = false)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = shape,
        preset = GlassPreset.Card,
        backdrop = backdrop,
        useBackdropCapture = true,
        surfaceTint = selectedSurfaceTint,
        motionState = motion,
        pressShadowEnabled = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected && reduceTransparency) MaterialTheme.colorScheme.onPrimaryContainer
                else readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleInk,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subInk,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (selected && reduceTransparency) MaterialTheme.colorScheme.onPrimaryContainer
                    else readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SeedImportWordEntrySection(
    wordCount: Int,
    onWordCountChange: (Int) -> Unit,
    words: MutableList<String>,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val supportedCounts = remember { listOf(12, 15, 18, 21, 24) }
    val focusRequesters = remember { List(24) { FocusRequester() } }
    var activeIndex by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = walletStrings.wordCountLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            supportedCounts.forEach { count ->
                FluidGlassChip(
                    modifier = Modifier.weight(1f),
                    selected = wordCount == count,
                    useBackdropCapture = false,
                    lockParentGestures = false,
                    onClick = { onWordCountChange(count) },
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }

        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = false,
            lockParentGestures = false,
            onClick = {
                val clip = readTextFromClipboard(context) ?: return@FluidGlassButton
                val tokens = clip.trim().split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotEmpty() }
                if (tokens.size in supportedCounts) {
                    onWordCountChange(tokens.size)
                }
                for (i in 0 until 24) {
                    words[i] = tokens.getOrNull(i) ?: ""
                }
            },
        ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(walletStrings.pasteFullPhrase, fontWeight = FontWeight.SemiBold)
        }

        Text(
            text = walletStrings.importSeedHint,
            style = MaterialTheme.typography.bodySmall,
            color = glassSecondaryContentColor(),
        )

        // Suggestion strip for active word
        val activeQuery = words.getOrNull(activeIndex).orEmpty()
        val suggestions: List<String> = remember(activeQuery) {
            if (activeQuery.length in 1..10 && !Bip39.isValidWord(activeQuery)) {
                Bip39.suggestWords(activeQuery, limit = 4)
            } else {
                emptyList()
            }
        }
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { candidate ->
                    FluidGlassChip(
                        selected = false,
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = {
                            words[activeIndex] = candidate
                            if (activeIndex + 1 < wordCount) {
                                activeIndex++
                                runCatching { focusRequesters[activeIndex].requestFocus() }
                            }
                        },
                    ) {
                        Text(candidate, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 2-column numbered word fields with 4-letter unique prefix autocomplete & auto-advance
        val indices = (0 until wordCount).toList()
        indices.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { idx ->
                    val currentVal = words[idx]
                    val isValidWord = currentVal.isNotEmpty() && Bip39.isValidWord(currentVal)
                    FluidGlassTextField(
                        value = currentVal,
                        onValueChange = { raw ->
                            activeIndex = idx
                            val cleaned = raw.lowercase().replace(Regex("[^a-z]"), "")
                            val uniqueMatch = if (cleaned.length >= 4) {
                                Bip39.uniqueWordForPrefix(cleaned)
                            } else {
                                null
                            }
                            if (uniqueMatch != null) {
                                words[idx] = uniqueMatch
                                if (idx + 1 < wordCount) {
                                    activeIndex = idx + 1
                                    runCatching { focusRequesters[idx + 1].requestFocus() }
                                }
                            } else {
                                words[idx] = cleaned
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequesters[idx]),
                        placeholder = "#${idx + 1}",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = if (idx + 1 < wordCount) ImeAction.Next else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                if (idx + 1 < wordCount) {
                                    activeIndex = idx + 1
                                    runCatching { focusRequesters[idx + 1].requestFocus() }
                                }
                            },
                        ),
                        leadingContent = {
                            Text(
                                text = "${idx + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isValidWord) MaterialTheme.colorScheme.primary else glassSecondaryContentColor(),
                            )
                        },
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ============================================================================
// Step 4 (Create Only): Safety First Gate + Single-Screen 3-Column Seed Grid
// + Reroll + Copy Warning + Screenshot Detection + Optional Dice Roll Entropy
// ============================================================================

@Composable
private fun RecoveryPhraseStep(
    wordCount: Int,
    onWordCountChange: (Int) -> Unit,
    words: List<String>,
    revealed: Boolean,
    onReveal: () -> Unit,
    onReroll: () -> Unit,
    onApplyDicePhrase: (String) -> Unit,
    onCopiedMessage: (String) -> Unit,
    onBack: () -> Unit,
    topContentPadding: Dp = 0.dp,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current

    var showCopyWarningDialog by remember { mutableStateOf(false) }
    var showScreenshotWarningDialog by remember { mutableStateOf(false) }
    var showDiceEntropyDialog by remember { mutableStateOf(false) }

    // Allow screenshots on the seed screen while actively warning the user if they take one!
    ScreenshotDetectorEffect(
        enabled = revealed,
        onScreenshotDetected = { showScreenshotWarningDialog = true },
    )

    Box(modifier = Modifier.fillMaxSize().padding(top = topContentPadding)) {
        if (!revealed) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(22.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = walletStrings.safetyFirstTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = walletStrings.safetyFirstBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassSecondaryContentColor(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        accent = true,
                        useBackdropCapture = false,
                        onClick = onReveal,
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(walletStrings.revealPhraseButton, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    FluidGlassButton(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        onClick = onBack,
                    ) {
                        Text(kineticStrings.back, fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else {
            // Single-screen 3-column grid wrapped in frosted Kinetic Aero glass backing so every word
            // stays crisp and legible over any wallpaper in Light & Dark mode.
            val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = true,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = walletStrings.recoveryPhraseTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = walletStrings.recoveryPhraseSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                    Spacer(Modifier.height(10.dp))
                    FluidGlassSegmentedControl(
                        items = listOf(12, 24),
                        selectedItem = wordCount,
                        onSelect = onWordCountChange,
                        labelFor = { if (it == 12) walletStrings.words12Label else walletStrings.words24Label },
                    )
                    Spacer(Modifier.height(8.dp))
                    // Seed utility actions: Reroll | Copy (with warning) | Dice (Optional/Advanced)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = onReroll,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            MarqueeText(
                                text = walletStrings.rerollPhraseButton,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        FluidGlassChip(
                            modifier = Modifier.weight(1f),
                            useBackdropCapture = false,
                            lockParentGestures = false,
                            onClick = { showCopyWarningDialog = true },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            MarqueeText(
                                text = walletStrings.copyPhraseButton,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FluidGlassChip(
                        modifier = Modifier.fillMaxWidth(),
                        useBackdropCapture = false,
                        lockParentGestures = false,
                        onClick = { showDiceEntropyDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Casino,
                            contentDescription = null,
                            tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                            modifier = Modifier.size(16.dp),
                        )
                        MarqueeText(
                            text = "${walletStrings.diceEntropyToggleLabel} • ${walletStrings.diceEntropyBadge}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                FluidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    useBackdropCapture = true,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    RecoverySeedGrid3Col(words = words)
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        // Clipboard Copy Security Warning Dialog (full-screen edge-to-edge window scrim)
        if (showCopyWarningDialog) {
            FluidGlassDialog(
                onDismissRequest = { showCopyWarningDialog = false },
                windowOverlay = true,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = walletStrings.copyPhraseWarningTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                        )
                    }
                },
                actions = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            destructive = true,
                            useBackdropCapture = false,
                            onClick = {
                                copyTextToClipboard(context, "Recovery Phrase", words.joinToString(" "))
                                onCopiedMessage(walletStrings.copiedToClipboard)
                                showCopyWarningDialog = false
                            },
                        ) {
                            Text(walletStrings.copyAnywayButton, fontWeight = FontWeight.Bold)
                        }
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = false,
                            onClick = { showCopyWarningDialog = false },
                        ) {
                            Text(kineticStrings.cancel)
                        }
                    }
                },
            ) {
                Text(
                    text = walletStrings.copyPhraseWarningBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glassSecondaryContentColor(),
                )
            }
        }

        // Screenshot Detected Warning Dialog (full-screen edge-to-edge window scrim)
        if (showScreenshotWarningDialog) {
            FluidGlassDialog(
                onDismissRequest = { showScreenshotWarningDialog = false },
                windowOverlay = true,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = walletStrings.screenshotWarningTitle,
                            style = MaterialTheme.typography.titleMedium,
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
                        onClick = { showScreenshotWarningDialog = false },
                    ) {
                        Text(walletStrings.ok, fontWeight = FontWeight.SemiBold)
                    }
                },
            ) {
                Text(
                    text = walletStrings.screenshotWarningBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glassSecondaryContentColor(),
                )
            }
        }

        // Optional / Advanced Dice Roll Entropy Generator Dialog
        if (showDiceEntropyDialog) {
            DiceRollEntropyDialog(
                wordCount = wordCount,
                onApplyPhrase = { phrase ->
                    onApplyDicePhrase(phrase)
                    showDiceEntropyDialog = false
                },
                onDismiss = { showDiceEntropyDialog = false },
            )
        }
    }
}

@Composable
private fun DiceRollEntropyDialog(
    wordCount: Int,
    onApplyPhrase: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val requiredRolls = remember(wordCount) { Bip39.requiredDiceRollsForWordCount(wordCount) }
    val rolls = remember(wordCount) { mutableStateListOf<Int>() }

    FluidGlassDialog(
        onDismissRequest = onDismiss,
        windowOverlay = true,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = walletStrings.diceEntropyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                )
                Text(
                    text = walletStrings.diceEntropyBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                )
            }
        },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FluidGlassButton(
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                    enabled = rolls.size >= requiredRolls,
                    useBackdropCapture = false,
                    onClick = {
                        runCatching { Bip39.mnemonicFromDiceRolls(rolls.toList(), wordCount) }
                            .onSuccess(onApplyPhrase)
                    },
                ) {
                    Text(walletStrings.diceEntropyGenerateButton, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        enabled = rolls.isNotEmpty(),
                        useBackdropCapture = false,
                        onClick = { if (rolls.isNotEmpty()) rolls.removeAt(rolls.lastIndex) },
                    ) {
                        Text(walletStrings.diceUndoButton)
                    }
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = onDismiss,
                    ) {
                        Text(kineticStrings.cancel)
                    }
                }
            }
        },
    ) {
        Text(
            text = walletStrings.diceEntropyWarning,
            style = MaterialTheme.typography.bodySmall,
            color = glassSecondaryContentColor(),
        )
        Text(
            text = "${walletStrings.diceEntropyProgressLabel}: ${rolls.size} / $requiredRolls",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (rolls.size >= requiredRolls) MaterialTheme.colorScheme.primary else glassPrimaryContentColor(),
        )
        if (rolls.isNotEmpty()) {
            Text(
                text = rolls.takeLast(36).joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = glassSecondaryContentColor(),
            )
        }
        // 2x3 Dice keypad (1..6)
        listOf(listOf(1, 2, 3), listOf(4, 5, 6)).forEach { rowDice ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowDice.forEach { face ->
                    FluidGlassButton(
                        modifier = Modifier.weight(1f),
                        useBackdropCapture = false,
                        onClick = { rolls.add(face) },
                    ) {
                        Text("$face", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ============================================================================
// Step 5 (Create Only): Simple Kaspium-Style Backup Confirmation (NO 3-word quiz)
// ============================================================================

@Composable
private fun BackupConfirmationStep(
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = walletStrings.backupConfirmTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = glassPrimaryContentColor(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = walletStrings.backupConfirmBody,
                style = MaterialTheme.typography.bodyMedium,
                color = glassSecondaryContentColor(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(22.dp))
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                useBackdropCapture = false,
                onClick = onYes,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(walletStrings.backupConfirmYes, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                onClick = onNo,
            ) {
                Text(walletStrings.backupConfirmNo, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ============================================================================
// Step 6: Security PIN & Biometrics (Plain English, Launches Full-Screen PIN)
// ============================================================================

@Composable
private fun SecuritySetupStep(
    hasPin: Boolean,
    biometricsEnabled: Boolean,
    onConfigurePin: () -> Unit,
    onBiometricsChanged: (Boolean) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val context = LocalContext.current
    val bioAvailable = remember(context) { SecureVault.isBiometricAvailable(context) }
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = walletStrings.securitySetupTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = walletStrings.securitySetupSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = glassSecondaryContentColor(),
        )

        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = walletStrings.setUpPinTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = walletStrings.setUpPinDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                accent = !hasPin,
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = onConfigurePin,
            ) {
                MarqueeText(
                    text = if (hasPin) "${walletStrings.pinConfiguredBadge} — ${walletStrings.changePinButton}" else walletStrings.configurePinButton,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = walletStrings.biometricsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = if (bioAvailable) walletStrings.biometricsDesc else walletStrings.biometricsUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                }
                FluidGlassSwitch(
                    checked = biometricsEnabled && bioAvailable,
                    enabled = bioAvailable,
                    onCheckedChange = onBiometricsChanged,
                )
            }
        }
    }
}

// ============================================================================
// Step 7: Display Currency & Privacy (Easy Defaults)
// ============================================================================

@Composable
private fun CurrencyAndPrivacyStep(
    selectedCurrency: FiatCurrency,
    onSelectCurrency: (FiatCurrency) -> Unit,
    kasOnlyPrivacy: Boolean,
    onKasOnlyPrivacyChanged: (Boolean) -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = walletStrings.currencyStepTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = walletStrings.currencyStepSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = glassSecondaryContentColor(),
        )

        FluidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            useBackdropCapture = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = walletStrings.kasOnlyPrivacyTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = walletStrings.kasOnlyPrivacyDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                }
                FluidGlassSwitch(
                    checked = kasOnlyPrivacy,
                    onCheckedChange = onKasOnlyPrivacyChanged,
                )
            }
        }

        if (!kasOnlyPrivacy) {
            FluidOptionGridSelector(
                title = walletStrings.displayCurrencyLabel,
                items = FiatCurrency.entries,
                selectedItem = selectedCurrency,
                onSelect = onSelectCurrency,
                labelFor = { "${it.code} (${it.symbol})" },
                initiallyExpanded = true,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================================
// Step 8: Single Network & Node Config + Live Test Connection
// ============================================================================

@Composable
fun NodeConfigurationStep(
    nodeConfig: NodeConfig,
    onNodeConfigChange: (NodeConfig) -> Unit,
    onTestConnection: suspend (NodeConfig) -> NodeHealth,
    errorMessage: String? = null,
) {
    val walletStrings = LocalWalletStrings.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var lastHealth by remember { mutableStateOf<NodeHealth?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = walletStrings.nodeStepTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = glassPrimaryContentColor(),
        )
        Text(
            text = walletStrings.nodeStepSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = glassSecondaryContentColor(),
        )

        NodeConfigEditorBody(
            nodeConfig = nodeConfig,
            onNodeConfigChange = {
                lastHealth = null
                onNodeConfigChange(it)
            },
            testing = testing,
            lastHealth = lastHealth,
            onTestClick = {
                scope.launch {
                    testing = true
                    lastHealth = runCatching { onTestConnection(nodeConfig) }.getOrNull()
                    testing = false
                }
            },
        )

        if (!errorMessage.isNullOrBlank()) {
            ErrorInlineBanner(errorMessage)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Reusable Network & Node selector shared between Onboarding Step 8 and Settings -> Network & Node.
 */
@Composable
fun NodeConfigEditorBody(
    nodeConfig: NodeConfig,
    onNodeConfigChange: (NodeConfig) -> Unit,
    testing: Boolean,
    lastHealth: NodeHealth?,
    onTestClick: () -> Unit,
) {
    val walletStrings = LocalWalletStrings.current
    val isDark = LocalGlassDarkTheme.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = walletStrings.networkLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
        FluidGlassSegmentedControl(
            items = listOf(KaspaNetwork.MAINNET, KaspaNetwork.TESTNET_10),
            selectedItem = nodeConfig.network,
            onSelect = { net -> onNodeConfigChange(nodeConfig.copy(network = net)) },
            labelFor = { if (it == KaspaNetwork.MAINNET) walletStrings.mainnetLabel else walletStrings.testnet10Label },
        )

        Text(
            text = walletStrings.connectionModeLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
        val activeMode = nodeConfig.effectiveConnectionMode
        val modeOptions = listOf(
            Triple(
                NodeConnectionMode.AUTOMATIC_SCAN,
                walletStrings.nodeModeAutoScanTitle,
                walletStrings.nodeModeAutoScanDesc,
            ),
            Triple(
                NodeConnectionMode.TLS_ONLY,
                walletStrings.nodeModeTlsOnlyTitle,
                walletStrings.nodeModeTlsOnlyDesc,
            ),
            Triple(
                NodeConnectionMode.CUSTOM,
                walletStrings.nodeModeCustomTitle,
                walletStrings.nodeModeCustomDesc,
            ),
        )
        val backdrop = LocalGlassBackdrop.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            modeOptions.forEach { (mode, title, desc) ->
                val selected = activeMode == mode
                val reduceTransparency = LocalReduceTransparency.current
                val motion = rememberFluidMotionState()
                val cardShape = ContinuousCornerShape(22.dp)
                // Canonical Kinetic Aero glass pairing:
                // - Selected: GlassPreset.HighlightSurface (AGSL specular rim + primaryContainer glass)
                // - Unselected: GlassPreset.Control (matching the luminous Test Connection glass button!)
                val cardPreset = if (selected) GlassPreset.HighlightSurface else GlassPreset.Control
                val cardTint = when {
                    !selected -> null
                    isDark -> MaterialTheme.colorScheme.primaryContainer
                    reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                }
                val titleColor = when {
                    selected && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                    selected && isDark -> Color(0xFFF6FFF9)
                    else -> glassPrimaryContentColor()
                }
                val descColor = when {
                    selected && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    selected && isDark -> Color(0xFFDCEEE4)
                    else -> glassSecondaryContentColor()
                }
                val iconTint = when {
                    selected && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                    selected && isDark -> MaterialTheme.colorScheme.onPrimaryContainer
                    selected -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
                    else -> glassSecondaryContentColor()
                }
                FluidGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fluidTouchPhysics(
                            state = motion,
                            pressedScale = 0.96f,
                            lockParentGestures = false,
                        )
                        .clip(cardShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onNodeConfigChange(
                                nodeConfig.copy(
                                    connectionMode = mode,
                                    useCustomNode = (mode == NodeConnectionMode.CUSTOM),
                                ),
                            )
                        },
                    shape = cardShape,
                    preset = cardPreset,
                    backdrop = backdrop,
                    useBackdropCapture = backdrop != null && !reduceTransparency,
                    surfaceTint = cardTint,
                    motionState = motion,
                    pressShadowEnabled = true,
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(19.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = titleColor,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (mode == NodeConnectionMode.AUTOMATIC_SCAN) {
                                    val badgeShape = ContinuousCornerShape(10.dp)
                                    val badgeBg = when {
                                        reduceTransparency -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        isDark && selected -> Color.White.copy(alpha = 0.14f)
                                        isDark -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.80f)
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    }
                                    val badgeInk = when {
                                        reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isDark -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> readableAccentInk(MaterialTheme.colorScheme.primary, false)
                                    }
                                    val badgeBorder = if (isDark && !reduceTransparency) {
                                        Modifier.border(0.75.dp, Color.White.copy(alpha = 0.28f), badgeShape)
                                    } else {
                                        Modifier
                                    }
                                    Text(
                                        text = walletStrings.nodeModeRecommendedBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeInk,
                                        modifier = Modifier
                                            .clip(badgeShape)
                                            .background(badgeBg, badgeShape)
                                            .then(badgeBorder)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = descColor,
                            )
                        }
                    }
                }
            }
        }

        if (activeMode == NodeConnectionMode.CUSTOM) {
            FluidGlassTextField(
                value = nodeConfig.customGrpcHostPort,
                onValueChange = { onNodeConfigChange(nodeConfig.copy(customGrpcHostPort = it)) },
                label = walletStrings.customGrpcLabel,
                placeholder = walletStrings.customGrpcPlaceholder,
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = walletStrings.customGrpcTlsTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = glassPrimaryContentColor(),
                    )
                    Text(
                        text = walletStrings.customGrpcTlsDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = glassSecondaryContentColor(),
                    )
                }
                FluidGlassSwitch(
                    checked = nodeConfig.customGrpcUseTls,
                    onCheckedChange = { tls -> onNodeConfigChange(nodeConfig.copy(customGrpcUseTls = tls)) },
                )
            }
            FluidGlassTextField(
                value = nodeConfig.customRestUrl,
                onValueChange = { onNodeConfigChange(nodeConfig.copy(customRestUrl = it)) },
                label = walletStrings.customRestLabel,
                placeholder = walletStrings.customRestPlaceholder,
                singleLine = true,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val grpcSummary = if (activeMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    val port = if (nodeConfig.network.isTestnet) 16210 else 16110
                    "gRPC: ${walletStrings.nodeModeAutoScanTitle} (:$port / ${nodeConfig.network.defaultGrpcHost}:${nodeConfig.network.defaultGrpcPort})"
                } else {
                    "gRPC: ${nodeConfig.effectiveGrpcDisplay}"
                }
                Text(
                    text = grpcSummary,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = glassPrimaryContentColor(),
                )
                Text(
                    text = "REST: ${nodeConfig.effectiveRestUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = glassSecondaryContentColor(),
                )
            }
        }

        FluidGlassButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing,
            useBackdropCapture = false,
            lockParentGestures = false,
            onClick = onTestClick,
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(walletStrings.testingConnection, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(walletStrings.testConnection, fontWeight = FontWeight.SemiBold)
            }
        }

        if (lastHealth != null) {
            val reduceTrans = LocalReduceTransparency.current
            val tint = if (lastHealth.reachable) {
                if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            val cardTint = if (lastHealth.reachable) {
                if (isDark || reduceTrans) MaterialTheme.colorScheme.primaryContainer else Color(0xFFDCF7EA)
            } else {
                if (reduceTrans) MaterialTheme.colorScheme.errorContainer
                else if (isDark) Color(0xFF4A1820) else Color(0xFFFFDFE3)
            }
            val primaryInk = when {
                lastHealth.reachable && (isDark || reduceTrans) -> Color(0xFFF6FFF9)
                !lastHealth.reachable && reduceTrans -> MaterialTheme.colorScheme.onErrorContainer
                else -> glassPrimaryContentColor()
            }
            val secondaryInk = when {
                lastHealth.reachable && (isDark || reduceTrans) -> Color(0xFFDCEEE4)
                !lastHealth.reachable && reduceTrans -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                else -> glassSecondaryContentColor()
            }
            FluidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousCornerShape(20.dp),
                preset = GlassPreset.HighlightSurface,
                backdrop = backdrop,
                useBackdropCapture = backdrop != null && !reduceTrans,
                surfaceTint = cardTint,
                contentPadding = PaddingValues(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (lastHealth.reachable) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = when {
                                !lastHealth.reachable -> walletStrings.nodeUnreachable
                                lastHealth.isSynced -> walletStrings.nodeReachableSynced
                                else -> walletStrings.nodeReachableSyncing
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryInk,
                        )
                    }
                    if (lastHealth.reachable) {
                        val utxoLabel = if (lastHealth.isUtxoIndexed) walletStrings.utxoIndexReady else walletStrings.utxoIndexMissing
                        Text(
                            text = "${lastHealth.transportUsed} • $utxoLabel • ${walletStrings.latencyMsLabel}: ${lastHealth.latencyMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryInk,
                        )
                        if (lastHealth.virtualDaaScore > 0L) {
                            Text(
                                text = "${walletStrings.daaScoreLabel}: ${lastHealth.virtualDaaScore}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = secondaryInk,
                            )
                        }
                    } else if (!lastHealth.errorMessage.isNullOrBlank()) {
                        Text(
                            text = lastHealth.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDark && !reduceTrans) Color(0xFFFFB4B4) else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorInlineBanner(
    message: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    useBackdropCapture: Boolean = true,
    copyButtonLabel: String? = null,
    onCopy: (() -> Unit)? = null,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val warnGlassTint = when {
        reduceTransparency -> MaterialTheme.colorScheme.errorContainer
        isDark -> Color(0xFF42161C)
        else -> Color(0xFFFFDFE3)
    }
    val inkColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.onErrorContainer
    } else if (isDark) {
        Color(0xFFFFB4B4)
    } else {
        MaterialTheme.colorScheme.error
    }

    FluidGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = ContinuousCornerShape(24.dp),
        preset = GlassPreset.Card,
        backdrop = backdrop,
        useBackdropCapture = useBackdropCapture && backdrop != null && !reduceTransparency,
        surfaceTint = warnGlassTint,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = inkColor,
                modifier = Modifier.padding(top = 1.dp).size(19.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = inkColor,
                modifier = Modifier.weight(1f),
            )
        }
        if (onCopy != null && !copyButtonLabel.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            FluidGlassButton(
                modifier = Modifier.fillMaxWidth(),
                useBackdropCapture = false,
                lockParentGestures = false,
                onClick = onCopy,
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                MarqueeText(
                    text = copyButtonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
