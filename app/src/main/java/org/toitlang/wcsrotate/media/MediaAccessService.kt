// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.media

import android.service.notification.NotificationListenerService
import org.toitlang.wcsrotate.rotation.RotationService

class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        RotationService.refreshIfArmed(this)
    }
}
