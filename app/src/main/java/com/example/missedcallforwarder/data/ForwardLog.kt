package com.example.missedcallforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Outcome of attempting to forward a single missed call. */
enum class ForwardStatus { SENT, SUPPRESSED, FAILED, SKIPPED_DISABLED, CAPPED }

/**
 * One row per detected missed call. Doubles as:
 *  - the user-facing history list, and
 *  - the dedup source (latest SENT row for a number within the window).
 */
@Entity(tableName = "forward_log")
data class ForwardLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callerNumber: String,
    val callTime: Long,
    val forwardedTime: Long,
    val status: ForwardStatus,
    val messageBody: String?,
    val detail: String? = null
)
