package com.example.missedcallforwarder.ui

import android.Manifest
import android.os.Build

/**
 * Splits permissions into:
 *  - essential: forwarding cannot work without these (call detection + SMS).
 *  - optional:  nice-to-have; denial must NOT block the app.
 *      POST_NOTIFICATIONS -> status notifications (Notifier skips if absent)
 *      READ_PHONE_NUMBERS -> labels for the SIM picker only
 *
 * The UI gates on `essential`; we still request `all` so the user is prompted
 * for the optional ones too.
 */
object Permissions {

    val essential: Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS
    )

    private fun optional(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            list.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    /** Everything to request in one prompt. */
    fun all(): Array<String> = essential + optional()
}
