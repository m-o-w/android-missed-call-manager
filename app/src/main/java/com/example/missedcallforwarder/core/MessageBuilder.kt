package com.example.missedcallforwarder.core

import com.example.missedcallforwarder.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the forward SMS body from the template. Shared by the live forward
 * path and the "send test" action so both produce identical output.
 */
object MessageBuilder {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun build(number: String, timeMillis: Long, settings: Settings): String {
        val numberText = number.ifBlank { "unknown" }
        val timeText = timeFmt.format(Date(timeMillis))
        val waUrl = if (settings.includeWhatsAppLink) {
            PhoneNumbers.waMeUrl(number, settings.defaultCountryCode) ?: "(unavailable)"
        } else {
            ""
        }
        return settings.messageTemplate
            .replace("{number}", numberText)
            .replace("{time}", timeText)
            .replace("{wa}", waUrl)
            .trim()
    }
}
