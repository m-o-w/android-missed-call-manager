package com.example.missedcallforwarder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Immutable snapshot of user configuration. */
data class Settings(
    val enabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = "",
    val messageTemplate: String = DEFAULT_TEMPLATE,
    val greeting: String = DEFAULT_GREETING,
    val dedupMinutes: Int = 60,
    val dailyMessageCap: Int = 0,        // 0 = unlimited; else max messages per rolling 24h
    val defaultCountryCode: String = "", // ISO region override (e.g. "IN"); blank = auto-detect
    val simFilter: Int = SIM_BOTH        // which SIM's missed calls to respond to
) {
    companion object {
        const val DEFAULT_TEMPLATE =
            "\uD83D\uDCDE Missed business call\nFrom: {number}\nTime: {time}\nOpen WhatsApp: {wa}"
        const val DEFAULT_GREETING = "Hi, sorry we missed your call. How can we help?"

        // simFilter values
        const val SIM_BOTH = 0
        const val SIM_1 = 1
        const val SIM_2 = 2
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val BOT_TOKEN = stringPreferencesKey("bot_token")
        val CHAT_ID = stringPreferencesKey("chat_id")
        val TEMPLATE = stringPreferencesKey("message_template")
        val GREETING = stringPreferencesKey("greeting")
        val DEDUP = intPreferencesKey("dedup_minutes")
        val DAILY_CAP = intPreferencesKey("daily_message_cap")
        val REGION = stringPreferencesKey("default_region")
        val SIM_FILTER = intPreferencesKey("sim_filter")
    }

    private fun read(p: androidx.datastore.preferences.core.Preferences) = Settings(
        enabled = p[Keys.ENABLED] ?: false,
        botToken = p[Keys.BOT_TOKEN] ?: "",
        chatId = p[Keys.CHAT_ID] ?: "",
        messageTemplate = p[Keys.TEMPLATE] ?: Settings.DEFAULT_TEMPLATE,
        greeting = p[Keys.GREETING] ?: Settings.DEFAULT_GREETING,
        dedupMinutes = p[Keys.DEDUP] ?: 60,
        dailyMessageCap = p[Keys.DAILY_CAP] ?: 0,
        defaultCountryCode = p[Keys.REGION] ?: "",
        simFilter = p[Keys.SIM_FILTER] ?: Settings.SIM_BOTH
    )

    val settings: Flow<Settings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.ENABLED] = next.enabled
            p[Keys.BOT_TOKEN] = next.botToken
            p[Keys.CHAT_ID] = next.chatId
            p[Keys.TEMPLATE] = next.messageTemplate
            p[Keys.GREETING] = next.greeting
            p[Keys.DEDUP] = next.dedupMinutes
            p[Keys.DAILY_CAP] = next.dailyMessageCap
            p[Keys.REGION] = next.defaultCountryCode
            p[Keys.SIM_FILTER] = next.simFilter
        }
    }
}
