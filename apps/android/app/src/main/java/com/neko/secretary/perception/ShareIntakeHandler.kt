package com.neko.secretary.perception

import android.content.Intent
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class SharedIntakePayload(
    val text: String?,
    val imageUri: Uri?
)

object ShareIntakeHandler {
    fun extractSharedPayload(intent: Intent): SharedIntakePayload {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return SharedIntakePayload(text = sharedText, imageUri = imageUri)
    }
}

class ExternalNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Week 2: Forward user-authorized AI task completions & exam/interview deadline alerts
        // to Core Hub via EventEnvelope ("sensor.notification_external").
    }
}
