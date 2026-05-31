package com.example.missedcallforwarder.core

import android.content.Context
import android.util.Log
import com.example.missedcallforwarder.data.AppDatabase
import com.example.missedcallforwarder.data.ForwardLog
import com.example.missedcallforwarder.data.ForwardStatus
import com.example.missedcallforwarder.data.Settings
import com.example.missedcallforwarder.data.SettingsStore
import com.example.missedcallforwarder.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Decides what to do with a detected missed call: dedup, daily cap, build the
 * Telegram message (with a pre-filled wa.me link), deliver it, and log the
 * outcome.
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
        if (settings.botToken.isBlank() || settings.chatId.isBlank()) {
            record(call, ForwardStatus.FAILED, null, "Telegram not configured")
            Notifier.show(appContext, "Not forwarded", "Set the Telegram bot token and chat id.")
            return
        }

        // --- Dedup: skip if we already notified for this number within the window ---
        val windowMs = settings.dedupMinutes.toLong() * 60_000L
        if (windowMs > 0 && call.number.isNotBlank()) {
            val since = System.currentTimeMillis() - windowMs
            val recent = db.forwardLogDao().lastSentForNumberSince(call.number, since)
            if (recent != null) {
                record(call, ForwardStatus.SUPPRESSED, null,
                    "Within ${settings.dedupMinutes} min of a previous notification")
                return
            }
        }

        // --- Daily cap: stop once the rolling-24h limit is hit ---
        if (settings.dailyMessageCap > 0) {
            val since = System.currentTimeMillis() - DAY_MS
            val sentToday = db.forwardLogDao().sentCountSince(since)
            if (sentToday >= settings.dailyMessageCap) {
                record(call, ForwardStatus.CAPPED, null,
                    "Daily cap reached ($sentToday/${settings.dailyMessageCap} in last 24h)")
                Notifier.show(
                    appContext,
                    "Daily cap reached",
                    "Skipped ${call.number.ifBlank { "unknown" }}. Limit ${settings.dailyMessageCap}/24h."
                )
                return
            }
        }

        val body = MessageBuilder.build(appContext, call.number, call.time, settings)
        val result = withContext(Dispatchers.IO) {
            TelegramClient.sendMessage(settings.botToken, settings.chatId, body)
        }
        when (result) {
            is TelegramResult.Success -> {
                record(call, ForwardStatus.SENT, body, null)
                Notifier.show(appContext, "Lead sent to Telegram", call.number.ifBlank { "unknown" })
            }
            is TelegramResult.Failure -> {
                record(call, ForwardStatus.FAILED, body, result.reason)
                Notifier.show(appContext, "Telegram send failed", result.reason)
            }
        }
    }

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
