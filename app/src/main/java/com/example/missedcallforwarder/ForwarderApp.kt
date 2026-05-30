package com.example.missedcallforwarder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.missedcallforwarder.data.AppDatabase

class ForwarderApp : Application() {

    // Lazily-created singletons used across receivers and UI.
    val database: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_status_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_STATUS = "forwarding_status"
    }
}
