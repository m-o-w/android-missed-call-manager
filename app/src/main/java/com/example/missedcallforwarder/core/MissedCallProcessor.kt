package com.example.missedcallforwarder.core

import android.content.Context
import android.util.Log
import com.example.missedcallforwarder.data.AppDatabase
import com.example.missedcallforwarder.data.ForwardLog
import com.example.missedcallforwarder.data.ForwardStatus
import com.example.missedcallforwarder.data.Settings
import com.example.missedcallforwarder.data.SettingsStore
import com.example.missedcallforwarder.notify.Notifier
import kotlinx.coroutines.flow.first

/**
 * Decides what to do with a detected missed call: dedup, build the message
 * (with optional wa.me link), send the SMS, and record the outcome.
 *
 * Pure-ish: all side effects (DB, SMS, notification) are explicit so the flow
 * is easy to follow.
 */
class MissedCallProcessor(
    private val appContext: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore
) {

    suspend fun process(call: MissedCall) {
        val settings = settingsStore.settings.first()

        if (!settings.enabled) {
            record(call, ForwardStatus.SKIPPED_DISABLED, null, "Forwarding disabled")
            return
        }
        if (settings.destinationNumber.isBlank()) {
            record(call, ForwardStatus.FAILED, null, "No destination number set")
            Notifier.show(appContext, "Not forwarded", "Set a destination number in the app.")
            return
        }

        // --- Dedup: skip if we already forwarded this number within the window ---
        val windowMs = settings.dedupMinutes.toLong() * 60_000L
        if (windowMs > 0 && call.number.isNotBlank()) {
            val since = System.currentTimeMillis() - windowMs
            val recent = db.forwardLogDao().lastSentForNumberSince(call.number, since)
            if (recent != null) {
                record(call, ForwardStatus.SUPPRESSED, null,
                    "Within ${settings.dedupMinutes} min of a previous forward")
                return
            }
        }

        // --- Daily cost cap: stop sending once the rolling-24h limit is hit ---
        if (settings.dailySmsCap > 0) {
            val since = System.currentTimeMillis() - DAY_MS
            val sentToday = db.forwardLogDao().sentCountSince(since)
            if (sentToday >= settings.dailySmsCap) {
                record(call, ForwardStatus.CAPPED, null,
                    "Daily cap reached ($sentToday/${settings.dailySmsCap} in last 24h)")
                Notifier.show(
                    appContext,
                    "Daily SMS cap reached",
                    "Skipped forwarding ${call.number.ifBlank { "unknown" }}. " +
                        "Limit is ${settings.dailySmsCap} per 24h."
                )
                return
            }
        }

        val body = buildMessage(call, settings)
        when (val result = SmsSender.send(
            appContext, settings.destinationNumber, body, settings.sendSubscriptionId
        )) {
            is SmsResult.Success -> {
                record(call, ForwardStatus.SENT, body, null)
                Notifier.show(appContext, "Missed call forwarded", body)
            }
            is SmsResult.Failure -> {
                record(call, ForwardStatus.FAILED, body, result.reason)
                Notifier.show(appContext, "Forward failed", result.reason)
            }
        }
    }

    private fun buildMessage(call: MissedCall, settings: Settings): String =
        MessageBuilder.build(appContext, call.number, call.time, settings)

    private suspend fun record(
        call: MissedCall,
        status: ForwardStatus,
        body: String?,
        detail: String?
    ) {
        Log.i(TAG, "Missed call ${call.number} -> $status ${detail ?: ""}")
        db.forwardLogDao().insert(
            ForwardLog(
                callerNumber = call.number,
                callTime = call.time,
                forwardedTime = System.currentTimeMillis(),
                status = status,
                messageBody = body,
                detail = detail
            )
        )
    }

    companion object {
        private const val TAG = "MissedCallProcessor"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
