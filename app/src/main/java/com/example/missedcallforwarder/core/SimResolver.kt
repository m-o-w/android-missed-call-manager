package com.example.missedcallforwarder.core

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Maps a call-log PHONE_ACCOUNT_ID to a physical SIM slot (1-based: SIM 1, SIM 2).
 *
 * The call log stores which subscription handled a call in PHONE_ACCOUNT_ID. For
 * the cellular calling account this is usually the subscription id as a string,
 * which SubscriptionManager can map to a slot index. OEMs are inconsistent here,
 * so callers must treat a null result as "unknown" and decide policy accordingly.
 */
object SimResolver {

    private const val TAG = "SimResolver"

    /** Active SIMs as (slot1Based -> carrier label), for the settings UI. */
    fun activeSims(context: Context): List<Pair<Int, String>> {
        if (!hasPhoneStatePermission(context)) return emptyList()
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val active = sm.activeSubscriptionInfoList ?: return emptyList()
            active.map { info ->
                val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
                (info.simSlotIndex + 1) to carrier
            }.sortedBy { it.first }
        } catch (se: SecurityException) {
            emptyList()
        }
    }

    /**
     * Returns the 1-based SIM slot for the given call-log phoneAccountId, or null
     * if it can't be determined.
     */
    fun slotForPhoneAccountId(context: Context, phoneAccountId: String?): Int? {
        if (phoneAccountId.isNullOrBlank()) return null
        if (!hasPhoneStatePermission(context)) return null

        // Common case: the account id is the subscription id.
        val subId = phoneAccountId.toIntOrNull()
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            @Suppress("MissingPermission")
            val active = sm.activeSubscriptionInfoList ?: return null
            val match = when {
                subId != null -> active.firstOrNull { it.subscriptionId == subId }
                // Fallback: some devices store the ICCID in the account id.
                else -> active.firstOrNull { it.iccId == phoneAccountId }
            }
            match?.let { it.simSlotIndex + 1 }
        } catch (se: SecurityException) {
            Log.w(TAG, "No permission to resolve SIM slot", se)
            null
        }
    }

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
}
