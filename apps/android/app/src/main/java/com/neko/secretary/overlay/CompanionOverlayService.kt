package com.neko.secretary.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.neko.secretary.live2d.Live2DTextureRenderer

/**
 * Foreground service hosting the TYPE_APPLICATION_OVERLAY TextureView companion,
 * edge-peek drag physics, and instant office stealth capsule downgrade.
 */
class CompanionOverlayService : Service() {
    private val renderer = Live2DTextureRenderer()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Neko-Secretary")
            .setContentText("Companion ready (edge peek)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        renderer.setTargetFps(10)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Companion Overlay",
            NotificationManager.IMPORTANCE_MIN
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "neko_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
