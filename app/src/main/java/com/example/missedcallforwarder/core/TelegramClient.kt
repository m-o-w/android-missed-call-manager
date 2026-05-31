package com.example.missedcallforwarder.core

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Result of a Telegram API call. */
sealed class TelegramResult {
    object Success : TelegramResult()
    data class Failure(val reason: String) : TelegramResult()
}

/**
 * Minimal Telegram Bot API client over plain HTTPS (no third-party deps).
 *
 * Endpoints used:
 *   POST /bot<token>/sendMessage  -> deliver the missed-call notification
 *   GET  /bot<token>/getUpdates   -> auto-detect the user's chat id during setup
 *
 * The bot token is a secret; it is never logged.
 */
object TelegramClient {

    private const val TAG = "TelegramClient"
    private const val BASE = "https://api.telegram.org"
    private const val TIMEOUT_MS = 20_000

    /** Sends [text] to [chatId]. Returns a descriptive failure on any error. */
    fun sendMessage(botToken: String, chatId: String, text: String): TelegramResult {
        if (botToken.isBlank()) return TelegramResult.Failure("Bot token is empty")
        if (chatId.isBlank()) return TelegramResult.Failure("Chat id is empty")

        val body = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("disable_web_page_preview", true)
            .toString()

        return try {
            val (code, payload) = postJson("$BASE/bot$botToken/sendMessage", body)
            if (code in 200..299) {
                TelegramResult.Success
            } else {
                TelegramResult.Failure(extractApiError(payload, code))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            TelegramResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Reads recent updates and returns the most recent chat id that has messaged
     * the bot, or null if none. Used by the "Detect chat id" setup helper.
     */
    fun detectChatId(botToken: String): String? {
        if (botToken.isBlank()) return null
        return try {
            val (code, payload) = get("$BASE/bot$botToken/getUpdates")
            if (code !in 200..299) return null
            val result = JSONObject(payload).optJSONArray("result") ?: return null
            // Walk newest-first for the latest chat that contacted the bot.
            for (i in result.length() - 1 downTo 0) {
                val update = result.optJSONObject(i) ?: continue
                val message = update.optJSONObject("message")
                    ?: update.optJSONObject("edited_message")
                    ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val id = chat.opt("id")
                if (id != null) return id.toString()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "detectChatId failed", e)
            null
        }
    }

    /** Validates the token by calling getMe; returns the bot's @username on success. */
    fun verifyToken(botToken: String): String? {
        if (botToken.isBlank()) return null
        return try {
            val (code, payload) = get("$BASE/bot$botToken/getMe")
            if (code !in 200..299) return null
            JSONObject(payload).optJSONObject("result")?.optString("username")
        } catch (e: Exception) {
            null
        }
    }

    private fun extractApiError(payload: String, code: Int): String =
        try {
            val json = JSONObject(payload)
            val desc = json.optString("description").ifBlank { "HTTP $code" }
            "Telegram: $desc"
        } catch (e: Exception) {
            "Telegram HTTP $code"
        }

    private fun postJson(urlStr: String, json: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        return conn.use { readResponse(it) }
    }

    private fun get(urlStr: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return conn.use { readResponse(it) }
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return code to text
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    @Suppress("unused")
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
