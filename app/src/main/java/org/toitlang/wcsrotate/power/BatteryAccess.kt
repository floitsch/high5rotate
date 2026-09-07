// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.power

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class BatteryStatus {
    UNRESTRICTED,
    OPTIMIZED,
    RESTRICTED,
}

object BatteryAccess {
    fun status(context: Context): BatteryStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        ) {
            return BatteryStatus.RESTRICTED
        }
        return if (context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        ) {
            BatteryStatus.UNRESTRICTED
        } else {
            BatteryStatus.OPTIMIZED
        }
    }

    fun openSettings(context: Context): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        // AOSP/Pixel supports this direct battery page. Other devices can use App info.
        val actions = listOf(
            "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
        for (action in actions) {
            try {
                context.startActivity(Intent(action, packageUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next supported settings page.
            } catch (_: SecurityException) {
                // Some manufacturers do not expose the direct battery page to apps.
            }
        }
        return false
    }
}
