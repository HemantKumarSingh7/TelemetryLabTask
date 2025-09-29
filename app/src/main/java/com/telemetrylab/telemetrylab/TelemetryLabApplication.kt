package com.telemetrylab.telemetrylab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TelemetryLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telemetry Compute",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Edge compute processing notifications"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "telemetry_compute_channel"
    }
}