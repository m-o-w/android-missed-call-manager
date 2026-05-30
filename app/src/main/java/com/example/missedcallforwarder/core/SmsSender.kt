package com.example.missedcallforwarder.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/** Result of an SMS send attempt. */
sealed class SmsResult {
    object Success : SmsResult()
    data class Failure(val reason: String) : SmsResult()
}

object SmsSender {

    private const val TAG = "SmsSender"

    /**
     * Sends [body] to [destination] using the chosen SIM ([subscriptionId], or -1 for default).
     * Splits long messages into multipart automatically.
     *
     * Note: SmsManager.sendTextMessage sends without UI. This requires SEND_SMS,
     * a permission Google Play heavily restricts — fine for a sideloaded personal app.
     */
    @SuppressLint("MissingPermission")
    fun send(context: Context, destination: String, body: String, subscriptionId: Int): SmsResult {
        if (destination.isBlank()) return SmsResult.Failure("No destination number configured")

        return try {
            val sms = resolveManager(context, subscriptionId)
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(destination, null, parts, null, null)
            } else {
                sms.sendTextMessage(destination, null, body, null, null)
            }
            SmsResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            SmsResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveManager(context: Context, subscriptionId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
        } else {
            if (subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }
}
