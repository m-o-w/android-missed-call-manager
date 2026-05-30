package com.example.missedcallforwarder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.missedcallforwarder.ForwarderApp
import com.example.missedcallforwarder.core.MessageBuilder
import com.example.missedcallforwarder.core.SmsResult
import com.example.missedcallforwarder.core.SmsSender
import com.example.missedcallforwarder.data.ForwardLog
import com.example.missedcallforwarder.data.Settings
import com.example.missedcallforwarder.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val db = (app as ForwarderApp).database

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
     * Sends a one-off test SMS using the *saved* settings (destination, template,
     * SIM, wa.me option), with [sampleNumber] standing in for a caller. Bypasses
     * the enabled flag, dedup, and the daily cap so testing always goes through.
     * Reports the outcome via [onResult] on the main thread.
     */
    fun sendTestSms(sampleNumber: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val s = store.settings.first()
            if (s.destinationNumber.isBlank()) {
                onResult("Set and save a destination number first")
                return@launch
            }
            val body = MessageBuilder.build(sampleNumber, System.currentTimeMillis(), s)
            val prefixed = "[TEST] $body"
            when (val r = SmsSender.send(getApplication(), s.destinationNumber, prefixed, s.sendSubscriptionId)) {
                is SmsResult.Success -> onResult("Test SMS sent to ${s.destinationNumber}")
                is SmsResult.Failure -> onResult("Test failed: ${r.reason}")
            }
        }
    }
}
