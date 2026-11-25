package com.kaspakinetic.wallet.ui.components

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.kaspakinetic.wallet.ui.glass.ProgressiveGlassEdgeBand
import com.kaspakinetic.wallet.ui.glass.backdrop.Backdrop
import com.kaspakinetic.wallet.ui.glass.liquidGlassSurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.ui.glass.ContinuousCornerShape
import com.kaspakinetic.wallet.ui.glass.FluidGlassButton
import com.kaspakinetic.wallet.ui.glass.FluidGlassIconButton
import com.kaspakinetic.wallet.ui.glass.GlassPreset
import com.kaspakinetic.wallet.ui.glass.LocalGlassDarkTheme
import com.kaspakinetic.wallet.ui.glass.LocalReduceTransparency
import com.kaspakinetic.wallet.ui.glass.glassPrimaryContentColor
import com.kaspakinetic.wallet.ui.glass.glassSecondaryContentColor
import com.kaspakinetic.wallet.ui.locale.LocalKineticStrings
import com.kaspakinetic.wallet.ui.locale.LocalWalletStrings
import com.kaspakinetic.wallet.ui.theme.readableAccentInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun copyTextToClipboard(context: Context, label: String, value: String) {
    runCatching {
        val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        mgr?.setPrimaryClip(ClipData.newPlainText(label, value))
    }
}

fun readTextFromClipboard(context: Context): String? = runCatching {
    val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    mgr?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/**
 * Single-line text that stays completely stationary when it fits, and smoothly auto-scrolls
 * horizontally (marquee) with a gentle pause whenever it overflows narrow 6.2" buttons, pills,
 * or translated headers so labels are never truncated blindly with `...`.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
) {
    Text(
        text = text,
        color = color,
        style = style,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 1_200,
            repeatDelayMillis = 1_500,
            velocity = 36.dp,
        ),
    )
}

/**
 * Canonical pinned Kinetic Aero floating top header matching `KineticAeroSettingsScreen`:
 * - Status-bar legibility veil so scrolling content never clashes with system icons.
 * - Top `ProgressiveGlassEdgeBand` (respects `LocalDisableProgressiveBlur` / `LocalReduceTransparency`).
 * - Floating circular glass leading button (Back or Drawer Menu) + frosted Kinetic Aero Title Pill
 *   (preserving smooth horizontal marquee for long titles) + optional trailing circular action buttons
 *   and optional pinned sub-bar (e.g. segmented control).
 */
/** Provided at app root from `walletState.activeNetwork.isTestnet` so EVERY floating top header displays the exact `TN10` pill on Testnet-10! */
val LocalIsTestnetNetwork = compositionLocalOf { false }

/** Pure helper hiding `(X)` filter pill counts whenever Privacy Mask (`hideBalances`) is active. */
fun formatFilterChipLabel(baseLabel: String, count: Int, hideBalances: Boolean): String =
    if (hideBalances) baseLabel else "$baseLabel ($count)"

/** Exact Home `TN10` / `WO` status pill reused identically inside EVERY floating top header across the app. */
@Composable
fun SmallStatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.20f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
    }
}

/**
 * Exact Home balance-card spinning Refresh button reused across ALL screens
 * (MainWallet, Covenants Hub, Covenant Detail, Block Explorer, Network Stats, Your Performance).
 */
@Composable
fun KineticRefreshIconButton(
    isRefreshing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 18.dp,
    preset: GlassPreset = GlassPreset.FloatingBar,
    backdrop: Backdrop? = null,
    useBackdropCapture: Boolean = backdrop != null,
    lockParentGestures: Boolean = false,
) {
    FluidGlassIconButton(
        onClick = onClick,
        modifier = modifier,
        size = size,
        preset = preset,
        backdrop = backdrop,
        useBackdropCapture = useBackdropCapture,
        lockParentGestures = lockParentGestures,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun BoxScope.KineticFloatingTopHeaderOverlay(
    title: String,
    backdrop: Backdrop?,
    measuredHeightPx: Int,
    onHeightMeasured: (Int) -> Unit,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    showTestnetTag: Boolean = true,
    titleLeading: (@Composable RowScope.() -> Unit)? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    leadingButton: (@Composable () -> Unit)? = null,
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
    subBarContent: (@Composable () -> Unit)? = null,
) {
    val isDark = LocalGlassDarkTheme.current
    val isTestnet = LocalIsTestnetNetwork.current
    val reduceTransparency = LocalReduceTransparency.current
    val density = LocalDensity.current
    val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val measuredHeightDp = with(density) { measuredHeightPx.toDp() }
    val veilColor = if (isDark) Color(0xFF0A0D15) else Color(0xFFF3F6FC)

    // Subtle status-bar veil so scrolled content never clashes with the clock or battery icons
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(statusTopDp + 18.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        veilColor.copy(alpha = if (reduceTransparency) 1.0f else 0.90f),
                        veilColor.copy(alpha = if (reduceTransparency) 0.94f else 0.56f),
                        Color.Transparent,
                    ),
                ),
            ),
    )

    // Top Progressive Blur band (0 draw calls when Progressive Blur is OFF or Transparency is disabled)
    ProgressiveGlassEdgeBand(
        backdrop = backdrop,
        fromTop = true,
        maxBlurRadius = 16.dp,
        tintMaxAlpha = if (isDark) 0.56f else 0.62f,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(maxOf(statusTopDp + 86.dp, measuredHeightDp + 14.dp)),
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .onSizeChanged { onHeightMeasured(it.height) }
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingButton != null) {
                leadingButton()
            }

            val headerShape = ContinuousCornerShape(50.dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .liquidGlassSurface(
                        shape = headerShape,
                        preset = GlassPreset.FloatingBar,
                        backdrop = backdrop,
                    )
                    .clip(headerShape)
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTitleClick,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = if (subtitle.isNullOrBlank()) 10.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (titleLeading != null) {
                    titleLeading()
                }
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassPrimaryContentColor(),
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = readableAccentInk(MaterialTheme.colorScheme.primary, isDark),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showTestnetTag && isTestnet) {
                    SmallStatusTag(text = "TN10", color = MaterialTheme.colorScheme.tertiary)
                }
                if (titleTrailing != null) {
                    titleTrailing()
                }
            }

            if (trailingActions != null) {
                trailingActions()
            }
        }

        if (subBarContent != null) {
            subBarContent()
        }
    }
}

/**
 * Allows screenshots on the screen while actively detecting whenever the user takes a screenshot
 * so the wallet can warn them immediately. Uses Android 14+ official `registerScreenCaptureCallback`
 * with a lightweight MediaStore observer fallback on Android 9–13.
 */
@Composable
fun ScreenshotDetectorEffect(
    enabled: Boolean = true,
    onScreenshotDetected: () -> Unit,
) {
    val context = LocalContext.current
    val latestCallback = androidx.compose.runtime.rememberUpdatedState(onScreenshotDetected)

    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val activity = context as? Activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activity != null) {
            val callback = Activity.ScreenCaptureCallback {
                latestCallback.value()
            }
            val registered = runCatching {
                activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
                true
            }.getOrDefault(false)
            onDispose {
                if (registered) {
                    runCatching { activity.unregisterScreenCaptureCallback(callback) }
                }
            }
        } else {
            val resolver = context.contentResolver
            val handler = Handler(Looper.getMainLooper())
            var lastFiredMs = 0L
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    val now = System.currentTimeMillis()
                    if (now - lastFiredMs < 2_500L) return
                    val path = uri?.toString().orEmpty()
                    if (path.contains("images", ignoreCase = true) || path.contains("screenshot", ignoreCase = true)) {
                        lastFiredMs = now
                        latestCallback.value()
                    }
                }
            }
            val registered = runCatching {
                resolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer,
                )
                true
            }.getOrDefault(false)
            onDispose {
                if (registered) {
                    runCatching { resolver.unregisterContentObserver(observer) }
                }
            }
        }
    }
}

/**
 * Renders a Kaspa address with the `kaspa:` / `kaspatest:` prefix AND the final 8 checksum
 * characters color-highlighted for instant visual verification.
 */
@Composable
fun HighlightedKaspaAddressText(
    address: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    fontSizeSp: Float = 13f,
) {
    val isDark = LocalGlassDarkTheme.current
    val accent = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val baseInk = glassPrimaryContentColor()
    val parts = remember(address) { KaspaAddress.splitForHighlight(address) }

    val annotated = remember(parts, accent, baseInk) {
        buildAnnotatedString {
            if (parts.prefixWithColon.isNotEmpty()) {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                    append(parts.prefixWithColon)
                }
            }
            withStyle(SpanStyle(color = baseInk, fontWeight = FontWeight.Normal)) {
                append(parts.middleBody)
            }
            if (parts.checksum8.isNotEmpty()) {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                    append(parts.checksum8)
                }
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 5f).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Renders a scannable ZXing QR code inside a high-contrast squircle frame.
 */
@Composable
fun KaspaQrCodeBox(
    data: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 200.dp,
) {
    val bitmap = remember(data) {
        runCatching {
            val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }

    val shape = ContinuousCornerShape(22.dp)
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(shape)
            .background(Color.White, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = data,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Evenly spaced 3-column numbered recovery phrase grid modeled after Kaspium.
 * Designed so all 12 or 24 words fit comfortably on a single screen with zero vertical scrolling,
 * and static word cells NEVER block or intercept touch/scroll gestures.
 */
@Composable
fun RecoverySeedGrid3Col(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalGlassDarkTheme.current
    val reduceTransparency = LocalReduceTransparency.current
    val cellShape = ContinuousCornerShape(14.dp)
    val cellBg = when {
        reduceTransparency -> MaterialTheme.colorScheme.surfaceContainerHigh
        isDark -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.78f)
    }
    val cellBorder = when {
        reduceTransparency -> MaterialTheme.colorScheme.outlineVariant
        isDark -> Color.White.copy(alpha = 0.20f)
        else -> Color.White.copy(alpha = 0.90f)
    }
    val indexTint = readableAccentInk(MaterialTheme.colorScheme.primary, isDark)
    val rows = remember(words) { words.chunked(3) }
    val compact = words.size > 15

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        rows.forEachIndexed { rowIdx, rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowWords.forEachIndexed { colIdx, word ->
                    val number = rowIdx * 3 + colIdx + 1
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(cellShape)
                            .background(cellBg, cellShape)
                            .border(0.75.dp, cellBorder, cellShape)
                            .padding(
                                horizontal = 8.dp,
                                vertical = if (compact) 7.dp else 11.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = "$number.",
                            fontSize = if (compact) 11.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = indexTint,
                            maxLines = 1,
                        )
                        Text(
                            text = word,
                            fontSize = if (compact) 12.sp else 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = glassPrimaryContentColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - rowWords.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Full-screen in-window CameraX + bundled native `zxing-cpp` QR Code Scanner (zero Play Services /
 * 100% offline & F-Droid safe). Works reliably across Samsung, Oppo (ColorOS), Meizu (Flyme OS),
 * and all Android camera HALs via native `ImageProxy` row-stride/rotation handling, continuous AF
 * + tap-to-focus + periodic center metering, torch toggle, gallery image scan, and clipboard paste.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun FullScreenQrScannerOverlay(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val walletStrings = LocalWalletStrings.current
    val kineticStrings = LocalKineticStrings.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    var torchEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var galleryErrorMessage by remember { mutableStateOf<String?>(null) }
    var isDecodingGallery by remember { mutableStateOf(false) }
    val deliveredFlag = remember { AtomicBoolean(false) }
    val isDismissedFlag = remember { AtomicBoolean(false) }
    val activeProviderRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val activeAnalysisRef = remember { AtomicReference<ImageAnalysis?>(null) }
    val activeExecutorRef = remember { AtomicReference<ExecutorService?>(null) }

    /**
     * Immediately releases any active CameraX session (clears analyzer, turns off torch, unbinds
     * all use cases on main thread so Android's green camera indicator disappears instantly, and
     * shuts down the session's analyzer executor). Safe to invoke multiple times.
     */
    fun releaseActiveSessionNow(markDismissed: Boolean) {
        if (markDismissed) {
            isDismissedFlag.set(true)
        }
        runCatching { activeAnalysisRef.getAndSet(null)?.clearAnalyzer() }
        val providerToUnbind = activeProviderRef.getAndSet(null)
        val unbindBlock = {
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            runCatching { providerToUnbind?.unbindAll() }
            boundCamera = null
            torchEnabled = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            unbindBlock()
        } else {
            ContextCompat.getMainExecutor(context).execute(unbindBlock)
        }
        activeExecutorRef.getAndSet(null)?.let { exec ->
            runCatching { exec.shutdownNow() }
        }
    }

    BackHandler {
        releaseActiveSessionNow(markDismissed = true)
        onDismiss()
    }

    val barcodeReader = remember {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                maxNumberOfSymbols = 1,
            ),
        )
    }

    fun handlePickedImageUri(uri: Uri?) {
        if (uri == null || deliveredFlag.get() || isDismissedFlag.get()) return
        galleryErrorMessage = null
        isDecodingGallery = true
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeQrFromGalleryUri(context, uri, barcodeReader)
            }
            isDecodingGallery = false
            if (isDismissedFlag.get() || deliveredFlag.get()) return@launch
            if (!decoded.isNullOrBlank()) {
                if (deliveredFlag.compareAndSet(false, true)) {
                    releaseActiveSessionNow(markDismissed = true)
                    onResult(decoded)
                }
            } else {
                galleryErrorMessage = walletStrings.qrNoCodeFoundInImage
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handlePickedImageUri(uri) }

    val fallbackGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> handlePickedImageUri(uri) }

    // Auto-clear inline gallery error banner after 4s so scanner stays unobstructed
    LaunchedEffect(galleryErrorMessage) {
        if (galleryErrorMessage != null) {
            delay(4_000L)
            galleryErrorMessage = null
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Track lifecycle ON_RESUME / ON_PAUSE / ON_STOP cleanly so permission dialogs & gallery picker
    // temporarily release CameraX on ON_PAUSE and re-bind cleanly with a fresh executor on ON_RESUME!
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    isResumed = true
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isResumed = false
                    releaseActiveSessionNow(markDismissed = false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseActiveSessionNow(markDismissed = true)
        }
    }

    // Bind CameraX ONLY when permission is granted AND lifecycle is RESUMED AND PreviewView is ready.
    // Every active session allocates its own fresh single-thread executor and tears down cleanly in onDispose!
    DisposableEffect(hasCameraPermission, isResumed, previewViewRef) {
        val pv = previewViewRef
        if (!hasCameraPermission || !isResumed || pv == null || isDismissedFlag.get() || deliveredFlag.get()) {
            return@DisposableEffect onDispose {}
        }
        val sessionActive = AtomicBoolean(true)
        val sessionExecutor = Executors.newSingleThreadExecutor()
        activeExecutorRef.getAndSet(sessionExecutor)?.let { old -> runCatching { old.shutdownNow() } }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                runCatching {
                    val provider = cameraProviderFuture.get()
                    if (!sessionActive.get() || isDismissedFlag.get() || deliveredFlag.get()) {
                        provider.unbindAll()
                        return@runCatching
                    }
                    activeProviderRef.set(provider)
                    val previewBuilder = Preview.Builder()
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    val preview = previewBuilder.build().also {
                        it.surfaceProvider = pv.surfaceProvider
                    }
                    // ~1280x720 (~1 MP) keeps Oppo/Meizu high-MP sensors fast & stutter-free
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    val analysis = analysisBuilder.build()
                    activeAnalysisRef.set(analysis)
                    analysis.setAnalyzer(sessionExecutor) { imageProxy ->
                        imageProxy.use { proxy ->
                            if (!sessionActive.get() || isDismissedFlag.get() || deliveredFlag.get()) return@use
                            val decoded = runCatching {
                                barcodeReader.read(proxy)
                                    .firstOrNull { !it.text.isNullOrBlank() }
                                    ?.text
                                    ?.trim()
                            }.getOrNull()
                            if (!decoded.isNullOrBlank() &&
                                sessionActive.get() &&
                                !isDismissedFlag.get() &&
                                deliveredFlag.compareAndSet(false, true)
                            ) {
                                ContextCompat.getMainExecutor(context).execute {
                                    // Teardown CameraX BEFORE delivering result so green dot
                                    // clears immediately with zero residual analyzer frames!
                                    sessionActive.set(false)
                                    releaseActiveSessionNow(markDismissed = true)
                                    onResult(decoded)
                                }
                            }
                        }
                    }
                    provider.unbindAll()
                    if (!sessionActive.get() || isDismissedFlag.get() || deliveredFlag.get()) {
                        analysis.clearAnalyzer()
                        return@runCatching
                    }
                    boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            sessionActive.set(false)
            releaseActiveSessionNow(markDismissed = false)
            runCatching { sessionExecutor.shutdownNow() }
        }
    }

    // Periodic center autofocus pulse so ColorOS (Oppo) & Flyme (Meizu) lenses lock cleanly on
    // both small Kaspa address QRs and dense `.kpass` QRs without needing manual taps.
    LaunchedEffect(boundCamera, previewViewRef, isResumed) {
        val cam = boundCamera ?: return@LaunchedEffect
        val pv = previewViewRef ?: return@LaunchedEffect
        if (!isResumed) return@LaunchedEffect
        while (isResumed && !deliveredFlag.get() && !isDismissedFlag.get()) {
            runCatching {
                val w = pv.width.takeIf { it > 0 }?.toFloat() ?: 1f
                val h = pv.height.takeIf { it > 0 }?.toFloat() ?: 1f
                val pt = pv.meteringPointFactory.createPoint(w * 0.5f, h * 0.5f)
                val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
            }
            delay(3_500L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(85f)
            .background(Color.Black),
    ) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        setOnTouchListener { view, event ->
                            if (event.action == MotionEvent.ACTION_UP && !isDismissedFlag.get()) {
                                runCatching {
                                    val pt = meteringPointFactory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                    boundCamera?.cameraControl?.startFocusAndMetering(action)
                                }
                                view.performClick()
                            }
                            true
                        }
                    }.also { previewViewRef = it }
                },
                update = { pv ->
                    if (previewViewRef !== pv) previewViewRef = pv
                },
            )

            // Viewfinder reticle
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(250.dp)
                    .border(
                        width = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = ContinuousCornerShape(28.dp),
                    ),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = walletStrings.qrCameraPermissionNeeded,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                FluidGlassButton(
                    accent = true,
                    useBackdropCapture = false,
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                ) {
                    Text(walletStrings.qrGrantPermissionButton, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Top floating header
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FluidGlassIconButton(
                onClick = {
                    releaseActiveSessionNow(markDismissed = true)
                    onDismiss()
                },
                preset = GlassPreset.FloatingBar,
                useBackdropCapture = false,
            ) {
                Icon(Icons.Default.Close, contentDescription = kineticStrings.close)
            }
            Text(
                text = walletStrings.qrScannerTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (hasCameraPermission) {
                FluidGlassIconButton(
                    onClick = {
                        if (!isDismissedFlag.get()) {
                            torchEnabled = !torchEnabled
                            runCatching { boundCamera?.cameraControl?.enableTorch(torchEnabled) }
                        }
                    },
                    preset = GlassPreset.FloatingBar,
                    useBackdropCapture = false,
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = null,
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(42.dp))
            }
        }

        // Inline gallery decoding / error status pill cleanly above bottom action bar
        if (isDecodingGallery || galleryErrorMessage != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 92.dp)
                    .clip(ContinuousCornerShape(16.dp))
                    .background(
                        if (galleryErrorMessage != null) {
                            Color(0xFFD93025).copy(alpha = 0.88f)
                        } else {
                            Color(0xFF101820).copy(alpha = 0.86f)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDecodingGallery) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
                Text(
                    text = galleryErrorMessage ?: walletStrings.loading,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Bottom actions: Scan from Gallery (zxing-cpp software ARGB_8888 decode) + Paste from Clipboard
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluidGlassButton(
                modifier = Modifier.weight(1f),
                enabled = !isDecodingGallery,
                useBackdropCapture = false,
                onClick = {
                    galleryErrorMessage = null
                    runCatching {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }.onFailure {
                        fallbackGalleryLauncher.launch("image/*")
                    }
                },
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                MarqueeText(walletStrings.qrScanFromGalleryButton, fontWeight = FontWeight.SemiBold)
            }
            FluidGlassButton(
                modifier = Modifier.weight(1f),
                useBackdropCapture = false,
                onClick = {
                    val clip = readTextFromClipboard(context)?.trim()
                    if (!clip.isNullOrEmpty() && deliveredFlag.compareAndSet(false, true)) {
                        releaseActiveSessionNow(markDismissed = true)
                        onResult(clip)
                    }
                },
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                MarqueeText(walletStrings.qrManualPasteButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Decodes a gallery image [Uri] off the main thread into a readable software [Bitmap.Config.ARGB_8888]
 * bitmap (downsampled <= 2048px) and runs `zxing-cpp` [BarcodeReader] cleanly, recycling all bitmaps.
 */
private fun decodeQrFromGalleryUri(
    context: Context,
    uri: Uri,
    barcodeReader: BarcodeReader,
): String? {
    val maxDimTarget = 2048
    val rawBitmap = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > maxDimTarget) {
                val scale = maxDimTarget.toFloat() / maxDim.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrElse {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimTarget) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    } ?: return null

    var argbBitmap: Bitmap = rawBitmap
    var converted: Bitmap? = null
    return try {
        if (rawBitmap.config != Bitmap.Config.ARGB_8888) {
            converted = rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (converted != null) argbBitmap = converted
        }
        barcodeReader.read(argbBitmap)
            .firstOrNull { !it.text.isNullOrBlank() }
            ?.text
            ?.trim()
    } catch (_: Throwable) {
        null
    } finally {
        if (converted != null && !converted.isRecycled) converted.recycle()
        if (!rawBitmap.isRecycled) rawBitmap.recycle()
    }
}
