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

/** What the worker should do after a processing attempt. */
sealed class ProcessOutcome {
    /** Terminal — already recorded; nothing more to do. */
    object Handled : ProcessOutcome()
    /** Transient send failure — worker should retry later (not yet recorded). */
    data class Retry(val reason: String) : ProcessOutcome()
}

/**
 * Decides what to do with a detected missed call: gate (enabled / SIM filter /
 * dedup / daily cap), build the Telegram message, attempt ONE send, and report
 * an outcome. Retrying transient failures is delegated to WorkManager (see
 * SendWorker) so it survives process death and waits for connectivity.
 */
class MissedCallProcessor(
    private val appContext: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore
) {

    /**
     * Runs the pipeline once. [isFinalAttempt] tells us whether the worker still
     * has retries left, so we only persist a FAILED row when we've truly given up
     * (avoids a flurry of FAILED history rows during retries).
     */
    suspend fun process(call: MissedCall, isFinalAttempt: Boolean): ProcessOutcome {
        val settings = settingsStore.settings.first()

        if (!settings.enabled) {
            record(call, ForwardStatus.SKIPPED_DISABLED, null, "Forwarding disabled")
            return ProcessOutcome.Handled
        }
        if (settings.botToken.isBlank() || settings.chatId.isBlank()) {
            record(call, ForwardStatus.FAILED, null, "Telegram not configured")
            Notifier.show(appContext, "Not forwarded", "Set the Telegram bot token and chat id.")
            return ProcessOutcome.Handled
        }

        // --- SIM filter: only respond to the configured SIM(s) ---
        if (settings.simFilter != Settings.SIM_BOTH) {
            val slot = call.simSlot
            if (slot == null) {
                // Couldn't determine which SIM received the call. Forward anyway —
                // missing a lead is worse than an occasional extra notification.
                Log.w(TAG, "SIM slot unknown; forwarding despite filter=${settings.simFilter}")
            } else if (slot != settings.simFilter) {
                record(call, ForwardStatus.SKIPPED_SIM, null,
                    "Call on SIM $slot; filter set to SIM ${settings.simFilter}")
                return ProcessOutcome.Handled
            }
        }

        // --- Dedup: skip if we already notified for this number within the window ---
        val windowMs = settings.dedupMinutes.toLong() * 60_000L
        if (windowMs > 0 && call.number.isNotBlank()) {
            val since = System.currentTimeMillis() - windowMs
            val recent = db.forwardLogDao().lastSentForNumberSince(call.number, since)
            if (recent != null) {
                record(call, ForwardStatus.SUPPRESSED, null,
                    "Within ${settings.dedupMinutes} min of a previous notification")
                return ProcessOutcome.Handled
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
                return ProcessOutcome.Handled
            }
        }

        val body = MessageBuilder.build(appContext, call.number, call.time, settings)
        val result = withContext(Dispatchers.IO) {
            TelegramClient.sendMessage(settings.botToken, settings.chatId, body)
        }
        return when (result) {
            is TelegramResult.Success -> {
                record(call, ForwardStatus.SENT, body, null)
                Notifier.show(appContext, "Lead sent to Telegram", call.number.ifBlank { "unknown" })
                ProcessOutcome.Handled
            }
            is TelegramResult.Failure -> {
                if (result.retryable && !isFinalAttempt) {
                    // Don't record yet; let WorkManager retry with backoff.
                    Log.w(TAG, "Transient send failure, will retry: ${result.reason}")
                    ProcessOutcome.Retry(result.reason)
                } else {
                    val detail = if (result.retryable) "${result.reason} (gave up after retries)" else result.reason
                    record(call, ForwardStatus.FAILED, body, detail)
                    Notifier.show(appContext, "Telegram send failed", detail)
                    ProcessOutcome.Handled
                }
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
