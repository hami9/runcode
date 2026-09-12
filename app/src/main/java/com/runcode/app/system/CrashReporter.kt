package com.runcode.app.system

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the stack trace of an uncaught exception before the process dies.
 *
 * Android's "Something went wrong with runcode" dialog tells the user nothing and the trace
 * is only reachable over adb, which is useless on a phone. Writing it to the app's own files
 * means the next launch can show what actually happened.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val DIR_NAME = "diagnostics"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (_: Throwable) {
                // Never let the reporter mask the original crash.
            }
            // Hand back to the platform so the process still dies the way Android expects.
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? {
        val file = file(context)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("runcode crash report")
            appendLine("time:    $timestamp")
            appendLine("thread:  ${thread.name}")
            appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi:     ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            append(trace)
        }

        val file = file(context)
        file.parentFile?.mkdirs()
        file.writeText(report)
    }

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)
}
