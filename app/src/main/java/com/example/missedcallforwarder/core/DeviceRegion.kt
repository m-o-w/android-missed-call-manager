package com.example.missedcallforwarder.core

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort detection of the device's phone region (ISO 3166-1 alpha-2, e.g.
 * "IN"), used to resolve national-format caller numbers into international form
 * for wa.me links.
 *
 * Preference order:
 *   1) SIM country (the subscriber's home country) — most accurate for caller numbers
 *   2) Network country (where the phone is currently registered)
 *   3) Device locale country — last-ditch fallback
 *
 * A user-set override always takes precedence over this (see resolveRegion).
 */
object DeviceRegion {

    fun detect(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() }
        if (sim != null) return sim
        val network = tm?.networkCountryIso?.uppercase()?.takeIf { it.isNotBlank() }
        if (network != null) return network
        return Locale.getDefault().country.uppercase().takeIf { it.isNotBlank() }
    }

    /** User override if provided, otherwise auto-detected region. */
    fun resolveRegion(context: Context, userOverride: String?): String? {
        val override = userOverride?.trim()?.takeIf { it.isNotBlank() }
        return override ?: detect(context)
    }
}
