
package com.ultron.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        val recentNotifications = mutableListOf<String>()

        private val allowedPackages = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (sbn.packageName !in allowedPackages) return

        val extras = sbn.notification.extras

        val title = extras.getCharSequence("android.title")?.toString()
            ?: "WhatsApp"

        val message = extras.getCharSequence("android.text")?.toString()
            ?: return

        val preview = "$title: $message"

        synchronized(recentNotifications) {
            recentNotifications.add(0, preview)

            while (recentNotifications.size > 40) {
                recentNotifications.removeAt(recentNotifications.lastIndex)
            }
        }
    }
}
