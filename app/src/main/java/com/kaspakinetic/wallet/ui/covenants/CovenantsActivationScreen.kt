package com.kaspakinetic.wallet.ui.covenants

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.KineticFloatingTopHeaderOverlay
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
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
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import com.kaspakinetic.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-Time Safe Activation Flow for Kaspa L1 SilverScript Covenants:
 * - Educates clearly on L1 covenants, archival-node pruning vs `.kinetic` auto-backups, and L1 rules
 *   WITHOUT forcing the user to export an empty file upfront.
 * - Includes optional 1-tap "Already have a .kinetic file? Restore Backup" for returning users.
 * - Requires acknowledgment checkbox + live 5-second countdown (`Proceed (5s)` -> `Proceed`) on a
 *   canonical 3D Kinetic Aero button (`lockParentGestures = true`) before activation.
 */
@Composable
fun CovenantsActivationScreen(
    appearance: KineticAeroAppearanceSettings,
    viewModel: WalletViewModel,
    onActivated: (autoScanChain: Boolean) -> Unit,
    onDismiss: () -> Unit,
    readOnlyReviewMode: Boolean = false,
) {
    BackHandler { onDismiss() }

    val s = LocalWalletStrings.current
    val ks = LocalKineticStrings.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var acknowledged by remember { mutableStateOf(false) }
    var countdownSec by remember { mutableIntStateOf(5) }
    var topChromeHeightPx by remember { mutableIntStateOf(with(density) { 104.dp.roundToPx() }) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreNeedsPassphrase by remember { mutableStateOf(false) }
    var restorePassphrase by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }

    // 5-second countdown starts ONLY while checkbox is checked; unchecking resets immediately
    LaunchedEffect(acknowledged) {
        if (!acknowledged) {
            countdownSec = 5
            return@LaunchedEffect
        }
        countdownSec = 5
        while (countdownSec > 0) {
            delay(1_000L)
            countdownSec -= 1
        }
    }

    val openKineticLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val inspect = viewModel.inspectKineticBackupUri(uri)
                inspect.onSuccess { env ->
                    if (env.kdf == "pbkdf2-hmac-sha256") {
                        pendingRestoreUri = uri
                        restorePassphrase = ""
                        restoreError = null
                        restoreNeedsPassphrase = true
                    } else {
                        val res = viewModel.restoreKineticBackupFromUri(uri)
                        res.onSuccess { rep ->
                            viewModel.showMessage(safeFormatLocalized(s.covRestoredBackupMsgFmt, rep.merged.size, rep.addedCount))
                            onActivated(false)
                        }.onFailure { err ->
                            viewModel.showMessage(err.message ?: "Could not restore .kinetic backup")
                        }
                    }
                }.onFailure { err ->
                    viewModel.showMessage(err.message ?: "Invalid .kinetic backup file")
                }
            }
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = !gestureLocked)
                        .navigationBarsPadding()
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            top = topPadDp + 12.dp,
                            bottom = 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ActivationInfoCard(
                        icon = Icons.Default.Security,
                        title = s.covActivationIntroTitle,
                        body = s.covActivationIntroBody,
                        backdrop = effectiveAmbient,
                    )

                    ActivationInfoCard(
                        icon = Icons.Default.Backup,
                        title = s.covActivationBackupTitle,
                        body = s.covActivationBackupBody,
                        extraBody = s.covActivationAutoSaveBody,
                        backdrop = effectiveAmbient,
                        highlighted = true,
                    )

                    ActivationInfoCard(
                        icon = Icons.Default.Gavel,
                        title = s.covActivationRulesTitle,
                        body = s.covActivationRulesBody,
                        backdrop = effectiveAmbient,
                    )

                    if (readOnlyReviewMode) {
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            accent = true,
                            useBackdropCapture = true,
                            backdrop = effectiveAmbient,
                            lockParentGestures = true,
                            onClick = onDismiss,
                        ) {
                            Text(s.done, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Optional quick restore for returning users moving from another phone
                        FluidGlassButton(
                            modifier = Modifier.fillMaxWidth(),
                            useBackdropCapture = true,
                            backdrop = effectiveAmbient,
                            lockParentGestures = false,
                            onClick = { openKineticLauncher.launch(arrayOf("*/*")) },
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.covActivationRestoreButton, fontWeight = FontWeight.SemiBold)
                        }

                        // Mandatory acknowledgment checkbox + 5-second countdown hero CTA
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
                                    ) { acknowledged = !acknowledged },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = acknowledged,
                                    onCheckedChange = { acknowledged = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        uncheckedColor = glassPrimaryContentColor().copy(alpha = 0.72f),
                                    ),
                                )
                                Text(
                                    text = s.covActivationCheckbox,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = glassPrimaryContentColor(),
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            val canProceed = acknowledged && countdownSec == 0
                            val proceedLabel = when {
                                !acknowledged -> safeFormatLocalized(s.covActivationProceedCountdownFmt, 5)
                                countdownSec > 0 -> safeFormatLocalized(s.covActivationProceedCountdownFmt, countdownSec)
                                else -> s.covActivationProceedButton
                            }

                            FluidGlassButton(
                                modifier = Modifier.fillMaxWidth(),
                                accent = true,
                                enabled = canProceed,
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    viewModel.activateCovenants()
                                    viewModel.startPostActivationBlockDagScan()
                                    onActivated(true)
                                },
                            ) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(proceedLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Pinned Floating Top Header + Progressive Blur + In-Window Passphrase Dialog
        CompositionLocalProvider(
            LocalOverlayBackdrop provides effectiveViewport,
            LocalGlassBackdrop provides effectiveViewport,
        ) {
            KineticFloatingTopHeaderOverlay(
                title = if (readOnlyReviewMode) s.covReadActivationWarningTitle else s.covActivationTitle,
                backdrop = effectiveViewport,
                measuredHeightPx = topChromeHeightPx,
                onHeightMeasured = { topChromeHeightPx = it },
                leadingButton = {
                    FluidGlassIconButton(
                        onClick = onDismiss,
                        size = 42.dp,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveViewport,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = ks.back, modifier = Modifier.size(20.dp))
                    }
                },
            )

            if (restoreNeedsPassphrase && pendingRestoreUri != null) {
                FluidGlassDialog(
                    onDismissRequest = {
                        restoreNeedsPassphrase = false
                        pendingRestoreUri = null
                    },
                    title = {
                        Text(
                            text = s.covPassphrasePromptTitle,
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
                                onClick = {
                                    restoreNeedsPassphrase = false
                                    pendingRestoreUri = null
                                },
                            ) {
                                Text(ks.cancel)
                            }
                            FluidGlassButton(
                                modifier = Modifier.weight(1f),
                                accent = true,
                                enabled = restorePassphrase.isNotEmpty(),
                                useBackdropCapture = false,
                                lockParentGestures = true,
                                onClick = {
                                    val uri = pendingRestoreUri ?: return@FluidGlassButton
                                    scope.launch {
                                        val res = viewModel.restoreKineticBackupFromUri(uri, restorePassphrase)
                                        res.onSuccess { rep ->
                                            restoreNeedsPassphrase = false
                                            pendingRestoreUri = null
                                            viewModel.showMessage(safeFormatLocalized(s.covRestoredBackupMsgFmt, rep.merged.size, rep.addedCount))
                                            onActivated(false)
                                        }.onFailure { err ->
                                            restoreError = err.message ?: "Wrong passphrase"
                                        }
                                    }
                                },
                            ) {
                                Text(s.confirm, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                ) {
                    FluidGlassTextField(
                        value = restorePassphrase,
                        onValueChange = { restorePassphrase = it; restoreError = null },
                        placeholder = s.passphrasePlaceholder,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (!restoreError.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = restoreError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivationInfoCard(
    icon: ImageVector,
    title: String,
    body: String,
    extraBody: String? = null,
    backdrop: Backdrop?,
    highlighted: Boolean = false,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val preset = if (highlighted && isDark) GlassPreset.HighlightSurface else GlassPreset.Card
    val surfaceTint = when {
        highlighted && reduceTransparency -> MaterialTheme.colorScheme.primaryContainer
        highlighted && isDark -> MaterialTheme.colorScheme.primaryContainer
        highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else -> null
    }
    val titleColor = when {
        highlighted && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
        highlighted && isDark -> Color(0xFFF4FFF8)
        else -> glassPrimaryContentColor()
    }
    val bodyColor = when {
        highlighted && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
        highlighted && isDark -> Color(0xFFD6EDE0)
        else -> glassSecondaryContentColor()
    }
    val iconTint = when {
        highlighted && reduceTransparency -> MaterialTheme.colorScheme.onPrimaryContainer
        highlighted && isDark -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    }

    FluidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = backdrop,
        preset = preset,
        useBackdropCapture = !highlighted,
        surfaceTint = surfaceTint,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(ContinuousCornerShape(12.dp))
                    .background(
                        if (highlighted && isDark) Color.White.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.13f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
        )
        if (!extraBody.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = extraBody,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
            )
        }
    }
}
