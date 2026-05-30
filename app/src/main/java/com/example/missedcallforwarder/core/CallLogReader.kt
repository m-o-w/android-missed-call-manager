package com.example.missedcallforwarder.core

import android.content.Context
import android.provider.CallLog
import android.util.Log

data class MissedCall(
    val number: String,
    val time: Long,
    val callLogId: Long
)

/**
 * Reads the most recent missed call from the system call log.
 *
 * Why the call log and not the broadcast extra? On Android 9+ the incoming
 * number in PHONE_STATE is null by design. The call log is the reliable source,
 * but it is written with a small delay after the call ends, so callers should
 * retry briefly (see CallReceiver).
 *
 * We deliberately filter to MISSED_TYPE and exclude REJECTED_TYPE. Caveat: some
 * OEMs record a user-declined call as MISSED rather than REJECTED, so a rejected
 * call may occasionally slip through. This is a platform limitation.
 */
object CallLogReader {

    private const val TAG = "CallLogReader"

    fun latestMissedSince(context: Context, sinceMillis: Long): MissedCall? {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        // Pull recent rows and pick the newest qualifying one.
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf((sinceMillis).toString())
        val sort = "${CallLog.Calls.DATE} DESC"

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args, sort
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)

                while (c.moveToNext()) {
                    val type = c.getInt(typeIdx)
                    if (type == CallLog.Calls.MISSED_TYPE) {
                        val number = c.getString(numIdx) ?: ""
                        val date = c.getLong(dateIdx)
                        val id = c.getLong(idIdx)
                        return@use MissedCall(number, date, id)
                    }
                    // REJECTED_TYPE and others are ignored.
                }
                null
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG not granted", se)
            null
        }
    }
}
