package com.runcode.app.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import com.runcode.app.domain.models.DeviceCapabilities

class CompatibilityManager(private val context: Context) {

    fun getCapabilities(runningServicesCount: Int, wakeLockActive: Boolean): DeviceCapabilities {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalMemMb = memInfo.totalMem / (1024 * 1024)
        val availMemMb = memInfo.availMem / (1024 * 1024)
        val isLowMem = memInfo.lowMemory

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)

        val notifsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        return DeviceCapabilities(
            androidApi = Build.VERSION.SDK_INT,
            releaseVersion = Build.VERSION.RELEASE,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalMemoryMb = totalMemMb,
            availableMemoryMb = availMemMb,
            isLowMemory = isLowMem,
            freeStorageMb = freeStorageMb,
            notificationsAllowed = notifsAllowed,
            isIgnoringBatteryOptimizations = isIgnoringBattery,
            wakeLockActive = wakeLockActive,
            runningServicesCount = runningServicesCount
        )
    }
}
