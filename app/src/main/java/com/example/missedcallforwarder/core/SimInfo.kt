package com.example.missedcallforwarder.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** A user-selectable SIM for sending SMS. id = subscription id; -1 means "system default". */
data class SimOption(val id: Int, val label: String)

object SimInfo {

    /**
     * Returns the list to show in the SIM picker: a "Default SIM" entry followed by
     * each active subscription (carrier + slot). Falls back to just the default
     * option if the permission isn't granted or the device is single-SIM.
     */
    fun options(context: Context): List<SimOption> {
        val default = listOf(SimOption(-1, "Default SIM"))

        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return default

        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return default
            @Suppress("MissingPermission")
            val active = sm.activeSubscriptionInfoList ?: return default
            val sims = active.map { info ->
                val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
                // simSlotIndex is 0-based; show it 1-based for humans.
                val slot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.simSlotIndex + 1
                } else {
                    info.simSlotIndex + 1
                }
                SimOption(info.subscriptionId, "$carrier (SIM $slot)")
            }
            default + sims
        } catch (se: SecurityException) {
            default
        }
    }
}
