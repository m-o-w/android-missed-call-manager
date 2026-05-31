package com.example.missedcallforwarder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.missedcallforwarder.ForwarderApp
import com.example.missedcallforwarder.core.MessageBuilder
import com.example.missedcallforwarder.core.TelegramClient
import com.example.missedcallforwarder.core.TelegramResult
import com.example.missedcallforwarder.data.ForwardLog
import com.example.missedcallforwarder.data.Settings
import com.example.missedcallforwarder.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection state for the configured Telegram bot token. */
sealed class ConnectionStatus {
    object Unknown : ConnectionStatus()        // not checked yet
    object NotConfigured : ConnectionStatus()  // token blank
    object Checking : ConnectionStatus()
    data class Connected(val botUsername: String) : ConnectionStatus()
    object Invalid : ConnectionStatus()        // token rejected by Telegram
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val db = (app as ForwarderApp).database

    private val _connection = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val connection: StateFlow<ConnectionStatus> = _connection

    val settings = store.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null
    )

    val history = db.forwardLogDao().observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ForwardLog>()
    )

    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { store.update(transform) }
    }

    /** Commit a full settings snapshot at once (used by the Save button). */
    fun save(newSettings: Settings) {
        viewModelScope.launch { store.update { newSettings } }
    }

    fun clearHistory() {
        viewModelScope.launch { db.forwardLogDao().clear() }
    }

    /**
     * Validates [token] against Telegram (getMe) and updates [connection]. Debounced
     * by the caller. A blank token reports NotConfigured without a network call.
     */
    fun verifyToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            _connection.value = ConnectionStatus.NotConfigured
            return
        }
        _connection.value = ConnectionStatus.Checking
        viewModelScope.launch {
            val username = withContext(Dispatchers.IO) { TelegramClient.verifyToken(trimmed) }
            _connection.value = if (username != null) {
                ConnectionStatus.Connected(username)
            } else {
                ConnectionStatus.Invalid
            }
        }
    }

    /**
     * Sends a one-off test message to Telegram using the *saved* settings, with
     * [sampleNumber] standing in for a caller. Bypasses enabled/dedup/cap so a
     * test always goes through. Reports the outcome via [onResult].
     */
    fun sendTest(sampleNumber: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val s = store.settings.first()
            if (s.botToken.isBlank() || s.chatId.isBlank()) {
                onResult("Save a bot token and chat id first")
                return@launch
            }
            val body = MessageBuilder.build(getApplication(), sampleNumber, System.currentTimeMillis(), s)
            val result = withContext(Dispatchers.IO) {
                TelegramClient.sendMessage(s.botToken, s.chatId, "[TEST] $body")
            }
            when (result) {
                is TelegramResult.Success -> onResult("Test message sent to Telegram")
                is TelegramResult.Failure -> onResult("Test failed: ${result.reason}")
            }
        }
    }

    /**
     * Calls getUpdates to find the chat id of whoever last messaged the bot, then
     * saves it into the draft via [onResult]. The user must have tapped Start /
     * sent the bot a message first.
     */
    fun detectChatId(botToken: String, onResult: (chatId: String?, message: String) -> Unit) {
        viewModelScope.launch {
            if (botToken.isBlank()) {
                onResult(null, "Enter the bot token first")
                return@launch
            }
            val id = withContext(Dispatchers.IO) { TelegramClient.detectChatId(botToken) }
            if (id != null) {
                onResult(id, "Detected chat id: $id")
            } else {
                onResult(null, "No chat found. Open your bot in Telegram, tap Start, then retry.")
            }
        }
    }
}
