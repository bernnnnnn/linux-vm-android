package com.berns.linuxports

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BernsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SESSIONS,
                getString(R.string.channel_sessions),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a Linux container is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_SESSIONS = "berns.sessions"
    }
}
