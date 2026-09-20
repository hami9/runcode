package com.runcode.app.system

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Real CPU and memory numbers for this process and its service threads.
 *
 * Everything here reads `/proc/self`, which an app may always read for itself, so no
 * permission is involved and nothing outside this process is visible.
 *
 * CPU is a rate, not a value: one `/proc` read tells you the total jiffies a thread has ever
 * burned, which on its own means nothing. Each sample is therefore differenced against the
 * previous one for the same thread, and the first sample for a thread reports 0.
 */
class ProcessMonitor {

    private data class Sample(val jiffies: Long, val elapsedNanos: Long)

    private val lastByTid = ConcurrentHashMap<Long, Sample>()
    private val clockTicksPerSecond = 100.0 // _SC_CLK_TCK; 100 on every Android device

    /**
     * Percentage of one core that thread [tid] used since the previous call for that same
     * thread. Returns 0 for the first call, or when the thread has gone.
     */
    fun cpuPercentFor(tid: Long): Int {
        val jiffies = threadJiffies(tid) ?: run {
            lastByTid.remove(tid)
            return 0
        }
        val now = System.nanoTime()
        val previous = lastByTid.put(tid, Sample(jiffies, now)) ?: return 0

        val elapsedSeconds = (now - previous.elapsedNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) return 0

        val usedSeconds = (jiffies - previous.jiffies) / clockTicksPerSecond
        return ((usedSeconds / elapsedSeconds) * 100.0)
            .toInt()
            .coerceIn(0, 100 * Runtime.getRuntime().availableProcessors())
    }

    fun forget(tid: Long) {
        lastByTid.remove(tid)
    }

    /** Proportional set size for the whole process, in MB — what the OS attributes to us. */
    fun processMemoryMb(): Double {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss / 1024.0
        } catch (_: Throwable) {
            javaHeapMb()
        }
    }

    /** JVM heap in use, which is the part a runaway Kotlin-side service actually grows. */
    fun javaHeapMb(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
    }

    /** Memory class in MB: the heap ceiling this device imposes before an OutOfMemoryError. */
    fun heapLimitMb(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return manager?.largeMemoryClass ?: manager?.memoryClass ?: 0
    }

    fun threadCount(): Int = File("/proc/self/task").list()?.size ?: 0

    fun pid(): Int = Process.myPid()

    /**
     * utime + stime for one thread, from field 14 and 15 of /proc/self/task/<tid>/stat.
     * The comm field at index 1 can itself contain spaces, so the line is split after the
     * closing parenthesis rather than naively on whitespace.
     */
    private fun threadJiffies(tid: Long): Long? {
        return try {
            val stat = File("/proc/self/task/$tid/stat").readText()
            val afterComm = stat.substringAfterLast(") ")
            val fields = afterComm.split(' ')
            // After "pid (comm) ", field 0 is state, so utime is index 11 and stime 12.
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
            utime + stime
        } catch (_: Exception) {
            null
        }
    }
}
