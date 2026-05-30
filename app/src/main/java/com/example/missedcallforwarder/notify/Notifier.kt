package com.example.missedcallforwarder.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.missedcallforwarder.ForwarderApp
import java.util.concurrent.atomic.AtomicInteger

object Notifier {

    private val idGen = AtomicInteger(1000)

    fun show(context: Context, title: String, text: String) {
        // POST_NOTIFICATIONS is runtime-gated on Android 13+. If not granted,
        // silently skip — the forward itself still happened.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notification = NotificationCompat.Builder(context, ForwarderApp.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(idGen.incrementAndGet(), notification)
    }
}
