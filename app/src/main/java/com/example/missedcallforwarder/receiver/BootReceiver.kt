package com.example.missedcallforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-declared receivers are already restored after a reboot, so there's
 * nothing strictly required here. We keep this hook for OEMs that behave oddly
 * and as a place for any future warm-up work (e.g. re-scheduling).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed; CallReceiver remains registered via manifest.")
        }
    }
}
