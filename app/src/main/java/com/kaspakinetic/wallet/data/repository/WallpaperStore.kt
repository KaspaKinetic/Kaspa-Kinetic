package com.kaspakinetic.wallet.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves user-picked static images or animated GIFs into app-private storage (`filesDir/wallpapers/`)
 * while preserving original GIF frame streams untouched.
 *
 * Framework/DI-agnostic constructor so any app (Hilt, Koin, or manual wiring) can instantiate it
 * directly with a `Context`.
 */
class WallpaperStore(context: Context) {
    private val appContext: Context = context.applicationContext

    fun wallpapersDir(): File = File(appContext.filesDir, WallpapersDirName).apply { mkdirs() }

    suspend fun importFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri)
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = input.readBytes()
                if (buffer.isEmpty()) error("Selected file is empty.")
                if (buffer.size > MaxWallpaperBytes) {
                    error("Wallpaper file is larger than 40 MB.")
                }
                buffer
            } ?: error("Could not read selected image or GIF.")

            val ext = detectExtension(bytes = bytes, mimeType = mimeType)
            val dir = wallpapersDir()
            val targetFile = File(dir, "wallpaper_${UUID.randomUUID()}.$ext")
            targetFile.writeBytes(bytes)

            // Remove stale wallpapers after the new file is safely written
            dir.listFiles()?.forEach { existing ->
                if (existing.absolutePath != targetFile.absolutePath) {
                    runCatching { existing.delete() }
                }
            }

            targetFile.absolutePath
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        wallpapersDir().listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun detectExtension(bytes: ByteArray, mimeType: String?): String {
        if (bytes.size >= 6) {
            val header6 = String(bytes, 0, 6, Charsets.US_ASCII)
            if (header6 == "GIF87a" || header6 == "GIF89a") return "gif"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return "webp"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        val fromMime = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return when (fromMime?.lowercase()) {
            "gif" -> "gif"
            "webp" -> "webp"
            "png" -> "png"
            "jpeg", "jpg" -> "jpg"
            else -> "img"
        }
    }

    private companion object {
        const val WallpapersDirName = "wallpapers"
        const val MaxWallpaperBytes = 40 * 1024 * 1024
    }
}
