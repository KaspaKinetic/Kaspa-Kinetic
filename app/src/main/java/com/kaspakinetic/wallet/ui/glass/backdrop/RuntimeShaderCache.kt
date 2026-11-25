/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.kaspakinetic.wallet.ui.glass.backdrop

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.intellij.lang.annotations.Language

sealed interface RuntimeShaderCache {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun obtainRuntimeShader(key: String, @Language("AGSL") string: String): RuntimeShader

    fun obtainRuntimeShaderOrNull(key: String, @Language("AGSL") string: String): RuntimeShader?
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()
    private val failedKeys = mutableSetOf<String>()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { RuntimeShader(string) }
    }

    override fun obtainRuntimeShaderOrNull(key: String, string: String): RuntimeShader? {
        if (!isRuntimeShaderSupported()) return null
        runtimeShaders[key]?.let { return it }
        if (key in failedKeys) return null
        return try {
            RuntimeShader(string).also { runtimeShaders[key] = it }
        } catch (t: Throwable) {
            failedKeys.add(key)
            Log.w("LiquidGlass", "RuntimeShader compilation failed for '$key'; falling back cleanly.", t)
            null
        }
    }

    fun clear() {
        runtimeShaders.clear()
        failedKeys.clear()
    }
}
