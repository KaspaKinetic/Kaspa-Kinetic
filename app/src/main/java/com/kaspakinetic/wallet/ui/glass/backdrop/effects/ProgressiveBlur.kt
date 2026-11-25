package com.kaspakinetic.wallet.ui.glass.backdrop.effects

import androidx.compose.ui.graphics.Color
import com.kaspakinetic.wallet.ui.glass.backdrop.BackdropEffectScope
import com.kaspakinetic.wallet.ui.glass.backdrop.isRuntimeShaderSupported
import org.intellij.lang.annotations.Language

/**
 * AGSL runtime shader performing a smooth progressive vertical blur from either the top edge
 * downward ([fromTop] = true) or bottom edge upward ([fromTop] = false).
 *
 * Uses strict AGSL scalar/float types (`float` / `float4` accumulation returning `half4`)
 * so every Android 13+ GPU/SkSL compiler compiles cleanly.
 */
@Language("AGSL")
private const val ProgressiveVerticalBlurShaderString = """
uniform shader content;
uniform float2 size;
uniform float maxRadius;
uniform float fromTop;
uniform float startFrac;
uniform float endFrac;

float easeInOutQuad(float t) {
    return t < 0.5 ? 2.0 * t * t : 1.0 - pow(-2.0 * t + 2.0, 2.0) / 2.0;
}

float4 sampleClamped(float2 p) {
    float2 maxCoord = max(size - float2(1.0, 1.0), float2(1.0, 1.0));
    float2 safeP = clamp(p, float2(1.0, 1.0), maxCoord);
    return float4(content.eval(safeP));
}

half4 main(float2 coord) {
    float safeHeight = max(size.y, 1.0);
    float normY = clamp(coord.y / safeHeight, 0.0, 1.0);
    float dirY = fromTop > 0.5 ? (1.0 - normY) : normY;
    float denom = max(endFrac - startFrac, 0.001);
    float rawT = clamp((dirY - startFrac) / denom, 0.0, 1.0);
    float blurWeight = easeInOutQuad(rawT);
    float r = blurWeight * maxRadius;

    if (r < 0.35) {
        return half4(sampleClamped(coord));
    }

    float4 acc = sampleClamped(coord) * 1.4;
    float weightSum = 1.4;

    float goldenAngle = 2.3999632;
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float stepFrac = (fi + 0.5) / 12.0;
        float dist = sqrt(stepFrac) * r;
        float angle = fi * goldenAngle;
        float2 offset = float2(cos(angle), sin(angle)) * dist;
        float w = 1.1 - 0.35 * stepFrac;
        acc += sampleClamped(coord + offset) * w;
        weightSum += w;
    }

    return half4(acc / weightSum);
}
"""

fun BackdropEffectScope.progressiveVerticalBlur(
    maxRadiusPx: Float,
    fromTop: Boolean,
    startFraction: Float = 0.05f,
    endFraction: Float = 0.82f,
    tintColor: Color = Color.Transparent,
) {
    if (!isRuntimeShaderSupported() || maxRadiusPx <= 0f) return
    if (!size.width.isFinite() || !size.height.isFinite() || size.width < 1f || size.height < 1f) return
    @Suppress("UNUSED_VARIABLE")
    val unusedTint = tintColor

    runtimeShaderEffect(
        key = "ProgressiveVerticalBlurV2",
        shaderString = ProgressiveVerticalBlurShaderString,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("maxRadius", maxRadiusPx)
        setFloatUniform("fromTop", if (fromTop) 1f else 0f)
        setFloatUniform("startFrac", startFraction.coerceIn(0f, 0.99f))
        setFloatUniform("endFrac", endFraction.coerceIn(startFraction + 0.01f, 1f))
    }
}
