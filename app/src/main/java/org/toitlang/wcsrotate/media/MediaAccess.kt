// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object MediaAccess {
    fun component(context: Context) = ComponentName(context, MediaAccessService::class.java)

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.isNotificationListenerAccessGranted(component(context))
    }

    fun settingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            }
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }
}
