package com.example.missedcallforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardLogDao {

    @Insert
    suspend fun insert(log: ForwardLog): Long

    /** History list, newest first, for the UI. */
    @Query("SELECT * FROM forward_log ORDER BY callTime DESC LIMIT 500")
    fun observeAll(): Flow<List<ForwardLog>>

    /**
     * Most recent SUCCESSFUL forward to this number at/after [since].
     * Used for the dedup window. Returns null if none -> safe to send.
     */
    @Query(
        "SELECT * FROM forward_log " +
            "WHERE callerNumber = :number AND status = 'SENT' AND forwardedTime >= :since " +
            "ORDER BY forwardedTime DESC LIMIT 1"
    )
    suspend fun lastSentForNumberSince(number: String, since: Long): ForwardLog?

    /** Count of SMS actually sent at/after [since] — used for the daily cost cap. */
    @Query("SELECT COUNT(*) FROM forward_log WHERE status = 'SENT' AND forwardedTime >= :since")
    suspend fun sentCountSince(since: Long): Int

    @Query("DELETE FROM forward_log")
    suspend fun clear()
}
