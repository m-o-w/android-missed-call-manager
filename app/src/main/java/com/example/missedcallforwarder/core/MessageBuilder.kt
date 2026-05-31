package com.example.missedcallforwarder.core

import android.content.Context
import com.example.missedcallforwarder.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the Telegram notification body from the template. Shared by the live
 * forward path and the "send test" action so both produce identical output.
 *
 * The {wa} placeholder becomes a wa.me click-to-chat link for the caller, with
 * the configured greeting pre-filled so one tap on the primary phone opens the
 * lead's WhatsApp chat ready to send.
 */
object MessageBuilder {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun build(context: Context, number: String, timeMillis: Long, settings: Settings): String {
        val numberText = number.ifBlank { "unknown" }
        val timeText = timeFmt.format(Date(timeMillis))
        val region = DeviceRegion.resolveRegion(context, settings.defaultCountryCode)
        val waUrl = PhoneNumbers.waMeUrl(number, region, settings.greeting) ?: "(link unavailable)"
        return settings.messageTemplate
            .replace("{number}", numberText)
            .replace("{time}", timeText)
            .replace("{wa}", waUrl)
            .trim()
    }
}
