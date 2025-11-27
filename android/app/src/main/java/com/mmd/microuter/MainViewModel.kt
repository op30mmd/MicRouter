package com.mmd.microuter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _audioData = MutableStateFlow<ByteArray?>(null)
    val audioData = _audioData.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    // Preferences Logic (From chore branch)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val _serverPort = MutableStateFlow(prefs.getString("server_port", "6000") ?: "6000")
    val serverPort = _serverPort.asStateFlow()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "server_port") {
            _serverPort.value = sharedPreferences.getString(key, "6000") ?: "6000"
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mmd.microuter.WAVEFORM_DATA") {
                _audioData.value = intent.getByteArrayExtra("waveform_data")
                _isServiceRunning.value = true
            }
        }
    }

    init {
        // Register Broadcast Receiver
        val filter = IntentFilter("com.mmd.microuter.WAVEFORM_DATA")
        ContextCompat.registerReceiver(
            application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Register Preference Listener (From chore branch)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun toggleService(context: Context) {
        val intent = Intent(context, AudioService::class.java)
        if (_isServiceRunning.value) {
            context.stopService(intent)
            _isServiceRunning.value = false
            _audioData.value = null
        } else {
            ContextCompat.startForegroundService(context, intent)
            _isServiceRunning.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }
}
