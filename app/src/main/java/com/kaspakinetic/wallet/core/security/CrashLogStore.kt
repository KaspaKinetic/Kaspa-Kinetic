package com.kaspakinetic.wallet.core.security

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight uncaught exception recorder that persists the last stack trace locally
 * so the app can present a dismissible "Copy Last Crash Log" diagnostic overlay on next launch.
 */
object CrashLogStore {
    private const val FILE_NAME = "last_crash_log.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
                pw.println("Kaspa Kinetic Crash Log ($ts)")
                pw.println("Thread: ${thread.name}")
                throwable.printStackTrace(pw)
                pw.flush()
                File(appContext.filesDir, FILE_NAME).writeText(sw.toString(), Charsets.UTF_8)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrashLog(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0L) f.readText(Charsets.UTF_8) else null
    }.getOrNull()

    fun clearLastCrashLog(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }
}
