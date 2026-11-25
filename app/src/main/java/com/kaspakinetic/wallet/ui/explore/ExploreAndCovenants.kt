package com.kaspakinetic.wallet.ui.explore

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.ui.components.MarqueeText
import com.kaspakinetic.wallet.ui.components.copyTextToClipboard
import com.kaspakinetic.wallet.ui.glass.AmbientLiquidBackdrop
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassBottomSheet
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassCard
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassTextField
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalFluidGestureCoordinator
import com.kaspakinetic.wallet.ui.glass.LocalGlassBackdrop
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.layerBackdrop
import com.kaspakinetic.wallet.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.glass.kineticInteractiveRow
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk

// ============================================================================
// Stage 2 — Explore Placeholders (v1 strictly UI placeholders only!)
// ============================================================================

enum class ExplorePlaceholderKind {
    BLOCK_EXPLORER,
    NETWORK_STATS,
    BLOCKDAG_VISUALIZER,
    YOUR_PERFORMANCE,
    COVENANTS,
}

@Composable
fun ExplorePlaceholderOverlay(
    kind: ExplorePlaceholderKind,
    initialQuery: String,
    network: KaspaNetwork,
    appearance: KineticAeroAppearanceSettings,
    onNotify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val context = LocalContext.current
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val gestureLocked = LocalFluidGestureCoordinator.current?.isLocked == true

    var searchQuery by remember(initialQuery) { mutableStateOf(initialQuery) }

    val screenTitle = when (kind) {
        ExplorePlaceholderKind.BLOCK_EXPLORER -> walletStrings.blockExplorerTitle
        ExplorePlaceholderKind.NETWORK_STATS -> walletStrings.networkStatsTitle
        ExplorePlaceholderKind.BLOCKDAG_VISUALIZER -> walletStrings.blockDagTitle
        ExplorePlaceholderKind.YOUR_PERFORMANCE -> walletStrings.yourPerformanceTitle
        ExplorePlaceholderKind.COVENANTS -> walletStrings.covHubTitle
    }
    val heroIcon = when (kind) {
        ExplorePlaceholderKind.BLOCK_EXPLORER -> Icons.Default.Search
        ExplorePlaceholderKind.NETWORK_STATS -> Icons.Default.Analytics
        ExplorePlaceholderKind.BLOCKDAG_VISUALIZER -> Icons.Default.Hub
        ExplorePlaceholderKind.YOUR_PERFORMANCE -> Icons.Default.Analytics
        ExplorePlaceholderKind.COVENANTS -> Icons.Default.Security
    }
    val heroTitle = when (kind) {
        ExplorePlaceholderKind.BLOCK_EXPLORER -> walletStrings.blockExplorerV2Title
        ExplorePlaceholderKind.NETWORK_STATS -> walletStrings.networkStatsV2Title
        ExplorePlaceholderKind.BLOCKDAG_VISUALIZER -> walletStrings.blockDagV2Title
        ExplorePlaceholderKind.YOUR_PERFORMANCE -> walletStrings.yourPerformanceTitle
        ExplorePlaceholderKind.COVENANTS -> walletStrings.covenantsHeaderTitle
    }
    val heroBody = when (kind) {
        ExplorePlaceholderKind.BLOCK_EXPLORER -> walletStrings.blockExplorerV2Body
        ExplorePlaceholderKind.NETWORK_STATS -> walletStrings.networkStatsV2Body
        ExplorePlaceholderKind.BLOCKDAG_VISUALIZER -> walletStrings.blockDagV2Body
        ExplorePlaceholderKind.YOUR_PERFORMANCE -> walletStrings.yourPerformanceEmptyBody
        ExplorePlaceholderKind.COVENANTS -> walletStrings.covenantsHeaderSubtitle
    }

    val backdrop = rememberLayerBackdrop()
    val effectiveBackdrop: Backdrop? = if (reduceTransparency) null else backdrop
    val hasCustomWallpaper = !appearance.wallpaperPath.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(65f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!reduceTransparency) Modifier.layerBackdrop(backdrop) else Modifier),
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

        CompositionLocalProvider(LocalGlassBackdrop provides effectiveBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FluidGlassIconButton(
                        onClick = onDismiss,
                        preset = GlassPreset.FloatingBar,
                        backdrop = effectiveBackdrop,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = kineticStrings.back,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    MarqueeText(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                        modifier = Modifier.weight(1f),
                    )
                    StageBadgePill(text = walletStrings.comingInV2Badge)
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState(), enabled = !gestureLocked),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (kind == ExplorePlaceholderKind.BLOCK_EXPLORER) {
                        FluidGlassTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = walletStrings.blockExplorerSearchPlaceholder,
                            singleLine = true,
                            backdrop = effectiveBackdrop,
                            useBackdropCapture = true,
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = glassPrimaryContentColor().copy(alpha = 0.78f),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }

                    FluidGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backdrop = effectiveBackdrop,
                        contentPadding = PaddingValues(24.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.CenterHorizontally)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = heroIcon,
                                contentDescription = null,
                                tint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = heroTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = glassPrimaryContentColor(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = heroBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = glassSecondaryContentColor(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (kind == ExplorePlaceholderKind.BLOCK_EXPLORER) {
                            Spacer(Modifier.height(20.dp))
                            val trimmed = searchQuery.trim()
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FluidGlassButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = true,
                                    useBackdropCapture = false,
                                    lockParentGestures = false,
                                    onClick = {
                                        val url = buildKaspaStreamUrl(network, trimmed)
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = walletStrings.openOnKaspaStreamButton,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                }
                                if (trimmed.isNotEmpty()) {
                                    FluidGlassButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        useBackdropCapture = false,
                                        lockParentGestures = false,
                                        onClick = {
                                            copyTextToClipboard(context, "Kaspa ID", trimmed)
                                            onNotify(walletStrings.copiedToClipboard)
                                        },
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(walletStrings.copyIdButton, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildKaspaStreamUrl(network: KaspaNetwork, query: String): String {
    val base = network.explorerBaseUrl
    if (query.isBlank()) return base
    return when {
        query.startsWith("kaspa:") || query.startsWith("kaspatest:") -> "$base/addresses/$query"
        query.length == 64 && query.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> "$base/transactions/$query"
        else -> base
    }
}

@Composable
fun StageBadgePill(text: String) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val shape = ContinuousCornerShape(50.dp)
    val bg = if (reduceTransparency) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.24f else 0.14f)
    }
    val ink = if (reduceTransparency) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
        )
    }
}
