package com.example.missedcallforwarder.ui

import android.Manifest
import android.os.Build

/** All runtime permissions the app needs, adjusted for the running OS version. */
object Permissions {
    fun required(): Array<String> {
        val base = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            base.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base.toTypedArray()
    }
}
