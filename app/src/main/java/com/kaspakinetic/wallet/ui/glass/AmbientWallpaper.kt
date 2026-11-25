package com.kaspakinetic.wallet.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-bleed backdrop canvas behind app viewports and settings content:
 * - Renders user-selected static image OR animated GIF wallpaper using Android 9+ [ImageDecoder]
 *   directly into the Compose canvas (so [layerBackdrop] live-captures every GIF frame for real-time
 *   Liquid Glass refraction and blur), with continuous [ColorMatrix] brightness adjustment.
 * - When no wallpaper is set, renders a gentle, slow-moving Living Flow ambient aurora gradient
 *   matched to the active palette so glass refraction and chromatic dispersion remain tactile.
 */
@Composable
fun AmbientLiquidBackdrop(
    wallpaperPath: String?,
    wallpaperBrightness: Float,
    modifier: Modifier = Modifier,
    animateAurora: Boolean = false,
) {
    val isDark = LocalGlassDarkTheme.current
    val baseBg = MaterialTheme.colorScheme.background

    var decodedDrawable by remember(wallpaperPath) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(wallpaperPath) {
        if (wallpaperPath.isNullOrBlank()) {
            decodedDrawable = null
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(wallpaperPath)
                if (!file.exists()) return@runCatching null
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 1800) {
                        val sample = (maxDim / 1800).coerceAtLeast(2)
                        decoder.setTargetSampleSize(sample)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        decodedDrawable = result
    }

    val brightness = wallpaperBrightness.coerceIn(
        KineticAeroDefaults.MIN_WALLPAPER_BRIGHTNESS,
        KineticAeroDefaults.MAX_WALLPAPER_BRIGHTNESS,
    )

    val drawable = decodedDrawable
    if (drawable != null) {
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        var frameTick by remember(drawable) { mutableIntStateOf(0) }

        DisposableEffect(drawable) {
            val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    frameTick++
                }

                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                    mainHandler.postAtTime(what, who, `when`)
                }

                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                    mainHandler.removeCallbacks(what, who)
                }
            }
            drawable.callback = callback
            val anim = drawable as? AnimatedImageDrawable
            anim?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            anim?.start()
            onDispose {
                anim?.stop()
                drawable.callback = null
                mainHandler.removeCallbacksAndMessages(drawable)
            }
        }

        val brightnessFilter = remember(brightness) {
            val cm = ColorMatrix().apply {
                setScale(brightness, brightness, brightness, 1f)
            }
            ColorMatrixColorFilter(cm)
        }

        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Read frameTick inside Canvas draw scope so Compose redraws each GIF frame
                @Suppress("UNUSED_EXPRESSION")
                frameTick

                val canvasW = size.width
                val canvasH = size.height
                val dw = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
                val dh = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()

                // Center-crop scale so image/GIF fills the full viewport cleanly
                val scale = max(canvasW / dw, canvasH / dh)
                val scaledW = dw * scale
                val scaledH = dh * scale
                val left = ((canvasW - scaledW) / 2f).toInt()
                val top = ((canvasH - scaledH) / 2f).toInt()
                val right = left + scaledW.toInt()
                val bottom = top + scaledH.toInt()

                drawable.colorFilter = brightnessFilter
                drawable.setBounds(left, top, right, bottom)
                drawIntoCanvas { canvas ->
                    drawable.draw(canvas.nativeCanvas)
                }
            }
        }
        return
    }

    // Default Ambient Living Flow aurora mesh tailored to the selected ColorPalette
    if (LocalReduceTransparency.current) {
        Box(modifier = modifier.fillMaxSize().background(baseBg))
        return
    }

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    val phase = if (animateAurora) {
        val transition = rememberInfiniteTransition(label = "aurora")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "aurora_phase",
        )
        value
    } else {
        0.7f
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        val orb1Alpha = if (isDark) 0.22f else 0.17f
        val orb2Alpha = if (isDark) 0.18f else 0.15f
        val orb3Alpha = if (isDark) 0.14f else 0.12f

        val center1 = Offset(
            x = w * (0.24f + 0.14f * cos(phase)),
            y = h * (0.20f + 0.08f * sin(phase)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = orb1Alpha), Color.Transparent),
                center = center1,
                radius = max(w, h) * 0.62f,
            ),
            center = center1,
            radius = max(w, h) * 0.62f,
        )

        val center2 = Offset(
            x = w * (0.78f + 0.12f * sin(phase * 0.8f)),
            y = h * (0.48f + 0.12f * cos(phase * 0.8f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tertiary.copy(alpha = orb2Alpha), Color.Transparent),
                center = center2,
                radius = max(w, h) * 0.58f,
            ),
            center = center2,
            radius = max(w, h) * 0.58f,
        )

        val center3 = Offset(
            x = w * (0.35f + 0.16f * sin(phase * 1.15f + 1.2f)),
            y = h * (0.84f + 0.07f * cos(phase * 1.15f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = orb3Alpha), Color.Transparent),
                center = center3,
                radius = max(w, h) * 0.54f,
            ),
            center = center3,
            radius = max(w, h) * 0.54f,
        )
    }
}
