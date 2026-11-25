package com.kaspakinetic.wallet.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.fragment.app.FragmentActivity
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidSprings
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidHapticsEnabled
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Enables `FLAG_SECURE` on the host window while sensitive seed/PIN content is visible.
 */
@Composable
fun SecureScreenEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window = (context as? Activity)?.window
        if (enabled && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

enum class PinFlowMode {
    CREATE,
    VERIFY,
    CHANGE,
    REMOVE,
}

/**
 * Dedicated full-screen 6-digit 0-9 Kinetic Aero PIN keypad.
 * - Renders inside the root window hierarchy (never an Android Dialog!) with full Aero Glass refraction.
 * - Auto-advances immediately on the 6th digit (zero "Continue" button chopping the 0 key).
 * - All PBKDF2 hashing/verification executes off the main thread via suspend callbacks.
 */
@Composable
fun FullScreenPinOverlay(
    mode: PinFlowMode,
    wallpaperPath: String?,
    wallpaperBrightness: Float,
    allowDismiss: Boolean = true,
    allowBiometrics: Boolean = false,
    onVerifyPin: suspend (String) -> Boolean,
    onPinCreated: suspend (String) -> Unit = {},
    onVerifiedSuccess: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    SecureScreenEffect(enabled = true)
    BackHandler(enabled = allowDismiss) { onDismiss() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalFluidHapticsEnabled.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember(mode) {
        mutableStateOf(
            when (mode) {
                PinFlowMode.CREATE -> 1 // 1 = enter new, 2 = confirm new
                PinFlowMode.VERIFY, PinFlowMode.REMOVE, PinFlowMode.CHANGE -> 0 // 0 = verify current
            },
        )
    }
    var entered by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val shakeOffset = remember { Animatable(0f) }

    fun triggerError(message: String) {
        errorMsg = message
        entered = ""
        if (hapticsEnabled) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
        scope.launch {
            for (dx in listOf(-18f, 18f, -12f, 12f, -6f, 0f)) {
                shakeOffset.animateTo(dx, spring(dampingRatio = 0.5f, stiffness = 2400f))
            }
        }
    }

    fun triggerBiometricPrompt() {
        val activity = context as? FragmentActivity ?: return
        SecureVault.promptBiometric(
            activity = activity,
            title = walletStrings.appName,
            subtitle = walletStrings.biometricsDesc,
            negativeButtonText = kineticStrings.cancel,
            onSuccess = {
                if (mode == PinFlowMode.VERIFY) {
                    onVerifiedSuccess()
                }
            },
            onFallbackOrError = {},
        )
    }

    LaunchedEffect(mode, allowBiometrics) {
        if (mode == PinFlowMode.VERIFY && allowBiometrics && SecureVault.isBiometricAvailable(context)) {
            delay(220L)
            triggerBiometricPrompt()
        }
    }

    fun onDigit(digit: String) {
        if (busy || entered.length >= 6) return
        errorMsg = null
        val next = entered + digit
        entered = next
        if (next.length == 6) {
            scope.launch {
                busy = true
                delay(70L) // Brief visual tick so user sees 6th dot illuminate before advancing
                when (stage) {
                    0 -> {
                        val valid = runCatching { onVerifyPin(next) }.getOrDefault(false)
                        if (valid) {
                            when (mode) {
                                PinFlowMode.VERIFY, PinFlowMode.REMOVE -> onVerifiedSuccess()
                                PinFlowMode.CHANGE -> {
                                    entered = ""
                                    stage = 1
                                }
                                PinFlowMode.CREATE -> Unit
                            }
                        } else {
                            triggerError(walletStrings.pinIncorrectError)
                        }
                    }
                    1 -> {
                        firstPin = next
                        entered = ""
                        stage = 2
                    }
                    2 -> {
                        if (next == firstPin) {
                            runCatching { onPinCreated(next) }
                                .onSuccess { onVerifiedSuccess() }
                                .onFailure { triggerError(it.message ?: walletStrings.pinMismatchError) }
                        } else {
                            firstPin = ""
                            stage = 1
                            triggerError(walletStrings.pinMismatchError)
                        }
                    }
                }
                busy = false
            }
        }
    }

    val titleText = when (stage) {
        0 -> if (mode == PinFlowMode.VERIFY) walletStrings.enterPinTitle else walletStrings.verifyCurrentPinTitle
        1 -> walletStrings.createPinTitle
        else -> walletStrings.confirmPinTitle
    }

    val ambientBackdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else ambientBackdrop

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(90f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(ambientBackdrop) else Modifier),
        ) {
            if (reduceTransparency) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            } else {
                AmbientLiquidBackdrop(
                    wallpaperPath = wallpaperPath,
                    wallpaperBrightness = wallpaperBrightness,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isDark) Color(0xFF0A0F14).copy(alpha = 0.52f)
                            else Color(0xFFF3F8F5).copy(alpha = 0.56f),
                        ),
                )
            }
        }

        CompositionLocalProvider(LocalGlassBackdrop provides effectiveBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top back button bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (allowDismiss) {
                        FluidGlassIconButton(
                            onClick = {
                                if (stage == 2) {
                                    stage = 1
                                    firstPin = ""
                                    entered = ""
                                    errorMsg = null
                                } else {
                                    onDismiss()
                                }
                            },
                            preset = GlassPreset.FloatingBar,
                            backdrop = effectiveBackdrop,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = kineticStrings.back,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(42.dp))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.weight(0.6f))

                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = glassPrimaryContentColor(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(22.dp))

                // 6-digit animated dot indicators
                Row(
                    modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(6) { idx ->
                        val filled = idx < entered.length
                        val dotColor by animateColorAsState(
                            targetValue = when {
                                errorMsg != null -> MaterialTheme.colorScheme.error
                                filled -> MaterialTheme.colorScheme.primary
                                isDark -> Color.White.copy(alpha = 0.20f)
                                else -> Color.Black.copy(alpha = 0.14f)
                            },
                            animationSpec = FluidSprings.pillSlide(),
                            label = "pin_dot_$idx",
                        )
                        Box(
                            modifier = Modifier
                                .size(if (filled) 16.dp else 14.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .border(
                                    width = 1.dp,
                                    color = if (filled) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (errorMsg != null) {
                        Text(
                            text = errorMsg.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.8f))

                // Fixed 3x4 Aero Glass 0-9 keypad (never chopped, full spring physics & Tier 1 glass)
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    rows.forEach { rowDigits ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowDigits.forEach { d ->
                                PinKeypadDigitButton(
                                    digit = d,
                                    backdrop = effectiveBackdrop,
                                    enabled = !busy,
                                    onClick = { onDigit(d) },
                                )
                            }
                        }
                    }

                    // Bottom row: Biometrics (optional) | 0 | Backspace
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mode == PinFlowMode.VERIFY && allowBiometrics && SecureVault.isBiometricAvailable(context)) {
                            FluidGlassIconButton(
                                onClick = { triggerBiometricPrompt() },
                                size = 72.dp,
                                shape = CircleShape,
                                preset = GlassPreset.Control,
                                backdrop = effectiveBackdrop,
                                lockParentGestures = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = walletStrings.useBiometricsButton,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(72.dp))
                        }

                        PinKeypadDigitButton(
                            digit = "0",
                            backdrop = effectiveBackdrop,
                            enabled = !busy,
                            onClick = { onDigit("0") },
                        )

                        FluidGlassIconButton(
                            onClick = {
                                if (!busy && entered.isNotEmpty()) {
                                    entered = entered.dropLast(1)
                                    errorMsg = null
                                }
                            },
                            size = 72.dp,
                            shape = CircleShape,
                            preset = GlassPreset.Control,
                            backdrop = effectiveBackdrop,
                            lockParentGestures = true,
                            enabled = !busy && entered.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = walletStrings.clear,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PinKeypadDigitButton(
    digit: String,
    backdrop: Backdrop?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val motion = rememberFluidMotionState()
    Box(
        modifier = Modifier
            .size(72.dp)
            .fluidTouchPhysics(
                enabled = enabled,
                state = motion,
                pressedScale = 0.90f,
                releaseStretchAmount = 0.05f,
                lockParentGestures = true,
            )
            .liquidGlassSurface(
                shape = CircleShape,
                preset = GlassPreset.Control,
                backdrop = backdrop,
                motionState = motion,
                pressShadowEnabled = true,
                useBackdropCapture = true,
            )
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = glassPrimaryContentColor(),
        )
    }
}
