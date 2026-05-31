package com.example.missedcallforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.missedcallforwarder.ForwarderApp
import com.example.missedcallforwarder.core.MissedCall
import com.example.missedcallforwarder.core.MissedCallProcessor
import com.example.missedcallforwarder.core.ProcessOutcome
import com.example.missedcallforwarder.data.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Performs the Telegram send for one detected missed call. Running in WorkManager
 * (instead of directly in the broadcast receiver) gives us:
 *   - durability across process death / the ~10s receiver limit,
 *   - a NETWORK CONNECTED constraint so we don't even try while offline, and
 *   - exponential-backoff retry for transient failures.
 *
 * Permanent failures (bad token/chat id) are recorded by the processor and the
 * worker returns success so WorkManager stops retrying.
 */
class SendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val number = inputData.getString(KEY_NUMBER) ?: ""
        val time = inputData.getLong(KEY_TIME, System.currentTimeMillis())
        val callLogId = inputData.getLong(KEY_CALL_LOG_ID, -1L)
        val simSlot = inputData.getInt(KEY_SIM_SLOT, -1).takeIf { it > 0 }

        val call = MissedCall(number = number, time = time, callLogId = callLogId, simSlot = simSlot)

        val app = applicationContext as ForwarderApp
        val processor = MissedCallProcessor(
            appContext = applicationContext,
            db = app.database,
            settingsStore = SettingsStore(applicationContext)
        )

        // This is the final attempt when WorkManager won't retry anymore.
        val isFinalAttempt = runAttemptCount >= MAX_ATTEMPTS - 1

        return when (val outcome = processor.process(call, isFinalAttempt)) {
            is ProcessOutcome.Handled -> Result.success()
            is ProcessOutcome.Retry -> {
                Log.w(TAG, "Retry requested (attempt ${runAttemptCount + 1}): ${outcome.reason}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SendWorker"
        private const val MAX_ATTEMPTS = 5

        private const val KEY_NUMBER = "number"
        private const val KEY_TIME = "time"
        private const val KEY_CALL_LOG_ID = "call_log_id"
        private const val KEY_SIM_SLOT = "sim_slot"

        fun enqueue(context: Context, call: MissedCall) {
            val data: Data = workDataOf(
                KEY_NUMBER to call.number,
                KEY_TIME to call.time,
                KEY_CALL_LOG_ID to call.callLogId,
                KEY_SIM_SLOT to (call.simSlot ?: -1)
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            // Unique per call-log id so duplicate broadcasts don't double-send.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "send_${call.callLogId}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
