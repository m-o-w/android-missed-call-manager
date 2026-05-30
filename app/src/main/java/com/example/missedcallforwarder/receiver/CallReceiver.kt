package com.example.missedcallforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.missedcallforwarder.ForwarderApp
import com.example.missedcallforwarder.core.CallLogReader
import com.example.missedcallforwarder.core.MissedCallProcessor
import com.example.missedcallforwarder.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Infers a missed call from PHONE_STATE transitions.
 *
 * A call is "missed" when we see RINGING and then IDLE *without* an OFFHOOK
 * (answered) in between. PHONE_STATE fires several times per call and gives no
 * number on Android 9+, so on a suspected miss we read the actual number from
 * the call log (with a short retry, because the log lags the broadcast).
 *
 * State is kept in a companion object: manifest receivers are instantiated per
 * broadcast, so instance fields would not survive between RINGING and IDLE.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val now = System.currentTimeMillis()

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                sawRinging = true
                wasAnswered = false
                ringingStartedAt = now
                Log.d(TAG, "RINGING")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Either an outgoing call or the user answered an incoming one.
                wasAnswered = true
                Log.d(TAG, "OFFHOOK")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val missed = sawRinging && !wasAnswered
                Log.d(TAG, "IDLE (missed=$missed)")
                // Reset for the next call regardless.
                val ringedAt = ringingStartedAt
                sawRinging = false
                wasAnswered = false
                ringingStartedAt = 0L

                if (missed) {
                    handleSuspectedMiss(context, ringedAt)
                }
            }
        }
    }

    private fun handleSuspectedMiss(context: Context, ringedAt: Long) {
        val app = context.applicationContext as ForwarderApp
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Call log lags the broadcast; retry a few times.
                val since = (if (ringedAt > 0) ringedAt else System.currentTimeMillis()) - 5_000L
                var call = CallLogReader.latestMissedSince(context, since)
                var attempts = 0
                while (call == null && attempts < MAX_LOOKUPS) {
                    delay(LOOKUP_DELAY_MS)
                    call = CallLogReader.latestMissedSince(context, since)
                    attempts++
                }

                if (call == null) {
                    Log.w(TAG, "Suspected miss but no matching call-log entry found")
                    return@launch
                }

                // Guard against handling the same call-log row twice (multiple IDLEs).
                if (call.callLogId == lastHandledCallLogId) {
                    Log.d(TAG, "Already handled call-log id ${call.callLogId}")
                    return@launch
                }
                lastHandledCallLogId = call.callLogId

                val processor = MissedCallProcessor(
                    appContext = context.applicationContext,
                    db = app.database,
                    settingsStore = SettingsStore(context.applicationContext)
                )
                processor.process(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Error processing missed call", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CallReceiver"
        private const val MAX_LOOKUPS = 4
        private const val LOOKUP_DELAY_MS = 600L

        @Volatile private var sawRinging = false
        @Volatile private var wasAnswered = false
        @Volatile private var ringingStartedAt = 0L
        @Volatile private var lastHandledCallLogId = -1L
    }
}
