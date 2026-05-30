package com.example.missedcallforwarder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Immutable snapshot of user configuration. */
data class Settings(
    val enabled: Boolean = false,
    val destinationNumber: String = "",
    val messageTemplate: String = DEFAULT_TEMPLATE,
    val dedupMinutes: Int = 60,
    val includeWhatsAppLink: Boolean = true,
    val defaultCountryCode: String = "",   // ISO region, e.g. "IN", "US"
    val sendSubscriptionId: Int = -1,      // -1 = system default SIM
    val dailySmsCap: Int = 0               // 0 = unlimited; else max SMS per rolling 24h
) {
    companion object {
        const val DEFAULT_TEMPLATE = "Missed call from {number} at {time}\nWhatsApp: {wa}"
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DEST = stringPreferencesKey("destination_number")
        val TEMPLATE = stringPreferencesKey("message_template")
        val DEDUP = intPreferencesKey("dedup_minutes")
        val INCLUDE_WA = booleanPreferencesKey("include_wa")
        val REGION = stringPreferencesKey("default_region")
        val SUB_ID = intPreferencesKey("send_sub_id")
        val DAILY_CAP = intPreferencesKey("daily_sms_cap")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            enabled = p[Keys.ENABLED] ?: false,
            destinationNumber = p[Keys.DEST] ?: "",
            messageTemplate = p[Keys.TEMPLATE] ?: Settings.DEFAULT_TEMPLATE,
            dedupMinutes = p[Keys.DEDUP] ?: 60,
            includeWhatsAppLink = p[Keys.INCLUDE_WA] ?: true,
            defaultCountryCode = p[Keys.REGION] ?: "",
            sendSubscriptionId = p[Keys.SUB_ID] ?: -1,
            dailySmsCap = p[Keys.DAILY_CAP] ?: 0
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        // Read current, apply transform, write back individual keys.
        context.dataStore.edit { p ->
            val current = Settings(
                enabled = p[Keys.ENABLED] ?: false,
                destinationNumber = p[Keys.DEST] ?: "",
                messageTemplate = p[Keys.TEMPLATE] ?: Settings.DEFAULT_TEMPLATE,
                dedupMinutes = p[Keys.DEDUP] ?: 60,
                includeWhatsAppLink = p[Keys.INCLUDE_WA] ?: true,
                defaultCountryCode = p[Keys.REGION] ?: "",
                sendSubscriptionId = p[Keys.SUB_ID] ?: -1,
                dailySmsCap = p[Keys.DAILY_CAP] ?: 0
            )
            val next = transform(current)
            p[Keys.ENABLED] = next.enabled
            p[Keys.DEST] = next.destinationNumber
            p[Keys.TEMPLATE] = next.messageTemplate
            p[Keys.DEDUP] = next.dedupMinutes
            p[Keys.INCLUDE_WA] = next.includeWhatsAppLink
            p[Keys.REGION] = next.defaultCountryCode
            p[Keys.SUB_ID] = next.sendSubscriptionId
            p[Keys.DAILY_CAP] = next.dailySmsCap
        }
    }
}
